package ir.inod.smsguard

import android.content.Context
import android.graphics.Typeface
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import ir.inod.smsguard.databinding.ItemThreadBinding

/**
 * Chooses direction per View, because one list mixes scripts.
 *
 * The default is the ambient direction (right-to-left in Persian), so names and
 * message bodies read correctly. Values that are Latin by nature — a phone
 * number, an OTP code, a URL, an amount — are marked as LTR explicitly, because
 * the bidi algorithm otherwise rearranges their punctuation and the digits look
 * broken in the middle of a Persian sentence.
 */
object TextDir {

    private val RTL = Regex("[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]")

    /** Numbers, punctuation and whitespace: no letters, so direction is arbitrary. */
    private val NOT_LETTERS = Regex("^[\\d\\s\\p{Punct}]+$")

    private val URL = Regex("(?i)\\b(https?://|www\\.)\\S+")

    /** Amounts and codes: currency words plus a run of digits. */
    private val AMOUNT = Regex(
        "(?i)(تومان|ریال|درهم|دلار|یورو|toman|rial|usd|irr|\\$|€)" +
            "|[\\d۰-۹]{3,}[\\s,،]*(تومان|ریال|ریال)"
    )

    fun isRtl(text: String): Boolean = RTL.containsMatchIn(text)

    /**
     * True when the whole string is a "technical" value that should not be
     * reordered: a bare number, a code, a link, or text carrying an amount.
     */
    fun isDirectionNeutral(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (NOT_LETTERS.matches(trimmed)) return true
        if (URL.containsMatchIn(trimmed)) return true
        return AMOUNT.containsMatchIn(trimmed)
    }

    fun apply(view: TextView, text: String) {
        view.textDirection = when {
            isDirectionNeutral(text) -> View.TEXT_DIRECTION_LTR
            isRtl(text) -> View.TEXT_DIRECTION_RTL
            else -> View.TEXT_DIRECTION_LTR
        }
    }
}

/**
 * The inbox list.
 *
 * Two things here are load-bearing for how the app feels on a real phone:
 *
 *  - every value the row needs (name, brand, category, risk sentence) is
 *    already resolved when the data is built, so a bind is only view work;
 *  - [merge] diffs by thread id, so a sync that changes one conversation
 *    repaints one row instead of the whole list.
 */
