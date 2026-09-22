package ir.inod.smsguard

import android.os.Bundle
import android.view.MenuItem
import android.view.Menu
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import ir.inod.smsguard.databinding.ActivityRulesBinding
import ir.inod.smsguard.databinding.DialogRuleBinding
import java.util.concurrent.Executors

class RulesActivity : BaseActivity() {

    private companion object { const val MENU_DELETE_SELECTED = 3101; const val MENU_SAMPLES = 3102 }

    private lateinit var binding: ActivityRulesBinding
    private lateinit var adapter: RuleAdapter

    private val store by lazy { RuleStore(this) }
    private val blockedStore by lazy { BlockedStore(this) }
    private val previewWorker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = RuleAdapter(
            onToggle = { rule, enabled ->
                store.setEnabled(rule.id, enabled)
                reload()
            },
            onDelete = { rule ->
                store.delete(rule.id)
                reload()
                Snackbar.make(binding.root, R.string.applied, Snackbar.LENGTH_LONG)
                    .setAction(R.string.undo) { store.restore(rule); reload() }.show()
            },
            onSelectionChanged = { count ->
                supportActionBar?.title = if (count > 0) Dates.count(this, count) else getString(R.string.rules)
                invalidateOptionsMenu()
            }
        )
        binding.recyclerRules.layoutManager = LinearLayoutManager(this)
        binding.recyclerRules.adapter = adapter

        binding.buttonAddRule.setOnClickListener { showAddDialog() }
        binding.buttonBlockedLog.setOnClickListener { showBlockedLog() }

