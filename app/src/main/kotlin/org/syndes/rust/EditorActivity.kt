package org.syndes.rust

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import org.syndes.rust.databinding.ActivityEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding

    // Текущая URI открытого файла (если есть)
    private var currentDocumentUri: Uri? = null

    // SAF лончеры
    private lateinit var openDocumentLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var createDocumentLauncher: ActivityResultLauncher<String>

    // Переменные для поиска
    private var lastQuery: String? = null
    private var lastMatchIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // Регистрируем SAF лончеры
        openDocumentLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readDocumentUri(it) }
        }

        createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { uriCreated ->
                // После создания документа — сохраняем в него текст
                currentDocumentUri = uriCreated
                writeToUri(uriCreated, binding.editor.text.toString())
            }
        }

        // Меню в Toolbar обрабатываем через onOptionsItemSelected
        binding.toolbar.setOnMenuItemClickListener { item ->
            onOptionsItemSelected(item)
        }

        // Если нужно — показываем подсказку
        binding.emptyHint.isVisible = binding.editor.text.isNullOrEmpty()

        // Поддержим обновление подсказки при изменении текста
        binding.editor.addTextChangedListener {
            binding.emptyHint.isVisible = it.isNullOrEmpty()
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open -> {
                // Открыть документ (text/*)
                openDocumentLauncher.launch(arrayOf("text/*"))
                true
            }
            R.id.action_save -> {
                // Сохраняем: если есть текущая URI — перезаписываем, иначе — CreateDocument
                val uri = currentDocumentUri
                if (uri != null) {
                    writeToUri(uri, binding.editor.text.toString())
                } else {
                    // Предложим имя по умолчанию
                    createDocumentLauncher.launch("untitled.txt")
                }
                true
            }
            R.id.action_find -> {
                showFindDialog()
                true
            }
            R.id.action_copy -> {
                // Заглушка: копировать — пока заглушка
                // Для примера: кладём в буфер выделенный текст (можно оставить как заглушку)
                val sel = binding.editor.text?.substring(binding.editor.selectionStart, binding.editor.selectionEnd)
                if (!sel.isNullOrEmpty()) {
                    copyToClipboard(sel)
                    Toast.makeText(this, "Copy (placeholder): copied selection to clipboard", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Copy (placeholder): nothing selected", Toast.LENGTH_SHORT).show()
                }
                true
            }
            R.id.action_paste -> {
                // Заглушка: вставить — пока заглушка, вставляет из системного буфера (если есть)
                val pasted = pasteFromClipboard()
                if (pasted != null) {
                    val start = binding.editor.selectionStart.coerceAtLeast(0)
                    binding.editor.text?.insert(start, pasted)
                    Toast.makeText(this, "Paste (placeholder): pasted from clipboard", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Paste (placeholder): clipboard empty", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Чтение документа из SAF URI в background
    private fun readDocumentUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                // Попытка взять persistable permission (если доступен)
                try {
                    val takeFlags = (intent?.flags ?: 0) and
                        (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    contentResolver.takePersistableUriPermission(uri, takeFlags)
                } catch (ignored: Exception) {
                    // не критично
                }

                val content = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { br ->
                            val sb = StringBuilder()
                            var line: String? = br.readLine()
                            while (line != null) {
                                sb.append(line)
                                // сохраняем переносы строк
                                line = br.readLine()
                                if (line != null) sb.append("\n")
                            }
                            sb.toString()
                        }
                    } ?: ""
                }

                currentDocumentUri = uri
                binding.editor.setText(content)
                binding.editor.setSelection(0)
                Toast.makeText(this@EditorActivity, "File opened", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@EditorActivity, "Error opening file: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Запись в URI (перезапись) в background
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

    // Показать диалог поиска (регистронезависимо, корректно работает со спецсимволами)
    private fun showFindDialog() {
        val input = EditText(this)
        input.hint = "Find"

        val alert = AlertDialog.Builder(this)
            .setTitle("Find")
            .setView(input)
            .setPositiveButton("Find Next") { _, _ ->
                val query = input.text.toString()
                if (query.isNotEmpty()) {
                    performFind(query)
                } else {
                    Toast.makeText(this, "Empty query", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Find Previous") { _, _ ->
                val query = input.text.toString()
                if (query.isNotEmpty()) {
                    performFindPrevious(query)
                } else {
                    Toast.makeText(this, "Empty query", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .create()

        // Если есть предыдущий запрос — вставим в поле
        if (!lastQuery.isNullOrEmpty()) input.setText(lastQuery)

        alert.show()
    }

    // Находит следующий матч для lastQuery или нового query
    private fun performFind(query: String) {
        val fullText = binding.editor.text?.toString() ?: ""
        if (fullText.isEmpty()) {
            Toast.makeText(this, "Document empty", Toast.LENGTH_SHORT).show()
            return
        }

        // Экранируем спецсимволы, используем регистронезависимый поиск
        val escaped = Regex.escape(query)
        val regex = Regex(escaped, RegexOption.IGNORE_CASE)

        val startSearchFrom = if (lastQuery == query && lastMatchIndex >= 0) lastMatchIndex + 1 else 0

        val match = regex.find(fullText, startSearchFrom)
        if (match != null) {
            val s = match.range.first
            val e = match.range.last + 1
            selectAndReveal(s, e)
            lastQuery = query
            lastMatchIndex = s
            Toast.makeText(this, "Found at $s", Toast.LENGTH_SHORT).show()
        } else {
            // если не найдено от текущей позиции, попробуем сначала от начала
            val wrapMatch = regex.find(fullText, 0)
            if (wrapMatch != null) {
                val s = wrapMatch.range.first
                val e = wrapMatch.range.last + 1
                selectAndReveal(s, e)
                lastQuery = query
                lastMatchIndex = s
                Toast.makeText(this, "Found (wrapped) at $s", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Not found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun performFindPrevious(query: String) {
        val fullText = binding.editor.text?.toString() ?: ""
        if (fullText.isEmpty()) {
            Toast.makeText(this, "Document empty", Toast.LENGTH_SHORT).show()
            return
        }

        val escaped = Regex.escape(query)
        val regex = Regex(escaped, RegexOption.IGNORE_CASE)

        // Ищем с позиции перед lastMatchIndex
        val searchEnd = if (lastQuery == query && lastMatchIndex > 0) lastMatchIndex - 1 else fullText.length
        // Перебираем все вхождения до позиции
        val allMatches = regex.findAll(fullText, 0).toList()
        val prev = allMatches.lastOrNull { it.range.first < searchEnd }
        if (prev != null) {
            val s = prev.range.first
            val e = prev.range.last + 1
            selectAndReveal(s, e)
            lastQuery = query
            lastMatchIndex = s
            Toast.makeText(this, "Found at $s", Toast.LENGTH_SHORT).show()
        } else {
            // пробуем найти с конца (wrap)
            val last = allMatches.lastOrNull()
            if (last != null) {
                val s = last.range.first
                val e = last.range.last + 1
                selectAndReveal(s, e)
                lastQuery = query
                lastMatchIndex = s
                Toast.makeText(this, "Found (wrapped) at $s", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Not found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectAndReveal(start: Int, end: Int) {
        binding.editor.requestFocus()
        val safeStart = start.coerceIn(0, binding.editor.text?.length ?: 0)
        val safeEnd = end.coerceIn(0, binding.editor.text?.length ?: 0)
        binding.editor.setSelection(safeStart, safeEnd)
    }

    // Копирование в системный буфер (в качестве заглушки/полезной реализации)
    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("text", text)
        clipboard.setPrimaryClip(clip)
    }

    // Вставка из системного буфера (в качестве заглушки)
    private fun pasteFromClipboard(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val primary = clipboard.primaryClip
        return if (primary != null && primary.itemCount > 0) {
            val item = primary.getItemAt(0)
            item.coerceToText(this).toString()
        } else null
    }
}
