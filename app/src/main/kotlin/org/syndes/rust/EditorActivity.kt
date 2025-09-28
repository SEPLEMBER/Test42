package org.syndes.rust

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
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
import java.util.ArrayDeque
import kotlin.math.min

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding

    // SAF launchers
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createDocumentLauncherSave: ActivityResultLauncher<String> // Save (create if not exists)
    private lateinit var createDocumentLauncherSaveAs: ActivityResultLauncher<String> // Save As

    // current doc uri
    private var currentDocumentUri: Uri? = null

    // Find/Replace state (kept minimal here)
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

    // coroutine scope helper
    private val bgDispatcher = Dispatchers.Default

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

        // text watcher: update hint, stats (debounced), and history
        binding.editor.addTextChangedListener(object : TextWatcher {
            private var lastChangeTime = 0L
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
            }
        })
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
            R.id.action_encrypt -> {
                promptEncryptCurrent()
                true
            }
            R.id.action_decrypt -> {
                promptDecryptCurrent()
                true
            }
            R.id.action_settings -> {
                // open settings placeholder — you can implement a SettingsActivity; for now toggle some prefs for demo
                showSettingsDialog()
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
                // set text on main thread
                binding.editor.setText(content)
                binding.editor.setSelection(0)
                // store initial state in undo stack
                undoStack.clear()
                redoStack.clear()
                pushUndoSnapshot(content)
                scheduleStatsUpdate()
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

    // ---------- FIND & REPLACE (kept as earlier) ----------
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

        // compute matches
        fun computeMatchesAndUpdate(query: String) {
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
                    binding.editor.setText(replaced)
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
                withContext(Dispatchers.Main) {
                    binding.tvStats.text = stats
                }
            }
        }
    }

    // backward-compatible alias
    private fun updateStatsAsync() = scheduleStatsUpdate()

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
        binding.editor.setText(prev)
        binding.editor.setSelection(min(prev.length, 0))
        scheduleStatsUpdate()
    }

    fun performRedo() {
        if (redoStack.isEmpty()) {
            Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show()
            return
        }
        val next = redoStack.removeFirst()
        pushUndoSnapshot(binding.editor.text?.toString() ?: "")
        binding.editor.setText(next)
        binding.editor.setSelection(min(next.length, 0))
        scheduleStatsUpdate()
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
                    binding.editor.setText(encrypted)
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
                    binding.editor.setText(plain)
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

    // ---------- SETTINGS DIALOG (simple) ----------
    private fun showSettingsDialog() {
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val prevent = sp.getBoolean(PREF_PREVENT_SCREENSHOT, false)
        val undo = sp.getBoolean(PREF_UNDO_ENABLED, true)
        val dark = sp.getBoolean(PREF_THEME_DARK, true)

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings_simple, null)
        // dialog_settings_simple is optional - if not present, fallback to simple toggle dialog below
        try {
            val cbPrevent = view.findViewById<View?>(R.id.cbPreventScreenshots)
            val cbUndo = view.findViewById<View?>(R.id.cbUndo)
            val cbTheme = view.findViewById<View?>(R.id.cbTheme)
            // if you add checkboxes in layout, you can set them here. For now we just show a text dialog.
        } catch (_: Exception) {
            // ignore missing layout
        }

        val dlg = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMessage("Settings available:\n• Prevent screenshots: $prevent\n• Undo enabled: $undo\n• Dark theme: $dark\n\nTo change these, implement a Settings screen (placeholder).")
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
