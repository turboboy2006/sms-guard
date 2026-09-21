package ir.inod.smsguard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ir.inod.smsguard.databinding.ItemRuleBinding

class RuleAdapter(
    private val onToggle: (Rule, Boolean) -> Unit,
    private val onDelete: (Rule) -> Unit,
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<RuleAdapter.VH>() {

    private val items = mutableListOf<Rule>()
    private val selectedIds = linkedSetOf<Long>()
    val selectionCount: Int get() = selectedIds.size

    fun selectedRules(): List<Rule> = items.filter { it.id in selectedIds }

    fun toggleSelection(rule: Rule) {
        if (!selectedIds.add(rule.id)) selectedIds.remove(rule.id)
        val position = items.indexOfFirst { it.id == rule.id }
        if (position >= 0) notifyItemChanged(position)
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun submit(list: List<Rule>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemRuleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRuleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.binding.textPattern.text = if (item.isRegex) item.pattern else buildString {
            append(item.pattern)
            if (item.excluded.isNotBlank()) append("  ·  ").append(context.getString(R.string.rule_except)).append(": ").append(item.excluded)
        }

        val targetLabel = when (item.target) {
            RuleTarget.SENDER -> context.getString(R.string.target_sender)
            RuleTarget.BODY -> context.getString(R.string.target_body)
            RuleTarget.BOTH -> context.getString(R.string.target_both)
        }
        val action = when (item.action) {
            RuleAction.BLOCK -> context.getString(R.string.rule_action_block)
            RuleAction.SPAM -> context.getString(R.string.rule_action_spam)
            RuleAction.PROMOTION -> context.getString(R.string.rule_action_promotion)
        }
        val join = if (item.join == RuleJoin.ALL) context.getString(R.string.rule_join_all) else context.getString(R.string.rule_join_any)
        holder.binding.textRuleMeta.text = if (item.isRegex) "$targetLabel · Regex · $action" else "$targetLabel · $join · $action"

        // Detach before setting state so binding does not fire the callback.
        holder.binding.switchEnabled.setOnCheckedChangeListener(null)
        holder.binding.switchEnabled.isChecked = item.enabled
        holder.binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            onToggle(item, checked)
        }

        holder.binding.buttonDelete.setOnClickListener { onDelete(item) }
        holder.binding.root.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(
                context,
                if (item.id in selectedIds) R.color.selection_bg else android.R.color.transparent
            )
        )
        holder.binding.root.setOnClickListener {
            if (selectionCount > 0) toggleSelection(item)
        }
        holder.binding.root.setOnLongClickListener {
            toggleSelection(item)
            true
        }
    }
}
