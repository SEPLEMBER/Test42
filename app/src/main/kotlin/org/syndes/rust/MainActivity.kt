package org.syndes.rust

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.syndes.rust.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Чтобы не срабатывать на первоначальной установке адаптера
    private var spinner1Initialized = false
    private var spinner2Initialized = false
    private var spinner3Initialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // --- Хардкоднутые строки (в коде, как просил)
        val label1Text = "Выбор 1"
        val label2Text = "Выбор 2"
        val label3Text = "Выбор 3"

        val options1 = listOf("Опция A1", "Опция A2", "Опция A3")
        val options2 = listOf("Опция B1", "Опция B2", "Опция B3", "Опция B4")
        val options3 = listOf("Опция C1", "Опция C2")

        // --- Хардкоднутые цвета (в коде)
        val backgroundColor = Color.parseColor("#FAFBFC")   // общий фон
        val cardColor = Color.parseColor("#FFFFFF")         // фон полей
        val labelColor = Color.parseColor("#111827")        // текст меток (тёмный)
        val accentColor = Color.parseColor("#2563EB")       // для акцентов (необязательно)

        // Применяем цвета
        binding.root.setBackgroundColor(backgroundColor)
        binding.label1.setTextColor(labelColor)
        binding.label2.setTextColor(labelColor)
        binding.label3.setTextColor(labelColor)

        // Устанавливаем текст меток программно (строки в коде)
        binding.label1.text = label1Text
        binding.label2.text = label2Text
        binding.label3.text = label3Text

        // Настраиваем спиннеры (адаптеры)
        setupSpinner(binding.spinner1, options1) { pos ->
            Toast.makeText(this, "$label1Text: ${options1[pos]}", Toast.LENGTH_SHORT).show()
        }

        setupSpinner(binding.spinner2, options2) { pos ->
            Toast.makeText(this, "$label2Text: ${options2[pos]}", Toast.LENGTH_SHORT).show()
        }

        setupSpinner(binding.spinner3, options3) { pos ->
            Toast.makeText(this, "$label3Text: ${options3[pos]}", Toast.LENGTH_SHORT).show()
        }

        // Небольшая стилизация: фон для контейнера спиннера (делаем вручную)
        binding.spinner1.setBackgroundColor(cardColor)
        binding.spinner2.setBackgroundColor(cardColor)
        binding.spinner3.setBackgroundColor(cardColor)

        // (опция) подсветить выбранный элемент цветом accent — делаем через prompt (не обяз.)
        binding.spinner1.prompt = "Выберите опцию"
        binding.spinner2.prompt = "Выберите опцию"
        binding.spinner3.prompt = "Выберите опцию"
    }

    /**
     * Вспомогательная функция: назначает адаптер и слушатель для Spinner.
     * callback возвращает позицию выбранного элемента.
     */
    private fun setupSpinner(spinner: androidx.appcompat.widget.AppCompatSpinner,
                             items: List<String>,
                             callback: (position: Int) -> Unit) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            // Определим, к какому спиннеру относится этот слушатель, чтобы избежать
            // немедленного срабатывания при установке адаптера.
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                // На первой установке адаптера многие Spinner'ы вызывают onItemSelected —
                // поэтому обработаем это в Activity с флагами (см. поля выше).
                when (spinner.id) {
                    binding.spinner1.id -> {
                        if (!spinner1Initialized) {
                            spinner1Initialized = true
                            return
                        }
                    }
                    binding.spinner2.id -> {
                        if (!spinner2Initialized) {
                            spinner2Initialized = true
                            return
                        }
                    }
                    binding.spinner3.id -> {
                        if (!spinner3Initialized) {
                            spinner3Initialized = true
                            return
                        }
                    }
                }
                callback(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {
                // пусто
            }
        }
    }
}
