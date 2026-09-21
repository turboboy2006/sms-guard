package ir.inod.smsguard

import android.content.Intent
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
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import ir.inod.smsguard.databinding.ActivitySettingsBinding

/**
 * Settings.
 *
 * The screen is grouped so each card answers one question: how messages are
 * judged, what the app looks like, what language it speaks, and what can be
 * managed. Everything that is purely cosmetic lives on its own screen
 * ([AppearanceActivity]) because those controls need a live preview to be
 * meaningful.
 *
 * The AI connector is entirely opt-in: with the switch off the app never sends
 * a single byte off the device, and every feature still works because
 * categorisation is computed locally.
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { SettingsStore(this) }
    private val theme by lazy { ThemePrefs(this) }

    private val langs = listOf("", "fa", "en")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // --- offline detection ---
        binding.sliderThreshold.value = settings.threshold.toFloat()
        binding.textThreshold.text = settings.threshold.toString()
        binding.sliderThreshold.addOnChangeListener { _, value, fromUser ->
            binding.textThreshold.text = value.toInt().toString()
            if (fromUser) settings.threshold = value.toInt()
        }

        // --- AI section ---
        binding.switchAi.isChecked = settings.aiEnabled
        binding.editBase.setText(settings.aiBaseUrl)
        binding.editKey.setText(settings.aiApiKey)
        binding.editModel.setText(settings.aiModel)
        binding.editTimeout.setText(settings.aiTimeoutMs.toString())

        binding.switchAi.setOnCheckedChangeListener { _, checked ->
            setAiFieldsEnabled(checked)
        }
        setAiFieldsEnabled(settings.aiEnabled)

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

        // --- appearance and cache ---
        binding.rowAppearance.setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
        binding.rowCache.setOnClickListener { confirmClearCache() }

        binding.buttonSave.setOnClickListener { save() }
        binding.buttonTest.setOnClickListener { testConnection() }
        binding.buttonCategories.setOnClickListener { manageCategories() }
        binding.buttonBrands.setOnClickListener {
            startActivity(Intent(this, ManagerActivity::class.java))
        }
        binding.buttonRules.setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Appearance is edited on another screen; the summary has to follow.
        binding.textAppearanceSummary.text = getString(
            R.string.appearance_summary,
            getString(fontLabel(theme.listFontScale)),
            getString(rowStyleLabel(theme.rowStyle))
        )
        binding.textCacheSummary.text = getString(
            R.string.cache_summary,
            ThreadCache.size(this)
        )
    }

    private fun fontLabel(scale: Float): Int = when {
        scale <= 0.95f -> R.string.font_small
        scale <= 1.05f -> R.string.font_normal
        scale <= 1.2f -> R.string.font_large
        scale <= 1.35f -> R.string.font_xlarge
        else -> R.string.font_huge
    }

    private fun rowStyleLabel(style: String): Int = when (style) {
        RowStyle.CARD -> R.string.row_style_card
        RowStyle.FLAT -> R.string.row_style_flat
        RowStyle.ACCENT -> R.string.row_style_accent
        RowStyle.BUBBLE -> R.string.row_style_bubble
        RowStyle.COMPACT -> R.string.row_style_compact
        RowStyle.SOFT -> R.string.row_style_soft
        RowStyle.OUTLINE -> R.string.row_style_outline
        RowStyle.STRIPED -> R.string.row_style_striped
        RowStyle.PILL -> R.string.row_style_pill
        else -> R.string.row_style_classic
    }

    // ------------------------------------------------------- cache handling

    private fun confirmClearCache() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.inbox_cache)
            .setMessage(R.string.confirm_clear_cache)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm) { _, _ ->
                ThreadCache.clear(this)
                binding.textCacheSummary.text = getString(R.string.cache_summary, 0)
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
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
        binding.textAiHint.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun save() {
        settings.aiEnabled = binding.switchAi.isChecked
        settings.aiBaseUrl = binding.editBase.text?.toString().orEmpty()
        settings.aiApiKey = binding.editKey.text?.toString().orEmpty()
        settings.aiModel = binding.editModel.text?.toString().orEmpty()
        settings.aiTimeoutMs = binding.editTimeout.text?.toString()?.toIntOrNull()
            ?.coerceIn(1000, 60000) ?: 8000
        settings.threshold = binding.textThreshold.text?.toString()?.toIntOrNull()
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
