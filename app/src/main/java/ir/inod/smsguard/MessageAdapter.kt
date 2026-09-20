package ir.inod.smsguard

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ir.inod.smsguard.databinding.ItemMessageBinding

/**
 * One bubble layout is reused for both directions; gravity and background are
 * switched in code so only a single binding class is needed.
 */
class MessageAdapter(
    private val onLongClick: (SmsMessage) -> Unit
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    private val items = mutableListOf<SmsMessage>()

    fun submit(list: List<SmsMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemMessageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        // item_message.xml has a LinearLayout root; `gravity` is a
        // LinearLayout property, which is why ViewGroup would not compile.
        val row = holder.itemView as LinearLayout
        val bubble = holder.binding.textBubble

        bubble.text = item.body
        holder.binding.textTime.text = Dates.full(context, item.date)

        val params = bubble.layoutParams as ViewGroup.MarginLayoutParams
        if (item.isIncoming) {
            row.gravity = Gravity.START
            bubble.setBackgroundResource(R.drawable.bubble_incoming)
            bubble.setTextColor(ContextCompat.getColor(context, R.color.bubble_in_text))
            params.marginStart = 0
        } else {
            row.gravity = Gravity.END
            bubble.setBackgroundResource(R.drawable.bubble_outgoing)
            bubble.setTextColor(ContextCompat.getColor(context, R.color.bubble_out_text))
            params.marginStart = 48
        }
        bubble.layoutParams = params

        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
}
