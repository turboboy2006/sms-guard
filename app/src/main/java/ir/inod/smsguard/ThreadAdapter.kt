package ir.inod.smsguard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.recyclerview.widget.RecyclerView
import ir.inod.smsguard.databinding.ItemThreadBinding

/**
 * Chooses direction per View so a row can mix scripts: a Latin sender ID stays
 * left-to-right while a Persian snippet reads right-to-left.
 */
object TextDir {

    private val RTL = Regex("[\u0600-\u06FF\u0750-\u077F\uFB50-\uFDFF\uFE70-\uFEFF]")

    fun isRtl(text: String): Boolean = RTL.containsMatchIn(text)

    fun apply(view: TextView, text: String) {
        view.textDirection =
            if (isRtl(text)) View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
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
            val contact = ContactNames.displayName(context, address)
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

        b.textAddress.text = display
        b.textSnippet.text = item.snippet
        b.textDate.text = Dates.listLabel(context, item.date)

        TextDir.apply(b.textAddress, display)
        TextDir.apply(b.textSnippet, item.snippet)

        // --- typography -----------------------------------------------------
        val titleSize = if (compact) 14.5f else 16f
        val snippetSize = if (compact) 12.5f else 14f
        b.textAddress.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSize * layout.listFont)
        b.textSnippet.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, snippetSize * layout.listFont)
        b.textCategory.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            11f * layout.listFont.coerceAtMost(1.3f)
        )
        b.textDate.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            11.5f * layout.listFont.coerceAtMost(1.3f)
        )

        // --- spacing --------------------------------------------------------
        val vertical = (layout.padding * density).toInt()
        val horizontal = (16 * density).toInt()
        b.rowContent.setPadding(horizontal, vertical, horizontal, vertical)

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

        bindBadge(context, b, item)
        b.textCategory.visibility =
            if (!compact && b.textCategory.text.isNotEmpty()) View.VISIBLE else View.GONE

        val suspicious = item.categoryId == Cat.SUSPICIOUS
        b.iconWarning.visibility = if (suspicious) View.VISIBLE else View.GONE

        // Unread: a tinted row, a dot, a bolder name and a darker preview. The
        // tint goes on the inner row so the divider below stays neutral.
        val background = RowStyler.background(context, layout, position, unread)
        val fallback = if (unread) {
            ContextCompat.getColor(context, R.color.unread_bg)
        } else {
            Color.TRANSPARENT
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
     * Neutral badge for an ordinary category; a red badge carrying the
     * reason for a suspicious one, so the row states *why* rather than only
     * turning red.
     */
    private fun bindBadge(context: Context, b: ItemThreadBinding, item: ThreadSummary) {
        if (item.categoryId == Cat.SUSPICIOUS) {
            b.textCategory.text = item.riskLabel
                ?: context.getString(R.string.cat_suspicious)
            b.textCategory.background =
                badge(ContextCompat.getColor(context, R.color.badge_danger_bg))
            b.textCategory.setTextColor(
                ContextCompat.getColor(context, R.color.badge_danger_text)
            )
            b.textCategory.visibility = View.VISIBLE
            return
        }

        val category = categories(context)[item.categoryId]
        if (category != null && item.categoryId != Cat.OTHER) {
            b.textCategory.text = category.label(context)
            b.textCategory.background = badge(ContextCompat.getColor(context, R.color.badge_bg))
            b.textCategory.setTextColor(ContextCompat.getColor(context, R.color.badge_text))
            b.textCategory.visibility = View.VISIBLE
        } else {
            b.textCategory.text = ""
            b.textCategory.visibility = View.GONE
        }
    }

    /** Fully rounded, no stroke, no elevation. */
    private fun badge(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 40f
        setColor(color)
    }

    private fun bindAvatar(
        context: Context,
        b: ItemThreadBinding,
        item: ThreadSummary,
        display: String
    ) {
        val photo = AvatarHelper.photo(context, item.address)
        if (photo != null) {
            val rounded = RoundedBitmapDrawableFactory
                .create(context.resources, photo)
                .apply { isCircular = true }
            b.avatar.background = null
            b.avatarImage.setPadding(0, 0, 0, 0)
            b.avatarImage.scaleType = ImageView.ScaleType.CENTER_CROP
            b.avatarImage.setImageDrawable(rounded)
            b.avatarLetter.text = null
            return
        }

        val spec = BrandResolver.resolve(context, item.address, display, item.categoryId)
        b.avatar.background = AvatarHelper.circle(parseColor(spec.colorHex))

        if (spec.iconRes != null) {
            val pad = (12 * context.resources.displayMetrics.density).toInt()
            b.avatarLetter.text = null
            b.avatarImage.setPadding(pad, pad, pad, pad)
            b.avatarImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            b.avatarImage.setImageResource(spec.iconRes)
            return
        }

        b.avatarImage.setImageDrawable(null)
        b.avatarLetter.text = AvatarHelper.monogram(display)
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: Exception) {
        Color.GRAY
    }
}
