package org.syndes.rust

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.TextWatcher
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.syndes.rust.databinding.ActivityEditorBinding
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.ArrayList
import kotlin.math.max
import kotlin.math.min

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding

    // SAF launchers
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createDocumentLauncherSave: ActivityResultLauncher<String>
    private lateinit var createDocumentLauncherSaveAs: ActivityResultLauncher<String>
    private lateinit var loadSyntaxMappingLauncher: ActivityResultLauncher<Array<String>>

    // current doc uri
    private var currentDocumentUri: Uri? = null

    // Find/Replace state
    private var lastQuery: String? = null
    private var matches: List<IntRange> = emptyList()
    private var currentMatchIdx: Int = -1

    // stats debounce job
    private var statsJob: Job? = null
    private var lastStatsText: String = ""

    // History (undo/redo)
    private val history = ArrayList<String>()
    private var historyIndex = -1
    private var historyJob: Job? = null
    private val maxHistory = 50
    private var ignoreTextWatcher = false

    // Preferences
    private val prefsName = "editor_prefs"
    private val PREF_PREVENT_SCREENSHOT = "prevent_screenshot"
    private val PREF_UNDO_ENABLED = "undo_enabled"
    private val PREF_THEME_DARK = "theme_dark"
    private val PREF_FONT_SIZE = "font_size"

    // other prefs keys (kept from previous)
    private val PREF_FORMAT_ON = "format_on"
    private val PREF_SHOW_LINE_NUMBERS = "show_line_numbers"
    private val PREF_RETRO_MODE = "retro_mode"
    private val PREF_SYNTAX_HIGHLIGHT = "syntax_highlight"
    private val PREF_SYNTAX_MAPPING_URI = "syntax_mapping_uri"
    private val PREF_SYNTAX_LANGUAGE = "syntax_language"
    private val PREF_AMBER_MODE = "amber_mode"

    // coroutine helper
    private val bgDispatcher = Dispatchers.Default

    // highlighting and UI jobs
    private var highlightJob: Job? = null
    private var uiUpdateJob: Job? = null
    private var scrollObserverAttached = false
    private var onScrollListener: ViewTreeObserver.OnScrollChangedListener? = null

    // syntax helpers (kept)
    private val kotlinKeywords = setOf(
        "fun","val","var","if","else","for","while","return","import","class","object",
        "private","public","protected","internal","override","when","in","is","null","true","false"
    )
    private val syntaxMapping = mutableMapOf<String, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        // apply theme pref early
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val dark = sp.getBoolean(PREF_THEME_DARK, true)
        AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // make jump zones accept clicks
        binding.jumpTop.isClickable = true
        binding.jumpTop.isFocusable = true
        binding.jumpTop.bringToFront()
        binding.jumpBottom.isClickable = true
        binding.jumpBottom.isFocusable = true
        binding.jumpBottom.bringToFront()

        // Apply FLAG_SECURE if preference set
        if (sp.getBoolean(PREF_PREVENT_SCREENSHOT, false)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        // SAF: Open and two distinct CreateDocument launchers (save / save as)
        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readDocumentUri(it) }
        }

        createDocumentLauncherSave = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { uriCreated ->
                currentDocumentUri = uriCreated
                writeToUri(uriCreated, binding.editor.text?.toString() ?: "")
            }
        }

        createDocumentLauncherSaveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { uriCreated ->
                writeToUri(uriCreated, binding.editor.text?.toString() ?: "")
            }
        }

        // optional mapping loader (kept, may be unused)
        loadSyntaxMappingLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                lifecycleScope.launch {
                    parseAndStoreSyntaxMappingFromUri(it)
                    Toast.makeText(this@EditorActivity, "Syntax mapping loaded", Toast.LENGTH_SHORT).show()
                    scheduleHighlight()
                }
            }
        }

        // menu clicks
        binding.toolbar.setOnMenuItemClickListener { item ->
            onOptionsItemSelected(item)
        }

        // initial hint & stats
        binding.emptyHint.visibility = if (binding.editor.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        scheduleStatsUpdate()

        // apply font size & visuals
        applyFontSizeFromPrefs()
        applyPrefsVisuals()

        // text watcher: update hint, stats (debounced), and history
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { /* no-op */ }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { /* no-op */ }
            override fun afterTextChanged(s: Editable?) {
                if (ignoreTextWatcher) return
                binding.emptyHint.visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
                scheduleStatsUpdate()
                scheduleHistorySnapshot()
                matches = emptyList()
                currentMatchIdx = -1
                scheduleHighlight()
            }
        })

        // jump zones
        binding.jumpTop.setOnClickListener {
            binding.editor.requestFocus()
            binding.editor.setSelection(0)
            binding.editor.post { binding.editor.scrollTo(0, 0) }
        }
        binding.jumpBottom.setOnClickListener {
            val len = binding.editor.text?.length ?: 0
            binding.editor.requestFocus()
            binding.editor.setSelection(len)
            binding.editor.post {
                val layout = binding.editor.layout
                if (layout != null) {
                    val lastLine = max(0, layout.lineCount - 1)
                    val y = layout.getLineTop(lastLine)
                    binding.editor.scrollTo(0, y)
                } else {
                    binding.editor.scrollTo(0, Int.MAX_VALUE)
                }
            }
        }

        // attach scroll observer for highlight updates
        attachScrollObserver()

        // quick edge scroll handling (right edge) — kept
        binding.root.setOnTouchListener { v, ev ->
            try {
                val edgeWidthPx = dpToPx(56)
                val x = ev.x
                val y = ev.y
                val w = v.width
                if (x >= w - edgeWidthPx) {
                    if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                        val layout = binding.editor.layout ?: return@setOnTouchListener true
                        val ratio = (y / v.height).coerceIn(0f, 1f)
                        val targetLine = ((layout.lineCount - 1) * ratio).toInt().coerceIn(0, max(0, layout.lineCount - 1))
                        val offset = layout.getLineStart(targetLine)
                        binding.editor.requestFocus()
                        binding.editor.setSelection(offset)
                        val top = layout.getLineTop(targetLine)
                        binding.editor.scrollTo(0, top)
                    }
                    return@setOnTouchListener true
                }
            } catch (_: Exception) { /* ignore touch errors */ }
            false
        }

        // overlay buttons: hook listeners (initially overlay is gone)
        setupOverlayControls()
    }

    override fun onResume() {
        super.onResume()
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (sp.getBoolean(PREF_PREVENT_SCREENSHOT, false)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        applyFontSizeFromPrefs()
        applyPrefsVisuals()
        scheduleHighlight()
    }

    override fun onDestroy() {
        super.onDestroy()
        highlightJob?.cancel()
        uiUpdateJob?.cancel()
        statsJob?.cancel()
        historyJob?.cancel()
    }

    // ---------- MENU ACTIONS ----------
    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open -> {
                openDocumentLauncher.launch(arrayOf("*/*"))
                true
            }
            R.id.action_save -> {
                val uri = currentDocumentUri
                if (uri != null) {
                    writeToUri(uri, binding.editor.text?.toString() ?: "")
                } else {
                    createDocumentLauncherSave.launch("untitled.txt")
                }
                true
            }
            R.id.action_save_as -> {
                createDocumentLauncherSaveAs.launch("untitled.txt")
                true
            }
            R.id.action_find -> {
                showFindReplaceDialog()
                true
            }
            R.id.action_copy -> {
                val selStart = binding.editor.selectionStart
                val selEnd = binding.editor.selectionEnd
                val text = binding.editor.text?.toString() ?: ""
                val toCopy = if (selStart >= 0 && selEnd > selStart) text.substring(selStart, selEnd) else text
                if (toCopy.isNotEmpty()) {
                    copyToClipboard(toCopy)
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_paste -> {
                val pasted = pasteFromClipboard()
                if (!pasted.isNullOrEmpty()) {
                    val pos = binding.editor.selectionStart.coerceAtLeast(0)
                    binding.editor.text?.insert(pos, pasted)
                    Toast.makeText(this, "Pasted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_undo -> { performUndo(); true }
            R.id.action_redo -> { performRedo(); true }
            R.id.action_clear -> { confirmAndClear(); true }
            R.id.action_select_all -> {
                val tlen = binding.editor.text?.length ?: 0
                if (tlen > 0) {
                    binding.editor.requestFocus()
                    binding.editor.selectAll()
                }
                true
            }
            R.id.action_encrypt -> { promptEncryptCurrent(); true }
            R.id.action_decrypt -> { promptDecryptCurrent(); true }
            R.id.action_settings -> {
                try {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                } catch (e: Exception) {
                    showSettingsFallbackDialog()
                }
                true
            }
            R.id.action_about -> {
                try {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                } catch (e: Exception) {
                    showSettingsFallbackDialog()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmAndClear() {
        val dlg = AlertDialog.Builder(this)
            .setTitle("Clear document")
            .setMessage("Are you sure you want to clear the entire document? This action can be undone (if Undo is enabled).")
            .setPositiveButton("Clear") { _, _ ->
                pushHistorySnapshot(binding.editor.text?.toString() ?: "")
                ignoreTextWatcher = true
                binding.editor.setText("", TextView.BufferType.EDITABLE)
                binding.editor.setSelection(0)
                ignoreTextWatcher = false
                scheduleStatsUpdate()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dlg.show()
    }

    // ---------- FILE IO ----------
    private fun readDocumentUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                try {
                    val takeFlags = (intent?.flags ?: 0) and
                            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (_: Exception) { /* ignore */ }

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
                ignoreTextWatcher = true
                binding.editor.setText(content, TextView.BufferType.EDITABLE)
                binding.editor.setSelection(0)
                ignoreTextWatcher = false

                history.clear()
                historyIndex = -1
                pushHistorySnapshot(content)

                scheduleStatsUpdate()
                scheduleHighlight()
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
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
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

    // ---------- FIND & REPLACE (full dialog) ----------
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
        val btnMinimize = view.findViewById<View>(R.id.btnMinimize)

        if (!lastQuery.isNullOrEmpty()) etFind.setText(lastQuery)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Find / Replace")
            .setView(view)
            .setNegativeButton("Close", null)
            .create()

        // enable minimize only if there's a query
        fun updateMinimizeState() {
            val q = etFind.text?.toString() ?: ""
            btnMinimize.isEnabled = q.isNotEmpty()
        }

        updateMinimizeState()

        // select match (activity-level)
        fun selectMatchAtLocal(index: Int) {
            if (index < 0 || index >= matches.size) return
            val range = matches[index]
            binding.editor.requestFocus()
            val safeStart = range.first.coerceIn(0, binding.editor.text?.length ?: 0)
            val safeEnd = (range.last + 1).coerceIn(0, binding.editor.text?.length ?: 0)
            binding.editor.setSelection(safeStart, safeEnd)
            revealSelection(safeStart)
            lastQuery = etFind.text.toString()
        }

        // compute matches (activity-level helper)
        fun computeMatchesAndUpdateLocal(query: String) {
            // special "go to line" check
            if (query.startsWith(":")) {
                val num = query.substring(1).toIntOrNull()
                if (num != null) {
                    goToLine(num)
                    tvCount.text = "Go to line"
                    return
                }
            }

            lifecycleScope.launch(bgDispatcher) {
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
                        selectMatchAtLocal(currentMatchIdx)
                    }
                }
            }
        }

        // next / prev
        btnNext.setOnClickListener {
            if (matches.isEmpty()) { Toast.makeText(this, "No matches", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            currentMatchIdx = (currentMatchIdx + 1) % matches.size
            selectMatchAtLocal(currentMatchIdx)
        }
        btnPrev.setOnClickListener {
            if (matches.isEmpty()) { Toast.makeText(this, "No matches", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            currentMatchIdx = if (currentMatchIdx - 1 < 0) matches.size - 1 else currentMatchIdx - 1
            selectMatchAtLocal(currentMatchIdx)
        }

        btnR1.setOnClickListener {
            val q = etFind.text.toString()
            val r = etReplace.text.toString()
            if (q.isEmpty()) { Toast.makeText(this, "Query empty", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (matches.isEmpty() || currentMatchIdx < 0) { Toast.makeText(this, "No current match to replace", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val range = matches[currentMatchIdx]
            val editable = binding.editor.text ?: return@setOnClickListener
            editable.replace(range.first, range.last + 1, r)
            computeMatchesAndUpdateLocal(q)
            pushHistorySnapshot(binding.editor.text?.toString() ?: "")
        }

        btnRAll.setOnClickListener {
            val q = etFind.text.toString()
            val r = etReplace.text.toString()
            if (q.isEmpty()) { Toast.makeText(this, "Query empty", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            lifecycleScope.launch(bgDispatcher) {
                val full = binding.editor.text?.toString() ?: ""
                val escaped = Regex.escape(q)
                val regex = Regex(escaped, RegexOption.IGNORE_CASE)
                val replaced = regex.replace(input = full, replacement = r)
                withContext(Dispatchers.Main) {
                    ignoreTextWatcher = true
                    binding.editor.setText(replaced, TextView.BufferType.EDITABLE)
                    binding.editor.setSelection(0)
                    ignoreTextWatcher = false
                    matches = emptyList()
                    currentMatchIdx = -1
                    tvCount.text = "0 matches"
                    scheduleStatsUpdate()
                    pushHistorySnapshot(replaced)
                    Toast.makeText(this@EditorActivity, "Replaced all", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Minimize: compute matches (if needed), dismiss dialog and show overlay controls
        btnMinimize.setOnClickListener {
            val q = etFind.text?.toString() ?: ""
            if (q.isEmpty()) {
                Toast.makeText(this, "Enter query first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // compute matches and open overlay (overlay will update when compute finishes)
            computeMatchesAndUpdateLocal(q)
            // remember lastQuery
            lastQuery = q
            dialog.dismiss()
            showOverlayControls()
        }

        // enable/disable minimize based on text
        etFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateMinimizeState(); computeMatchesAndUpdateLocal(s?.toString() ?: "") }
        })

        dialog.show()
        // initial compute
        val initialQuery = etFind.text.toString()
        if (initialQuery.isNotEmpty()) computeMatchesAndUpdateLocal(initialQuery)
    }

    // activity-level computeMatches used by minimize flow and overlay
    private fun computeMatches(query: String, selectFirst: Boolean = true, onDone: (() -> Unit)? = null) {
        if (query.startsWith(":")) {
            // go to line command — handle on main thread
            val num = query.substring(1).toIntOrNull()
            if (num != null) {
                goToLine(num)
                onDone?.invoke()
                return
            }
        }

        lifecycleScope.launch(bgDispatcher) {
            val text = binding.editor.text?.toString() ?: ""
            if (query.isEmpty()) {
                matches = emptyList()
                currentMatchIdx = -1
            } else {
                val escaped = Regex.escape(query)
                val regex = Regex(escaped, RegexOption.IGNORE_CASE)
                val found = regex.findAll(text).map { it.range.first..it.range.last }.toList()
                matches = found
                currentMatchIdx = if (found.isNotEmpty() && selectFirst) 0 else if (found.isNotEmpty()) 0 else -1
            }
            withContext(Dispatchers.Main) {
                updateOverlayCount(matches.size)
                if (selectFirst && currentMatchIdx >= 0 && matches.isNotEmpty()) selectMatchAt(currentMatchIdx)
                onDone?.invoke()
            }
        }
    }

    // select match activity-level
    private fun selectMatchAt(index: Int) {
        if (index < 0 || index >= matches.size) return
        val range = matches[index]
        binding.editor.requestFocus()
        val safeStart = range.first.coerceIn(0, binding.editor.text?.length ?: 0)
        val safeEnd = (range.last + 1).coerceIn(0, binding.editor.text?.length ?: 0)
        binding.editor.setSelection(safeStart, safeEnd)
        revealSelection(safeStart)
        lastQuery = null // keep lastQuery only in dialog; we will set it when needed
    }

    private fun goToLine(lineNumber: Int) {
        if (lineNumber <= 0) return
        binding.editor.post {
            val layout = binding.editor.layout ?: return@post
            val targetLine = (lineNumber - 1).coerceIn(0, layout.lineCount - 1)
            val offset = layout.getLineStart(targetLine)
            binding.editor.requestFocus()
            binding.editor.setSelection(offset)
            val y = layout.getLineTop(targetLine)
            binding.editor.scrollTo(0, y)
            highlightLineTemporary(targetLine)
        }
    }

    private fun highlightLineTemporary(line: Int) {
        val layout = binding.editor.layout ?: return
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        val editable = binding.editor.text ?: return
        val span = ForegroundColorSpan(Color.YELLOW)
        editable.setSpan(span, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        lifecycleScope.launch {
            delay(700)
            withContext(Dispatchers.Main) {
                try { editable.removeSpan(span) } catch (_: Exception) {}
            }
        }
    }

    private fun revealSelection(selectionStart: Int) {
        binding.editor.post {
            val layout = binding.editor.layout ?: return@post
            val line = layout.getLineForOffset(selectionStart)
            val y = layout.getLineTop(line)
            binding.editor.scrollTo(0, y)
        }
    }

    // ---------- overlay controls (center buttons) ----------
    private fun setupOverlayControls() {
        binding.overlayControls.visibility = View.GONE
        binding.overlayPrev.setOnClickListener {
            if (matches.isEmpty()) { Toast.makeText(this, "No matches", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            currentMatchIdx = if (currentMatchIdx - 1 < 0) matches.size - 1 else currentMatchIdx - 1
            selectMatchAt(currentMatchIdx)
            updateOverlayCount(matches.size)
        }
        binding.overlayNext.setOnClickListener {
            if (matches.isEmpty()) { Toast.makeText(this, "No matches", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            currentMatchIdx = (currentMatchIdx + 1) % matches.size
            selectMatchAt(currentMatchIdx)
            updateOverlayCount(matches.size)
        }
        binding.overlayOpenFind.setOnClickListener {
            // hide overlay and reopen full dialog
            hideOverlayControls()
            showFindReplaceDialog()
        }
        // tap outside overlay closes it: set click on container background (optional)
        binding.overlayControls.setOnClickListener { /* consume click so it doesn't pass through */ }
    }

    private fun showOverlayControls() {
        binding.overlayControls.visibility = View.VISIBLE
        // set initial states
        binding.overlayCount.text = if (matches.isEmpty()) "Searching..." else "${matches.size} matches"
        // If matches not computed yet, kick off compute using lastQuery if available
        val q = lastQuery ?: ""
        if (q.isNotEmpty()) computeMatches(q, selectFirst = true)
    }

    private fun hideOverlayControls() {
        binding.overlayControls.visibility = View.GONE
    }

    private fun updateOverlayCount(count: Int) {
        binding.overlayCount.text = "$count matches"
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
        val text = binding.editor.text?.toString() ?: ""
        lifecycleScope.launch(bgDispatcher) {
            val chars = text.length
            var charsNoSpace = 0
            var lines = 0
            var words = 0
            var inWord = false
            for (ch in text) {
                if (!ch.isWhitespace()) {
                    charsNoSpace++
                    if (!inWord) { inWord = true; words++ }
                } else {
                    if (ch == '\n') lines++
                    inWord = false
                }
            }
            if (text.isNotEmpty() && text.last() != '\n') lines++
            val stats = "Words: $words | Chars: $chars | NoSpace: $charsNoSpace | Lines: $lines"
            if (stats != lastStatsText) {
                lastStatsText = stats
                lifecycleScope.launch(Dispatchers.Main) { binding.tvStats.text = stats }
            }
        }
    }

    private fun updateStatsAsync() = scheduleStatsUpdate()

    // ---------- FONT SIZE & PREF VISUALS (kept) ----------
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
        binding.editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    private fun applyPrefsVisuals() {
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val retro = sp.getBoolean(PREF_RETRO_MODE, false)
        val amber = sp.getBoolean(PREF_AMBER_MODE, false)
        val syntaxOn = sp.getBoolean(PREF_SYNTAX_HIGHLIGHT, false)

        if (amber) {
            binding.editor.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            binding.editor.setTextColor(Color.parseColor("#FFBF00"))
        } else if (retro) {
            binding.editor.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            binding.editor.setTextColor(Color.parseColor("#00FF66"))
        } else {
            binding.editor.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            binding.editor.setTextColor(getColorFromAttrOrDefault(android.R.attr.textColorPrimary, Color.parseColor("#E0E0E0")))
        }

        if (syntaxOn) {
            if (syntaxMapping.isEmpty()) {
                val mappingUriString = sp.getString(PREF_SYNTAX_MAPPING_URI, null)
                if (!mappingUriString.isNullOrEmpty()) {
                    try {
                        val uri = Uri.parse(mappingUriString)
                        lifecycleScope.launch { parseAndStoreSyntaxMappingFromUri(uri); scheduleHighlight() }
                    } catch (_: Exception) { loadSyntaxMappingFromAssetsIfAvailable() }
                } else loadSyntaxMappingFromAssetsIfAvailable()
            } else scheduleHighlight()
        } else {
            clearForegroundSpansInRange(0, binding.editor.text?.length ?: 0)
            clearFormattingSpansInRange(0, binding.editor.text?.length ?: 0)
        }
    }

    // parse mapping from uri / assets (kept)...
    private fun loadSyntaxMappingFromAssetsIfAvailable() {
        val spref = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val lang = spref.getString(PREF_SYNTAX_LANGUAGE, "kotlin") ?: "kotlin"
        val candidate1 = "${lang}.txt"
        val candidate2 = "${lang}lang.txt"
        lifecycleScope.launch {
            try {
                val available = assets.list("")?.toList() ?: emptyList()
                val found = when {
                    available.contains(candidate1) -> candidate1
                    available.contains(candidate2) -> candidate2
                    else -> null
                }
                if (found != null) {
                    parseAndStoreSyntaxMappingFromAssets(found)
                    scheduleHighlight()
                } else {
                    try { parseAndStoreSyntaxMappingFromAssets(candidate1); scheduleHighlight() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    private suspend fun parseAndStoreSyntaxMappingFromUri(uri: Uri) {
        withContext(Dispatchers.IO) {
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
                                val token = parts[0]
                                val colorStr = parts[1]
                                try {
                                    val color = Color.parseColor(colorStr)
                                    if (token.isNotEmpty()) map[token] = color
                                } catch (_: Exception) {}
                            }
                        }
                        withContext(Dispatchers.Main) {
                            syntaxMapping.clear()
                            syntaxMapping.putAll(map)
                            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(PREF_SYNTAX_MAPPING_URI, uri.toString()).apply()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@EditorActivity, "Failed to read mapping file: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private suspend fun parseAndStoreSyntaxMappingFromAssets(filename: String) {
        withContext(Dispatchers.IO) {
            try {
                assets.open(filename).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { br ->
                        val map = mutableMapOf<String, Int>()
                        br.forEachLine { raw ->
                            val line = raw.trim()
                            if (line.isEmpty()) return@forEachLine
                            if (line.startsWith("#")) return@forEachLine
                            val parts = line.split("=").map { it.trim() }
                            if (parts.size >= 2) {
                                val token = parts[0]
                                val colorStr = parts[1]
                                try {
                                    val color = Color.parseColor(colorStr)
                                    if (token.isNotEmpty()) map[token] = color
                                } catch (_: Exception) {}
                            }
                        }
                        withContext(Dispatchers.Main) {
                            syntaxMapping.clear()
                            syntaxMapping.putAll(map)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@EditorActivity, "Failed to read mapping asset: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---------- HIGHLIGHT VISIBLE ----------
    private fun scheduleHighlight(delayMs: Long = 80L) {
        uiUpdateJob?.cancel()
        uiUpdateJob = lifecycleScope.launch {
            delay(delayMs)
            updateVisibleHighlight()
        }
    }

    private fun updateVisibleHighlight() {
        highlightJob?.cancel()
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val syntaxOn = sp.getBoolean(PREF_SYNTAX_HIGHLIGHT, false)
        val retro = sp.getBoolean(PREF_RETRO_MODE, false)
        val formatOn = sp.getBoolean(PREF_FORMAT_ON, false)
        if (!syntaxOn || retro) {
            clearForegroundSpansInRange(0, binding.editor.text?.length ?: 0)
            if (!formatOn) clearFormattingSpansInRange(0, binding.editor.text?.length ?: 0)
            return
        }

        highlightJob = lifecycleScope.launch(bgDispatcher) {
            val layout = binding.editor.layout ?: return@launch
            val scrollY = binding.editor.scrollY
            val topLine = layout.getLineForVertical(scrollY)
            val bottomLine = layout.getLineForVertical(scrollY + binding.editor.height)
            val startOffset = layout.getLineStart(topLine).coerceAtLeast(0)
            val endOffset = layout.getLineEnd(bottomLine).coerceAtMost(binding.editor.text?.length ?: 0)

            val visibleText = binding.editor.text?.subSequence(startOffset, endOffset)?.toString() ?: ""
            val spansToApply = mutableListOf<Triple<Int, Int, Int>>()
            if (visibleText.isNotEmpty()) {
                var idx = 0
                val len = visibleText.length
                while (idx < len) {
                    val c = visibleText[idx]
                    if (c.isLetter() || c == '_') {
                        val start = idx
                        idx++
                        while (idx < len && (visibleText[idx].isLetterOrDigit() || visibleText[idx] == '_')) idx++
                        val word = visibleText.substring(start, idx)
                        val color = when {
                            syntaxMapping.containsKey(word) -> syntaxMapping[word]
                            kotlinKeywords.contains(word) -> Color.parseColor("#82B1FF")
                            else -> null
                        }
                        if (color != null) {
                            val globalStart = startOffset + start
                            val globalEnd = startOffset + idx
                            spansToApply.add(Triple(globalStart, globalEnd, color))
                        }
                    } else idx++
                }
            }

            withContext(Dispatchers.Main) {
                try {
                    clearForegroundSpansInRange(startOffset, endOffset)
                    val editable = binding.editor.text
                    if (editable is Spannable) {
                        for ((s, e, color) in spansToApply) {
                            editable.setSpan(ForegroundColorSpan(color), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        if (formatOn) {
                            clearFormattingSpansInRange(startOffset, endOffset)
                            applyInlineFormattingInRange(editable, startOffset, endOffset)
                        } else {
                            clearFormattingSpansInRange(startOffset, endOffset)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun clearForegroundSpansInRange(rangeStart: Int, rangeEnd: Int) {
        val editable = binding.editor.text ?: return
        if (editable !is Spannable) return
        val spans = editable.getSpans(rangeStart, rangeEnd, ForegroundColorSpan::class.java)
        for (sp in spans) {
            try { editable.removeSpan(sp) } catch (_: Exception) {}
        }
    }

    private fun clearFormattingSpansInRange(rangeStart: Int, rangeEnd: Int) {
        val editable = binding.editor.text ?: return
        if (editable !is Spannable) return
        val styleSpans = editable.getSpans(rangeStart, rangeEnd, StyleSpan::class.java)
        for (sp in styleSpans) { try { editable.removeSpan(sp) } catch (_: Exception) {} }
        val alignSpans = editable.getSpans(rangeStart, rangeEnd, AlignmentSpan::class.java)
        for (sp in alignSpans) { try { editable.removeSpan(sp) } catch (_: Exception) {} }
        val replSpans = editable.getSpans(rangeStart, rangeEnd, ReplacementSpan::class.java)
        for (sp in replSpans) { try { editable.removeSpan(sp) } catch (_: Exception) {} }
    }

    private fun applyInlineFormattingInRange(editable: Spannable, rangeStart: Int, rangeEnd: Int) {
        val raw = editable.subSequence(rangeStart, rangeEnd).toString()
        val boldRegex = Regex("<b>(.*?)</b>", RegexOption.DOT_MATCHES_ALL)
        for (m in boldRegex.findAll(raw)) {
            val inner = m.groups[1] ?: continue
            val openTagLen = "<b>".length
            val s = rangeStart + m.range.first + openTagLen
            val e = s + inner.value.length
            try { editable.setSpan(StyleSpan(android.graphics.Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Exception) {}
        }
        val italicRegex = Regex("<i>(.*?)</i>", RegexOption.DOT_MATCHES_ALL)
        for (m in italicRegex.findAll(raw)) {
            val inner = m.groups[1] ?: continue
            val openTagLen = "<i>".length
            val s = rangeStart + m.range.first + openTagLen
            val e = s + inner.value.length
            try { editable.setSpan(StyleSpan(android.graphics.Typeface.ITALIC), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Exception) {}
        }
        val centerRegex = Regex("<center>(.*?)</center>", RegexOption.DOT_MATCHES_ALL)
        for (m in centerRegex.findAll(raw)) {
            val inner = m.groups[1] ?: continue
            val openTagLen = "<center>".length
            val s = rangeStart + m.range.first + openTagLen
            val e = s + inner.value.length
            try { editable.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Exception) {}
        }
        val tagRegex = Regex("<[^>]+>")
        for (m in tagRegex.findAll(raw)) {
            val tagText = m.value
            val tagGlobalStart = rangeStart + m.range.first
            val tagGlobalEnd = rangeStart + m.range.last + 1
            try {
                if (tagText.equals("<tab>", ignoreCase = true)) {
                    editable.setSpan(TabSpan(), tagGlobalStart, tagGlobalEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), tagGlobalStart, tagGlobalEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } catch (_: Exception) {}
        }
    }

    private fun attachScrollObserver() {
        if (scrollObserverAttached) return
        onScrollListener = ViewTreeObserver.OnScrollChangedListener { scheduleHighlight() }
        binding.editor.viewTreeObserver.addOnScrollChangedListener(onScrollListener)
        scrollObserverAttached = true
    }

    // ---------- HISTORY ----------
    private fun pushHistorySnapshot(value: String) {
        addHistorySnapshot(value)
    }

    private fun addHistorySnapshot(value: String) {
        if (historyIndex >= 0 && historyIndex < history.size && history[historyIndex] == value) return
        if (historyIndex < history.size - 1) {
            for (i in history.size - 1 downTo historyIndex + 1) history.removeAt(i)
        }
        history.add(value)
        historyIndex = history.size - 1
        while (history.size > maxHistory) {
            history.removeAt(0)
            historyIndex--
        }
    }

    private fun scheduleHistorySnapshot(delayMs: Long = 800L) {
        historyJob?.cancel()
        historyJob = lifecycleScope.launch {
            delay(delayMs)
            if (ignoreTextWatcher) return@launch
            val current = binding.editor.text?.toString() ?: ""
            addHistorySnapshot(current)
        }
    }

    fun performUndo() {
        val undoEnabled = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getBoolean(PREF_UNDO_ENABLED, true)
        if (!undoEnabled) { Toast.makeText(this, "Undo disabled in settings", Toast.LENGTH_SHORT).show(); return }
        if (historyIndex <= 0) { Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show(); return }
        historyIndex--
        val prev = history.getOrNull(historyIndex) ?: ""
        ignoreTextWatcher = true
        binding.editor.setText(prev, TextView.BufferType.EDITABLE)
        binding.editor.setSelection(min(prev.length, binding.editor.text?.length ?: prev.length))
        ignoreTextWatcher = false
        scheduleStatsUpdate()
        scheduleHighlight()
    }

    fun performRedo() {
        if (historyIndex >= history.size - 1) { Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show(); return }
        historyIndex++
        val next = history.getOrNull(historyIndex) ?: ""
        ignoreTextWatcher = true
        binding.editor.setText(next, TextView.BufferType.EDITABLE)
        binding.editor.setSelection(min(next.length, binding.editor.text?.length ?: next.length))
        ignoreTextWatcher = false
        scheduleStatsUpdate()
        scheduleHighlight()
    }

    // ---------- ENCRYPT / DECRYPT ----------
    private fun promptEncryptCurrent() {
        val input = EditText(this)
        input.hint = "Password"
        val dlg = AlertDialog.Builder(this)
            .setTitle("Encrypt")
            .setView(input)
            .setPositiveButton("Encrypt", null)
            .setNegativeButton("Cancel", null)
            .create()

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
        val dlg = AlertDialog.Builder(this)
            .setTitle("Decrypt")
            .setView(input)
            .setPositiveButton("Decrypt", null)
            .setNegativeButton("Cancel", null)
            .create()

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
        val plain = binding.editor.text?.toString() ?: ""
        if (plain.isEmpty()) { Toast.makeText(this, "Nothing to encrypt", Toast.LENGTH_SHORT).show(); return }
        val waitDlg = AlertDialog.Builder(this)
            .setTitle("Encrypting")
            .setMessage("Please wait")
            .setCancelable(false)
            .create()
        waitDlg.show()

        val dotsJob = lifecycleScope.launch {
            var dots = 0
            while (isActive) {
                withContext(Dispatchers.Main) { waitDlg.setMessage("Please wait" + ".".repeat(dots)) }
                dots = (dots + 1) % 4
                delay(400)
            }
        }

        lifecycleScope.launch(bgDispatcher) {
            try {
                val encrypted = Secure.encrypt(password, plain)
                withContext(Dispatchers.Main) {
                    ignoreTextWatcher = true
                    binding.editor.setText(encrypted, TextView.BufferType.EDITABLE)
                    binding.editor.setSelection(0)
                    ignoreTextWatcher = false
                    scheduleStatsUpdate()
                    pushHistorySnapshot(encrypted)
                    Toast.makeText(this@EditorActivity, "Encryption done", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@EditorActivity, "Encryption failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            } finally {
                dotsJob.cancel()
                withContext(Dispatchers.Main) { waitDlg.dismiss() }
            }
        }
    }

    private fun performDecrypt(password: CharArray) {
        val encrypted = binding.editor.text?.toString() ?: ""
        if (encrypted.isEmpty()) { Toast.makeText(this, "Nothing to decrypt", Toast.LENGTH_SHORT).show(); return }
        val waitDlg = AlertDialog.Builder(this)
            .setTitle("Decrypting")
            .setMessage("Please wait")
            .setCancelable(false)
            .create()
        waitDlg.show()

        val dotsJob = lifecycleScope.launch {
            var dots = 0
            while (isActive) {
                withContext(Dispatchers.Main) { waitDlg.setMessage("Please wait" + ".".repeat(dots)) }
                dots = (dots + 1) % 4
                delay(400)
            }
        }

        lifecycleScope.launch(bgDispatcher) {
            try {
                val plain = Secure.decrypt(password, encrypted)
                withContext(Dispatchers.Main) {
                    ignoreTextWatcher = true
                    binding.editor.setText(plain, TextView.BufferType.EDITABLE)
                    binding.editor.setSelection(0)
                    ignoreTextWatcher = false
                    scheduleStatsUpdate()
                    pushHistorySnapshot(plain)
                    Toast.makeText(this@EditorActivity, "Decryption done", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@EditorActivity, "Decryption failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            } finally {
                dotsJob.cancel()
                withContext(Dispatchers.Main) { waitDlg.dismiss() }
            }
        }
    }

    // ---------- SETTINGS (fallback) ----------
    private fun showSettingsFallbackDialog() {
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val prevent = sp.getBoolean(PREF_PREVENT_SCREENSHOT, false)
        val undo = sp.getBoolean(PREF_UNDO_ENABLED, true)
        val dark = sp.getBoolean(PREF_THEME_DARK, true)
        val font = sp.getString(PREF_FONT_SIZE, "normal") ?: "normal"
        val format = sp.getBoolean(PREF_FORMAT_ON, false)
        val gutter = sp.getBoolean(PREF_SHOW_LINE_NUMBERS, false)
        val retro = sp.getBoolean(PREF_RETRO_MODE, false)
        val syntax = sp.getBoolean(PREF_SYNTAX_HIGHLIGHT, false)

        val dlg = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMessage("Settings available:\n• Prevent screenshots: $prevent\n• Undo enabled: $undo\n• Dark theme: $dark\n• Font size: $font\n• Formatting: $format\n• Line numbers (ignored): $gutter\n• Retro: $retro\n• Syntax highlight: $syntax\n\nOpen SettingsActivity to change these.")
            .setPositiveButton("OK", null)
            .create()
        dlg.show()
    }

    // ---------- CLIPBOARD ----------
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

    // ---------- Helpers ----------
    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    private fun getColorFromAttrOrDefault(attr: Int, def: Int): Int {
        return try {
            val typed = obtainStyledAttributes(intArrayOf(attr))
            val color = typed.getColor(0, def)
            typed.recycle()
            color
        } catch (e: Exception) {
            def
        }
    }

    // ReplacementSpan that draws an empty block of width ~ 4 spaces (tab)
    private class TabSpan(private val spaces: Int = 4) : ReplacementSpan() {
        override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
            val spaceWidth = paint.measureText(" ")
            return (spaceWidth * spaces).toInt()
        }
        override fun draw(canvas: android.graphics.Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: android.graphics.Paint) {
            // draw nothing (just advance)
        }
    }
}
