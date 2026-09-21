package ir.inod.smsguard

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import ir.inod.smsguard.databinding.ActivityRulesBinding
import ir.inod.smsguard.databinding.DialogRuleBinding

class RulesActivity : BaseActivity() {

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

        AlertDialog.Builder(this)
            .setTitle(R.string.add_rule)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                val pattern = dialogBinding.editPattern.text?.toString()?.trim().orEmpty()
                if (pattern.isEmpty()) return@setPositiveButton
                val index = dialogBinding.spinnerTarget.selectedItemPosition
                    .coerceIn(0, targets.size - 1)
                store.add(pattern, targets[index], dialogBinding.checkRegex.isChecked)
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
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
