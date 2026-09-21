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

class ThreadAdapter(
    private val onClick: (ThreadSummary) -> Unit,
    private val onLongClick: (ThreadSummary) -> Unit
) : RecyclerView.Adapter<ThreadAdapter.VH>() {

    private val items = mutableListOf<ThreadSummary>()
    private var categoryCache: Map<String, Category>? = null

    fun submit(list: List<ThreadSummary>) {
        items.clear()
        items.addAll(list)
        categoryCache = null
        notifyDataSetChanged()
    }

    class VH(val binding: ItemThreadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemThreadBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    private fun categories(context: Context): Map<String, Category> =
        categoryCache ?: CategoryStore(context).all().associateBy { it.id }
            .also { categoryCache = it }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val b = holder.binding

        val contactName = ContactNames.displayName(context, item.address)
        val brandMatch = BrandCatalog.find(item.address, contactName)
        val display = if (contactName == item.address && brandMatch != null) {
            brandMatch.displayName
        } else {
            contactName
        }
        b.textAddress.text = display
        b.textSnippet.text = item.snippet
        b.textDate.text = Dates.listLabel(context, item.date)

        TextDir.apply(b.textAddress, display)
        TextDir.apply(b.textSnippet, item.snippet)

        bindAvatar(context, b, item, display)
        bindBadge(context, b, item)

        val suspicious = item.categoryId == Cat.SUSPICIOUS
        b.iconWarning.visibility = if (suspicious) View.VISIBLE else View.GONE

        // Unread: tinted row plus a dot, and a bolder name. The tint goes on the
        // inner row so the hairline divider below stays neutral.
        val unread = item.unreadCount > 0
        b.rowContent.setBackgroundColor(
            if (unread) ContextCompat.getColor(context, R.color.unread_bg) else Color.TRANSPARENT
        )
        b.textUnread.visibility = if (unread) View.VISIBLE else View.GONE
        b.textAddress.setTypeface(null, Typeface.BOLD)
        b.textAddress.setAlpha(if (unread) 1f else 0.85f)
        b.textSnippet.setTextColor(
            ContextCompat.getColor(
                context,
                if (unread) R.color.text_primary else R.color.text_secondary
            )
        )

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    /**
     * Neutral grey badge for an ordinary category; a red badge carrying the
     * reason for a suspicious one, so the row states *why* rather than only
     * turning red.
     */
    private fun bindBadge(context: Context, b: ItemThreadBinding, item: ThreadSummary) {
        if (item.categoryId == Cat.SUSPICIOUS) {
            val reason = riskLabel(context, item.address, item.snippet)
                ?: context.getString(R.string.cat_suspicious)
            b.textCategory.text = reason
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
            val pad = (13 * context.resources.displayMetrics.density).toInt()
            b.avatarLetter.text = null
            b.avatarImage.setPadding(pad, pad, pad, pad)
            b.avatarImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            b.avatarImage.setImageResource(spec.iconRes)
            return
        }

        b.avatarImage.setImageDrawable(null)
        b.avatarLetter.text = AvatarHelper.monogram(display)
    }

    private fun riskLabel(context: Context, address: String, body: String): String? {
        val tags = Classifier.classifyLocal(context, address, body).reasons
        val priority = listOf(
            "fraud-words", "card-number", "sheba", "brand-impersonation",
            "brand-mismatch", "ip-link", "punycode", "domain-blocked",
            "prefix-blocked", "sender-hostile", "callback-number",
            "campaign", "shortener", "risky-tld", "money", "emoji-lure",
            "link", "cta", "promo", "urgency", "late-night", "pattern"
        )
        val tag = priority.firstOrNull { it in tags } ?: tags.firstOrNull() ?: return null
        val res = when (tag) {
            "fraud-words" -> R.string.risk_fraud
            "card-number", "sheba" -> R.string.risk_bank_details
            "brand-impersonation", "brand-mismatch" -> R.string.risk_brand
            "ip-link", "punycode", "shortener", "risky-tld", "link" -> R.string.risk_link
            "domain-blocked", "prefix-blocked", "sender-hostile" -> R.string.risk_known
            "callback-number" -> R.string.risk_callback
            "campaign" -> R.string.risk_campaign
            "money" -> R.string.risk_money
            "emoji-lure" -> R.string.risk_prize
            "cta" -> R.string.risk_cta
            "urgency", "late-night" -> R.string.risk_urgency
            else -> R.string.risk_promo
        }
        return context.getString(res)
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: Exception) {
        Color.GRAY
    }
}
