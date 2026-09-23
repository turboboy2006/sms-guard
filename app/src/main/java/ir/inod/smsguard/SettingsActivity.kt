package ir.inod.smsguard

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import com.google.android.material.tabs.TabLayout
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
    private var simIds: List<Int> = listOf(-1)
    private val retentionValues = listOf(0, 7, 30, 90)

    private val exportBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(SettingsBackup.export(this))
            }
        }.onSuccess { Toast.makeText(this, R.string.backup_saved, Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show() }
    }

    private val importBackup = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("empty backup")
            SettingsBackup.import(this, raw)
        }.onSuccess {
            Toast.makeText(this, R.string.backup_restored, Toast.LENGTH_SHORT).show()
            recreate()
        }.onFailure { Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_LONG).show() }
    }

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

        // --- quiet hours ---
        setUpQuietHours()

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
        binding.spinnerTrashRetention.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.trash_keep_forever), getString(R.string.trash_days, 7), getString(R.string.trash_days, 30), getString(R.string.trash_days, 90)))
        binding.spinnerTrashRetention.setSelection(retentionValues.indexOf(settings.trashRetentionDays).coerceAtLeast(0))
        setUpSimPicker()
        setUpSwipeActions()
        decorateManagementRows()

        // --- appearance and cache ---
        binding.rowAppearance.setOnClickListener {
            startActivity(Intent(this, AppearanceActivity::class.java))
        }
        binding.rowCache.setOnClickListener { confirmClearCache() }

        binding.buttonSave.setOnClickListener { save() }
        binding.buttonTest.setOnClickListener { testConnection() }
        binding.buttonAiScan.setOnClickListener { confirmAiScan() }
        binding.buttonCategories.setOnClickListener {
            startActivity(Intent(this, CategoriesActivity::class.java))
        }
        binding.buttonBrands.setOnClickListener {
            startActivity(Intent(this, ManagerActivity::class.java))
        }
        binding.buttonRules.setOnClickListener {
            startActivity(Intent(this, RulesActivity::class.java))
        }
        binding.buttonScheduled.setOnClickListener {
            startActivity(Intent(this, ScheduledMessagesActivity::class.java))
        }
        binding.buttonSavedMessages.setOnClickListener {
            startActivity(Intent(this, SavedMessagesActivity::class.java))
        }
        binding.buttonExportBackup.setOnClickListener {
            exportBackup.launch("sms-guard-backup.json")
        }
        binding.buttonImportBackup.setOnClickListener {
            importBackup.launch(arrayOf("application/json", "text/plain"))
        }
        moveAdvancedSettingsToEnd()
        setUpTabs()
    }

    private fun setUpTabs() {
        val column = binding.buttonSave.parent as ViewGroup
        val offline = ((binding.sliderThreshold.parent as View).parent as View).parent as View
        val appearance = ((binding.rowAppearance.parent as View).parent as View)
        val appearanceContent = binding.rowAppearance.parent as LinearLayout
        val cache = binding.rowCache
        appearanceContent.removeView(cache)
        // The old combined card let the cache action leak into Appearance.
        // Keep only the appearance row in that card.
        if (appearanceContent.childCount > 1) appearanceContent.removeViewAt(1)
        val general = ((binding.spinnerLanguage.parent as View).parent as View)
        val management = ((binding.buttonCategories.parent as View).parent as View)
        val managementHeader = column.getChildAt(column.indexOfChild(management) - 1) as TextView
        managementHeader.setText(R.string.manage_categories)
        fun dedicatedSection(title: Int, buttons: List<View>): List<View> {
            val header = TextView(this).apply {
                setText(title)
                setTextAppearance(R.style.TextAppearance_SmsGuard_Group)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = resources.getDimensionPixelSize(R.dimen.space_16)
                }
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padding = resources.getDimensionPixelSize(R.dimen.space_8)
                setPadding(padding, padding, padding, padding)
            }
            buttons.forEach { button ->
                (button.parent as ViewGroup).removeView(button)
                content.addView(button)
            }
            val card = MaterialCardView(this).apply {
                radius = resources.getDimension(R.dimen.radius_lg)
                cardElevation = 0f
                strokeWidth = resources.getDimensionPixelSize(R.dimen.space_2)
                strokeColor = androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.divider)
                addView(content)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    topMargin = resources.getDimensionPixelSize(R.dimen.space_8)
                }
            }
            val insertion = column.indexOfChild(binding.buttonSave)
            column.addView(header, insertion)
            column.addView(card, insertion + 1)
            return listOf(header, card)
        }
        val dataSection = dedicatedSection(R.string.settings_tab_data, listOf(
            cache, binding.buttonBrands, binding.buttonRules, binding.buttonScheduled, binding.buttonSavedMessages))
        val backupSection = dedicatedSection(R.string.settings_tab_backup, listOf(
            binding.buttonExportBackup, binding.buttonImportBackup))
        val quiet = (binding.switchQuiet.parent as View).parent as View
        val ai = (binding.switchAi.parent as View).parent as View
        fun section(card: View): List<View> {
            val index = column.indexOfChild(card)
            return listOfNotNull(column.getChildAt(index - 1), card)
        }
        val offlineHeader = column.getChildAt(column.indexOfChild(offline) - 1)
        // These are real section tabs, not a second settings menu: each tab
        // exposes a focused group of cards and preserves the same controls.
        val groups = listOf(
            section(general) + listOf(quiet),
            section(appearance),
            listOf(managementHeader, management),
            listOf(offlineHeader, ai, offline),
            dataSection,
            backupSection
        )
        val tabs = TabLayout(this).apply {
            tabMode = TabLayout.MODE_SCROLLABLE
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.card_bg))
        }
        val names = listOf(R.string.settings_tab_general,
            R.string.group_appearance, R.string.settings_tab_categories, R.string.ai_section,
            R.string.settings_tab_data, R.string.settings_tab_backup)
        val icons = listOf(R.drawable.ic_settings,
            R.drawable.ic_cat_shop, R.drawable.ic_tab_all, R.drawable.ic_cat_security,
            R.drawable.ic_archive, R.drawable.ic_archive)
        names.indices.forEach { index ->
            tabs.addTab(tabs.newTab().setText(names[index]).setIcon(icons[index]))
        }
        (binding.root as ViewGroup).addView(tabs, 1)
        fun select(index: Int) {
            groups.flatten().distinct().forEach { it.visibility = View.GONE }
            groups[index].forEach { it.visibility = View.VISIBLE }
            // Each tab owns its own card; no controls are reused across tabs.
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = select(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        select(0)
    }

    private fun moveAdvancedSettingsToEnd() {
        val column = binding.buttonSave.parent as ViewGroup
        val aiCard = (binding.switchAi.parent as View).parent as View
        val quietCard = (binding.switchQuiet.parent as View).parent as View
        column.removeView(aiCard)
        column.removeView(quietCard)
        column.addView(quietCard, column.childCount - 1)
        column.addView(aiCard, column.childCount - 1)
        val aiContent = binding.switchAi.parent as ViewGroup
        val details = listOf(binding.editBase.parent, binding.editKey.parent,
            binding.editModel.parent, binding.editTimeout.parent,
            binding.buttonTest, binding.buttonAiScan, binding.textTestResult).map { it as View }
        details.forEach { it.visibility = View.GONE }
        val heading = aiContent.getChildAt(0)
        heading.setOnClickListener {
            val show = details.first().visibility != View.VISIBLE
            details.forEach { it.visibility = if (show) View.VISIBLE else View.GONE }
        }
        heading.isClickable = true
    }

    private fun decorateManagementRows() {
        val rows = listOf(
            binding.buttonCategories to R.drawable.ic_tab_service,
            binding.buttonBrands to R.drawable.ic_person,
            binding.buttonRules to R.drawable.ic_cat_security,
            binding.buttonScheduled to R.drawable.ic_send,
            binding.buttonSavedMessages to R.drawable.ic_cat_receipt,
            binding.buttonExportBackup to R.drawable.ic_chevron,
            binding.buttonImportBackup to R.drawable.ic_chevron
        )
        rows.forEachIndexed { index, (button, icon) ->
            button.setIconResource(icon)
            button.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            button.iconPadding = (12 * resources.displayMetrics.density).toInt()
            button.iconTint = android.content.res.ColorStateList.valueOf(
                if (index < 5) theme.accentColor() else androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)
            )
            button.cornerRadius = (14 * resources.displayMetrics.density).toInt()
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

    // ------------------------------------------------------- quiet hours

    /**
     * Quiet hours are off until asked for, so the hour pickers only appear once
     * the switch is on — an explanation of what will be silenced, and then the
     * window, in that order.
     */
    private fun setUpQuietHours() {
        val labels = (0..23).map { QuietHours.hourLabel(this, it) }
        binding.spinnerQuietFrom.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        binding.spinnerQuietTo.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        binding.spinnerQuietFrom.setSelection(settings.quietFrom)
        binding.spinnerQuietTo.setSelection(settings.quietTo)

        binding.switchQuiet.isChecked = settings.quietHoursEnabled
        binding.switchQuiet.setOnCheckedChangeListener { _, checked ->
            settings.quietHoursEnabled = checked
            applyQuietVisibility()
        }

        binding.spinnerQuietFrom.onItemSelectedListener = onHourChosen { hour ->
            settings.quietFrom = hour
            applyQuietVisibility()
        }
        binding.spinnerQuietTo.onItemSelectedListener = onHourChosen { hour ->
            settings.quietTo = hour
            applyQuietVisibility()
        }
        applyQuietVisibility()
    }

    private fun applyQuietVisibility() {
        binding.groupQuietHours.visibility =
            if (settings.quietHoursEnabled) View.VISIBLE else View.GONE
        binding.textQuietWindow.text = getString(
            R.string.quiet_window,
            QuietHours.hourLabel(this, settings.quietFrom),
            QuietHours.hourLabel(this, settings.quietTo)
        )
    }

    /** Plain spinner listener; the pickers are applied as they change. */
    private fun onHourChosen(chosen: (Int) -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = chosen(position)

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

    // ------------------------------------------------------- cache handling

    private fun confirmClearCache() {
        ConfirmSheet.show(this, getString(R.string.inbox_cache),
            getString(R.string.confirm_clear_cache), R.drawable.ic_tab_trash) {
                ThreadCache.clear(this)
                binding.textCacheSummary.text = getString(R.string.cache_summary, 0)
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
    }

    private fun setAiFieldsEnabled(enabled: Boolean) {
        binding.editBase.isEnabled = enabled
        binding.editKey.isEnabled = enabled
        binding.editModel.isEnabled = enabled
        binding.editTimeout.isEnabled = enabled
        binding.buttonTest.isEnabled = enabled
        binding.buttonAiScan.isEnabled = enabled
        binding.textAiHint.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun save() {
        saveAiFields()
        settings.threshold = binding.textThreshold.text?.toString()?.toIntOrNull()
            ?.coerceIn(10, 95) ?: 40
        settings.language = langs[binding.spinnerLanguage.selectedItemPosition.coerceIn(0, 2)]
        settings.defaultSimId = simIds.getOrElse(binding.spinnerDefaultSim.selectedItemPosition) { -1 }
        settings.trashRetentionDays = retentionValues.getOrElse(binding.spinnerTrashRetention.selectedItemPosition) { 0 }
        settings.swipeEnabled = binding.switchSwipe.isChecked
        settings.swipeRightAction = SwipeAction.IDS.getOrElse(binding.spinnerSwipeRight.selectedItemPosition) { SwipeAction.READ }
        settings.swipeLeftAction = SwipeAction.IDS.getOrElse(binding.spinnerSwipeLeft.selectedItemPosition) { SwipeAction.SPAM }

        // Applies immediately and survives restart.
        val tag = settings.language
        AppCompatDelegate.setApplicationLocales(
            if (tag.isBlank()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(tag)
        )

        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun saveAiFields() {
        settings.aiEnabled = binding.switchAi.isChecked
        settings.aiBaseUrl = binding.editBase.text?.toString().orEmpty()
        settings.aiApiKey = binding.editKey.text?.toString().orEmpty()
        settings.aiModel = binding.editModel.text?.toString().orEmpty()
        settings.aiTimeoutMs = binding.editTimeout.text?.toString()?.toIntOrNull()
            ?.coerceIn(1000, 60000) ?: 8000
    }

    private fun setUpSimPicker() {
        val labels = mutableListOf(getString(R.string.sim_system_default))
        val ids = mutableListOf(-1)
        try {
            val manager = getSystemService(android.telephony.SubscriptionManager::class.java)
            manager?.activeSubscriptionInfoList.orEmpty().forEach { info ->
                ids += info.subscriptionId
                labels += getString(
                    R.string.sim_label,
                    info.simSlotIndex + 1,
                    info.carrierName?.toString().orEmpty()
                )
            }
        } catch (_: SecurityException) { }
        simIds = ids
        binding.spinnerDefaultSim.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        binding.spinnerDefaultSim.setSelection(ids.indexOf(settings.defaultSimId).coerceAtLeast(0))
    }

    private fun setUpSwipeActions() {
        val ids = SwipeAction.IDS
        val labels = listOf(R.string.swipe_mark_read, R.string.mark_spam, R.string.move_to_trash, R.string.archive)
            .map { getString(it) }
        binding.spinnerSwipeRight.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerSwipeLeft.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerSwipeRight.setSelection(ids.indexOf(settings.swipeRightAction).coerceAtLeast(0))
        binding.spinnerSwipeLeft.setSelection(ids.indexOf(settings.swipeLeftAction).coerceAtLeast(0))
        binding.switchSwipe.isChecked = settings.swipeEnabled
        binding.switchSwipe.setOnCheckedChangeListener { _, enabled ->
            binding.groupSwipeActions.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        binding.groupSwipeActions.visibility = if (settings.swipeEnabled) View.VISIBLE else View.GONE
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

    private fun confirmAiScan() {
        saveAiFields()
        ConfirmSheet.show(this, getString(R.string.ai_scan_inbox), getString(R.string.ai_scan_explanation),
            R.drawable.ic_cat_security, R.string.start, dangerous = false) {
                binding.buttonAiScan.isEnabled = false
                binding.textTestResult.text = getString(R.string.ai_scan_starting)
                AiInboxScanner.scan(
                    this,
                    onProgress = { done, total, learned -> runOnUiThread {
                        if (!isFinishing) binding.textTestResult.text = getString(R.string.ai_scan_progress, done, total, learned)
                    } },
                    onDone = { learned -> runOnUiThread {
                        if (!isFinishing) {
                            binding.buttonAiScan.isEnabled = binding.switchAi.isChecked
                            binding.textTestResult.text = getString(R.string.ai_scan_done, learned)
                        }
                    } }
                )
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
