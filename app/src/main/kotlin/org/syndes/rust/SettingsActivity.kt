package org.syndes.rust

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.WindowManager
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class SettingsActivity : AppCompatActivity() {

    private val prefsName = "editor_prefs"
    private val PREF_PREVENT_SCREENSHOT = "prevent_screenshot"
    private val PREF_UNDO_ENABLED = "undo_enabled"
    private val PREF_THEME_DARK = "theme_dark"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar_settings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        val sp = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        val swPrevent = findViewById<Switch>(R.id.swPreventScreenshots)
        val swUndo = findViewById<Switch>(R.id.swUndoEnabled)
        val swDark = findViewById<Switch>(R.id.swDarkTheme)

        // initialize states
        swPrevent.isChecked = sp.getBoolean(PREF_PREVENT_SCREENSHOT, false)
        swUndo.isChecked = sp.getBoolean(PREF_UNDO_ENABLED, true)
        swDark.isChecked = sp.getBoolean(PREF_THEME_DARK, true)

        // listener: Prevent screenshots (applies to this window immediately)
        swPrevent.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
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
        swUndo.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            sp.edit().putBoolean(PREF_UNDO_ENABLED, checked).apply()
            Toast.makeText(this, if (checked) getString(R.string.settings_undo_on) else getString(R.string.settings_undo_off), Toast.LENGTH_SHORT).show()
        }

        // listener: Theme toggle (applies immediately across app)
        swDark.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            sp.edit().putBoolean(PREF_THEME_DARK, checked).apply()
            AppCompatDelegate.setDefaultNightMode(if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            // recreate to apply to this activity; other activities will pick up on restart or when recreated
            recreate()
            Toast.makeText(this, getString(R.string.settings_theme_changed), Toast.LENGTH_SHORT).show()
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
