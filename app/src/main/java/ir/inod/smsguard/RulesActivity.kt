package ir.inod.smsguard

import android.os.Bundle
import android.view.MenuItem
import android.view.Menu
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import ir.inod.smsguard.databinding.ActivityRulesBinding
import ir.inod.smsguard.databinding.DialogRuleBinding

class RulesActivity : BaseActivity() {

    private companion object { const val MENU_DELETE_SELECTED = 3101; const val MENU_SAMPLES = 3102 }

    private lateinit var binding: ActivityRulesBinding
    private lateinit var adapter: RuleAdapter

    private val store by lazy { RuleStore(this) }
    private val blockedStore by lazy { BlockedStore(this) }

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

        AlertDialog.Builder(this)
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
        val message = if (blocked.isEmpty()) {
            getString(R.string.blocked_empty)
        } else {
            blocked.joinToString("\n\n") {
                "${Dates.full(this, it.date)}\n${it.address}\n${it.body}\n[${it.rulePattern}]"
            }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.blocked_log) + " (${blocked.size})")
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.clear) { _, _ ->
                blockedStore.clear()
                Toast.makeText(this, R.string.cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_SAMPLES) {
            val samples = listOf(
                Triple("برنده شدید،جایزه", RuleJoin.ANY, RuleAction.SPAM),
                Triple("وام فوری،بدون ضامن", RuleJoin.ALL, RuleAction.PROMOTION),
                Triple("تخفیف ویژه،فروش فوق‌العاده", RuleJoin.ANY, RuleAction.PROMOTION),
                Triple("قرعه‌کشی،دریافت جایزه", RuleJoin.ALL, RuleAction.SPAM)
            )
            AlertDialog.Builder(this).setTitle(R.string.sample_rules)
                .setItems(samples.map { it.first.replace("،", " + ") }.toTypedArray()) { _, index ->
                    val sample = samples[index]
                    store.addSimple(sample.first.replace('،', ','), "", sample.second, RuleTarget.BODY, sample.third)
                    reload()
                }.setNegativeButton(R.string.cancel, null).show()
            return true
        }
        if (item.itemId == MENU_DELETE_SELECTED) {
            adapter.selectedRules().forEach { store.delete(it.id) }
            adapter.clearSelection()
            reload()
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
}
