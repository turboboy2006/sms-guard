package ir.inod.smsguard

import android.content.Context
import android.graphics.Typeface
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
    private val onLongClick: (ThreadSummary) -> Unit
) : RecyclerView.Adapter<ThreadAdapter.VH>() {

    private val items = mutableListOf<ThreadSummary>()
    private var categoryCache: Map<String, Category>? = null
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
        showDividers = true
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
        if (items.isEmpty()) {
            submit(list)
            return
        }
        val old = items.toList()
        val nextIds = HashSet<Long>(list.size * 2)
        for (row in list) nextIds.add(row.threadId)

        // 1. Conversations the provider no longer has, removed from the tail
        //    backwards so the reported positions stay valid.
        for (i in old.indices.reversed()) {
            if (old[i].threadId !in nextIds) {
                items.removeAt(i)
                notifyItemRemoved(i)
            }
        }

        // 2. Conversations that arrived since the last build, inserted from the
        //    top down because the inbox is newest-first.
        for (i in list.indices) {
            val row = list[i]
            if (items.none { it.threadId == row.threadId }) {
                items.add(i, row)
                notifyItemInserted(i)
            }
        }

        // 3. Rows whose content moved on: a new message, a new unread count, a
        //    different category. Only these are rebound.
        for (i in items.indices) {
            val incoming = list.getOrNull(i) ?: break
            if (items[i].threadId != incoming.threadId) continue
            if (items[i] != incoming) {
                items[i] = incoming
                notifyItemChanged(i)
            }
        }

        // Anything the steps above could not express — a re-sorted list, for
        // instance — is worth a full rebind rather than a wrong screen.
        if (items.size != list.size || items != list) submit(list)
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
                BrandCatalog.find(address, "")?.displayName ?: address
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

        b.textAddress.text = display
        b.textSnippet.text = item.snippet
        b.textDate.text = Dates.listLabel(context, item.date)

        // Names and bodies follow the ambient (RTL) direction; a preview that is
        // really a code, a link or an amount is pinned to LTR so its digits are
        // not reordered.
        TextDir.apply(b.textAddress, display)
        if (TextDir.isDirectionNeutral(item.snippet)) {
            b.textSnippet.textDirection = View.TEXT_DIRECTION_LTR
        } else {
            TextDir.apply(b.textSnippet, item.snippet)
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

        // --- spacing --------------------------------------------------------
        val vertical = (layout.padding * density).toInt()
        b.rowContent.setPadding(
            (8 * density).toInt(), vertical, (16 * density).toInt(), vertical
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
        // A name, two lines of preview and a badge have a natural ceiling; the
        // promise is a row between 88dp and 108dp whatever the text does.
        val rowParams = b.rowContent.layoutParams as? LinearLayout.LayoutParams
        if (rowParams != null) {
            val ceiling = dp(density, 108)
            val measured = if (b.rowContent.height > 0) b.rowContent.height else 0
            val target = if (measured > ceiling) ceiling else ViewGroup.LayoutParams.WRAP_CONTENT
            if (rowParams.height != target) {
                rowParams.height = target
                b.rowContent.layoutParams = rowParams
            }
        }

        // --- avatar ---------------------------------------------------------
        b.avatar.visibility = if (showAvatar) View.VISIBLE else View.GONE
        if (showAvatar) {
            val avatarSize = dp(density, if (compact) 40 else 48)
            if (b.avatar.layoutParams.width != avatarSize) {
                b.avatar.layoutParams = b.avatar.layoutParams.apply {
                    width = avatarSize
                    height = avatarSize
                }
            }
            bindAvatar(context, b, item, display)
        }

        // --- badge ----------------------------------------------------------
        if (showBadge && bindBadge(context, b, item, risk)) {
            b.textCategory.visibility = View.VISIBLE
        } else {
            b.textCategory.visibility = View.GONE
        }

        // The warning icon marks a real risk, not merely an unknown sender: a
        // row already red-badged does not need a second alarm next to its name.
        b.iconWarning.visibility = if (risk != null) View.VISIBLE else View.GONE

        // --- background -----------------------------------------------------
        val background = RowStyler.background(context, layout, position, unread)
        val fallback = if (unread) {
            ContextCompat.getColor(context, R.color.unread_bg)
        } else {
            android.graphics.Color.TRANSPARENT
        }
        RowStyler.apply(b.rowContent, background, fallback)
        b.textUnread.visibility = if (unread) View.VISIBLE else View.GONE
        b.textAddress.setTypeface(null, Typeface.BOLD)
        b.textAddress.setAlpha(if (unread) 1f else 0.85f)
        b.textSnippet.setTextColor(
            ContextCompat.getColor(
                context,
                if (unread) R.color.text_primary else R.color.text_secondary
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
            if (!category.showBadge) return false
            label = category.label(context)
            background = if (category.badgeBgColor != Category.NO_COLOR) {
                category.badgeBgColor
            } else {
                ContextCompat.getColor(context, R.color.badge_bg)
            }
            foreground = if (category.badgeTextColor != Category.NO_COLOR) {
                category.badgeTextColor
            } else {
                ContextCompat.getColor(context, R.color.badge_text)
            }
        }

        // An empty label means an empty pill; that is never worth drawing.
        if (label.isBlank()) return false

        b.textCategory.text = label
        b.textCategory.background = badge(background)
        b.textCategory.setTextColor(foreground)
        b.textCategory.maxLines = 2
        b.textCategory.ellipsize = null
        b.textCategory.maxWidth = (200 * context.resources.displayMetrics.density).toInt()
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
            showPersonGlyph(context, b, pad)
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

        showPersonGlyph(context, b, pad)
    }

    /** Grey circle, muted person silhouette: an unnamed sender, stated calmly. */
    private fun showPersonGlyph(context: Context, b: ItemThreadBinding, pad: Int) {
        b.avatar.background = AvatarHelper.circle(
            ContextCompat.getColor(context, R.color.surface_sunken)
        )
        b.avatarLetter.text = null
        b.avatarImage.setPadding(pad, pad, pad, pad)
        b.avatarImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
        b.avatarImage.setImageResource(R.drawable.ic_person)
        b.avatarImage.setColorFilter(ContextCompat.getColor(context, R.color.text_muted))
    }

    private fun parseColor(hex: String): Int = try {
        android.graphics.Color.parseColor(hex)
    } catch (e: Exception) {
        android.graphics.Color.GRAY
    }
}
