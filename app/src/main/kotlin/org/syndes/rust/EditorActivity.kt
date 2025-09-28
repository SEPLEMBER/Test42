package org.syndes.rust

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.syndes.rust.databinding.ActivityEditorBinding
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding

    // SAF launchers
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createDocumentLauncher: ActivityResultLauncher<String>

    // current doc uri
    private var currentDocumentUri: Uri? = null

    // Find/Replace state
    private var lastQuery: String? = null
    private var matches: List<IntRange> = emptyList()
    private var currentMatchIdx: Int = -1

    // last stats string to avoid redundant UI updates
    private var lastStatsText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // SAF
        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readDocumentUri(it) }
        }
        createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let {
                currentDocumentUri = it
                writeToUri(it, binding.editor.text.toString())
            }
        }

        // menu clicks
        binding.toolbar.setOnMenuItemClickListener { item ->
            onOptionsItemSelected(item)
        }

        // initial hint visibility, word/char count
        binding.emptyHint.visibility = if (binding.editor.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        updateStatsAsync()

        // text watcher: update hint and stats and clear matches on change
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                binding.emptyHint.visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
                // update stats off the main thread
                updateStatsAsync()
                // Invalidate previous matches (we'll recompute when user searches again)
                matches = emptyList()
                currentMatchIdx = -1
            }
        })

        // Scroll thumb: touch to jump scroll
        setupScrollThumb()
    }

    // ---------- MENU ACTIONS ----------
    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open -> {
                openDocumentLauncher.launch(arrayOf("text/*"))
                true
            }
            R.id.action_save -> {
                val uri = currentDocumentUri
                if (uri != null) {
                    writeToUri(uri, binding.editor.text.toString())
                } else {
                    createDocumentLauncher.launch("untitled.txt")
                }
                true
            }
            R.id.action_find -> {
                showFindReplaceDialog()
                true
            }
            R.id.action_copy -> {
                val selStart = binding.editor.selectionStart
                val selEnd = binding.editor.selectionEnd
                if (selStart >= 0 && selEnd > selStart) {
                    val sel = binding.editor.text?.substring(selStart, selEnd)
                    if (!sel.isNullOrEmpty()) {
                        copyToClipboard(sel)
                        Toast.makeText(this, "Copied selection to clipboard", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Nothing selected", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_paste -> {
                val pasted = pasteFromClipboard()
                if (!pasted.isNullOrEmpty()) {
                    val pos = binding.editor.selectionStart.coerceAtLeast(0)
                    binding.editor.text?.insert(pos, pasted)
                    Toast.makeText(this, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---------- FILE IO ----------
    private fun readDocumentUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                // try to persist permission if available (best-effort)
                try {
                    val takeFlags = (intent?.flags ?: 0) and
                        (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (ignored: Exception) {}

                val content = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { br ->
                            val sb = StringBuilder()
                            var line: String? = br.readLine()
                            while (line != null) {
                                sb.append(line)
                                line = br.readLine()
                                if (line != null) sb.append("\n")
                            }
                            sb.toString()
                        }
                    } ?: ""
                }

                currentDocumentUri = uri
                // set text on main thread
                binding.editor.setText(content, TextView.BufferType.EDITABLE)
                binding.editor.setSelection(0)
                updateStatsAsync()
                Toast.makeText(this@EditorActivity, "File opened", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@EditorActivity, "Error opening file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun writeToUri(uri: Uri, text: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                        BufferedWriter(OutputStreamWriter(outputStream, Charsets.UTF_8)).use { bw ->
                            bw.write(text)
                            bw.flush()
                        }
                    } ?: throw IllegalStateException("Cannot open output stream")
                }
                Toast.makeText(this@EditorActivity, "Saved", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@EditorActivity, "Error saving file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- FIND & REPLACE ----------
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

        // prefill with last query
        if (!lastQuery.isNullOrEmpty()) etFind.setText(lastQuery)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Find / Replace")
            .setView(view) // explicitly pass View
            .setNegativeButton("Close", null)
            .create()

        // select given match index on main thread and reveal it
        fun selectMatchAt(index: Int) {
            if (index < 0 || index >= matches.size) return
            val range = matches[index]
            binding.editor.requestFocus()
            val safeStart = range.first.coerceIn(0, binding.editor.text?.length ?: 0)
            val safeEnd = (range.last + 1).coerceIn(0, binding.editor.text?.length ?: 0)
            binding.editor.setSelection(safeStart, safeEnd)
            revealSelection(safeStart)
            lastQuery = etFind.text.toString()
        }

        // recompute matches when user types query (off main thread)
        fun computeMatchesAndUpdate(query: String) {
            lifecycleScope.launch(Dispatchers.Default) {
                val text = binding.editor.text?.toString() ?: ""
                if (query.isEmpty()) {
                    matches = emptyList()
                    currentMatchIdx = -1
                } else {
                    val escaped = Regex.escape(query)
                    val regex = Regex(escaped, RegexOption.IGNORE_CASE)
                    val found = regex.findAll(text).map { it.range.first..it.range.last }.toList()
                    matches = found
                    currentMatchIdx = if (found.isNotEmpty()) 0 else -1
                }
                withContext(Dispatchers.Main) {
                    tvCount.text = "${matches.size} matches"
                    if (currentMatchIdx >= 0 && matches.isNotEmpty()) {
                        selectMatchAt(currentMatchIdx)
                    }
                }
            }
        }

        // next / prev handlers
        btnNext.setOnClickListener {
            if (matches.isEmpty()) {
                Toast.makeText(this, "No matches", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentMatchIdx = (currentMatchIdx + 1) % matches.size
            selectMatchAt(currentMatchIdx)
        }
        btnPrev.setOnClickListener {
            if (matches.isEmpty()) {
                Toast.makeText(this, "No matches", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentMatchIdx = if (currentMatchIdx - 1 < 0) matches.size - 1 else currentMatchIdx - 1
            selectMatchAt(currentMatchIdx)
        }

        // Replace one (R1)
        btnR1.setOnClickListener {
            val q = etFind.text.toString()
            val r = etReplace.text.toString()
            if (q.isEmpty()) {
                Toast.makeText(this, "Query empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (matches.isEmpty() || currentMatchIdx < 0) {
                Toast.makeText(this, "No current match to replace", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val range = matches[currentMatchIdx]
            val editable = binding.editor.text ?: return@setOnClickListener
            // perform replacement on main thread (small op)
            editable.replace(range.first, range.last + 1, r)
            // recompute matches (do on background)
            computeMatchesAndUpdate(q)
        }

        // Replace All (RALL)
        btnRAll.setOnClickListener {
            val q = etFind.text.toString()
            val r = etReplace.text.toString()
            if (q.isEmpty()) {
                Toast.makeText(this, "Query empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch(Dispatchers.Default) {
                val full = binding.editor.text?.toString() ?: ""
                val escaped = Regex.escape(q)
                val regex = Regex(escaped, RegexOption.IGNORE_CASE)
                // explicitly name parameters to avoid overload ambiguity
                val replaced = regex.replace(input = full, replacement = r)
                withContext(Dispatchers.Main) {
                    binding.editor.setText(replaced, TextView.BufferType.EDITABLE)
                    // move cursor to start
                    binding.editor.setSelection(0)
                    matches = emptyList()
                    currentMatchIdx = -1
                    tvCount.text = "0 matches"
                    updateStatsAsync()
                    Toast.makeText(this@EditorActivity, "Replaced all", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // recompute when query changes
        etFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                computeMatchesAndUpdate(q)
            }
        })

        dialog.show()
        // initial compute
        val initialQuery = etFind.text.toString()
        if (initialQuery.isNotEmpty()) computeMatchesAndUpdate(initialQuery)
    }

    // reveal selection by scrolling editor so the selection line is visible
    private fun revealSelection(selectionStart: Int) {
        binding.editor.post {
            val layout = binding.editor.layout ?: return@post
            val line = layout.getLineForOffset(selectionStart)
            val y = layout.getLineTop(line)
            // smooth scroll to approximate position
            binding.editor.scrollTo(0, y)
        }
    }

    // ---------- SCROLL THUMB ----------
    private fun setupScrollThumb() {
        // map touch y to editor scroll
        binding.scrollThumb.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val h = v.height.toFloat().coerceAtLeast(1f)
                    val y = event.y.coerceIn(0f, h)
                    val layout = binding.editor.layout
                    if (layout != null) {
                        // layout exists -> compute immediately
                        val contentHeight = layout.height
                        val viewHeight = binding.editor.height
                        val maxScroll = (contentHeight - viewHeight).coerceAtLeast(0)
                        val proportion = (y / h).coerceIn(0f, 1f)
                        val scrollY = (proportion * maxScroll).toInt()
                        binding.editor.scrollTo(0, scrollY)
                    } else {
                        // layout not ready yet -> post and compute later
                        binding.editor.post {
                            val layout2 = binding.editor.layout ?: return@post
                            val contentHeight = layout2.height
                            val viewHeight = binding.editor.height
                            val maxScroll = (contentHeight - viewHeight).coerceAtLeast(0)
                            val proportion = (y / h).coerceIn(0f, 1f)
                            val scrollY = (proportion * maxScroll).toInt()
                            binding.editor.scrollTo(0, scrollY)
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    // ---------- UTILS ----------
    private fun updateStatsAsync() {
        val currentText = binding.editor.text?.toString() ?: ""
        lifecycleScope.launch(Dispatchers.Default) {
            val chars = currentText.length
            // fast iterative word count (avoids regex overhead)
            var words = 0
            var inWord = false
            for (c in currentText) {
                if (!c.isWhitespace()) {
                    if (!inWord) {
                        words++
                        inWord = true
                    }
                } else {
                    inWord = false
                }
            }
            val stats = "Words: $words | Chars: $chars"
            if (stats != lastStatsText) {
                lastStatsText = stats
                withContext(Dispatchers.Main) {
                    binding.tvStats.text = stats
                }
            }
        }
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
            val item = primary.getItemAt(0)
            item.coerceToText(this).toString()
        } else null
    }
}
