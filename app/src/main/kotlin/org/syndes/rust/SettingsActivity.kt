package org.syndes.rust

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar

class SettingsActivity : AppCompatActivity() {

    private val prefsName = "editor_prefs"
    private val PREF_PREVENT_SCREENSHOT = "prevent_screenshot"
    private val PREF_UNDO_ENABLED = "undo_enabled"
    private val PREF_THEME_DARK = "theme_dark"
    private val PREF_FONT_SIZE = "font_size" // "small" | "normal" | "medium" | "large"

    // new options
    private val PREF_FORMAT_ON = "format_on"
    private val PREF_RETRO_MODE = "retro_mode"
    private val PREF_SYNTAX_HIGHLIGHT = "syntax_highlight"
    private val PREF_AMBER_MODE = "amber_mode"
    private val PREF_SYNTAX_LANGUAGE = "syntax_language" // e.g. "kotlin"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // prefs early so we can ensure default and apply theme before inflating layout
        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // Ensure dark pref exists and default to true (only set if missing)
        if (!sp.contains(PREF_THEME_DARK)) {
            sp.edit().putBoolean(PREF_THEME_DARK, true).apply()
        }

        // Apply theme according to preference (prevents flicker)
        AppCompatDelegate.setDefaultNightMode(
            if (sp.getBoolean(PREF_THEME_DARK, true))
                AppCompatDelegate.MODE_NIGHT_YES
            else
                AppCompatDelegate.MODE_NIGHT_NO
        )

        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar_settings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        // Note: sp already defined above
        // val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE) -- moved earlier

        val swPrevent = findViewById<Switch?>(R.id.swPreventScreenshots)
        val swUndo = findViewById<Switch?>(R.id.swUndoEnabled)
        // swDark removed (hidden from UI)
        // swFormat removed (hidden from UI)

        // NEW switches (remaining)
        val swRetro = findViewById<Switch?>(R.id.swRetroMode)
        val swSyntax = findViewById<Switch?>(R.id.swSyntaxHighlight)
        val swAmber = findViewById<Switch?>(R.id.swAmberMode)

        val rgFont = findViewById<RadioGroup?>(R.id.rgFontSize)
        val rbSmall = findViewById<RadioButton?>(R.id.rbFontSmall)
        val rbNormal = findViewById<RadioButton?>(R.id.rbFontNormal)
        val rbMedium = findViewById<RadioButton?>(R.id.rbFontMedium)
        val rbLarge = findViewById<RadioButton?>(R.id.rbFontLarge)

        // Language radio group (many languages)
        val rgLang = findViewById<RadioGroup?>(R.id.rgLanguage)
        val rbPython = findViewById<RadioButton?>(R.id.rbLangPython)
        val rbJava = findViewById<RadioButton?>(R.id.rbLangJava)
        val rbJavaScript = findViewById<RadioButton?>(R.id.rbLangJavaScript)
        val rbCSharp = findViewById<RadioButton?>(R.id.rbLangCSharp)
        val rbGo = findViewById<RadioButton?>(R.id.rbLangGo)
        val rbSwift = findViewById<RadioButton?>(R.id.rbLangSwift)
        val rbPHP = findViewById<RadioButton?>(R.id.rbLangPHP)
        val rbTS = findViewById<RadioButton?>(R.id.rbLangTypeScript)
        val rbKotlin = findViewById<RadioButton?>(R.id.rbLangKotlin)
        val rbRuby = findViewById<RadioButton?>(R.id.rbLangRuby)
        val rbRust = findViewById<RadioButton?>(R.id.rbLangRust)
        val rbDart = findViewById<RadioButton?>(R.id.rbLangDart)
        val rbCSS = findViewById<RadioButton?>(R.id.rbLangCSS)
        val rbHTML = findViewById<RadioButton?>(R.id.rbLangHTML)

        // OPTIONAL: views for hiding syntax/language UI (double-safety)
        val llSyntax = findViewById<LinearLayout?>(R.id.llSyntaxContainer)
        val tvLangLabel = findViewById<TextView?>(R.id.tvLanguageLabel)

        // hide syntax and language UI visually (keeps prefs & logic intact)
        llSyntax?.visibility = View.GONE
        tvLangLabel?.visibility = View.GONE
        rgLang?.visibility = View.GONE

        // initialize states (null-safe)
        swPrevent?.isChecked = sp.getBoolean(PREF_PREVENT_SCREENSHOT, false)
        swUndo?.isChecked = sp.getBoolean(PREF_UNDO_ENABLED, true)
        // swDark removed: we don't show toggle