class ThreadAdapter(
    private val onClick: (ThreadSummary) -> Unit,
    private val onLongClick: (ThreadSummary) -> Unit,
    private val onAvatarClick: (ThreadSummary) -> Unit = {},
    private val onCategoryClick: (ThreadSummary) -> Unit = {},
    private val onSelectionChanged: (Int) -> Unit = {},
    private val identityByMessage: Boolean = false
) : RecyclerView.Adapter<ThreadAdapter.VH>() {

    init { setHasStableIds(true) }
    private fun key(item: ThreadSummary) = if (identityByMessage) item.messageId else item.threadId
    override fun getItemId(position: Int): Long = key(items[position])

    private val items = mutableListOf<ThreadSummary>()
    private var highlightQuery: String = ""
    fun setHighlightQuery(query: String) {
        highlightQuery = query.trim()
        notifyDataSetChanged()
    }
    private val selectedIds = linkedSetOf<Long>()
    private var categoryCache: Map<String, Category>? = null
    private var notificationModes: MutableMap<String, CategoryAlertMode> = HashMap()
    private var mutedAddresses: Set<String>? = null
    private var vibrateAddresses: Set<String>? = null
    private var nameCache: HashMap<String, String> = HashMap()
    private var layout = RowLayout(
        style = RowStyle.CLASSIC,
        padding = 12,
        spacing = 0,
        inset = 0,
        listPadding = 8,
        listFont = 1f,
        messageFont = 1f,
        bubbleRadius = 14,
        showDividers = true,
        colorScheme = ThemePalette.OCEAN
    )

    fun submit(list: List<ThreadSummary>) {
        items.clear()
        items.addAll(list)
        categoryCache = null
        nameCache = HashMap()
        notifyDataSetChanged()
    }

    /**
     * Replaces the contents, emitting the smallest set of changes.
     *
     * An inbox usually changes in three small ways — a new message at the top,
     * a conversation marked read, an old conversation deleted — and telling
     * RecyclerView about only those changes is what keeps a live refresh from
     * flashing the list and losing the scroll position.
     *
     * Order is not preserved for existing rows. A new message bumps its
     * conversation to the top, and the next full sync sorts the list again;
     * pretending otherwise here would cost a move for every moved row on every
     * refresh for no visible gain.
     */
    fun merge(list: List<ThreadSummary>) {
        val old = items.toList()
        if (old.size == list.size && old.indices.all { key(old[it]) == key(list[it]) }) {
            items.clear()
            items.addAll(list)
            old.indices.forEach { index -> if (old[index] != list[index]) notifyItemChanged(index) }
            return
        }
        // DiffUtil's move detection can take seconds in a very large mailbox.
        // Stable IDs preserve the visible anchor without calculating thousands
        // of moves on the UI thread after returning from a conversation.
        if (old.size + list.size > 800) {
            items.clear()
            items.addAll(list)
            selectedIds.retainAll(items.mapTo(HashSet()) { key(it) })
            notifyDataSetChanged()
            return
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                key(old[oldPos]) == key(list[newPos])
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == list[newPos]
        }, true)
        items.clear()
        items.addAll(list)
        selectedIds.retainAll(items.mapTo(HashSet()) { key(it) })
        diff.dispatchUpdatesTo(this)
    }

    /**
     * Applies new appearance settings and repaints what is on screen. Called
     * from the Activity, so a slider on the settings screen updates the list
     * behind it as soon as it is let go.
     */
    fun applyLayout(next: RowLayout) {
        layout = next
        nameCache = HashMap()
        notifyDataSetChanged()
    }

    fun currentLayout(): RowLayout = layout

    fun refreshDrafts() {
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    fun refreshContactNames() {
        nameCache.clear()
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    fun refreshNotificationState() {
        notificationModes.clear()
        mutedAddresses = null
        vibrateAddresses = null
        if (items.isNotEmpty()) notifyItemRangeChanged(0, items.size)
    }

    val selectionCount: Int get() = selectedIds.size

    fun itemAt(position: Int): ThreadSummary? = items.getOrNull(position)

    /** Complete a read swipe without rebuilding an unrelated row. */
    fun finishReadSwipe(threadId: Long, remove: Boolean) {
        val position = items.indexOfFirst { it.threadId == threadId }
        if (position < 0) return
        if (remove) {
            items.removeAt(position)
            notifyItemRemoved(position)
        } else {
            val updated = items[position].copy(unreadCount = 0)
            // ItemTouchHelper keeps a completed swipe in its pending-cleanup
            // set until the ViewHolder is detached. A simple change notification
            // reuses that swiped holder and leaves its blue action exposed.
            items.removeAt(position)
            notifyItemRemoved(position)
            items.add(position, updated)
            notifyItemInserted(position)
        }
    }

    fun selectedItems(): List<ThreadSummary> = items.filter { key(it) in selectedIds }

    fun toggleSelection(item: ThreadSummary) {
        if (!selectedIds.add(key(item))) selectedIds.remove(key(item))
        val position = items.indexOfFirst { key(it) == key(item) }
        if (position >= 0) notifyItemChanged(position)
        onSelectionChanged(selectedIds.size)
    }

    fun clearSelection() {
        if (selectedIds.isEmpty()) return
        val old = selectedIds.toSet()
        selectedIds.clear()
        items.forEachIndexed { index, item -> if (key(item) in old) notifyItemChanged(index) }
        onSelectionChanged(0)
    }

    class VH(val binding: ItemThreadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemThreadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    private fun categories(context: Context): Map<String, Category> =
        categoryCache ?: CategoryStore(context).all().associateBy { it.id }
            .also { categoryCache = it }

    /**
     * Contact and brand resolution is memoised per address: the list is
     * re-bound constantly while scrolling, and the address book is large.
     */
    private fun displayName(context: Context, address: String): String =
        nameCache.getOrPut(address) {
            val contact = ContactNames.displayNameUi(address)
            if (contact == address) {
                // No contact: let the brand catalogue name a known sender ID.
                if (Dates.isPersian(context)) BrandCatalog.find(address, "")?.displayName ?: address
                else address
            } else {
                contact
            }
        }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val b = holder.binding
        val density = context.resources.displayMetrics.density

        val display = displayName(context, item.address)
        val unread = item.unreadCount > 0
        val compact = layout.style == RowStyle.COMPACT
        val showAvatar = layout.style != RowStyle.FLAT && layout.style != RowStyle.COMPACT
        val showBadge = !compact
        val risk = if (item.categoryId == Cat.SUSPICIOUS) item.riskLabel else null

        val draft = context.getSharedPreferences("conversation_drafts", Context.MODE_PRIVATE)
            .getString(item.address, "").orEmpty().trim()
        b.textAddress.text = display
        b.iconPinned.visibility = if (item.pinned) View.VISIBLE else View.GONE
        b.iconPinned.setColorFilter(themeColor(context, item.colorHex))
        val muted = (mutedAddresses ?: SenderStore(context).mutedAddresses()
            .also { mutedAddresses = it }).contains(item.address)
        val vibrateOnly = (vibrateAddresses ?: SenderStore(context).vibrateOnlyAddresses()
            .also { vibrateAddresses = it }).contains(item.address)
        val notificationMode = notificationModes.getOrPut(item.categoryId) {
            CategoryNotificationStore(context).get(item.categoryId).mode
        }
        val notificationIcon = when {
            muted || notificationMode == CategoryAlertMode.OFF ||
                notificationMode == CategoryAlertMode.SILENT -> R.drawable.ic_notification_silent
            vibrateOnly || notificationMode == CategoryAlertMode.VIBRATE_ONLY -> R.drawable.ic_notification_vibrate
            else -> 0
        }
        b.iconNotificationMode.visibility = if (notificationIcon == 0) View.GONE else View.VISIBLE
        if (notificationIcon != 0) {
            b.iconNotificationMode.setImageResource(notificationIcon)
            b.iconNotificationMode.contentDescription = context.getString(
                if ((vibrateOnly || notificationMode == CategoryAlertMode.VIBRATE_ONLY) && !muted)
                    R.string.alert_vibrate_only else R.string.notification_silent)
        }
        val preview = if (draft.isNotBlank()) {
            context.getString(R.string.draft_preview, draft)
        } else item.snippet
        if (highlightQuery.length >= 2) {
            val index = preview.indexOf(highlightQuery, ignoreCase = true)
            b.textSnippet.text = if (index >= 0) SpannableString(preview).apply {
                setSpan(BackgroundColorSpan(Color.argb(52, 255, 195, 63)),
                    index, index + highlightQuery.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else preview
        } else b.textSnippet.text = preview
        b.textDate.text = Dates.listLabel(context, item.date)
        b.textDelivery.visibility = if (item.delivery == null) View.GONE else View.VISIBLE
        b.textDelivery.text = when (item.delivery) {
            DeliveryState.DELIVERED -> ""
            DeliveryState.SENT -> "✓"
            DeliveryState.FAILED -> "!"
            DeliveryState.SENDING -> "…"
            else -> ""
        }
        b.textDelivery.setCompoundDrawablesWithIntrinsicBounds(
            if (item.delivery == DeliveryState.DELIVERED) R.drawable.ic_double_check else 0,
            0, 0, 0)
        b.textDelivery.setTextColor(ContextCompat.getColor(context, when (item.delivery) {
            DeliveryState.DELIVERED -> R.color.success
            DeliveryState.FAILED -> R.color.danger
            else -> R.color.text_muted
        }))
        val persianUi = Dates.isPersian(context)
        // START is the physical right in an RTL row. END previously placed
        // several Persian previews on the left, especially mixed-script SMS.
        b.textAddress.gravity = Gravity.START
        b.textSnippet.gravity = Gravity.START
        b.textAddress.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        b.textSnippet.textAlignment = View.TEXT_ALIGNMENT_VIEW_START

        // Names and bodies follow the ambient (RTL) direction; a preview that is
        // really a code, a link or an amount is pinned to LTR so its digits are
        // not reordered.
        // Inbox columns always align to the reading edge. Text direction only
        // affects character order, never the physical placement of a row.
        if (persianUi) {
            b.textAddress.textDirection = View.TEXT_DIRECTION_RTL
            b.textSnippet.textDirection = View.TEXT_DIRECTION_RTL
        } else {
            TextDir.apply(b.textAddress, display)
            TextDir.apply(b.textSnippet, b.textSnippet.text.toString())
        }

        // --- typography -----------------------------------------------------
        val titleSize = if (compact) 14.5f else 16f
        val snippetSize = if (compact) 12.5f else 13.5f
        b.textAddress.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSize * layout.listFont)
        b.textSnippet.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, snippetSize * layout.listFont)
        b.textCategory.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            11.5f * layout.listFont.coerceAtMost(1.25f)
        )
        b.textDate.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            11f * layout.listFont.coerceAtMost(1.2f)
        )
        b.textDate.textDirection = if (persianUi) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        b.textDate.textAlignment = View.TEXT_ALIGNMENT_VIEW_END

        // --- spacing --------------------------------------------------------
        val vertical = (layout.padding * density).toInt()
        b.rowContent.setPadding(
            (16 * density).toInt(), vertical, (16 * density).toInt(), vertical
        )

        val params = b.root.layoutParams as? ViewGroup.MarginLayoutParams
        if (params != null) {
            val side = (layout.inset * density).toInt()
            val gap = (layout.spacing * density).toInt()
            val bottom = if (compact) (gap / 2) else gap
            if (params.leftMargin != side || params.rightMargin != side ||
                params.topMargin != 0 || params.bottomMargin != bottom
            ) {
                params.setMargins(side, 0, side, bottom)
                b.root.layoutParams = params
            }
        }
        // Keep a minimum touch target but let large accessibility fonts choose
        // their natural height. Clamping after measurement caused recycled rows
        // to jump and could clip the second preview line.
        b.rowContent.minimumHeight = dp(density, if (compact) 72 else 84)

        // --- avatar ---------------------------------------------------------
        b.avatar.visibility = if (showAvatar) View.VISIBLE else View.GONE
        if (showAvatar) {
            val avatarSize = dp(density, if (compact) 40 else 64)
            if (b.avatar.layoutParams.width != avatarSize) {
                b.avatar.layoutParams = b.avatar.layoutParams.apply {
                    width = avatarSize
                    height = avatarSize
                }
            }
            if (key(item) in selectedIds) {
                b.avatar.background = AvatarHelper.circle(ContextCompat.getColor(context, R.color.colorPrimary))
                b.avatarImage.visibility = View.GONE
                b.avatarLetter.text = "✓"
                b.avatarLetter.setTextColor(android.graphics.Color.WHITE)
            } else {
                b.avatarImage.visibility = View.VISIBLE
                bindAvatar(context, b, item, display)
            }
        }

        // --- badge ----------------------------------------------------------
        if (showBadge && bindBadge(context, b, item, risk)) {
            b.textCategory.visibility = View.VISIBLE
            b.textCategory.setOnClickListener { onCategoryClick(item) }
        } else {
            b.textCategory.visibility = View.GONE
            b.textCategory.setOnClickListener(null)
        }

        // The warning icon marks a real risk, not merely an unknown sender: a
        // row already red-badged does not need a second alarm next to its name.
        b.iconWarning.visibility = if (risk != null) View.VISIBLE else View.GONE

        // --- background -----------------------------------------------------
        val background = if (item.pinned && !unread) {
            GradientDrawable().apply {
                cornerRadius = layout.bubbleRadius * density
                setColor(ColorUtils.blendARGB(
                    ContextCompat.getColor(context, R.color.surface_elevated),
                    themeColor(context, item.colorHex), 0.045f))
            }
        } else RowStyler.background(context, layout, position, unread)
        val fallback = if (unread) {
            ContextCompat.getColor(context, R.color.unread_bg)
        } else {
            android.graphics.Color.TRANSPARENT
        }
        RowStyler.apply(b.rowContent, background, fallback)
        if (key(item) in selectedIds) {
            b.rowContent.setBackgroundColor(ContextCompat.getColor(context, R.color.selection_bg))
        }
        // Unread is stated twice on purpose: the row tint, and a small blue disc
        // next to the time. The disc carries the count when there is more than
        // one, which the tint alone cannot say.
        if (unread) {
            b.textUnread.visibility = View.VISIBLE
            b.textUnread.text = Dates.count(context, item.unreadCount)
        } else {
            b.textUnread.visibility = View.GONE
        }
        b.textAddress.setTypeface(null, Typeface.BOLD)
        b.textAddress.setAlpha(if (unread) 1f else 0.85f)
        b.textSnippet.setTextColor(
            ContextCompat.getColor(
                context,
                if (draft.isNotBlank()) R.color.danger_deep
                else if (unread) R.color.text_primary else R.color.text_secondary
            )
        )

        // --- accent bar -----------------------------------------------------
        val accent = layout.style == RowStyle.ACCENT
        b.accentBar.visibility = if (accent) View.VISIBLE else View.GONE
        if (accent) {
            b.accentBar.setBackgroundColor(RowStyler.accentColor(context, item.colorHex))
            val width = dp(density, RowStyler.STRIPE_DP)
            if (b.accentBar.layoutParams.width != width) {
                b.accentBar.layoutParams = b.accentBar.layoutParams.apply { this.width = width }
            }
        }

        // --- divider --------------------------------------------------------
        b.divider.visibility =
            if (layout.showDividers && layout.style != RowStyle.CARD) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener { onClick(item) }
        b.avatar.setOnClickListener {
            if (selectionCount > 0) toggleSelection(item) else onAvatarClick(item)
        }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    private fun dp(density: Float, value: Int): Int = (value * density).toInt()

    /**
     * The category pill.
     *
     * An ordinary row states what it is, and only for the categories a reader
     * actually scans for — [Category.showBadge] decides which. A suspicious row
     * always badges, and prints the *reason* rather than the word, because
     * "why" is the useful thing to say about a message already flagged.
     *
     * Two failure modes this avoids, both of which were visible on a real
     * phone: a pill drawn with no text (an empty grey lozenge) and a label cut
     * off mid-word.
     *
     * @return true when a badge should be shown
     */
    private fun bindBadge(
        context: Context,
        b: ItemThreadBinding,
        item: ThreadSummary,
        risk: String?
    ): Boolean {
        val label: String
        val background: Int
        val foreground: Int

        if (item.categoryId == Cat.SUSPICIOUS) {
            label = risk ?: context.getString(R.string.cat_suspicious)
            background = ContextCompat.getColor(context, R.color.badge_danger_bg)
            foreground = ContextCompat.getColor(context, R.color.badge_danger_text)
        } else {
            val category = categories(context)[item.categoryId] ?: return false
            label = category.label(context)
            foreground = runCatching { Color.parseColor(category.colorHex) }
                .getOrDefault(ContextCompat.getColor(context, R.color.badge_text))
            background = ColorUtils.blendARGB(
                ContextCompat.getColor(context, R.color.card_bg), foreground, 0.13f)
        }

        // An empty label means an empty pill; that is never worth drawing.
        if (label.isBlank()) return false

        b.textCategory.text = label
        b.textCategory.background = badge(background)
        b.textCategory.setTextColor(foreground)
        b.textCategory.maxLines = 2
        b.textCategory.ellipsize = null
        b.textCategory.maxWidth = (110 * context.resources.displayMetrics.density).toInt()
        return true
    }

    /** Fully rounded, no stroke, no elevation. */
    private fun badge(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 40f
        setColor(color)
    }

    /**
     * The avatar states, in priority order: a real photo, a known business (its
     * own colour with a white glyph), a person (a soft tinted circle with a
     * deep-toned initial), an unnamed sender (a grey circle with a person
     * glyph).
     *
     * Nothing here is red or orange. An avatar says *who*, the badge says *how
     * risky*; mixing the two is what made a list of unknown senders look like a
     * wall of alarms.
     */
    private fun bindAvatar(
        context: Context,
        b: ItemThreadBinding,
        item: ThreadSummary,
        display: String
    ) {
        val density = context.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        b.avatarImage.setPadding(0, 0, 0, 0)
        // A recycled view keeps its colour filter, so every branch states it.
        b.avatarImage.clearColorFilter()
        b.avatarLetter.setTextColor(android.graphics.Color.WHITE)

        // Nothing to draw a person from: a calm grey circle with a silhouette.
        // This has to be checked before anything else, otherwise the row falls
        // through to a branch that leaves the avatar square and empty.
        if (AvatarHelper.isUnknown(display)) {
            showPersonGlyph(context, b, pad, item.address)
            return
        }

        val photo = AvatarHelper.photo(context, item.address)
        if (photo != null) {
            val rounded = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
                .create(context.resources, photo)
                .apply { isCircular = true }
            b.avatar.background = null
            b.avatarImage.scaleType = ImageView.ScaleType.CENTER_CROP
            b.avatarImage.setImageDrawable(rounded)
            b.avatarLetter.text = null
            return
        }

        // A business that the catalogue or the user knows: its own colour.
        val spec = BrandResolver.resolve(context, item.address, display, item.categoryId)
        if (spec.iconRes != null && spec.displayName != null) {
            b.avatar.background = AvatarHelper.circle(parseColor(spec.colorHex))
            b.avatarLetter.text = null
            b.avatarImage.setPadding(pad, pad, pad, pad)
            b.avatarImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            b.avatarImage.setImageResource(spec.iconRes)
            return
        }

        val letter = AvatarHelper.monogram(display)
        if (letter != null) {
            val (container, ink) = AvatarHelper.softPair(display)
            b.avatar.background = AvatarHelper.circle(container)
            b.avatarImage.setImageDrawable(null)
            b.avatarLetter.setTextColor(ink)
            b.avatarLetter.text = letter
            return
        }

        showPersonGlyph(context, b, pad, item.address)
    }

    /** Grey circle, muted person silhouette: an unnamed sender, stated calmly. */
    private fun showPersonGlyph(context: Context, b: ItemThreadBinding, pad: Int, key: String) {
        val (container, ink) = AvatarHelper.softPair(key)
        b.avatar.background = AvatarHelper.circle(container)
        b.avatarLetter.text = null
        b.avatarImage.setPadding(pad, pad, pad, pad)
        b.avatarImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
        b.avatarImage.setImageResource(R.drawable.ic_person)
        b.avatarImage.setColorFilter(ink)
    }

    private fun parseColor(hex: String): Int = try {
        android.graphics.Color.parseColor(hex)
    } catch (e: Exception) {
        android.graphics.Color.GRAY
    }

    private fun themeColor(context: Context, hex: String): Int = runCatching { android.graphics.Color.parseColor(hex) }
        .getOrDefault(ThemePrefs(context).accentColor())
}
