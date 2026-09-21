package ir.inod.smsguard

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import ir.inod.smsguard.databinding.ItemDayHeaderBinding
import ir.inod.smsguard.databinding.ItemMessageBinding
import java.util.Calendar

/**
 * Conversation list.
 *
 * Messages are grouped by day and a centred divider is inserted whenever the
 * day changes, so a long thread reads as sections rather than one wall of
 * bubbles. One bubble layout is reused for both directions; gravity and
 * background are switched in code.
 */
class MessageAdapter(
    private val onLongClick: (SmsMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Day(val millis: Long) : Row
        data class Msg(val message: SmsMessage) : Row
    }

    private val rows = mutableListOf<Row>()

    fun submit(list: List<SmsMessage>) {
        rows.clear()
        var lastDay = Int.MIN_VALUE
        for (message in list) {
            val day = dayKey(message.date)
            if (day != lastDay) {
                rows.add(Row.Day(message.date))
                lastDay = day
            }
            rows.add(Row.Msg(message))
        }
        notifyDataSetChanged()
    }

    private fun dayKey(millis: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    /** "Today" / "Yesterday" / a full date, in the active language. */
    private fun dayLabel(context: android.content.Context, millis: Long): String {
        val today = dayKey(System.currentTimeMillis())
        val day = dayKey(millis)
        return when (day) {
            today -> context.getString(R.string.today)
            today - 1 -> context.getString(R.string.yesterday)
            else -> Dates.listLabel(context, millis)
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Day) TYPE_DAY else TYPE_MESSAGE

    override fun getItemCount(): Int = rows.size

    class DayVH(val binding: ItemDayHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    class MsgVH(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_DAY) {
            DayVH(ItemDayHeaderBinding.inflate(inflater, parent, false))
        } else {
            MsgVH(ItemMessageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Day -> {
                val h = holder as DayVH
                h.binding.textDayHeader.text = dayLabel(h.itemView.context, row.millis)
            }
            is Row.Msg -> bindMessage(holder as MsgVH, row.message)
        }
    }

    private fun bindMessage(holder: MsgVH, item: SmsMessage) {
        val context = holder.itemView.context
        val row = holder.binding.root
        val bubble = holder.binding.textBubble

        bubble.text = item.body
        holder.binding.textTime.text = Dates.full(context, item.date)
        TextDir.apply(bubble, item.body)

        val params = bubble.layoutParams as LinearLayout.LayoutParams
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

    private companion object {
        const val TYPE_DAY = 0
        const val TYPE_MESSAGE = 1
    }
}