        // swFormat removed: we don't show toggle
        swRetro?.isChecked = sp.getBoolean(PREF_RETRO_MODE, false)
        swSyntax?.isChecked = sp.getBoolean(PREF_SYNTAX_HIGHLIGHT, false)
        swAmber?.isChecked = sp.getBoolean(PREF_AMBER_MODE, false)

        // language init (default kotlin)
        val lang = sp.getString(PREF_SYNTAX_LANGUAGE, "kotlin") ?: "kotlin"
        when (lang) {
            "python" -> rbPython?.isChecked = true
            "java" -> rbJava?.isChecked = true
            "javascript" -> rbJavaScript?.isChecked = true
            "csharp" -> rbCSharp?.isChecked = true
            "go" -> rbGo?.isChecked = true
            "swift" -> rbSwift?.isChecked = true
            "php" -> rbPHP?.isChecked = true
            "typescript" -> rbTS?.isChecked = true
            "kotlin" -> rbKotlin?.isChecked = true
            "ruby" -> rbRuby?.isChecked = true
            "rust" -> rbRust?.isChecked = true
            "dart" -> rbDart?.isChecked = true
            "css" -> rbCSS?.isChecked = true
            "html" -> rbHTML?.isChecked = true
            else -> rbKotlin?.isChecked = true
        }

        // initial font radio selection
        when (sp.getString(PREF_FONT_SIZE, "normal")) {
            "small" -> rbSmall?.isChecked = true
            "normal" -> rbNormal?.isChecked = true
            "medium" -> rbMedium?.isChecked = true
            "large" -> rbLarge?.isChecked = true
            else -> rbNormal?.isChecked = true
        }

        // listener: Prevent screenshots (applies to this window immediately)
        swPrevent?.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            sp.edit().putBoolean(PREF_PREVENT_SCREENSHOT, checked).apply()
            if (checked) {
                window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                Toast.makeText(this, getString(R.string.settings_screenshots_protected), Toast.LENGTH_SHORT).show()
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                Toast.makeText(this, getString(R.string.settings_screenshots_allowed), Toast.LENGTH_SHORT).show()
            }
        }

        // listener: Undo enabled
        swUndo?.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            sp.edit().putBoolean(PREF_UNDO_ENABLED, checked).apply()
            Toast.makeText(this, if (checked) getString(R.string.settings_undo_on) else getString(R.string.settings_undo_off), Toast.LENGTH_SHORT).show()
        }

        // NEW listeners: retro / syntax / amber
        swRetro?.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            sp.edit().putBoolean(PREF_RETRO_MODE, checked).apply()
            Toast.makeText(this, if (checked) getString(R.string.settings_retro_on) else getString(R.string.settings_retro_off), Toast.LENGTH_SHORT).show()
        }

        swSyntax?.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            sp.edit().putBoolean(PREF_SYNTAX_HIGHLIGHT, checked).apply()
            Toast.makeText(this, if (checked) getString(R.string.settings_syntax_on) else getString(R.string.settings_syntax_off), Toast.LENGTH_SHORT).show()
        }

        swAmber?.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            sp.edit().putBoolean(PREF_AMBER_MODE, checked).apply()
            Toast.makeText(
                this,
                if (checked) getString(R.string.toast_amber_on) else getString(R.string.toast_amber_off),
                Toast.LENGTH_SHORT
            ).show()
        }

        // listener: font size radio group
        rgFont?.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.rbFontSmall -> "small"
                R.id.rbFontNormal -> "normal"
                R.id.rbFontMedium -> "medium"
                R.id.rbFontLarge -> "large"
                else -> "normal"
            }
            sp.edit().putString(PREF_FONT_SIZE, value).apply()
            Toast.makeText(this, getString(R.string.settings_font_changed, value), Toast.LENGTH_SHORT).show()
        }

        // listener: language selection (expanded list)
        rgLang?.setOnCheckedChangeListener { _, checkedId ->
            val langValue = when (checkedId) {
                R.id.rbLangPython -> "python"
                R.id.rbLangJava -> "java"
                R.id.rbLangJavaScript -> "javascript"
                R.id.rbLangCSharp -> "csharp"
                R.id.rbLangGo -> "go"
                R.id.rbLangSwift -> "swift"
                R.id.rbLangPHP -> "php"
                R.id.rbLangTypeScript -> "typescript"
                R.id.rbLangKotlin -> "kotlin"
                R.id.rbLangRuby -> "ruby"
                R.id.rbLangRust -> "rust"
                R.id.rbLangDart -> "dart"
                R.id.rbLangCSS -> "css"
                R.id.rbLangHTML -> "html"
                else -> "kotlin"
            }
            sp.edit().putString(PREF_SYNTAX_LANGUAGE, langValue).apply()
            Toast.makeText(
                this,
                getString(R.string.toast_language_set, langValue),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
