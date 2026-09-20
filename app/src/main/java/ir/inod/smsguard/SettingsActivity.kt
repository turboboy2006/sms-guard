package ir.inod.smsguard

import android.os.Bundle
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import ir.inod.smsguard.databinding.ActivitySettingsBinding

/**
 * Settings. The AI connector is entirely opt-in: with the switch off the app
 * never sends a single byte off the device, and every feature still works
 * because categorisation is computed locally.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { SettingsStore(this) }

    private val langs = listOf("", "fa", "en")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // --- AI section ---
        binding.switchAi.isChecked = settings.aiEnabled
        binding.editBase.setText(settings.aiBaseUrl)
        binding.editKey.setText(settings.aiApiKey)
        binding.editModel.setText(settings.aiModel)
        binding.editTimeout.setText(settings.aiTimeoutMs.toString())
        binding.editThreshold.setText(settings.threshold.toString())

        // --- language ---
        val labels = listOf(
            getString(R.string.lang_system),
            getString(R.string.lang_fa),
            getString(R.string.lang_en)
        )
        binding.spinnerLanguage.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        binding.spinnerLanguage.setSelection(langs.indexOf(settings.language).coerceAtLeast(0))

        binding.switchAi.setOnCheckedChangeListener { _, checked ->
            setAiFieldsEnabled(checked)
        }
        setAiFieldsEnabled(settings.aiEnabled)

        binding.buttonSave.setOnClickListener { save() }
        binding.buttonTest.setOnClickListener { testConnection() }
    }

    private fun setAiFieldsEnabled(enabled: Boolean) {
        binding.editBase.isEnabled = enabled
        binding.editKey.isEnabled = enabled
        binding.editModel.isEnabled = enabled
        binding.editTimeout.isEnabled = enabled
        binding.buttonTest.isEnabled = enabled
        binding.textAiHint.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun save() {
        settings.aiEnabled = binding.switchAi.isChecked
        settings.aiBaseUrl = binding.editBase.text?.toString().orEmpty()
        settings.aiApiKey = binding.editKey.text?.toString().orEmpty()
        settings.aiModel = binding.editModel.text?.toString().orEmpty()
        settings.aiTimeoutMs = binding.editTimeout.text?.toString()?.toIntOrNull()
            ?.coerceIn(1000, 60000) ?: 8000
        settings.threshold = binding.editThreshold.text?.toString()?.toIntOrNull()
            ?.coerceIn(10, 95) ?: 40
        settings.language = langs[binding.spinnerLanguage.selectedItemPosition.coerceIn(0, 2)]

        // Applies immediately and survives restart.
        val tag = settings.language
        AppCompatDelegate.setApplicationLocales(
            if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testConnection() {
        binding.textTestResult.text = getString(R.string.testing)
        val snapshot = SettingsStore(this).apply {
            aiBaseUrl = binding.editBase.text?.toString().orEmpty()
            aiApiKey = binding.editKey.text?.toString().orEmpty()
            aiModel = binding.editModel.text?.toString().orEmpty()
            aiTimeoutMs = binding.editTimeout.text?.toString()?.toIntOrNull() ?: 8000
        }
        Thread {
            val result = AiAnalyzer(snapshot).testConnection()
            runOnUiThread { binding.textTestResult.text = result }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
