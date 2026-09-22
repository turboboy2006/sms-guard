package ir.inod.smsguard

import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.text.util.Linkify
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
        data class Sim(val subscriptionId: Int) : Row
        data class Msg(val message: SmsMessage) : Row
    }

    private val rows = mutableListOf<Row>()
    private val selectedIds = linkedSetOf<Long>()
    private val expandedMetaIds = linkedSetOf<Long>()
    private val latestMetaIds = linkedSetOf<Long>()
    private val attachedMessages = linkedSetOf<MsgVH>()

    val selectionCount: Int get() = selectedIds.size

    fun selectedMessages(): List<SmsMessage> = rows.mapNotNull {
        (it as? Row.Msg)?.message?.takeIf { message -> message.id in selectedIds }
    }

    fun allMessages(): List<SmsMessage> = rows.mapNotNull { (it as? Row.Msg)?.message }

    fun positionOf(messageId: Long): Int = rows.indexOfFirst {
        it is Row.Msg && it.message.id == messageId
    }

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
        latestMetaIds.clear()
        list.asReversed().take(2).forEach { latestMetaIds += it.id }
        var lastDay = Int.MIN_VALUE
        var lastYear = Int.MIN_VALUE
        var lastOutgoingSim: Int? = null
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
            if (!message.isIncoming && message.subscriptionId != lastOutgoingSim) {
                rows.add(Row.Sim(message.subscriptionId))
                lastOutgoingSim = message.subscriptionId
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

    /** Temporary pinch zoom; persisted by the conversation after the gesture. */
    fun setFontZoom(scale: Float) {
        val next = layout.copy(fontScale = scale.coerceIn(0.85f, 1.5f))
        if (next == layout) return
        layout = next
        attachedMessages.forEach { holder ->
            holder.binding.textBubble.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f * next.fontScale)
            holder.binding.textTime.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f * next.fontScale.coerceAtMost(1.3f))
            holder.binding.textStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f * next.fontScale.coerceAtMost(1.3f))
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is MsgVH) attachedMessages.add(holder)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is MsgVH) attachedMessages.remove(holder)
        super.onViewDetachedFromWindow(holder)
    }

    private fun dayKey(millis: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = Dates.normalizedMillis(millis) }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    /** "Today" / "Yesterday" / a full date, in the active language. */
    private fun dayLabel(context: android.content.Context, millis: Long): String {
        return Dates.conversationDay(context, millis)
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Day || rows[position] is Row.Sim) TYPE_DAY else TYPE_MESSAGE

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
            is Row.Sim -> {
                val h = holder as DayVH
                val id = row.subscriptionId
                val label = runCatching {
                    val manager = context.getSystemService(android.telephony.SubscriptionManager::class.java)
                    val info = manager?.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == id }
                    if (info == null) context.getString(R.string.sim_unknown)
                    else context.getString(R.string.sim_label, info.simSlotIndex + 1, info.carrierName?.toString().orEmpty())
                }.getOrDefault(context.getString(R.string.sim_unknown))
                h.binding.textDayHeader.text = label
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
        applySafeLinks(context, bubble, item)
        time.text = Dates.full(context, item.date)
        val showMeta = item.id in latestMetaIds || item.id in expandedMetaIds || selectedIds.isNotEmpty()
        time.visibility = if (showMeta) View.VISIBLE else View.GONE
        status.visibility = if (item.isIncoming || !showMeta) View.GONE else View.VISIBLE
        if (!item.isIncoming) {
            status.text = when (item.delivery) {
                DeliveryState.SENDING -> "◷"
                DeliveryState.SENT -> "✓"
                DeliveryState.DELIVERED -> "✓\u200A✓"
                DeliveryState.FAILED -> "!"
                DeliveryState.RECEIVED -> ""
            }
            status.contentDescription = when (item.delivery) {
                DeliveryState.SENDING -> context.getString(R.string.delivery_sending)
                DeliveryState.SENT -> context.getString(R.string.delivery_sent)
                DeliveryState.DELIVERED -> context.getString(R.string.delivery_delivered)
                DeliveryState.FAILED -> context.getString(R.string.delivery_failed)
                DeliveryState.RECEIVED -> ""
            }
            status.setTextColor(when (item.delivery) {
                DeliveryState.DELIVERED -> android.graphics.Color.parseColor("#16A34A")
                DeliveryState.FAILED -> androidx.core.content.ContextCompat.getColor(context, R.color.danger)
                else -> androidx.core.content.ContextCompat.getColor(context, R.color.text_muted)
            })
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
            14f * layout.fontScale.coerceAtMost(1.3f)
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

        holder.itemView.setOnClickListener {
            if (selectedIds.isEmpty()) {
                if (!expandedMetaIds.add(item.id)) expandedMetaIds.remove(item.id)
                val changed = holder.bindingAdapterPosition
                if (changed != RecyclerView.NO_POSITION) notifyItemChanged(changed)
            }
            onClick(item)
        }

        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    /** Enables ordinary web links while leaving spam and risky domains inert. */
    private fun applySafeLinks(context: android.content.Context, bubble: android.widget.TextView,
                               item: SmsMessage) {
        bubble.movementMethod = null
        val category = CategoryStore(context).byId(item.categoryId)
        if (category?.spamFolder == true || item.categoryId == Cat.TRASH) return
        if (!Linkify.addLinks(bubble, Linkify.WEB_URLS)) return
        val text = bubble.text as? Spannable ?: return
        val blocked = BlockStore(context)
        text.getSpans(0, text.length, URLSpan::class.java).forEach { span ->
            val feature = UrlIntel.extract(span.url).firstOrNull()
            val unsafe = feature == null || blocked.matchesDomain(feature.host) ||
                feature.isIp || feature.isShortener || feature.isPunycode ||
                feature.hasRedirectParam || feature.riskyTld
            if (unsafe) text.removeSpan(span)
        }
        if (text.getSpans(0, text.length, URLSpan::class.java).isNotEmpty()) {
            bubble.movementMethod = LinkMovementMethod.getInstance()
            bubble.highlightColor = android.graphics.Color.TRANSPARENT
            bubble.linksClickable = true
        }
    }

    private companion object {
        const val TYPE_DAY = 0
        const val TYPE_MESSAGE = 1
    }
}
