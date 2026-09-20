package ir.inod.smsguard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ir.inod.smsguard.databinding.ItemThreadBinding

class ThreadAdapter(
    private val onClick: (ThreadSummary) -> Unit
) : RecyclerView.Adapter<ThreadAdapter.VH>() {

    private val items = mutableListOf<ThreadSummary>()

    fun submit(list: List<ThreadSummary>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemThreadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemThreadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        holder.binding.textAddress.text = ContactNames.displayName(context, item.address)
        holder.binding.textSnippet.text = item.snippet
        holder.binding.textDate.text = Dates.listLabel(item.date)

        if (item.unreadCount > 0) {
            holder.binding.textUnread.visibility = View.VISIBLE
            holder.binding.textUnread.text = item.unreadCount.toString()
        } else {
            holder.binding.textUnread.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(item) }
    }
}
