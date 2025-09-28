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
import android.text.Editable
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.ViewTreeObserver
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
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding

    // SAF launchers
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createDocumentLauncherSave: ActivityResultLauncher<String> // Save (create if not exists)
    private lateinit var createDocumentLauncherSaveAs: ActivityResultLauncher<String> // Save As

    // current doc uri
    private var currentDocumentUri: Uri? = null

    // Find/Replace state
    private var lastQuery: String? = null
    private var matches: List<IntRange> = emptyList()
    private var currentMatchIdx: Int = -1

    // stats debounce job
    private var statsJob: Job? = null
    private var lastStatsText: String = ""

    // Undo/Redo stacks (in-memory)
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private val maxHistory = 50
    private var historyJob: Job? = null

    // Preferences
    private val prefsName = "editor_prefs"
    private val PREF_PREVENT_SCREENSHOT = "prevent_screenshot"
    private val PREF_UNDO_ENABLED = "undo_enabled"
    private val PREF_THEME_DARK = "theme_dark"
    private val PREF_FONT_SIZE = "font_size" // values: small, normal, medium, large

    // new prefs
    private val PREF_FORMAT_ON = "format_on"
    private val PREF_SHOW_LINE_NUMBERS = "show_line_numbers"
    private val PREF_RETRO_MODE = "retro_mode"
    private val PREF_SYNTAX_HIGHLIGHT = "syntax_highlight"

    // coroutine scope helper
    private val bgDispatcher = Dispatchers.Default

    // gutter (line numbers)
    private var gutter: TextView? = null
    private var gutterWidth = 0
    private var gutterVisible = false

    // highlight job
    private var highlightJob: Job? = null
    private var scrollObserverAttached = false
    private var onScrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var uiUpdateJob: Job? = null

    // simple kotlin keywords for demo highlighting
    private val kotlinKeywords = setOf(
        "fun","val","var","if","else","for","while","return","import","class","object",
        "private","public","protected","internal","override","when","in","is","null","true","false"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // apply theme preference early
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val dark = sp.getBoolean(PREF_THEME_DARK, true)
        AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // Ensure jump zones are above other overlays and accept clicks
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
            // "Save" when no existing uri -> we get the uri here
            uri?.let { uriCreated ->
                currentDocumentUri = uriCreated
                writeToUri(uriCreated, binding.editor.text?.toString() ?: "")
            }
        }

        createDocumentLauncherSaveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            // Save As: always write into returned uri (don't change currentDocumentUri unless desired)
            uri?.let { uriCreated ->
                writeToUri(uriCreated, binding.editor.text?.toString() ?: "")
            }
        }

        // menu clicks
        binding.toolbar.setOnMenuItemClickListener { item ->
            onOptionsItemSelected(item)
        }

        // initial hint & stats
        binding.emptyHint.visibility = if (binding.editor.text.isNullOrEmpty()) View.VISIBLE else View.GONE
        scheduleStatsUpdate()

        // apply font size preference immediately
        applyFontSizeFromPrefs()

        // apply retro mode / syntax toggle / gutter
        applyPrefsVisuals()

        // text watcher: update hint, stats (debounced), and history
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // push previous state to undo stack on first change if enabled
                val undoEnabled = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getBoolean(PREF_UNDO_ENABLED, true)
                if (undoEnabled) {
                    val current = binding.editor.text?.toString() ?: ""
                    if (undoStack.isEmpty() || undoStack.peekFirst() != current) {
                        // keep a snapshot of the previous text
                        pushUndoSnapshot(current)
                        // clear redo on new edit
                        redoStack.clear()
                    }
                }
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { /* no-op */ }

            override fun afterTextChanged(s: Editable?) {
                binding.emptyHint.visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
                scheduleStatsUpdate()
                // schedule history snapshot after short idle (avoid on each keystroke)
                scheduleHistorySnapshot()
                // invalidate search matches
                matches = emptyList()
                currentMatchIdx = -1

                // if gutter active -> update quickly (debounced)
                scheduleGutterAndHighlight()
            }
        })

        // top-right / bottom-right invisible zones for jump-to-start/end
        binding.jumpTop.setOnClickListener {
            // move caret and view to start
            binding.editor.requestFocus()
            binding.editor.setSelection(0)
            binding.editor.post { binding.editor.scrollTo(0, 0) }
        }
        binding.jumpBottom.setOnClickListener {
            val len = binding.editor.text?.length ?: 0
            binding.editor.requestFocus()
            binding.editor.setSelection(len)
            // scroll to last line
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

        // attach scroll observer for gutter/highlight updates
        attachScrollObserver()
    }

    override fun onResume() {
        super.onResume()
        // re-apply prefs in case settings changed
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        if (sp.getBoolean(PREF_PREVENT_SCREENSHOT, false)) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        applyFontSizeFromPrefs()
        applyPrefsVisuals()
        scheduleGutterAndHighlight()
    }

    override fun onDestroy() {
        super.onDestroy()
        highlightJob?.cancel()
        uiUpdateJob?.cancel()
        statsJob?.cancel()
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
                    writeToUri(uri, binding.editor.text?.toString() ?: "")
                } else {
                    // "Save" -> CreateDocument when no current file
                    createDocumentLauncherSave.launch("untitled.txt")
                }
                true
            }
            R.id.action_save_as -> {
                // Save As always asks for path/name
                createDocumentLauncherSaveAs.launch("untitled.txt")
                true
            }
            R.id.action_find -> {
                showFindReplaceDialog()
                true
            }
            R.id.action_copy -> {
                // copy selection or whole file if nothing selected
                val selStart = binding.editor.selectionStart
                val selEnd = binding.editor.selectionEnd
                val text = binding.editor.text?.toString() ?: ""
                val toCopy = if (selStart >= 0 && selEnd > selStart) {
                    text.substring(selStart, selEnd)
                } else {
                    // copy whole doc
                    text
                }
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
            R.id.action_undo -> {
                performUndo()
                true
            }
            R.id.action_redo -> {
                performRedo()
                true
            }
            R.id.action_clear -> {
                confirmAndClear()
                true
            }
            R.id.action_select_all -> {
                binding.editor.selectAll()
                true
            }
            R.id.action_encrypt -> {
                promptEncryptCurrent()
                true
            }
            R.id.action_decrypt -> {
                promptDecryptCurrent()
                true
            }
            R.id.action_settings -> {
                // open SettingsActivity if available
                try {
                    val intent = Intent(this, SettingsActivity::class.java)
                    startActivity(intent)
                } catch (e: Exception) {
                    // fallback to simple dialog
                    showSettingsFallbackDialog()
                }
                true
            }
            R.id.action_about -> {
                // temporary: open settings/activity about
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
                // push snapshot so user can undo
                pushUndoSnapshot(binding.editor.text?.toString() ?: "")
                binding.editor.setText("", TextView.BufferType.EDITABLE)
                binding.editor.setSelection(0)
                redoStack.clear()
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
                // try to persist permission if available (best-effort)
                try {
                    val takeFlags = (intent?.flags ?: 0) and
                            (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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
                // set text on main thread (explicit BufferType to avoid ambiguity)
                binding.editor.setText(content, TextView.BufferType.EDITABLE)
                binding.editor.setSelection(0)
                // store initial state in undo stack
                undoStack.clear()
                redoStack.clear()
                pushUndoSnapshot(content)
                scheduleStatsUpdate()
                scheduleGutterAndHighlight()
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

        if (!lastQuery.isNullOrEmpty()) etFind.setText(lastQuery)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Find / Replace")
            .setView(view)
            .setNegativeButton("Close", null)
            .create()

        // select match
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

        // if user types :NNN go to line NNN
        fun tryGoToLine(query: String): Boolean {
            if (query.startsWith(":")) {
                val num = query.substring(1).toIntOrNull() ?: return false
                goToLine(num)
                return true
            }
            return false
        }

        fun computeMatchesAndUpdate(query: String) {
            if (tryGoToLine(query)) {
                tvCount.text = "Go to line"
                return
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
                        selectMatchAt(currentMatchIdx)
                    }
                }
            }
        }

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
            editable.replace(range.first, range.last + 1, r)
            computeMatchesAndUpdate(q)
        }

        btnRAll.setOnClickListener {
            val q = etFind.text.toString()
            val r = etReplace.text.toString()
            if (q.isEmpty()) {
                Toast.makeText(this, "Query empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch(bgDispatcher) {
                val full = binding.editor.text?.toString() ?: ""
                val escaped = Regex.escape(q)
                val regex = Regex(escaped, RegexOption.IGNORE_CASE)
                val replaced = regex.replace(input = full, replacement = r)
                withContext(Dispatchers.Main) {
                    binding.editor.setText(replaced, TextView.BufferType.EDITABLE)
                    binding.editor.setSelection(0)
                    matches = emptyList()
                    currentMatchIdx = -1
                    tvCount.text = "0 matches"
                    scheduleStatsUpdate()
                    Toast.makeText(this@EditorActivity, "Replaced all", Toast.LENGTH_SHORT).show()
                }
            }
        }

        etFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                computeMatchesAndUpdate(q)
            }
        })

        dialog.show()
        val initialQuery = etFind.text.toString()
        if (initialQuery.isNotEmpty()) computeMatchesAndUpdate(initialQuery)
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
            // temporary highlight of line
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
                try {
                    editable.removeSpan(span)
                } catch (_: Exception) {}
            }
        }
    }

    // reveal selection by scrolling editor so the selection line is visible
    private fun revealSelection(selectionStart: Int) {
        binding.editor.post {
            val layout = binding.editor.layout ?: return@post
            val line = layout.getLineForOffset(selectionStart)
            val y = layout.getLineTop(line)
            binding.editor.scrollTo(0, y)
        }
    }

    // ---------- STATS (debounced) ----------
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
                    if (!inWord) {
                        inWord = true
                        words++
                    }
                } else {
                    if (ch == '\n') lines++
                    inWord = false
                }
            }
            // account last line if text not empty and does not end with newline
            if (text.isNotEmpty() && text.last() != '\n') lines++

            val stats = "Words: $words | Chars: $chars | NoSpace: $charsNoSpace | Lines: $lines"
            if (stats != lastStatsText) {
                lastStatsText = stats
                lifecycleScope.launch(Dispatchers.Main) {
                    binding.tvStats.text = stats
                }
            }
        }
    }

    // backward-compatible alias
    private fun updateStatsAsync() = scheduleStatsUpdate()

    // ---------- FONT SIZE ----------
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

    // ---------- PREF VISUALS (retro / gutter / syntax) ----------
    private fun applyPrefsVisuals() {
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val retro = sp.getBoolean(PREF_RETRO_MODE, false)
        val showGutter = sp.getBoolean(PREF_SHOW_LINE_NUMBERS, false)
        val syntaxOn = sp.getBoolean(PREF_SYNTAX_HIGHLIGHT, false)

        // retro mode
        if (retro) {
            binding.editor.setTextColor(Color.parseColor("#00FF66")) // greenish retro
        } else {
            // default color - let theme decide; use light grey fallback
            binding.editor.setTextColor(getColorFromAttrOrDefault(android.R.attr.textColorPrimary, Color.parseColor("#E0E0E0")))
        }

        // gutter
        if (showGutter) {
            ensureGutter()
            gutterVisible = true
            gutter?.visibility = View.VISIBLE
            // shift editor padding to the right of gutter
            binding.editor.post {
                gutter?.let {
                    if (gutterWidth == 0) {
                        it.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                        gutterWidth = it.measuredWidth
                        val padLeft = gutterWidth + 8
                        binding.editor.setPadding(padLeft, binding.editor.paddingTop, binding.editor.paddingRight, binding.editor.paddingBottom)
                    }
                }
            }
        } else {
            gutterVisible = false
            gutter?.visibility = View.GONE
            // restore padding
            binding.editor.setPadding(8, binding.editor.paddingTop, binding.editor.paddingRight, binding.editor.paddingBottom)
        }

        // syntax highlight: schedule update (retro disables)
        scheduleGutterAndHighlight()
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

    private fun ensureGutter() {
        if (gutter != null) return
        // try to add gutter inside editor's parent FrameLayout
        val parent = binding.editor.parent
        if (parent is ViewGroup) {
            val tv = TextView(this)
            tv.setTextColor(getColorFromAttrOrDefault(android.R.attr.textColorSecondary, Color.GRAY))
            tv.typeface = Typeface.MONOSPACE
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            tv.setPadding(6, 8, 6, 8)
            tv.gravity = Gravity.START or Gravity.TOP
            tv.setBackgroundColor(Color.TRANSPARENT)
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            lp.gravity = Gravity.START or Gravity.TOP
            parent.addView(tv, lp)
            gutter = tv
        }
    }

    private fun scheduleGutterAndHighlight(delayMs: Long = 80L) {
        uiUpdateJob?.cancel()
        uiUpdateJob = lifecycleScope.launch {
            delay(delayMs)
            updateGutter()
            updateVisibleHighlight()
        }
    }

    private fun updateGutter() {
        if (!gutterVisible) return
        val layout = binding.editor.layout ?: return
        val scrollY = binding.editor.scrollY
        val topLine = layout.getLineForVertical(scrollY)
        val bottomLine = layout.getLineForVertical(scrollY + binding.editor.height)
        val sb = StringBuilder()
        for (ln in topLine..bottomLine) {
            sb.append(ln + 1).append('\n')
        }
        gutter?.text = sb.toString()
    }

    private fun attachScrollObserver() {
        if (scrollObserverAttached) return
        onScrollListener = ViewTreeObserver.OnScrollChangedListener {
            // debounce UI update
            scheduleGutterAndHighlight()
        }
        binding.editor.viewTreeObserver.addOnScrollChangedListener(onScrollListener)
        scrollObserverAttached = true
    }

    // ---------- UNDO / REDO ----------
    private fun pushUndoSnapshot(value: String) {
        if (undoStack.peekFirst() == value) return
        undoStack.addFirst(value)
        while (undoStack.size > maxHistory) undoStack.removeLast()
    }

    private fun scheduleHistorySnapshot(delayMs: Long = 800L) {
        historyJob?.cancel()
        historyJob = lifecycleScope.launch {
            delay(delayMs)
            val undoEnabled = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getBoolean(PREF_UNDO_ENABLED, true)
            if (undoEnabled) {
                val current = binding.editor.text?.toString() ?: ""
                if (undoStack.isEmpty() || undoStack.peekFirst() != current) {
                    pushUndoSnapshot(current)
                    // on new change, clear redo
                    redoStack.clear()
                }
            }
        }
    }

    fun performUndo() {
        val undoEnabled = getSharedPreferences(prefsName, Context.MODE_PRIVATE).getBoolean(PREF_UNDO_ENABLED, true)
        if (!undoEnabled) {
            Toast.makeText(this, "Undo disabled in settings", Toast.LENGTH_SHORT).show()
            return
        }
        if (undoStack.size <= 1) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            return
        }
        val current = binding.editor.text?.toString() ?: ""
        // move current to redo, pop previous from undo and set it
        redoStack.addFirst(current)
        // pop current snapshot
        undoStack.removeFirst()
        val prev = undoStack.peekFirst() ?: ""
        binding.editor.setText(prev, TextView.BufferType.EDITABLE)
        binding.editor.setSelection(min(prev.length, binding.editor.text?.length ?: prev.length))
        scheduleStatsUpdate()
        scheduleGutterAndHighlight()
    }

    fun performRedo() {
        if (redoStack.isEmpty()) {
            Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show()
            return
        }
        val next = redoStack.removeFirst()
        pushUndoSnapshot(binding.editor.text?.toString() ?: "")
        binding.editor.setText(next, TextView.BufferType.EDITABLE)
        binding.editor.setSelection(min(next.length, binding.editor.text?.length ?: next.length))
        scheduleStatsUpdate()
        scheduleGutterAndHighlight()
    }

    // ---------- HIGHLIGHT (visible area only) ----------
    private fun updateVisibleHighlight() {
        // cancel previous
        highlightJob?.cancel()
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val syntaxOn = sp.getBoolean(PREF_SYNTAX_HIGHLIGHT, false)
        val retro = sp.getBoolean(PREF_RETRO_MODE, false)
        if (!syntaxOn || retro) {
            // clear visible foreground spans if any
            clearForegroundSpansInRange(0, binding.editor.text?.length ?: 0)
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
            // build list of matches for keywords
            val spansToApply = mutableListOf<Triple<Int, Int, Int>>() // (start, end, color)
            if (visibleText.isNotEmpty()) {
                // simple word scanning - avoid regex for perf
                var idx = 0
                val len = visibleText.length
                while (idx < len) {
                    // skip non-letter/digit/underscore
                    val c = visibleText[idx]
                    if (c.isLetter() || c == '_') {
                        val start = idx
                        idx++
                        while (idx < len && (visibleText[idx].isLetterOrDigit() || visibleText[idx] == '_')) idx++
                        val word = visibleText.substring(start, idx)
                        if (kotlinKeywords.contains(word)) {
                            val globalStart = startOffset + start
                            val globalEnd = startOffset + idx
                            spansToApply.add(Triple(globalStart, globalEnd, Color.parseColor("#82B1FF"))) // bluish
                        }
                    } else {
                        idx++
                    }
                }
            }

            // apply spans on main thread
            withContext(Dispatchers.Main) {
                try {
                    // remove existing ForegroundColorSpan in range
                    clearForegroundSpansInRange(startOffset, endOffset)
                    val editable = binding.editor.text
                    if (editable is Spannable) {
                        for ((s, e, color) in spansToApply) {
                            editable.setSpan(ForegroundColorSpan(color), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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
            try {
                editable.removeSpan(sp)
            } catch (_: Exception) {}
        }
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
                if (pw.isEmpty()) {
                    Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
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
                if (pw.isEmpty()) {
                    Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dlg.dismiss()
                performDecrypt(pw.toCharArray())
            }
        }
        dlg.show()
    }

    private fun performEncrypt(password: CharArray) {
        val plain = binding.editor.text?.toString() ?: ""
        if (plain.isEmpty()) {
            Toast.makeText(this, "Nothing to encrypt", Toast.LENGTH_SHORT).show()
            return
        }
        // Dialog with animated "please wait..."
        val waitDlg = AlertDialog.Builder(this)
            .setTitle("Encrypting")
            .setMessage("Please wait")
            .setCancelable(false)
            .create()
        waitDlg.show()

        val dotsJob = lifecycleScope.launch {
            var dots = 0
            while (isActive) {
                withContext(Dispatchers.Main) {
                    waitDlg.setMessage("Please wait" + ".".repeat(dots))
                }
                dots = (dots + 1) % 4
                delay(400)
            }
        }

        lifecycleScope.launch(bgDispatcher) {
            try {
                val encrypted = Secure.encrypt(password, plain)
                withContext(Dispatchers.Main) {
                    binding.editor.setText(encrypted, TextView.BufferType.EDITABLE)
                    binding.editor.setSelection(0)
                    scheduleStatsUpdate()
                    Toast.makeText(this@EditorActivity, "Encryption done", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, "Encryption failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                dotsJob.cancel()
                withContext(Dispatchers.Main) {
                    waitDlg.dismiss()
                }
            }
        }
    }

    private fun performDecrypt(password: CharArray) {
        val encrypted = binding.editor.text?.toString() ?: ""
        if (encrypted.isEmpty()) {
            Toast.makeText(this, "Nothing to decrypt", Toast.LENGTH_SHORT).show()
            return
        }
        val waitDlg = AlertDialog.Builder(this)
            .setTitle("Decrypting")
            .setMessage("Please wait")
            .setCancelable(false)
            .create()
        waitDlg.show()

        val dotsJob = lifecycleScope.launch {
            var dots = 0
            while (isActive) {
                withContext(Dispatchers.Main) {
                    waitDlg.setMessage("Please wait" + ".".repeat(dots))
                }
                dots = (dots + 1) % 4
                delay(400)
            }
        }

        lifecycleScope.launch(bgDispatcher) {
            try {
                val plain = Secure.decrypt(password, encrypted)
                withContext(Dispatchers.Main) {
                    binding.editor.setText(plain, TextView.BufferType.EDITABLE)
                    binding.editor.setSelection(0)
                    scheduleStatsUpdate()
                    Toast.makeText(this@EditorActivity, "Decryption done", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, "Decryption failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } finally {
                dotsJob.cancel()
                withContext(Dispatchers.Main) {
                    waitDlg.dismiss()
                }
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
            .setMessage("Settings available:\n• Prevent screenshots: $prevent\n• Undo enabled: $undo\n• Dark theme: $dark\n• Font size: $font\n• Formatting: $format\n• Line numbers: $gutter\n• Retro: $retro\n• Syntax highlight: $syntax\n\nOpen SettingsActivity to change these.")
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
}
