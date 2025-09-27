package org.syndes.rust

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.syndes.rust.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Editor -> запускает EditorActivity
        binding.btnEditor.setOnClickListener {
            val intent = Intent(this, EditorActivity::class.java)
            startActivity(intent)
        }

        // Заглушки для других кнопок
        val placeholderToast = { msg: String ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.btnPlaceholder1.setOnClickListener {
            placeholderToast("Placeholder 1: Not implemented yet")
        }

        binding.btnPlaceholder2.setOnClickListener {
            placeholderToast("Placeholder 2: Not implemented yet")
        }
    }
}
