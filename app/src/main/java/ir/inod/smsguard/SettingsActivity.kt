package ir.inod.smsguard

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
        binding.buttonCategories.setOnClickListener { manageCategories() }
    }

    // ------------------------------------------------------- category manager

    private fun manageCategories() {
        val store = CategoryStore(this)
        val custom = store.custom()
        val labels = (
            custom.map { getString(R.string.delete) + " · " + it.customName } +
                listOf(getString(R.string.new_category))
            ).toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.manage_categories)
            .setItems(labels) { _, which ->
                if (which < custom.size) {
                    store.delete(custom[which].id)
                    Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
                } else {
                    addCategory()
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun addCategory() {
        val input = EditText(this).apply { hint = getString(R.string.category_name_hint) }
        val palette = Categories.PALETTE
        val listAdapter = object : BaseAdapter() {
            override fun getCount(): Int = palette.size
            override fun getItem(position: Int): Any = palette[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = (convertView as? TextView) ?: TextView(this@SettingsActivity).apply {
                    setPadding(56, 36, 56, 36)
                    textSize = 15f
                }
                tv.text = palette[position]
                tv.setBackgroundColor(Color.parseColor(palette[position]))
                tv.setTextColor(Color.WHITE)
                return tv
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_category)
            .setView(input)
            .setPositiveButton(R.string.add) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) return@setPositiveButton
                // Name first, then colour: two short steps beat one cramped form.
                AlertDialog.Builder(this)
                    .setTitle(R.string.pick_color)
                    .setAdapter(listAdapter) { _, which ->
                        CategoryStore(this).add(name, palette[which])
                        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                    }
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
