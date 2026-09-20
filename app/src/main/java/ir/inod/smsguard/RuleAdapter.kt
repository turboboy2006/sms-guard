package ir.inod.smsguard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ir.inod.smsguard.databinding.ItemRuleBinding

class RuleAdapter(
    private val onToggle: (Rule, Boolean) -> Unit,
    private val onDelete: (Rule) -> Unit
) : RecyclerView.Adapter<RuleAdapter.VH>() {

    private val items = mutableListOf<Rule>()

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

        holder.binding.textPattern.text = item.pattern

        val targetLabel = when (item.target) {
            RuleTarget.SENDER -> context.getString(R.string.target_sender)
            RuleTarget.BODY -> context.getString(R.string.target_body)
            RuleTarget.BOTH -> context.getString(R.string.target_both)
        }
        holder.binding.textRuleMeta.text =
            if (item.isRegex) "$targetLabel · Regex" else targetLabel

        // Detach before setting state so binding does not fire the callback.
        holder.binding.switchEnabled.setOnCheckedChangeListener(null)
        holder.binding.switchEnabled.isChecked = item.enabled
        holder.binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            onToggle(item, checked)
        }

        holder.binding.buttonDelete.setOnClickListener { onDelete(item) }
    }
}
