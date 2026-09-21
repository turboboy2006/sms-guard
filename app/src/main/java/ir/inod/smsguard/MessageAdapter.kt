package ir.inod.smsguard

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import android.widget.LinearLayout
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
    private val context: android.content.Context,
    private val onLongClick: (SmsMessage) -> Unit,
    private val onClick: (SmsMessage) -> Unit = {},
    private val onRetry: (SmsMessage) -> Unit = {},
    private val onSelectionChanged: (Int) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed interface Row {
        data class Day(val millis: Long, val yearHeader: Boolean = false) : Row
        data class Msg(val message: SmsMessage) : Row
    }

    private val rows = mutableListOf<Row>()
    private val selectedIds = linkedSetOf<Long>()

    val selectionCount: Int get() = selectedIds.size

    fun selectedMessages(): List<SmsMessage> = rows.mapNotNull {
        (it as? Row.Msg)?.message?.takeIf { message -> message.id in selectedIds }
    }

    fun allMessages(): List<SmsMessage> = rows.mapNotNull { (it as? Row.Msg)?.message }

    fun toggleSelection(message: SmsMessage) {
        if (!selectedIds.add(message.id)) selectedIds.remove(message.id)
        val index = rows.indexOfFirst { it is Row.Msg && it.message.id == message.id }
        if (index >= 0) notifyItemChanged(index)
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectAll() {
        rows.forEach { row -> if (row is Row.Msg) selectedIds.add(row.message.id) }
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
    }

    private var layout = MessageLayout(
        fontScale = 1f,
        spacing = 2,
        radius = 14,
        style = MessageStyle.FILLED,
        colorScheme = ThemePalette.OCEAN
    )

    fun submit(list: List<SmsMessage>) {
        rows.clear()
        var lastDay = Int.MIN_VALUE
        var lastYear = Int.MIN_VALUE
        for (message in list) {
            val year = Dates.year(context, message.date)
            if (lastYear != Int.MIN_VALUE && year != lastYear) {
                rows.add(Row.Day(message.date, yearHeader = true))
            }
            lastYear = year
            val day = dayKey(message.date)
            if (day != lastDay) {
                rows.add(Row.Day(message.date))
                lastDay = day
            }
            rows.add(Row.Msg(message))
        }
        notifyDataSetChanged()
    }

    /**
     * Applies the appearance preferences. Called from the conversation screen
     * whenever the settings change, so a font or bubble change is visible
     * without leaving the app.
     */
    fun applyLayout(next: MessageLayout) {
        layout = next
        notifyDataSetChanged()
    }

    fun currentLayout(): MessageLayout = layout

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
            else -> Dates.conversationDay(context, millis)
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
                h.binding.textDayHeader.text = if (row.yearHeader) {
                    "────  ${Dates.yearLabel(h.itemView.context, row.millis)}  ────"
                } else dayLabel(h.itemView.context, row.millis)
            }
            is Row.Msg -> bindMessage(holder as MsgVH, row.message)
        }
    }

    /**
     * One incoming message.
     *
     * The timestamp is a plain caption rather than a second bubble line, and it
     * hugs the same edge as its bubble, so a run of messages reads as one
     * column per speaker. The day divider gets its own small pill so a long
     * thread still reads as sections.
     */
    private fun bindMessage(holder: MsgVH, item: SmsMessage) {
        val context = holder.itemView.context
        val row = holder.binding.root
        val bubble = holder.binding.textBubble
        val time = holder.binding.textTime
        val status = holder.binding.textStatus
        val density = context.resources.displayMetrics.density

        bubble.text = item.body
        time.text = Dates.full(context, item.date)
        status.visibility = if (item.isIncoming) View.GONE else View.VISIBLE
        if (!item.isIncoming) {
            status.text = when (item.delivery) {
                DeliveryState.SENDING -> context.getString(R.string.delivery_sending)
                DeliveryState.SENT -> context.getString(R.string.delivery_sent)
                DeliveryState.DELIVERED -> context.getString(R.string.delivery_delivered)
                DeliveryState.FAILED -> context.getString(R.string.delivery_failed)
                DeliveryState.RECEIVED -> ""
            }
            status.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    context,
                    if (item.delivery == DeliveryState.FAILED) R.color.danger else R.color.text_muted
                )
            )
            status.setOnClickListener {
                if (item.delivery == DeliveryState.FAILED) onRetry(item)
            }
        } else status.setOnClickListener(null)
        TextDir.apply(bubble, item.body)

        // Type and colour: two independent knobs, so a large font does not
        // force a particular bubble style.
        bubble.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            15f * layout.fontScale
        )
        time.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            10.5f * layout.fontScale.coerceAtMost(1.3f)
        )
        status.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            11f * layout.fontScale.coerceAtMost(1.3f)
        )
        time.setTextColor(
            androidx.core.content.ContextCompat.getColor(context, R.color.text_muted)
        )
        val pad = (MessageStyler.TEXT_PADDING_DP * density).toInt()
        bubble.setPadding(pad, pad, pad, pad)
        bubble.background = MessageStyler.background(
            context, layout.style, layout.radius, !item.isIncoming
        )
        bubble.setTextColor(MessageStyler.textColor(context, !item.isIncoming))

        val params = bubble.layoutParams as LinearLayout.LayoutParams
        val half = (layout.spacing * density).toInt() / 2
        if (item.isIncoming) {
            row.gravity = Gravity.START
            params.marginStart = 0
            time.gravity = Gravity.START
        } else {
            row.gravity = Gravity.END
            params.marginStart = (48 * density).toInt()
            time.gravity = Gravity.END
        }
        // A minimum of one pixel keeps consecutive bubbles from touching when
        // the spacing preference is zero.
        val vertical = if (half < 1) 1 else half
        row.setPadding((12 * density).toInt(), vertical, (12 * density).toInt(), vertical)
        bubble.layoutParams = params
        row.setBackgroundColor(
            androidx.core.content.ContextCompat.getColor(
                context,
                if (item.id in selectedIds) R.color.selection_bg else android.R.color.transparent
            )
        )

        holder.itemView.setOnClickListener { onClick(item) }

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
