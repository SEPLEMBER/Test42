package org.syndes.rust

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.*
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.util.Pair
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.syndes.rust.databinding.ActivityEditorBinding
import org.syndes.rust.databinding.ItemLineBinding
import java.io.*
import java.util.*
import kotlin.collections.LinkedHashMap
import kotlin.math.max
import kotlin.math.min

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding

    // SAF launchers
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createDocumentLauncherSave: ActivityResultLauncher<String>
    private lateinit var createDocumentLauncherSaveAs: ActivityResultLauncher<String>
    private lateinit var loadSyntaxMappingLauncher: ActivityResultLauncher<Array<String>>

    private var currentDocumentUri: Uri? = null

    // Prefs
    private val prefsName = "editor_prefs"
    private val PREF_PREVENT_SCREENSHOT = "prevent_screenshot"
    private val PREF_FONT_SIZE = "font_size"
    private val PREF_RETRO_MODE = "retro_mode"
    private val PREF_AMBER_MODE = "amber_mode"
    private val PREF_SYNTAX_HIGHLIGHT = "syntax_highlight"
    private val PREF_SYNTAX_MAPPING_URI = "syntax_mapping_uri"
    private val PREF_SYNTAX_LANGUAGE = "syntax_language"
    private val PREF_FORMAT_ON = "format_on"
    private val PREF_UNDO_ENABLED = "undo_enabled"

    // background dispatcher
    private val bgDispatcher = Dispatchers.IO

    // ---------- BLOCKING / CHUNK model ----------
    private val BLOCK_SIZE = 1000 // lines per block file (tuneable)

    // temp directory for blocks of the currently opened file
    private var blocksDir: File? = null

    // number of original blocks
    private var originalBlockCount = 0

    // number of original lines (sum of lines in original blocks)
    private var originalTotalLines = 0L

    // chunk model: sequence of chunks representing current logical doc
    private val chunks = LinkedList<Chunk>() // mutable list of chunks

    // LRU cache for block content: blockIndex -> List<String>
    private val blockCache = object : LinkedHashMap<Int, List<String>>(8, 0.75f, true) {
        private val MAX_ENTRIES = 6
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<String>>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    // Edited / inserted lines are stored in InsertedLines chunks (in-memory)
    // Deleted lines are represented by removal from chunks.

    // Adapter & recycler
    private lateinit var adapter: LinesAdapter

    // history of operations for undo/redo (keeps operations, not full text)
    private val history = ArrayList<EditOp>()
    private var historyIndex = -1
    private val maxHistory = 200

    // syntax mapping
    private val syntaxMapping = mutableMapOf<String, Int>()
    private val kotlinKeywords = setOf(
        "fun","val","var","if","else","for","while","return","import","class","object",
        "private","public","protected","internal","override","when","in","is","null","true","false"
    )

    // Other jobs
    private var statsJob: Job? = null
    private var uiUpdateJob: Job? = null

    // flags
    private var ignoreAdapterWatchers = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // theme early
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val dark = sp.getBoolean("theme_dark", true)
        AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // jump zones
        binding.jumpTop.bringToFront()
        binding.jumpBottom.bringToFront()

        // FLAG_SECURE
        if (sp.getBoolean(PREF_PREVENT_SCREENSHOT, false)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // SAF launchers
        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { openLargeFileUri(it) }
        }
        createDocumentLauncherSave = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { saveToUri(it) }
        }
        createDocumentLauncherSaveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { saveToUri(it) }
        }
        loadSyntaxMappingLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                lifecycleScope.launch {
                    parseAndStoreSyntaxMappingFromUri(it)
                    scheduleVisibleHighlight()
                }
            }
        }

        binding.toolbar.setOnMenuItemClickListener { item -> onOptionsItemSelected(item) }

        // Setup RecyclerView
        adapter = LinesAdapter()
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        // initial states
        binding.emptyHint.visibility = View.VISIBLE
        binding.tvStats.text = "Words: 0 | Chars: 0"

        // jumps
        binding.jumpTop.setOnClickListener { jumpToLineIndex(0) }
        binding.jumpBottom.setOnClickListener { jumpToLineIndex(adapter.itemCount - 1) }

        binding.root.setOnTouchListener { v, ev ->
            try {
                val edgeWidthPx = dpToPx(56)
                val x = ev.x
                val y = ev.y
                val w = v.width
                if (x >= w - edgeWidthPx) {
                    if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                        val ratio = (y / v.height).coerceIn(0f, 1f)
                        val target = ((adapter.itemCount - 1) * ratio).toInt().coerceIn(0, max(0, adapter.itemCount - 1))
                        binding.recycler.scrollToPosition(target)
                    }
                    return@setOnTouchListener true
                }
            } catch (_: Exception) {}
            false
        }

        binding.recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                scheduleVisibleHighlight()
            }
        })

        applyFontSizeFromPrefs()
        applyPrefsVisuals()
    }

    override fun onResume() {
        super.onResume()
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (sp.getBoolean(PREF_PREVENT_SCREENSHOT, false)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        applyFontSizeFromPrefs()
        applyPrefsVisuals()
        scheduleVisibleHighlight()
    }

    override fun onDestroy() {
        super.onDestroy()
        // cleanup blocks
        cleanupBlocksDir()
        uiUpdateJob?.cancel()
        statsJob?.cancel()
    }

    // ---------- OPENING LARGE FILE (stream -> block files) ----------
    private fun openLargeFileUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                // take URI permission if possible
                try {
                    val takeFlags = (intent?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (_: Exception) {}

                // cleanup previous blocks
                cleanupBlocksDir()

                // create blocks dir
                blocksDir = File(cacheDir, "editor_blocks_${System.currentTimeMillis()}")
                blocksDir?.mkdirs()

                originalBlockCount = 0
                originalTotalLines = 0L
                chunks.clear()
                blockCache.clear()
                history.clear()
                historyIndex = -1

                withContext(bgDispatcher) {
                    // open stream via contentResolver
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                        val buffer = ArrayList<String>(BLOCK_SIZE)
                        var line: String? = reader.readLine()
                        while (line != null) {
                            buffer.add(line)
                            originalTotalLines++
                            if (buffer.size >= BLOCK_SIZE) {
                                writeBlockToFile(buffer)
                                buffer.clear()
                            }
                            line = reader.readLine()
                        }
                        if (buffer.isNotEmpty()) {
                            writeBlockToFile(buffer)
                            buffer.clear()
                        }
                    } ?: run {
                        // empty file
                    }
                }

                currentDocumentUri = uri

                // build initial chunks sequence (all original blocks)
                for (i in 0 until originalBlockCount) chunks.add(Chunk.OriginalBlock(i))

                // ensure at least one empty line exists for editing
                if (getLogicalTotalLines() == 0) {
                    chunks.clear()
                    chunks.add(Chunk.InsertedLines(mutableListOf("")))
                }

                withContext(Dispatchers.Main) {
                    adapter.notifyDataSetChanged()
                    binding.emptyHint.visibility = if (getLogicalTotalLines() == 0) View.VISIBLE else View.GONE
                    pushHistorySnapshot(OperationSnapshot.FullDocumentEmpty()) // initial marker
                    scheduleStatsUpdate()
                    scheduleVisibleHighlight()
                    Toast.makeText(this@EditorActivity, "File opened", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, "Error opening file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun writeBlockToFile(lines: List<String>) {
        val idx = originalBlockCount
        val f = File(blocksDir, "block_$idx.txt")
        BufferedWriter(OutputStreamWriter(FileOutputStream(f), Charsets.UTF_8)).use { bw ->
            for (i in lines.indices) {
                bw.write(lines[i])
                if (i < lines.lastIndex) bw.write("\n")
            }
            bw.flush()
        }
        originalBlockCount++
    }

    private fun cleanupBlocksDir() {
        try {
            blocksDir?.let {
                if (it.exists()) it.deleteRecursively()
            }
        } catch (_: Exception) {}
        blocksDir = null
        blockCache.clear()
    }

    // ---------- SAVE (stream combine) ----------
    private fun saveToUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(bgDispatcher) {
                    // open output stream (overwrite)
                    contentResolver.openOutputStream(uri)?.use { os ->
                        val writer = BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8))
                        val total = getLogicalTotalLines()
                        for (i in 0 until total) {
                            val line = getLogicalLine(i)
                            writer.write(line ?: "")
                            if (i < total - 1) writer.newLine()
                        }
                        writer.flush()
                    } ?: throw IllegalStateException("Cannot open output stream")
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, "Saved", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, "Error saving file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- CHUNK MODEL & HELPERS ----------
    private sealed class Chunk {
        data class OriginalBlock(val blockIndex: Int): Chunk()
        data class InsertedLines(val lines: MutableList<String>): Chunk()
    }

    private sealed class EditOp {
        data class ReplaceLine(val pos: Int, val old: String, val new: String): EditOp()
        data class InsertLine(val pos: Int, val content: String): EditOp()
        data class DeleteLine(val pos: Int, val old: String): EditOp()
        class FullSnapshot(val snapshotChunks: List<Chunk>) : EditOp()
    }

    private fun pushHistorySnapshot(op: EditOp) {
        // trim forward
        if (historyIndex < history.size - 1) {
            for (i in history.size - 1 downTo historyIndex + 1) history.removeAt(i)
        }
        history.add(op)
        historyIndex = history.size - 1
        while (history.size > maxHistory) {
            history.removeAt(0)
            historyIndex--
        }
    }

    private fun pushHistorySnapshot(snapshot: OperationSnapshot) {
        // compatibility placeholder if needed
    }

    private fun performUndo() {
        val undoEnabled = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getBoolean(PREF_UNDO_ENABLED, true)
        if (!undoEnabled) {
            Toast.makeText(this, "Undo disabled", Toast.LENGTH_SHORT).show(); return
        }
        if (historyIndex < 0) { Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show(); return }
        val op = history[historyIndex]
        when (op) {
            is EditOp.ReplaceLine -> {
                applyReplaceLine(op.pos, op.old, recordHistory = false)
            }
            is EditOp.InsertLine -> {
                applyDeleteLine(op.pos, recordHistory = false)
            }
            is EditOp.DeleteLine -> {
                applyInsertLine(op.pos, op.old, recordHistory = false)
            }
            is EditOp.FullSnapshot -> {
                // restore snapshot
                chunks.clear()
                chunks.addAll(op.snapshotChunks.map { copyChunk(it) })
                blockCache.clear()
            }
        }
        historyIndex--
        adapter.notifyDataSetChanged()
        scheduleStatsUpdate()
        scheduleVisibleHighlight()
    }

    private fun performRedo() {
        if (historyIndex >= history.size - 1) { Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show(); return }
        historyIndex++
        val op = history[historyIndex]
        when (op) {
            is EditOp.ReplaceLine -> applyReplaceLine(op.pos, op.new, recordHistory = false)
            is EditOp.InsertLine -> applyInsertLine(op.pos, op.content, recordHistory = false)
            is EditOp.DeleteLine -> applyDeleteLine(op.pos, recordHistory = false)
            is EditOp.FullSnapshot -> {
                chunks.clear()
                chunks.addAll(op.snapshotChunks.map { copyChunk(it) })
                blockCache.clear()
            }
        }
        adapter.notifyDataSetChanged()
        scheduleStatsUpdate()
        scheduleVisibleHighlight()
    }

    // helpers to copy chunk
    private fun copyChunk(c: Chunk): Chunk {
        return when (c) {
            is Chunk.OriginalBlock -> Chunk.OriginalBlock(c.blockIndex)
            is Chunk.InsertedLines -> Chunk.InsertedLines(ArrayList(c.lines))
        }
    }

    // Map logical pos -> Pair(chunkIndex, localIndex)
    private data class ChunkLocation(val chunkIndex: Int, val localIndex: Int)

    private fun mapPosToChunk(pos: Int): ChunkLocation {
        var acc = 0
        var idx = 0
        for (c in chunks) {
            val size = chunkSize(c)
            if (pos < acc + size) {
                return ChunkLocation(idx, pos - acc)
            }
            acc += size
            idx++
        }
        // fallback to last
        return ChunkLocation(chunks.size - 1, max(0, chunkSize(chunks.last) - 1))
    }

    private fun chunkSize(c: Chunk): Int = when (c) {
        is Chunk.OriginalBlock -> getBlockLineCount(c.blockIndex)
        is Chunk.InsertedLines -> c.lines.size
    }

    private fun getLogicalTotalLines(): Int {
        var total = 0
        for (c in chunks) total += chunkSize(c)
        return total
    }

    // read one logical line by global pos
    private fun getLogicalLine(pos: Int): String? {
        val loc = mapPosToChunk(pos)
        val c = chunks[loc.chunkIndex]
        return when (c) {
            is Chunk.InsertedLines -> c.lines[loc.localIndex]
            is Chunk.OriginalBlock -> {
                val blockIndex = c.blockIndex
                val blockLines = loadBlock(blockIndex)
                if (loc.localIndex in blockLines.indices) blockLines[loc.localIndex] else ""
            }
        }
    }

    private fun getBlockLineCount(blockIndex: Int): Int {
        // attempt to use cache
        val blk = loadBlock(blockIndex)
        return blk.size
    }

    private fun loadBlock(blockIndex: Int): List<String> {
        synchronized(blockCache) {
            blockCache[blockIndex]?.let { return it }
        }
        // read from file
        val f = File(blocksDir, "block_$blockIndex.txt")
        return try {
            val list = ArrayList<String>()
            BufferedReader(InputStreamReader(FileInputStream(f), Charsets.UTF_8)).use { br ->
                var ln = br.readLine()
                while (ln != null) {
                    list.add(ln)
                    ln = br.readLine()
                }
            }
            synchronized(blockCache) { blockCache[blockIndex] = list }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    // When editing an original line we replace it by inserting an InsertedLines chunk with the new line
    private fun applyReplaceLine(globalPos: Int, newValue: String, recordHistory: Boolean = true) {
        val loc = mapPosToChunk(globalPos)
        val c = chunks[loc.chunkIndex]
        if (c is Chunk.InsertedLines) {
            val old = c.lines[loc.localIndex]
            c.lines[loc.localIndex] = newValue
            if (recordHistory) pushHistorySnapshot(EditOp.ReplaceLine(globalPos, old, newValue))
        } else if (c is Chunk.OriginalBlock) {
            // split original block into up to three chunks: left original, inserted(new), right original
            val blockIdx = c.blockIndex
            val blockLines = loadBlock(blockIdx).toMutableList()
            val localIdx = loc.localIndex
            val left = blockLines.subList(0, localIdx)
            val right = blockLines.subList(localIdx + 1, blockLines.size)
            // replace chunk at loc.chunkIndex
            val toInsert = LinkedList<Chunk>()
            if (left.isNotEmpty()) {
                // write left part back into a temp block file (we'll create a new block file for left)
                val leftBlockIdx = createBlockFileFromLines(left)
                toInsert.add(Chunk.OriginalBlock(leftBlockIdx))
            }
            // inserted replaced line
            toInsert.add(Chunk.InsertedLines(mutableListOf(newValue)))
            if (right.isNotEmpty()) {
                val rightBlockIdx = createBlockFileFromLines(right)
                toInsert.add(Chunk.OriginalBlock(rightBlockIdx))
            }
            // replace in chunks
            chunks.removeAt(loc.chunkIndex)
            var insertIndex = loc.chunkIndex
            for (nc in toInsert) {
                chunks.add(insertIndex, nc)
                insertIndex++
            }
            if (recordHistory) {
                val oldVal = blockLines[localIdx]
                pushHistorySnapshot(EditOp.ReplaceLine(globalPos, oldVal, newValue))
            }
            // clear blockCache for old block file
            synchronized(blockCache) { blockCache.remove(blockIdx) }
        }
    }

    // Create a new block file from given lines; returns blockIndex (we append to blocksDir)
    private fun createBlockFileFromLines(lines: List<String>): Int {
        // simply append new block file with incremented index
        val idx = originalBlockCount++
        val f = File(blocksDir, "block_$idx.txt")
        BufferedWriter(OutputStreamWriter(FileOutputStream(f), Charsets.UTF_8)).use { bw ->
            for (i in lines.indices) {
                bw.write(lines[i])
                if (i < lines.lastIndex) bw.write("\n")
            }
            bw.flush()
        }
        // we don't add to chunks here; caller will create OriginalBlock pointing to idx
        return idx
    }

    private fun applyInsertLine(globalPos: Int, content: String, recordHistory: Boolean = true) {
        // insert before globalPos
        val loc = mapPosToChunk(globalPos)
        val c = chunks[loc.chunkIndex]
        if (c is Chunk.InsertedLines) {
            c.lines.add(loc.localIndex, content)
        } else if (c is Chunk.OriginalBlock) {
            // split original block into left & right, insert InsertedLines in between
            val blockLines = loadBlock(c.blockIndex).toMutableList()
            val left = blockLines.subList(0, loc.localIndex)
            val right = blockLines.subList(loc.localIndex, blockLines.size)
            val newChunks = LinkedList<Chunk>()
            if (left.isNotEmpty()) newChunks.add(Chunk.OriginalBlock(createBlockFileFromLines(left)))
            newChunks.add(Chunk.InsertedLines(mutableListOf(content)))
            if (right.isNotEmpty()) newChunks.add(Chunk.OriginalBlock(createBlockFileFromLines(right)))
            chunks.removeAt(loc.chunkIndex)
            var insertIndex = loc.chunkIndex
            for (nc in newChunks) { chunks.add(insertIndex, nc); insertIndex++ }
        }
        if (recordHistory) pushHistorySnapshot(EditOp.InsertLine(globalPos, content))
    }

    private fun applyDeleteLine(globalPos: Int, recordHistory: Boolean = true) {
        val loc = mapPosToChunk(globalPos)
        val c = chunks[loc.chunkIndex]
        if (c is Chunk.InsertedLines) {
            val old = c.lines.removeAt(loc.localIndex)
            if (c.lines.isEmpty()) chunks.removeAt(loc.chunkIndex)
            if (recordHistory) pushHistorySnapshot(EditOp.DeleteLine(globalPos, old))
        } else if (c is Chunk.OriginalBlock) {
            val blockLines = loadBlock(c.blockIndex).toMutableList()
            val old = blockLines.removeAt(loc.localIndex)
            val left = blockLines.subList(0, loc.localIndex)
            val right = blockLines.subList(loc.localIndex, blockLines.size)
            val newChunks = LinkedList<Chunk>()
            if (left.isNotEmpty()) newChunks.add(Chunk.OriginalBlock(createBlockFileFromLines(left)))
            if (right.isNotEmpty()) newChunks.add(Chunk.OriginalBlock(createBlockFileFromLines(right)))
            chunks.removeAt(loc.chunkIndex)
            var insertIndex = loc.chunkIndex
            for (nc in newChunks) { chunks.add(insertIndex, nc); insertIndex++ }
            if (recordHistory) pushHistorySnapshot(EditOp.DeleteLine(globalPos, old))
            synchronized(blockCache) { blockCache.remove(c.blockIndex) }
        }
    }

    // ---------- FIND / REPLACE (stream over chunks) ----------
    private fun findAllMatches(query: String): List<IntRange> {
        val res = ArrayList<IntRange>()
        if (query.isEmpty()) return res
        val regex = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        var acc = 0
        for (c in chunks) {
            when (c) {
                is Chunk.InsertedLines -> {
                    for (i in c.lines.indices) {
                        val ln = c.lines[i]
                        for (m in regex.findAll(ln)) {
                            val start = acc + m.range.first
                            val end = acc + m.range.last
                            res.add(start..end)
                        }
                        acc += ln.length + 1 // plus newline
                    }
                }
                is Chunk.OriginalBlock -> {
                    val blockLines = loadBlock(c.blockIndex)
                    for (ln in blockLines) {
                        for (m in regex.findAll(ln)) {
                            val start = acc + m.range.first
                            val end = acc + m.range.last
                            res.add(start..end)
                        }
                        acc += ln.length + 1
                    }
                }
            }
        }
        // trim last added newline offset if not present; user-facing mapping may assume no trailing newline
        return res
    }

    private fun replaceAll(query: String, replacement: String) {
        lifecycleScope.launch {
            withContext(bgDispatcher) {
                if (query.isEmpty()) return@withContext
                // We'll iterate through all lines and replace in-memory where needed:
                var globalPos = 0
                val newChunks = LinkedList<Chunk>()
                for (c in chunks) {
                    when (c) {
                        is Chunk.InsertedLines -> {
                            val newLines = c.lines.map { it.replace(Regex(Regex.escape(query), RegexOption.IGNORE_CASE), replacement) }.toMutableList()
                            newChunks.add(Chunk.InsertedLines(newLines))
                        }
                        is Chunk.OriginalBlock -> {
                            val blockLines = loadBlock(c.blockIndex)
                            val newLines = blockLines.map { it.replace(Regex(Regex.escape(query), RegexOption.IGNORE_CASE), replacement) }
                            // write new block file(s)
                            // we can split into BLOCK_SIZE sized writes to keep same structure
                            var tempBuf = ArrayList<String>()
                            for (ln in newLines) {
                                tempBuf.add(ln)
                                if (tempBuf.size >= BLOCK_SIZE) {
                                    val idx = createBlockFileFromLines(tempBuf)
                                    newChunks.add(Chunk.OriginalBlock(idx))
                                    tempBuf.clear()
                                }
                            }
                            if (tempBuf.isNotEmpty()) {
                                val idx = createBlockFileFromLines(tempBuf)
                                newChunks.add(Chunk.OriginalBlock(idx))
                                tempBuf.clear()
                            }
                        }
                    }
                }
                // replace chunks
                chunks.clear()
                chunks.addAll(newChunks)
                blockCache.clear()
            }
            withContext(Dispatchers.Main) {
                adapter.notifyDataSetChanged()
                scheduleStatsUpdate()
                scheduleVisibleHighlight()
                pushHistorySnapshot(EditOp.FullSnapshot(chunks.map { copyChunk(it) }))
            }
        }
    }

    // ---------- STATS ----------
    private fun scheduleStatsUpdate(delayMs: Long = 300L) {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch {
            delay(delayMs)
            updateStatsNow()
        }
    }

    private fun updateStatsNow() {
        lifecycleScope.launch(bgDispatcher) {
            var chars = 0L
            var charsNoSpace = 0L
            var words = 0L
            var lines = 0
            for (i in 0 until getLogicalTotalLines()) {
                val ln = getLogicalLine(i) ?: ""
                lines++
                chars += ln.length
                for (ch in ln) {
                    if (!ch.isWhitespace()) {
                        charsNoSpace++
                        // word counting simple
                    }
                }
                // word count quick split
                val w = ln.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                words += w
            }
            val stats = "Words: $words | Chars: $chars | NoSpace: $charsNoSpace | Lines: $lines"
            withContext(Dispatchers.Main) {
                binding.tvStats.text = stats
            }
        }
    }

    // ---------- FONT SIZE / VISUALS ----------
    private fun applyFontSizeFromPrefs() {
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val sizePref = sp.getString(PREF_FONT_SIZE, "normal") ?: "normal"
        val sizeSp = when (sizePref) {
            "small" -> 14f
            "normal" -> 16f
            "medium" -> 18f
            "large" -> 20f
            else -> 16f
        }
        adapter.lineTextSizeSp = sizeSp
        adapter.notifyDataSetChanged()
    }

    private fun applyPrefsVisuals() {
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        adapter.retroMode = sp.getBoolean(PREF_RETRO_MODE, false)
        adapter.amberMode = sp.getBoolean(PREF_AMBER_MODE, false)
        adapter.syntaxOn = sp.getBoolean(PREF_SYNTAX_HIGHLIGHT, false)
        adapter.syntaxMapping = syntaxMapping
        adapter.notifyDataSetChanged()
    }

    // ---------- SYNTAX MAPPING (same as before) ----------
    private suspend fun parseAndStoreSyntaxMappingFromUri(uri: Uri) {
        withContext(bgDispatcher) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { br ->
                        val map = mutableMapOf<String, Int>()
                        br.forEachLine { raw ->
                            val line = raw.trim()
                            if (line.isEmpty()) return@forEachLine
                            if (line.startsWith("#")) return@forEachLine
                            val parts = line.split("=").map { it.trim() }
                            if (parts.size >= 2) {
                                val token = parts[0]; val colorStr = parts[1]
                                try { val color = Color.parseColor(colorStr); if (token.isNotEmpty()) map[token] = color } catch (_: Exception) {}
                            }
                        }
                        withContext(Dispatchers.Main) {
                            syntaxMapping.clear(); syntaxMapping.putAll(map)
                            adapter.syntaxMapping = syntaxMapping
                            adapter.notifyDataSetChanged()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, "Failed to read mapping file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- VISIBLE HIGHLIGHT for RecyclerView items ----------
    private fun scheduleVisibleHighlight(delayMs: Long = 80L) {
        uiUpdateJob?.cancel()
        uiUpdateJob = lifecycleScope.launch {
            delay(delayMs)
            highlightVisibleItems()
        }
    }

    private fun highlightVisibleItems() {
        val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = lm.findLastVisibleItemPosition().coerceAtLeast(0)
        for (i in first..last) {
            val vh = binding.recycler.findViewHolderForAdapterPosition(i) as? LinesAdapter.LineViewHolder
            vh?.applyHighlightAndFormatting(i)
        }
    }

    // ---------- JUMP / UTIL ----------
    private fun jumpToLineIndex(idx: Int) {
        if (idx < 0) return
        val pos = idx.coerceIn(0, adapter.itemCount - 1)
        binding.recycler.post { binding.recycler.scrollToPosition(pos) }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    // ---------- ADAPTER ----------
    inner class LinesAdapter : RecyclerView.Adapter<LinesAdapter.LineViewHolder>() {

        var lineTextSizeSp = 16f
        var retroMode = false
        var amberMode = false
        var syntaxOn = true
        var syntaxMapping: Map<String, Int> = this@EditorActivity.syntaxMapping

        inner class LineViewHolder(val bindingItem: ItemLineBinding) : RecyclerView.ViewHolder(bindingItem.root) {
            private var watcher: TextWatcher? = null

            fun bind(position: Int) {
                val text = getLogicalLine(position) ?: ""
                // remove watcher to set safely
                watcher?.let { bindingItem.etLine.removeTextChangedListener(it) }
                bindingItem.etLine.setText(text)
                bindingItem.etLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, lineTextSizeSp)
                when {
                    amberMode -> bindingItem.etLine.setTextColor(Color.parseColor("#FFBF00"))
                    retroMode -> bindingItem.etLine.setTextColor(Color.parseColor("#00FF66"))
                    else -> bindingItem.etLine.setTextColor(getColorFromAttrOrDefault(android.R.attr.textColorPrimary, Color.parseColor("#E0E0E0")))
                }

                watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        if (ignoreAdapterWatchers) return
                        val newVal = s?.toString() ?: ""
                        // we will treat this as replace of that line
                        applyReplaceLine(adapterPosition, newVal)
                        notifyItemChanged(adapterPosition)
                        scheduleStatsUpdate()
                        scheduleVisibleHighlight()
                    }
                }
                bindingItem.etLine.addTextChangedListener(watcher)

                bindingItem.etLine.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        // do nothing
                        true
                    } else false
                }

                // apply highlight & formatting
                applyHighlightAndFormatting(position)
            }

            fun applyHighlightAndFormatting(position: Int) {
                val raw = getLogicalLine(position) ?: ""
                val editable = bindingItem.etLine.text ?: SpannableStringBuilder("").also { bindingItem.etLine.setText(it) }

                // clear spans
                try {
                    val fg = editable.getSpans(0, editable.length, ForegroundColorSpan::class.java)
                    for (s in fg) try { editable.removeSpan(s) } catch (_: Exception) {}
                    val ss = editable.getSpans(0, editable.length, StyleSpan::class.java)
                    for (s in ss) try { editable.removeSpan(s) } catch (_: Exception) {}
                    val asps = editable.getSpans(0, editable.length, AlignmentSpan::class.java)
                    for (s in asps) try { editable.removeSpan(s) } catch (_: Exception) {}
                    val rs = editable.getSpans(0, editable.length, ReplacementSpan::class.java)
                    for (s in rs) try { editable.removeSpan(s) } catch (_: Exception) {}
                } catch (_: Exception) {}

                if (!syntaxOn || retroMode) return

                // basic token highlight
                var idx = 0
                val len = raw.length
                while (idx < len) {
                    val c = raw[idx]
                    if (c.isLetter() || c == '_') {
                        val start = idx; idx++
                        while (idx < len && (raw[idx].isLetterOrDigit() || raw[idx] == '_')) idx++
                        val word = raw.substring(start, idx)
                        val color = when {
                            syntaxMapping.containsKey(word) -> syntaxMapping[word]
                            kotlinKeywords.contains(word) -> Color.parseColor("#82B1FF")
                            else -> null
                        }
                        if (color != null) {
                            try { editable.setSpan(ForegroundColorSpan(color), start, idx, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Exception) {}
                        }
                    } else idx++
                }

                // inline formatting (same as before)
                applyInlineFormattingToEditable(editable, raw)
            }

            private fun applyInlineFormattingToEditable(editable: Editable, raw: String) {
                // bold
                val boldRegex = Regex("<b>(.*?)</b>", RegexOption.DOT_MATCHES_ALL)
                for (m in boldRegex.findAll(raw)) {
                    val inner = m.groups[1] ?: continue
                    val openLen = "<b>".length
                    val s = m.range.first + openLen
                    val e = s + inner.value.length
                    try { editable.setSpan(StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Exception) {}
                }
                // italic
                val italicRegex = Regex("<i>(.*?)</i>", RegexOption.DOT_MATCHES_ALL)
                for (m in italicRegex.findAll(raw)) {
                    val inner = m.groups[1] ?: continue
                    val openLen = "<i>".length
                    val s = m.range.first + openLen
                    val e = s + inner.value.length
                    try { editable.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Exception) {}
                }
                // center
                val centerRegex = Regex("<center>(.*?)</center>", RegexOption.DOT_MATCHES_ALL)
                for (m in centerRegex.findAll(raw)) {
                    val inner = m.groups[1] ?: continue
                    val openLen = "<center>".length
                    val s = m.range.first + openLen
                    val e = s + inner.value.length
                    try { editable.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Exception) {}
                }
                // hide tags and handle <tab>
                val tagRegex = Regex("<[^>]+>")
                for (m in tagRegex.findAll(raw)) {
                    val tagText = m.value
                    val tagStart = m.range.first
                    val tagEnd = m.range.last + 1
                    try {
                        if (tagText.equals("<tab>", ignoreCase = true)) {
                            editable.setSpan(TabSpan(), tagStart, tagEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } else {
                            editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), tagStart, tagEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LineViewHolder {
            val b = ItemLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return LineViewHolder(b)
        }

        override fun getItemCount(): Int = getLogicalTotalLines()

        override fun onBindViewHolder(holder: LineViewHolder, position: Int) { holder.bind(position) }

        // helper for external focus/select
        fun requestFocusAndSelect(position: Int, selStart: Int, selEnd: Int) {
            if (position < 0 || position >= itemCount) return
            binding.recycler.post {
                binding.recycler.scrollToPosition(position)
                val vh = binding.recycler.findViewHolderForAdapterPosition(position) as? LineViewHolder
                vh?.let {
                    it.bindingItem.etLine.requestFocus()
                    val len = it.bindingItem.etLine.text?.length ?: 0
                    val s = selStart.coerceIn(0, len)
                    val e = selEnd.coerceIn(0, len)
                    it.bindingItem.etLine.setSelection(s, e)
                }
            }
        }
    }

    // ---------- ReplacementSpan Tab ----------
    private class TabSpan(private val spaces: Int = 4) : ReplacementSpan() {
        override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
            val spaceWidth = paint.measureText(" ")
            return (spaceWidth * spaces).toInt()
        }
        override fun draw(canvas: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {}
    }

    // ---------- remaining menu actions: copy/paste/find/replace/encrypt/decrypt similar to original but adapted ----------

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open -> { openDocumentLauncher.launch(arrayOf("*/*")); true }
            R.id.action_save -> {
                val uri = currentDocumentUri
                if (uri != null) saveToUri(uri) else createDocumentLauncherSave.launch("untitled.txt")
                true
            }
            R.id.action_save_as -> { createDocumentLauncherSaveAs.launch("untitled.txt"); true }
            R.id.action_find -> {
                showFindReplaceDialog(); true
            }
            R.id.action_copy -> {
                // copy full document
                val full = buildString {
                    for (i in 0 until getLogicalTotalLines()) {
                        append(getLogicalLine(i) ?: "")
                        if (i < getLogicalTotalLines() - 1) append("\n")
                    }
                }
                if (full.isNotEmpty()) {
                    copyToClipboard(full); Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_paste -> {
                val pasted = pasteFromClipboard()
                if (!pasted.isNullOrEmpty()) {
                    val (pos, offset) = getFocusedLineAndOffset()
                    val cursorPos = if (pos >= 0) pos else getLogicalTotalLines()
                    val line = if (pos >= 0) (getLogicalLine(pos) ?: "") else ""
                    if (pos >= 0) {
                        // insert pasted at cursor
                        val left = line.substring(0, offset)
                        val right = line.substring(offset)
                        applyReplaceLine(pos, left + pasted + right)
                    } else {
                        // append new lines at end
                        val parts = pasted.split("\n")
                        var idx = getLogicalTotalLines()
                        for (p in parts) { applyInsertLine(idx++, p) }
                    }
                    Toast.makeText(this, "Pasted", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_undo -> { performUndo(); true }
            R.id.action_redo -> { performRedo(); true }
            R.id.action_clear -> {
                val dlg = AlertDialog.Builder(this).setTitle("Clear document").setMessage("Clear entire document?").setPositiveButton("Clear") { _, _ ->
                    pushHistorySnapshot(EditOp.FullSnapshot(chunks.map { copyChunk(it) }))
                    chunks.clear()
                    chunks.add(Chunk.InsertedLines(mutableListOf("")))
                    adapter.notifyDataSetChanged()
                    scheduleStatsUpdate()
                }.setNegativeButton("Cancel", null).create()
                dlg.show()
                true
            }
            R.id.action_select_all -> {
                // not trivial across multiple EditTexts; we can focus first and instruct user
                if (adapter.itemCount > 0) {
                    adapter.requestFocusAndSelect(0, 0, adapter.itemCount)
                }
                true
            }
            R.id.action_encrypt -> { promptEncryptCurrent(); true }
            R.id.action_decrypt -> { promptDecryptCurrent(); true }
            R.id.action_settings -> {
                try { startActivity(Intent(this, SettingsActivity::class.java)) } catch (_: Exception) { showSettingsFallbackDialog() }
                true
            }
            R.id.action_about -> {
                try { startActivity(Intent(this, AboutActivity::class.java)) } catch (_: Exception) { showSettingsFallbackDialog() }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // helper: get focused line and offset (search visible holders)
    private fun getFocusedLineAndOffset(): Pair<Int, Int> {
        val lm = binding.recycler.layoutManager as? LinearLayoutManager ?: return Pair(-1, 0)
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        for (i in first..last) {
            val vh = binding.recycler.findViewHolderForAdapterPosition(i) as? LinesAdapter.LineViewHolder
            vh?.let {
                if (it.bindingItem.etLine.isFocused) {
                    val off = it.bindingItem.etLine.selectionStart.coerceAtLeast(0)
                    return Pair(i, off)
                }
            }
        }
        return Pair(-1, 0)
    }

    // basic find/replace dialog adapted to our streaming model
    private fun showFindReplaceDialog() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_find_replace, null)
        val etFind = view.findViewById<EditText>(R.id.etFind)
        val etReplace = view.findViewById<EditText>(R.id.etReplace)
        val tvCount = view.findViewById<TextView>(R.id.tvMatchesCount)
        val btnPrev = view.findViewById<View>(R.id.btnPrev)
        val btnNext = view.findViewById<View>(R.id.btnNext)
        val btnR1 = view.findViewById<View>(R.id.btnReplaceOne)
        val btnRAll = view.findViewById<View>(R.id.btnReplaceAll)

        var matches: List<IntRange> = emptyList()
        var currentIdx = -1

        fun computeMatches(q: String) {
            lifecycleScope.launch {
                val found = withContext(bgDispatcher) { findAllMatches(q) }
                matches = found
                withContext(Dispatchers.Main) {
                    tvCount.text = "${matches.size} matches"
                    currentIdx = if (matches.isNotEmpty()) 0 else -1
                    if (currentIdx >= 0) {
                        // reveal
                        val startAbs = matches[currentIdx].first
                        revealSelection(startAbs)
                    }
                }
            }
        }

        fun tryGoToLine(query: String): Boolean {
            if (query.startsWith(":")) {
                val num = query.substring(1).toIntOrNull() ?: return false
                goToLine(num); return true
            }
            return false
        }

        btnNext.setOnClickListener {
            if (matches.isEmpty()) { Toast.makeText(this, "No matches", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            currentIdx = (currentIdx + 1) % matches.size
            val start = matches[currentIdx].first
            revealSelection(start)
        }
        btnPrev.setOnClickListener {
            if (matches.isEmpty()) { Toast.makeText(this, "No matches", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            currentIdx = if (currentIdx - 1 < 0) matches.size - 1 else currentIdx - 1
            val start = matches[currentIdx].first
            revealSelection(start)
        }

        btnR1.setOnClickListener {
            val q = etFind.text.toString()
            val r = etReplace.text.toString()
            if (q.isEmpty()) { Toast.makeText(this, "Query empty", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (matches.isEmpty() || currentIdx < 0) { Toast.makeText(this, "No current match", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val range = matches[currentIdx]
            // replace that occurrence by locating line and modifying its content
            val pos = range.first
            val (chunkLoc, local) = Pair(mapPosToChunk(pos).chunkIndex, mapPosToChunk(pos).localIndex)
            val line = getLogicalLine(pos) ?: ""
            val relStart = pos - cumulativeCharsBeforeLine(pos)
            // safer: replace first occurrence in that line (case-insensitive)
            val replacedLine = line.replaceFirst(Regex(Regex.escape(q), RegexOption.IGNORE_CASE), r)
            applyReplaceLine(mapPosToChunk(pos).let { val c = it.chunkIndex; // compute global pos of start of that line
                // get global line start pos: we already have pos's line index = global line number
                val globalLineIdx = mapPosToChunk(pos).let { (ci, li) ->
                    var acc = 0
                    for (i in 0 until ci) acc += chunkSize(chunks[i])
                    acc + li
                }
                globalLineIdx
            }, replacedLine)
            computeMatches(q)
            Toast.makeText(this, "Replaced one", Toast.LENGTH_SHORT).show()
        }

        btnRAll.setOnClickListener {
            val q = etFind.text.toString()
            val r = etReplace.text.toString()
            if (q.isEmpty()) { Toast.makeText(this, "Query empty", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            lifecycleScope.launch {
                withContext(bgDispatcher) {
                    replaceAll(q, r)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, "Replaced all", Toast.LENGTH_SHORT).show()
                }
            }
        }

        etFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                if (!tryGoToLine(q)) computeMatches(q)
            }
        })

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_find_replace_title))
            .setView(view)
            .setNegativeButton(getString(R.string.close_button), null)
            .create()
        dialog.show()
    }

    private fun cumulativeCharsBeforeLine(globalPos: Int): Int {
        // compute number of chars before start of the line containing globalPos
        var accChars = 0
        val loc = mapPosToChunk(globalPos)
        // compute global line index
        var globalLineIdx = 0
        for (i in 0 until loc.chunkIndex) globalLineIdx += chunkSize(chunks[i])
        globalLineIdx += loc.localIndex
        // now iterate lines up to globalLineIdx
        var counted = 0
        for (i in 0 until globalLineIdx) {
            val ln = getLogicalLine(i) ?: ""
            counted += ln.length + 1
        }
        return counted
    }

    private fun revealSelection(absOffset: Int) {
        // map absolute char offset to line index (we use cumulative)
        // simpler: we can compute line by scanning lines; not optimal but acceptable when triggered by user
        var acc = 0
        val total = getLogicalTotalLines()
        for (i in 0 until total) {
            val ln = getLogicalLine(i) ?: ""
            val len = ln.length
            if (absOffset <= acc + len) {
                binding.recycler.post {
                    binding.recycler.scrollToPosition(i)
                    adapter.requestFocusAndSelect(i, 0, 0)
                }
                return
            }
            acc += len + 1
        }
    }

    private fun goToLine(lineNumber: Int) {
        if (lineNumber <= 0) return
        val idx = (lineNumber - 1).coerceIn(0, getLogicalTotalLines() - 1)
        jumpToLineIndex(idx)
    }

    // ---------- encryption/decryption (reuse your Secure) ----------
    private fun promptEncryptCurrent() {
        val input = EditText(this)
        input.hint = "Password"
        val dlg = AlertDialog.Builder(this).setTitle("Encrypt").setView(input).setPositiveButton("Encrypt", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val pw = input.text.toString()
                if (pw.isEmpty()) { Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                dlg.dismiss()
                performEncrypt(pw.toCharArray())
            }
        }
        dlg.show()
    }

    private fun promptDecryptCurrent() {
        val input = EditText(this)
        input.hint = "Password"
        val dlg = AlertDialog.Builder(this).setTitle("Decrypt").setView(input).setPositiveButton("Decrypt", null).setNegativeButton("Cancel", null).create()
        dlg.setOnShowListener {
            val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val pw = input.text.toString()
                if (pw.isEmpty()) { Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                dlg.dismiss()
                performDecrypt(pw.toCharArray())
            }
        }
        dlg.show()
    }

    private fun performEncrypt(password: CharArray) {
        // build full text and encrypt — beware memory if huge; this mirrors original behaviour but note that
        // encrypting huge files will require memory. We can stream encryption later if needed.
        lifecycleScope.launch {
            val full = StringBuilder()
            val total = getLogicalTotalLines()
            for (i in 0 until total) {
                full.append(getLogicalLine(i) ?: "")
                if (i < total - 1) full.append("\n")
            }
            if (full.isEmpty()) { Toast.makeText(this@EditorActivity, "Nothing to encrypt", Toast.LENGTH_SHORT).show(); return@launch }
            val waitDlg = AlertDialog.Builder(this@EditorActivity).setTitle(getString(R.string.dialog_encrypting_title)).setMessage(getString(R.string.dialog_encrypting_message)).setCancelable(false).create()
            waitDlg.show()
            val dotsJob = lifecycleScope.launch {
                var dots = 0
                while (isActive) {
                    withContext(Dispatchers.Main) { waitDlg.setMessage(getString(R.string.dialog_encrypting_message) + ".".repeat(dots)) }
                    dots = (dots + 1) % 4; delay(400)
                }
            }
            try {
                val encrypted = withContext(bgDispatcher) { Secure.encrypt(password, full.toString()) }
                // replace whole doc with encrypted single-line
                chunks.clear()
                chunks.add(Chunk.InsertedLines(mutableListOf(encrypted)))
                adapter.notifyDataSetChanged()
                pushHistorySnapshot(EditOp.FullSnapshot(chunks.map { copyChunk(it) }))
                scheduleStatsUpdate()
                scheduleVisibleHighlight()
                Toast.makeText(this@EditorActivity, getString(R.string.toast_encrypt_done), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@EditorActivity, "Encryption failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            } finally {
                dotsJob.cancel()
                withContext(Dispatchers.Main) { waitDlg.dismiss() }
            }
        }
    }

    private fun performDecrypt(password: CharArray) {
        lifecycleScope.launch {
            // assemble full string (may be large)
            val sb = StringBuilder()
            val total = getLogicalTotalLines()
            for (i in 0 until total) {
                sb.append(getLogicalLine(i) ?: "")
                if (i < total - 1) sb.append("\n")
            }
            val encrypted = sb.toString()
            if (encrypted.isEmpty()) { Toast.makeText(this@EditorActivity, getString(R.string.toast_nothing_to_decrypt), Toast.LENGTH_SHORT).show(); return@launch }
            val waitDlg = AlertDialog.Builder(this@EditorActivity).setTitle(getString(R.string.dialog_decrypting_title)).setMessage(getString(R.string.dialog_decrypting_message)).setCancelable(false).create()
            waitDlg.show()
            val dotsJob = lifecycleScope.launch {
                var dots = 0
                while (isActive) {
                    withContext(Dispatchers.Main) { waitDlg.setMessage(getString(R.string.dialog_decrypting_message) + ".".repeat(dots)) }
                    dots = (dots + 1) % 4; delay(400)
                }
            }
            try {
                val plain = withContext(bgDispatcher) { Secure.decrypt(password, encrypted) }
                // reopen plain using block mechanism: cleanup and stream plain into blocks
                cleanupBlocksDir()
                blocksDir = File(cacheDir, "editor_blocks_${System.currentTimeMillis()}").also { it.mkdirs() }
                originalBlockCount = 0
                // write plain lines into blocks
                val lines = plain.split("\n")
                var buf = ArrayList<String>()
                for (ln in lines) {
                    buf.add(ln)
                    if (buf.size >= BLOCK_SIZE) {
                        writeBlockToFile(buf); buf.clear()
                    }
                }
                if (buf.isNotEmpty()) { writeBlockToFile(buf); buf.clear() }
                chunks.clear(); for (i in 0 until originalBlockCount) chunks.add(Chunk.OriginalBlock(i))
                adapter.notifyDataSetChanged()
                pushHistorySnapshot(EditOp.FullSnapshot(chunks.map { copyChunk(it) }))
                scheduleStatsUpdate(); scheduleVisibleHighlight()
                Toast.makeText(this@EditorActivity, getString(R.string.toast_decrypt_done), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@EditorActivity, "Decryption failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            } finally {
                dotsJob.cancel(); withContext(Dispatchers.Main) { waitDlg.dismiss() }
            }
        }
    }

    // ---------- small helpers ----------
    private fun getColorFromAttrOrDefault(attr: Int, def: Int): Int {
        return try {
            val typed = obtainStyledAttributes(intArrayOf(attr))
            val color = typed.getColor(0, def)
            typed.recycle()
            color
        } catch (e: Exception) { def }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("text", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun pasteFromClipboard(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val primary = clipboard.primaryClip
        return if (primary != null && primary.itemCount > 0) {
            primary.getItemAt(0).coerceToText(this).toString()
        } else null
    }

    private fun showSettingsFallbackDialog() {
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val prevent = sp.getBoolean(PREF_PREVENT_SCREENSHOT, false)
        val undo = sp.getBoolean(PREF_UNDO_ENABLED, true)
        val dark = sp.getBoolean("theme_dark", true)
        val font = sp.getString(PREF_FONT_SIZE, "normal") ?: "normal"
        val format = sp.getBoolean(PREF_FORMAT_ON, false)
        val gutter = sp.getBoolean("show_line_numbers", false)
        val retro = sp.getBoolean(PREF_RETRO_MODE, false)
        val syntax = sp.getBoolean(PREF_SYNTAX_HIGHLIGHT, false)
        AlertDialog.Builder(this).setTitle("Settings").setMessage(
            "Settings available:\n• Prevent screenshots: $prevent\n• Undo enabled: $undo\n• Dark theme: $dark\n• Font size: $font\n• Formatting: $format\n• Line numbers (ignored): $gutter\n• Retro: $retro\n• Syntax highlight: $syntax\n\nOpen SettingsActivity to change these."
        ).setPositiveButton("OK", null).create().show()
    }
}