        reload()
    }

    private fun reload() {
        val rules = store.all()
        adapter.submit(rules)
        binding.textEmpty.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddDialog() {
        val dialogBinding = DialogRuleBinding.inflate(layoutInflater)

        val targets = listOf(RuleTarget.BOTH, RuleTarget.SENDER, RuleTarget.BODY)
        val labels = listOf(
            getString(R.string.target_both),
            getString(R.string.target_sender),
            getString(R.string.target_body)
        )
        dialogBinding.spinnerTarget.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        val joins = listOf(RuleJoin.ANY, RuleJoin.ALL)
        dialogBinding.spinnerJoin.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.rule_join_any), getString(R.string.rule_join_all))
        )
        val actions = listOf(RuleAction.BLOCK, RuleAction.SPAM, RuleAction.PROMOTION)
        dialogBinding.spinnerAction.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.rule_action_block), getString(R.string.rule_action_spam),
                getString(R.string.rule_action_promotion)
            )
        )

        // Plain-language rules are the default. Regex remains available as an
        // advanced escape hatch, but the live preview makes ordinary rules
        // understandable before they can block a real message.
        val preview = TextView(this).apply {
            textSize = 13f
            setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            setTextColor(getColor(R.color.text_secondary))
            text = "پیش‌نمایش: عبارت‌ها را وارد کنید"
        }
        (dialogBinding.root as android.view.ViewGroup).addView(preview)
        var previewGeneration = 0
        fun refreshPreview() {
            val included = dialogBinding.editPattern.text?.toString()?.trim().orEmpty()
            if (included.isBlank()) { preview.text = "پیش‌نمایش: عبارت‌ها را وارد کنید"; return }
            val target = targets[dialogBinding.spinnerTarget.selectedItemPosition.coerceIn(0, targets.lastIndex)]
            val join = joins[dialogBinding.spinnerJoin.selectedItemPosition.coerceIn(0, joins.lastIndex)]
            val action = actions[dialogBinding.spinnerAction.selectedItemPosition.coerceIn(0, actions.lastIndex)]
            val rule = Rule(0, included, target, dialogBinding.checkRegex.isChecked, true,
                dialogBinding.editExcluded.text?.toString().orEmpty(), join, action)
            val token = ++previewGeneration
            preview.text = "در حال بررسی پیام‌های موجود…"
            previewWorker.execute {
                val count = SmsRepository(this).countMatches(rule)
                runOnUiThread {
                    if (!isFinishing && token == previewGeneration) {
                        preview.text = "پیش‌نمایش: $count پیام موجود با این قانون تطبیق دارد"
                    }
                }
            }
        }
        dialogBinding.editPattern.doAfterTextChanged { refreshPreview() }
        dialogBinding.editExcluded.doAfterTextChanged { refreshPreview() }
        dialogBinding.checkRegex.setOnCheckedChangeListener { _, _ -> refreshPreview() }
        listOf(dialogBinding.spinnerTarget, dialogBinding.spinnerJoin, dialogBinding.spinnerAction).forEach { spinner ->
            spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) = refreshPreview()
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_rule)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val pattern = dialogBinding.editPattern.text?.toString()?.trim().orEmpty()
                if (pattern.isEmpty()) return@setPositiveButton
                val index = dialogBinding.spinnerTarget.selectedItemPosition
                    .coerceIn(0, targets.size - 1)
                if (dialogBinding.checkRegex.isChecked) {
                    store.add(pattern, targets[index], true)
                } else {
                    store.addSimple(
                        included = pattern,
                        excluded = dialogBinding.editExcluded.text?.toString().orEmpty(),
                        join = joins[dialogBinding.spinnerJoin.selectedItemPosition.coerceIn(0, joins.lastIndex)],
                        target = targets[index],
                        action = actions[dialogBinding.spinnerAction.selectedItemPosition.coerceIn(0, actions.lastIndex)]
                    )
                }
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBlockedLog() {
        val blocked = blockedStore.all()
        val density = resources.displayMetrics.density
        val list = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (16 * density).toInt(); setPadding(p, p, p, p)
        }
        if (blocked.isEmpty()) list.addView(android.widget.TextView(this).apply {
            text = getString(R.string.blocked_empty)
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_tab_spam, 0, 0)
            setPadding(24, 48, 24, 48)
        })
        blocked.forEach { entry ->
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 16f * density
                strokeWidth = 1
                strokeColor = androidx.core.content.ContextCompat.getColor(this@RulesActivity, R.color.border_soft)
            }
            val body = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val p = (14 * density).toInt(); setPadding(p, p, p, p)
            }
            body.addView(android.widget.TextView(this).apply {
                text = entry.address
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_tab_spam, 0, 0, 0)
                compoundDrawablePadding = (8 * density).toInt()
            })
            body.addView(android.widget.TextView(this).apply {
                text = Dates.full(this@RulesActivity, entry.date)
                textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@RulesActivity, R.color.text_secondary))
            })
            body.addView(android.widget.TextView(this).apply {
                text = entry.body
                textSize = 14f
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            })
            body.addView(android.widget.TextView(this).apply {
                text = entry.rulePattern
                textSize = 12f
                setTextColor(androidx.core.content.ContextCompat.getColor(this@RulesActivity, R.color.danger))
            })
            card.addView(body)
            list.addView(card, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (8 * density).toInt() })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.blocked_log) + " (${blocked.size})")
            .setView(android.widget.ScrollView(this).apply { addView(list) })
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.clear) { _, _ ->
                blockedStore.clear()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SAMPLES) {
            data class Example(val words: String, val excluded: String,
                val join: RuleJoin, val action: RuleAction)
            val samples = listOf(
                Example("برنده شدید،دریافت جایزه", "رمز پویا،کد تایید", RuleJoin.ALL, RuleAction.SPAM),
                Example("حراج،تخفیف ویژه،فروش فوق‌العاده", "رسید خرید،کد تایید", RuleJoin.ANY, RuleAction.PROMOTION)
            )
            ChoiceSheet.show(this, getString(R.string.sample_rules), samples.map { sample ->
                ChoiceSheet.Option(sample.words.replace("،", if (sample.join == RuleJoin.ALL) " + " else " / "),
                    if (sample.action == RuleAction.SPAM) R.drawable.ic_tab_spam else R.drawable.ic_cat_shop,
                    detail = getString(if (sample.join == RuleJoin.ALL) R.string.rule_join_all else R.string.rule_join_any))
            }) { index ->
                    val sample = samples[index]
                    store.addSimple(sample.words, sample.excluded, sample.join, RuleTarget.BODY, sample.action)
                    reload()
                }
            return true
        }
        if (item.itemId == MENU_DELETE_SELECTED) {
            val removed = adapter.selectedRules()
            removed.forEach { store.delete(it.id) }
            adapter.clearSelection()
            reload()
            Snackbar.make(binding.root, R.string.applied, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) { removed.forEach(store::restore); reload() }.show()
            return true
        }
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SAMPLES, 1, R.string.sample_rules)
        menu.add(0, MENU_DELETE_SELECTED, 0, R.string.delete)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_DELETE_SELECTED)?.isVisible =
            ::adapter.isInitialized && adapter.selectionCount > 0
        menu.findItem(MENU_SAMPLES)?.isVisible = ::adapter.isInitialized && adapter.selectionCount == 0
        return super.onPrepareOptionsMenu(menu)
    }

    @Deprecated("Handled for selection mode")
    override fun onBackPressed() {
        if (::adapter.isInitialized && adapter.selectionCount > 0) adapter.clearSelection()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        previewWorker.shutdownNow()
        super.onDestroy()
    }
}
