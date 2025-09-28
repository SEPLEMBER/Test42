package org.syndes.rust

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
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
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar_settings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        val swPrevent = findViewById<Switch?>(R.id.swPreventScreenshots)
        val swUndo = findViewById<Switch?>(R.id.swUndoEnabled)
        val swDark = findViewById<Switch?>(R.id.swDarkTheme)

        // NEW switches (null-safe)
        val swFormat = findViewById<Switch?>(R.id.swFormatOn)
        val swRetro = findViewById<Switch?>(R.id.swRetroMode)
        val swSyntax = findViewById<Switch?>(R.id.swSyntaxHighlight)
        val swAmber = findViewById<Switch?>(R.id.swAmberMode)

        val rgFont = findViewById<RadioGroup?>(R.id.rgFontSize)
        val rbSmall = findViewById<RadioButton?>(R.id.rbFontSmall)
        val rbNormal = findViewById<RadioButton?>(R.id.rbFontNormal)
        val rbMedium = findViewById<RadioButton?>(R.id.rbFontMedium)
        val rbLarge = findViewById<RadioButton?>(R.id.rbFontLarge)

        // Language radio group
        val rgLang = findViewById<RadioGroup?>(R.id.rgLanguage)
        val rbKotlin = findViewById<RadioButton?>(R.id.rbLangKotlin)

        // initialize states (null-safe)
        swPrevent?.isChecked = sp.getBoolean(PREF_PREVENT_SCREENSHOT, false)
        swUndo?.isChecked = sp.getBoolean(PREF_UNDO_ENABLED, true)
        swDark?.isChecked = sp.getBoolean(PREF_THEME_DARK, true)

        swFormat?.isChecked = sp.getBoolean(PREF_FORMAT_ON, false)
        swRetro?.isChecked = sp.getBoolean(PREF_RETRO_MODE, false)
        swSyntax?.isChecked = sp.getBoolean(PREF_SYNTAX_HIGHLIGHT, false)
        swAmber?.isChecked = sp.getBoolean(PREF_AMBER_MODE, false)

        // language init (default kotlin)
        val lang = sp.getString(PREF_SYNTAX_LANGUAGE, "kotlin") ?: "kotlin"
        if (lang == "kotlin") rbKotlin?.isChecked = true

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

        // listener: Theme toggle (applies immediately across app)
        swDark?.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            sp.edit().putBoolean(PREF_THEME_DARK, checked).apply()
            // ensure amber/retro toggles are not both left enabled — but we keep it simple: user can toggle independently
            AppCompatDelegate.setDefaultNightMode(if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            recreate()
            Toast.makeText(this, getString(R.string.settings_theme_changed), Toast.LENGTH_SHORT).show()
        }

        // NEW listeners: format / retro / syntax / amber
        swFormat?.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            sp.edit().putBoolean(PREF_FORMAT_ON, checked).apply()
            Toast.makeText(this, if (checked) getString(R.string.settings_format_on) else getString(R.string.settings_format_off), Toast.LENGTH_SHORT).show()
        }

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
            Toast.makeText(this, if (checked) getString(R.string.settings_amber_on) else getString(R.string.settings_amber_off), Toast.LENGTH_SHORT).show()
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

        // listener: language selection (simple; currently only kotlin)
        rgLang?.setOnCheckedChangeListener { _, checkedId ->
            val langValue = when (checkedId) {
                R.id.rbLangKotlin -> "kotlin"
                else -> "kotlin"
            }
            sp.edit().putString(PREF_SYNTAX_LANGUAGE, langValue).apply()
            Toast.makeText(this, getString(R.string.settings_language_changed, langValue), Toast.LENGTH_SHORT).show()
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
