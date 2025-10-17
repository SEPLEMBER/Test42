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
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.syndes.rust.databinding.ActivityEditorBinding
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import kotlin.math.max
import kotlin.math.min
import android.view.WindowManager
import android.widget.TextView

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

    // History (undo/redo) — switched to linear history with index
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
    private val PREF_FONT_SIZE = "font_size" // values: small, normal, medium, large

    // new prefs
    private val PREF_FORMAT_ON = "format_on"
    private val PREF_SHOW_LINE_NUMBERS = "show_line_numbers" // temporarily ignored
    private val PREF_RETRO_MODE = "retro_mode"
    private val PREF_SYNTAX_HIGHLIGHT = "syntax_highlight"
    private val PREF_SYNTAX_MAPPING_URI = "syntax_mapping_uri"
    private val PREF_SYNTAX_LANGUAGE = "syntax_language" // e.g. "kotlin"
    private val PREF_AMBER_MODE = "amber_mode"

    // coroutine scope helper
    private val bgDispatcher = Dispatchers.Default

    // highlight job / observer
    private var highlightJob: Job? = null
    private var scrollObserverAttached = false
    private var onScrollListener: ViewTreeObserver.OnScrollChangedListener? = null
    private var uiUpdateJob: Job? = null

    // simple kotlin keywords fallback
    private val kotlinKeywords = setOf(
        "fun","val","var","if","else","for","while","return","import","class","object",
        "private","public","protected","internal","override","when","in","is","null","true","false"
    )

    // loaded mapping from external .txt (token(lowercase) -> color int)
    private val syntaxMapping = mutableMapOf<String, Int>()

    // helper: keys sorted by length desc for greedy matching
    private var keysByLengthDesc: List<String> = emptyList()

    // Colors used by special rules
    private val COLOR_PURPLE = Color.parseColor("#9C27B0")     // purple-lilac
    private val COLOR_TURQUOISE = Color.parseColor("#00BCD4")  // turquoise (bracket content)
    private val COLOR_COMMENT_GREY = Color.parseColor("#9E9E9E") // slight grey (comments)

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

        // Ensure jump zones accept clicks
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

        // launcher to load syntax mapping file (user picks a .txt mapping via SAF)
        loadSyntaxMappingLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                lifecycleScope.launch {
                    parseAndStoreSyntaxMappingFromUri(it)
                    Toast.makeText(this@EditorActivity, getString(R.string.toast_syntax_mapping_loaded), Toast.LENGTH_SHORT).show()
                    // request a highlight update
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

        // apply font size preference immediately
        applyFontSizeFromPrefs()

        // apply retro / syntax visuals (this may load mapping from assets if configured)
        applyPrefsVisuals()

        // text watcher: update hint, stats (debounced), and history
        binding.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // no-op — snapshots taken by debounce
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { /* no-op */ }

            override fun afterTextChanged(s: Editable?) {
                if (ignoreTextWatcher) return
                binding.emptyHint.visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
                scheduleStatsUpdate()
                scheduleHistorySnapshot() // debounced snapshot
                matches = emptyList()
                currentMatchIdx = -1
                // update visible highlight & formatting only
                scheduleHighlight()
            }
        })

        // top-right / bottom-right invisible zones for jump-to-start/end
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

        // touch on right edge -> quick scroll (approximate)
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
                // allow any file types — we'll try to read as UTF-8 text
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
                    Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_nothing_to_copy), Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_paste -> {
                val pasted = pasteFromClipboard()
                if (!pasted.isNullOrEmpty()) {
                    val pos = binding.editor.selectionStart.coerceAtLeast(0)
                    binding.editor.text?.insert(pos, pasted)
                    Toast.makeText(this, getString(R.string.toast_pasted), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.toast_clipboard_empty), Toast.LENGTH_SHORT).show()
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
                    // simply select all — do not try to force-show the contextual menu
                    binding.editor.selectAll()
                }
                true
            }
            // note: removed reference to R.id.action_load_syntax to avoid resource missing errors.
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
                    val intent = Intent(this, AboutActivity::class.java)
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
            .setTitle(getString(R.string.dialog_clear_document_title))
            .setMessage(getString(R.string.dialog_clear_document_message))
            .setPositiveButton(getString(R.string.clear_button)) { _, _ ->
                // snapshot current state before clearing
                pushHistorySnapshot(binding.editor.text?.toString() ?: "")
                ignoreTextWatcher = true
                binding.editor.setText("", TextView.BufferType.EDITABLE)
                binding.editor.setSelection(0)
                ignoreTextWatcher = false
                scheduleStatsUpdate()
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
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
                // set text without triggering history snapshot
                ignoreTextWatcher = true
                binding.editor.setText(content, TextView.BufferType.EDITABLE)
                binding.editor.setSelection(0)
                ignoreTextWatcher = false

                // reset history to the opened content
                history.clear()
                historyIndex = -1
                pushHistorySnapshot(content)

                scheduleStatsUpdate()
                scheduleHighlight()
                Toast.makeText(this@EditorActivity, getString(R.string.toast_file_opened), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@EditorActivity, getString(R.string.toast_error_opening_file, e.localizedMessage), Toast.LENGTH_LONG).show()
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
                    } ?: throw IllegalStateException(getString(R.string.error_cannot_open_output_stream))
                }
                Toast.makeText(this@EditorActivity, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@EditorActivity, getString(R.string.toast_error_saving_file, e.localizedMessage), Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- FIND & REPLACE ----------
    private fun showFindReplaceDialog() {
        val inflater = LayoutInflater.from(this)
        val view = inflater.inflate(R.layout.dialog_find_replace, null)
        val etFind = view.findViewById<EditText>(R.id.etFind)
        val etReplace = view.findViewById<EditText>(R.id.etReplace)
        val tvCount = view.findViewById<android.widget.TextView>(R.id.tvMatchesCount)
        val btnPrev = view.findViewById<View>(R.id.btnPrev)
        val btnNext = view.findViewById<View>(R.id.btnNext)
        val btnR1 = view.findViewById<View>(R.id.btnReplaceOne)
        val btnRAll = view.findViewById<View>(R.id.btnReplaceAll)

        if (!lastQuery.isNullOrEmpty()) etFind.setText(lastQuery)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_find_replace_title))
            .setView(view)
            .setNegativeButton(getString(R.string.close_button), null)
            .create()

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
                tvCount.text = getString(R.string.label_go_to_line)
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
                    tvCount.text = getString(R.string.label_matches_count, matches.size)
                    if (currentMatchIdx >= 0 && matches.isNotEmpty()) {
                        selectMatchAt(currentMatchIdx)
                    }
                }
            }
        }

        btnNext.setOnClickListener {
            if (matches.isEmpty()) { Toast.makeText(this, getString(R.string.toast_no_matches), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            currentMatchIdx = (currentMatchIdx + 1) % matches.size
            selectMatchAt(currentMatchIdx)
        }
        btnPrev.setOnClickListener {
            if (matches.isEmpty()) { Toast.makeText(this, getString(R.string.toast_no_matches), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            currentMatchIdx = if (currentMatchIdx - 1 < 0) matches.size - 1 else currentMatchIdx - 1
            selectMatchAt(currentMatchIdx)
        }

        btnR1.setOnClickListener {
            val q = etFind.text.toString()
            val r = etReplace.text.toString()
            if (q.isEmpty()) { Toast.makeText(this, getString(R.string.toast_query_empty), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (matches.isEmpty() || currentMatchIdx < 0) {
                Toast.makeText(this, getString(R.string.no_current_match_to_replace), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val range = matches[currentMatchIdx]
            val editable = binding.editor.text ?: return@setOnClickListener
            editable.replace(range.first, range.last + 1, r)
            computeMatchesAndUpdate(q)
            // push snapshot after replace-one
            pushHistorySnapshot(binding.editor.text?.toString() ?: "")
        }

        btnRAll.setOnClickListener {
            val q = etFind.text.toString()
            val r = etReplace.text.toString()
            if (q.isEmpty()) { Toast.makeText(this, getString(R.string.toast_query_empty), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
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
                    tvCount.text = getString(R.string.label_matches_count, 0)
                    scheduleStatsUpdate()
                    pushHistorySnapshot(replaced)
                    Toast.makeText(this@EditorActivity, getString(R.string.replaced_all), Toast.LENGTH_SHORT).show()
                }
            }
        }

        etFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { computeMatchesAndUpdate(s?.toString() ?: "") }
        })

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
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

    // ---------- STATS (debounced) ----------
    private fun scheduleStatsUpdate(delayMs: Long = 700L) {
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

    // ---------- PREF VISUALS (retro / amber / syntax) ----------
    private fun applyPrefsVisuals() {
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val retro = sp.getBoolean(PREF_RETRO_MODE, false)
        val amber = sp.getBoolean(PREF_AMBER_MODE, false)
        val syntaxOn = sp.getBoolean(PREF_SYNTAX_HIGHLIGHT, false)

        // amber takes precedence if enabled
        if (amber) {
            binding.editor.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            binding.editor.setTextColor(Color.parseColor("#FFBF00")) // amber
        } else if (retro) {
            binding.editor.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            binding.editor.setTextColor(Color.parseColor("#ff00ff00"))
        } else {
            binding.editor.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            binding.editor.setTextColor(getColorFromAttrOrDefault(android.R.attr.textColorPrimary, Color.parseColor("#E0E0E0")))
        }

        // syntax highlight: if enabled but mapping not loaded -> try to load mapping from either persisted URI or assets (language)
        if (syntaxOn) {
            if (syntaxMapping.isEmpty()) {
                // first try persisted URI
                val mappingUriString = sp.getString(PREF_SYNTAX_MAPPING_URI, null)
                if (!mappingUriString.isNullOrEmpty()) {
                    try {
                        val uri = Uri.parse(mappingUriString)
                        lifecycleScope.launch {
                            parseAndStoreSyntaxMappingFromUri(uri)
                            scheduleHighlight()
                        }
                    } catch (_: Exception) {
                        // fallback to assets language file
                        loadSyntaxMappingFromAssetsIfAvailable()
                    }
                } else {
                    // fallback to assets language file
                    loadSyntaxMappingFromAssetsIfAvailable()
                }
            } else {
                scheduleHighlight()
            }
        } else {
            // clear any highlighting if syntax is off
            clearForegroundSpansInRange(0, binding.editor.text?.length ?: 0)
            clearFormattingSpansInRange(0, binding.editor.text?.length ?: 0)
        }
    }

    private fun loadSyntaxMappingFromAssetsIfAvailable() {
        val spref = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val lang = spref.getString(PREF_SYNTAX_LANGUAGE, "kotlin") ?: "kotlin"
        // explicit safe interpolation
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
                    // if none found, try to open candidate1 directly (catch inside)
                    try {
                        parseAndStoreSyntaxMappingFromAssets(candidate1)
                        scheduleHighlight()
                    } catch (_: Exception) {
                        // silently ignore - no asset mapping available
                    }
                }
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    private fun promptUserToPickSyntaxMapping() {
        // launch SAF picker for text/* (user picks mapping .txt file)
        // Fire this on UI thread
        binding.root.post {
            try {
                loadSyntaxMappingLauncher.launch(arrayOf("text/*"))
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.toast_unable_open_file_picker), Toast.LENGTH_SHORT).show()
            }
        }
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

    // Parse mapping file (format lines: token=#RRGGBB) and store mapping in memory + persist URI
    private suspend fun parseAndStoreSyntaxMappingFromUri(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { br ->
                        val map = mutableMapOf<String, Int>()
                        br.forEachLine { raw ->
                            val line = raw.trim()
                            if (line.isEmpty()) return@forEachLine
                            if (line.startsWith("#")) return@forEachLine // comment
                            // allow token= #RRGGBB or token=#RRGGBB (with/without spaces)
                            val parts = line.split("=").map { it.trim() }
                            if (parts.size >= 2) {
                                val token = parts[0]
                                val colorStr = parts[1]
                                try {
                                    val color = Color.parseColor(colorStr)
                                    if (token.isNotEmpty()) map[token.lowercase()] = color
                                } catch (_: Exception) {
                                    // ignore invalid color
                                }
                            }
                        }
                        // swap into main map on UI thread
                        withContext(Dispatchers.Main) {
                            syntaxMapping.clear()
                            syntaxMapping.putAll(map)
                            // update keys sorted by length (desc) for greedy matching
                            keysByLengthDesc = syntaxMapping.keys.sortedByDescending { it.length }
                            // persist uri so we can reload next time
                            getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().putString(PREF_SYNTAX_MAPPING_URI, uri.toString()).apply()
                        }
                    }
                }
            } catch (e: Exception) {
                // ignore parse errors but show toast
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, getString(R.string.toast_failed_read_mapping_file, e.localizedMessage), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Parse mapping file from assets/<filename>
    private suspend fun parseAndStoreSyntaxMappingFromAssets(filename: String) {
        withContext(Dispatchers.IO) {
            try {
                assets.open(filename).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { br ->
                        val map = mutableMapOf<String, Int>()
                        br.forEachLine { raw ->
                            val line = raw.trim()
                            if (line.isEmpty()) return@forEachLine
                            if (line.startsWith("#")) return@forEachLine // comment
                            val parts = line.split("=").map { it.trim() }
                            if (parts.size >= 2) {
                                val token = parts[0]
                                val colorStr = parts[1]
                                try {
                                    val color = Color.parseColor(colorStr)
                                    if (token.isNotEmpty()) map[token.lowercase()] = color
                                } catch (_: Exception) {
                                    // ignore invalid color
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            syntaxMapping.clear()
                            syntaxMapping.putAll(map)
                            keysByLengthDesc = syntaxMapping.keys.sortedByDescending { it.length }
                            // do not persist assets as URI; but store language pref (already done via settings)
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@EditorActivity, getString(R.string.toast_failed_read_mapping_asset, e.localizedMessage), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- HIGHLIGHT (visible area only) ----------
    private fun scheduleHighlight(delayMs: Long = 500L) {
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
            if (visibleText.isEmpty()) {
                withContext(Dispatchers.Main) {
                    clearForegroundSpansInRange(startOffset, endOffset)
                    if (!formatOn) clearFormattingSpansInRange(startOffset, endOffset)
                }
                return@launch
            }

            val visibleLower = visibleText.lowercase()
            val len = visibleText.length

            // Arrays to mark regions
            val commentMask = BooleanArray(len) { false }  // true => skip other highlighting
            val occupied = BooleanArray(len) { false }     // true => already covered by higher-priority span

            val spansToApply = mutableListOf<Triple<Int, Int, Int>>() // globalStart, globalEnd, color

            // 1) Detect full-line comments (trimmed startsWith "//" or "<-") and mark them grey
            var localIdx = 0
            while (localIdx < len) {
                val lineStartLocal = localIdx
                val newlinePos = visibleText.indexOf('\n', localIdx).let { if (it == -1) len else it }
                val lineEndLocal = newlinePos
                // get trimmed leading substring to check start
                var p = lineStartLocal
                while (p < lineEndLocal && visibleText[p].isWhitespace()) p++
                if (p < lineEndLocal) {
                    // check for comment markers
                    if (visibleText.startsWith("//", p) || visibleText.startsWith("<-", p)) {
                        // mark entire line as comment
                        for (i in lineStartLocal until lineEndLocal) commentMask[i] = true
                        val gStart = startOffset + lineStartLocal
                        val gEnd = startOffset + lineEndLocal
                        spansToApply.add(Triple(gStart, gEnd, COLOR_COMMENT_GREY))
                        for (i in lineStartLocal until lineEndLocal) occupied[i] = true
                    }
                }
                localIdx = if (newlinePos == -1) len else newlinePos + 1
            }

            // 2) Bracket pairing for (), {}, []
            data class PairLocal(val open: Int, val close: Int)
            val stackRound = ArrayDeque<Int>()
            val stackCurly = ArrayDeque<Int>()
            val stackSquare = ArrayDeque<Int>()
            val pairs = mutableListOf<PairLocal>()
            for (i in 0 until len) {
                if (commentMask[i]) continue
                when (visibleText[i]) {
                    '(' -> stackRound.addLast(i)
                    '{' -> stackCurly.addLast(i)
                    '[' -> stackSquare.addLast(i)
                    ')' -> if (stackRound.isNotEmpty()) {
                        val o = stackRound.removeLast()
                        pairs.add(PairLocal(o, i))
                    }
                    '}' -> if (stackCurly.isNotEmpty()) {
                        val o = stackCurly.removeLast()
                        pairs.add(PairLocal(o, i))
                    }
                    ']' -> if (stackSquare.isNotEmpty()) {
                        val o = stackSquare.removeLast()
                        pairs.add(PairLocal(o, i))
                    }
                }
            }
            // For each pair, highlight inner content (excluding brackets). Do not color brackets.
            for (pr in pairs) {
                val innerStart = pr.open + 1
                val innerEnd = pr.close
                if (innerStart >= innerEnd) continue
                // We may have comments inside; only color subranges that are not commentMask
                var s = innerStart
                while (s < innerEnd) {
                    // skip commented
                    while (s < innerEnd && commentMask[s]) s++
                    if (s >= innerEnd) break
                    var e = s
                    while (e < innerEnd && !commentMask[e]) e++
                    // add turquoise span for [s, e)
                    val gS = startOffset + s
                    val gE = startOffset + e
                    // mark occupied
                    for (i in s until e) occupied[i] = true
                    spansToApply.add(Triple(gS, gE, COLOR_TURQUOISE))
                    s = e
                }
            }

            // 3) Special contextual rules based on word tokens:
            //    - package: all following words in same line -> purple
            //    - private var|val <first-name> -> purple (first name after var/val)
            //    - override fun <first-name> -> purple
            // We'll scan word tokens using regex-like approach (letters/digits/_)
            val wordRegex = Regex("[A-Za-z_][A-Za-z0-9_]*")
            val wordMatches = wordRegex.findAll(visibleText).toList() // includes positions
            // create list of (startLocal, endLocal, wordLower)
            data class W(val s: Int, val e: Int, val word: String)
            val words = wordMatches.map { W(it.range.first, it.range.last + 1, it.value.lowercase()) }

            var iWord = 0
            while (iWord < words.size) {
                val w = words[iWord]
                // skip if in commented line (we'll check commentMask at token start)
                if (commentMask.getOrNull(w.s) == true) { iWord++; continue }
                when (w.word) {
                    "package" -> {
                        // highlight all words after package in the same line
                        // compute line end local
                        val lineEndLocal = visibleText.indexOf('\n', w.e).let { if (it == -1) len else it }
                        // find all words that start >= w.e and < lineEndLocal
                        for (j in iWord + 1 until words.size) {
                            val ww = words[j]
                            if (ww.s >= lineEndLocal) break
                            if (commentMask.getOrNull(ww.s) == true) continue
                            val gS = startOffset + ww.s
                            val gE = startOffset + ww.e
                            // mark occupied and add purple span
                            var already = false
                            for (p in ww.s until ww.e) if (occupied[p]) { already = true; break }
                            if (!already) {
                                for (p in ww.s until ww.e) occupied[p] = true
                                spansToApply.add(Triple(gS, gE, COLOR_PURPLE))
                            }
                        }
                    }
                    "private" -> {
                        // look ahead to see if next meaningful word is var or val; if so, highlight following word name
                        var j = iWord + 1
                        var foundVarVal = false
                        while (j < words.size) {
                            val ww = words[j]
                            if (commentMask.getOrNull(ww.s) == true) { j++; continue }
                            if (ww.word == "var" || ww.word == "val" || ww.word == "fun") {
                                foundVarVal = true
                                break
                            } else {
                                break // other token -> stop
                            }
                        }
                        if (foundVarVal) {
                            val afterIdx = j + 1
                            if (afterIdx < words.size) {
                                val nameToken = words[afterIdx]
                                if (!commentMask.getOrNull(nameToken.s)!!) {
                                    var already = false
                                    for (p in nameToken.s until nameToken.e) if (occupied[p]) { already = true; break }
                                    if (!already) {
                                        for (p in nameToken.s until nameToken.e) occupied[p] = true
                                        spansToApply.add(Triple(startOffset + nameToken.s, startOffset + nameToken.e, COLOR_PURPLE))
                                    }
                                }
                            }
                        }
                    }
                    "override" -> {
                        // look for fun then next word
                        var j = iWord + 1
                        var foundFun = false
                        while (j < words.size) {
                            val ww = words[j]
                            if (commentMask.getOrNull(ww.s) == true) { j++; continue }
                            if (ww.word == "fun") { foundFun = true; break } else { break }
                        }
                        if (foundFun) {
                            val afterIdx = j + 1
                            if (afterIdx < words.size) {
                                val nameToken = words[afterIdx]
                                if (!commentMask.getOrNull(nameToken.s)!!) {
                                    var already = false
                                    for (p in nameToken.s until nameToken.e) if (occupied[p]) { already = true; break }
                                    if (!already) {
                                        for (p in nameToken.s until nameToken.e) occupied[p] = true
                                        spansToApply.add(Triple(startOffset + nameToken.s, startOffset + nameToken.e, COLOR_PURPLE))
                                    }
                                }
                            }
                        }
                    }
                }
                iWord++
            }

            // 4) Mapping & keywords — greedy matching via keysByLengthDesc for ANY token (including symbols/numbers)
            // We'll iterate through positions local i, attempt to match a key (longest first). Skip occupied or commented.
            if (keysByLengthDesc.isNotEmpty()) {
                var i = 0
                while (i < len) {
                    if (commentMask[i] || occupied[i]) { i++; continue }
                    var matched = false
                    for (key in keysByLengthDesc) {
                        val klen = key.length
                        if (klen == 0 || i + klen > len) continue
                        // compare to visibleLower substring without creating substrings: use regionMatches
                        if (visibleLower.regionMatches(i, key, 0, klen)) {
                            // ensure not overlapping occupied
                            var anyOcc = false
                            for (p in i until (i + klen)) { if (occupied[p]) { anyOcc = true; break } }
                            if (anyOcc) {
                                // skip this key, try others or advance
                                continue
                            }
                            // apply mapped color
                            val color = syntaxMapping[key] ?: continue
                            val gS = startOffset + i
                            val gE = startOffset + i + klen
                            spansToApply.add(Triple(gS, gE, color))
                            for (p in i until i + klen) occupied[p] = true
                            matched = true
                            i += klen
                            break
                        }
                    }
                    if (!matched) i++
                }
            }

            // 5) Fallback: highlight kotlinKeywords (word tokens) if not already occupied
            for (w in words) {
                if (commentMask.getOrNull(w.s) == true) continue
                if (occupied.sliceArray(w.s until w.e).any { it }) continue
                if (kotlinKeywords.contains(w.word)) {
                    val gS = startOffset + w.s
                    val gE = startOffset + w.e
                    for (p in w.s until w.e) occupied[p] = true
                    // use existing blue from previous code
                    val blue = Color.parseColor("#82B1FF")
                    spansToApply.add(Triple(gS, gE, blue))
                }
            }

            // Merge adjacent/overlapping spans of the same color to reduce number of spans applied
            val merged = mergeSpans(spansToApply)

            withContext(Dispatchers.Main) {
                try {
                    clearForegroundSpansInRange(startOffset, endOffset)
                    val editable = binding.editor.text
                    if (editable is Spannable) {
                        for ((s, e, color) in merged) {
                            try {
                                editable.setSpan(ForegroundColorSpan(color), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            } catch (_: Exception) {}
                        }
                        // formatting spans if enabled
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

    // Merge spans by color: input list may be unsorted
    private fun mergeSpans(input: List<Triple<Int, Int, Int>>): List<Triple<Int, Int, Int>> {
        if (input.isEmpty()) return emptyList()
        val byColor = input.groupBy { it.third }
        val out = mutableListOf<Triple<Int, Int, Int>>()
        for ((color, list) in byColor) {
            val ranges = list.map { it.first to it.second }.sortedWith(compareBy({ it.first }, { it.second }))
            var curS = ranges[0].first
            var curE = ranges[0].second
            for (i in 1 until ranges.size) {
                val (s, e) = ranges[i]
                if (s <= curE) {
                    curE = max(curE, e)
                } else {
                    out.add(Triple(curS, curE, color))
                    curS = s; curE = e
                }
            }
            out.add(Triple(curS, curE, color))
        }
        // finally sort merged spans by start
        return out.sortedBy { it.first }
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
        for (sp in styleSpans) {
            try { editable.removeSpan(sp) } catch (_: Exception) {}
        }
        val alignSpans = editable.getSpans(rangeStart, rangeEnd, AlignmentSpan::class.java)
        for (sp in alignSpans) {
            try { editable.removeSpan(sp) } catch (_: Exception) {}
        }
        val replSpans = editable.getSpans(rangeStart, rangeEnd, ReplacementSpan::class.java)
        for (sp in replSpans) {
            try { editable.removeSpan(sp) } catch (_: Exception) {}
        }
    }

    // Very light-weight inline formatting: applies StyleSpan / AlignmentSpan for tags found in the visible range.
    // IMPORTANT: tags remain in text (we only visually style inner content). This matches your requirement:
    // the raw file still contains tags but the editor shows visual formatting.
    private fun applyInlineFormattingInRange(editable: Spannable, rangeStart: Int, rangeEnd: Int) {
        val raw = editable.subSequence(rangeStart, rangeEnd).toString()

        // Hide tags (<...>) by applying transparent ForegroundColorSpan — then apply other spans to inner text.
        // We'll treat <tab> specially by placing a TabSpan (ReplacementSpan) to draw empty space.

        // First: handle bold
        val boldRegex = Regex("<b>(.*?)</b>", RegexOption.DOT_MATCHES_ALL)
        for (m in boldRegex.findAll(raw)) {
            val inner = m.groups[1] ?: continue
            val openTagLen = "<b>".length
            val s = rangeStart + m.range.first + openTagLen
            val e = s + inner.value.length
            try { editable.setSpan(StyleSpan(Typeface.BOLD), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Exception) {}
        }

        // italic
        val italicRegex = Regex("<i>(.*?)</i>", RegexOption.DOT_MATCHES_ALL)
        for (m in italicRegex.findAll(raw)) {
            val inner = m.groups[1] ?: continue
            val openTagLen = "<i>".length
            val s = rangeStart + m.range.first + openTagLen
            val e = s + inner.value.length
            try { editable.setSpan(StyleSpan(Typeface.ITALIC), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Exception) {}
        }

        // center
        val centerRegex = Regex("<center>(.*?)</center>", RegexOption.DOT_MATCHES_ALL)
        for (m in centerRegex.findAll(raw)) {
            val inner = m.groups[1] ?: continue
            val openTagLen = "<center>".length
            val s = rangeStart + m.range.first + openTagLen
            val e = s + inner.value.length
            try { editable.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), s, e, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) } catch (_: Exception) {}
        }

        // Now: hide all tags inside the range (<...>)
        val tagRegex = Regex("<[^>]+>")
        for (m in tagRegex.findAll(raw)) {
            val tagText = m.value
            val tagGlobalStart = rangeStart + m.range.first
            val tagGlobalEnd = rangeStart + m.range.last + 1
            try {
                if (tagText.equals("<tab>", ignoreCase = true)) {
                    // replace drawing of the tag with a visual blank using ReplacementSpan — leave underlying text intact.
                    // Choose tab width based on current editor paint: a few spaces width
                    editable.setSpan(TabSpan(), tagGlobalStart, tagGlobalEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    // hide textual tag by making its foreground transparent
                    editable.setSpan(ForegroundColorSpan(Color.TRANSPARENT), tagGlobalStart, tagGlobalEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } catch (_: Exception) {}
        }
    }

    private fun attachScrollObserver() {
        if (scrollObserverAttached) return
        onScrollListener = ViewTreeObserver.OnScrollChangedListener {
            scheduleHighlight()
        }
        binding.editor.viewTreeObserver.addOnScrollChangedListener(onScrollListener)
        scrollObserverAttached = true
    }

    // ---------- HISTORY helpers (undo/redo) ----------
    private fun pushHistorySnapshot(value: String) {
        // add immediate snapshot (used by explicit actions)
        addHistorySnapshot(value)
    }

    private fun addHistorySnapshot(value: String) {
        if (historyIndex >= 0 && historyIndex < history.size && history[historyIndex] == value) return
        // drop forward history
        if (historyIndex < history.size - 1) {
            for (i in history.size - 1 downTo historyIndex + 1) history.removeAt(i)
        }
        history.add(value)
        historyIndex = history.size - 1
        // trim oldest entries
        while (history.size > maxHistory) {
            history.removeAt(0)
            historyIndex--
        }
    }

    private fun scheduleHistorySnapshot(delayMs: Long = 5000L) {
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
        if (!undoEnabled) {
            Toast.makeText(this, getString(R.string.toast_undo_disabled), Toast.LENGTH_SHORT).show()
            return
        }
        if (historyIndex <= 0) {
            Toast.makeText(this, getString(R.string.toast_nothing_to_undo), Toast.LENGTH_SHORT).show()
            return
        }
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
        if (historyIndex >= history.size - 1) {
            Toast.makeText(this, getString(R.string.toast_nothing_to_redo), Toast.LENGTH_SHORT).show()
            return
        }
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
        input.hint = getString(R.string.hint_password)
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_encrypt_title))
            .setView(input)
            .setPositiveButton(getString(R.string.encrypt_button), null)
            .setNegativeButton(getString(R.string.cancel_button), null)
            .create()

        dlg.setOnShowListener {
            val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val pw = input.text.toString()
                if (pw.isEmpty()) { Toast.makeText(this, getString(R.string.toast_password_required), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                dlg.dismiss()
                performEncrypt(pw.toCharArray())
            }
        }
        dlg.show()
    }

    private fun promptDecryptCurrent() {
        val input = EditText(this)
        input.hint = getString(R.string.hint_password)
        val dlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_decrypt_title))
            .setView(input)
            .setPositiveButton(getString(R.string.decrypt_button), null)
            .setNegativeButton(getString(R.string.cancel_button), null)
            .create()

        dlg.setOnShowListener {
            val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.setOnClickListener {
                val pw = input.text.toString()
                if (pw.isEmpty()) { Toast.makeText(this, getString(R.string.toast_password_required), Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                dlg.dismiss()
                performDecrypt(pw.toCharArray())
            }
        }
        dlg.show()
    }

    private fun performEncrypt(password: CharArray) {
        val plain = binding.editor.text?.toString() ?: ""
        if (plain.isEmpty()) { Toast.makeText(this, getString(R.string.toast_nothing_to_encrypt), Toast.LENGTH_SHORT).show(); return }
        val waitDlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_encrypting_title))
            .setMessage(getString(R.string.dialog_encrypting_message))
            .setCancelable(false)
            .create()
        waitDlg.show()

        val dotsJob = lifecycleScope.launch {
            var dots = 0
            while (isActive) {
                withContext(Dispatchers.Main) {
                    waitDlg.setMessage(
                        getString(R.string.dialog_encrypting_message) + ".".repeat(dots)
                    )
                }
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
                    Toast.makeText(this@EditorActivity, getString(R.string.toast_encrypt_done), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@EditorActivity, getString(R.string.toast_encryption_failed, e.localizedMessage), Toast.LENGTH_LONG).show() }
            } finally {
                dotsJob.cancel()
                withContext(Dispatchers.Main) { waitDlg.dismiss() }
            }
        }
    }

    private fun performDecrypt(password: CharArray) {
        val encrypted = binding.editor.text?.toString() ?: ""
        if (encrypted.isEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.toast_nothing_to_decrypt),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val waitDlg = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_decrypting_title))
            .setMessage(getString(R.string.dialog_decrypting_message))
            .setCancelable(false)
            .create()
        waitDlg.show()

        val dotsJob = lifecycleScope.launch {
            var dots = 0
            while (isActive) {
                withContext(Dispatchers.Main) {
                    waitDlg.setMessage(
                        getString(R.string.dialog_decrypting_message) + ".".repeat(dots)
                    )
                }
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
                    Toast.makeText(
                        this@EditorActivity,
                        getString(R.string.toast_decrypt_done),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(this@EditorActivity, getString(R.string.toast_decryption_failed, e.localizedMessage), Toast.LENGTH_LONG).show() }
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
            .setTitle(getString(R.string.dialog_settings_title))
            .setMessage(getString(R.string.dialog_settings_message, prevent, undo, dark, font, format, gutter, retro, syntax))
            .setPositiveButton(getString(R.string.ok_button), null)
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

    // ReplacementSpan that draws an empty block of width ~ 4 spaces (tab)
    private class TabSpan(private val spaces: Int = 4) : ReplacementSpan() {
        override fun getSize(paint: android.graphics.Paint, text: CharSequence?, start: Int, end: Int, fm: android.graphics.Paint.FontMetricsInt?): Int {
            val spaceWidth = paint.measureText(" ")
            return (spaceWidth * spaces).toInt()
        }

        override fun draw(
            canvas: android.graphics.Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: android.graphics.Paint
        ) {
            // draw nothing (just advance)
            // If desired, could draw a faint guide; we intentionally draw nothing to leave blank space.
        }
    }
}
