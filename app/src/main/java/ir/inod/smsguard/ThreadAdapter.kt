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

    /** Category lookup is cached; SharedPreferences must not be read per bind. */
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

        // Contact name first; when the number is unknown, a catalog brand name
        // reads far better than a raw sender ID.
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

        val category = categories(context)[item.categoryId]
        if (category != null && item.categoryId != Cat.OTHER) {
            b.textCategory.text = category.label(context)
            // A rounded pill instead of a hard rectangle.
            b.textCategory.background = pill(parseColor(category.colorHex))
            b.textCategory.visibility = View.VISIBLE
        } else {
            b.textCategory.visibility = View.GONE
        }

        // The confirmation prompt: a red badge until the user decides.
        val suspicious = item.categoryId == Cat.SUSPICIOUS
        b.iconWarning.visibility = if (suspicious) View.VISIBLE else View.GONE

        // Explain the flag in words. "RiskBanner": never let red alone carry
        // the meaning, because the user cannot act on a colour.
        if (suspicious) {
            val label = riskLabel(context, item.address, item.snippet)
            if (label != null) {
                b.textRisk.text = label
                b.textRisk.background = riskChip(context)
                b.textRisk.visibility = View.VISIBLE
            } else {
                b.textRisk.visibility = View.GONE
            }
        } else {
            b.textRisk.visibility = View.GONE
        }

        // Unread reads stronger, read recedes. This is the single clearest cue
        // for "what still needs my attention".
        val unread = item.unreadCount > 0
        val primary = ContextCompat.getColor(context, R.color.text_primary)
        val secondary = ContextCompat.getColor(context, R.color.text_secondary)
        b.textAddress.setTextColor(if (unread) primary else secondary)
        b.textAddress.setTypeface(null, if (unread) Typeface.BOLD else Typeface.NORMAL)
        b.textSnippet.setTextColor(if (unread) primary else secondary)
        b.textDate.alpha = if (unread) 1f else 0.7f

        if (unread) {
            b.textUnread.visibility = View.VISIBLE
            b.textUnread.text = Dates.faDigits(item.unreadCount.toString())
        } else {
            b.textUnread.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    /**
     * Photo, else a coloured monogram, else a blank silhouette. Every branch
     * resets the ImageView, because rows are recycled.
     */
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

        // Resolution order: brand catalog, then category, then monogram, then a
        // grey person. Everything below a real photo goes through one resolver.
        val spec = BrandResolver.resolve(context, item.address, display, item.categoryId)
        b.avatar.background = AvatarHelper.circle(parseColor(spec.colorHex))

        if (spec.iconRes != null) {
            val pad = (11 * context.resources.displayMetrics.density).toInt()
            b.avatarLetter.text = null
            b.avatarImage.setPadding(pad, pad, pad, pad)
            b.avatarImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            b.avatarImage.setImageResource(spec.iconRes)
            return
        }

        // Deterministic monogram: the same name always gets the same colour.
        b.avatarImage.setImageDrawable(null)
        b.avatarLetter.text = AvatarHelper.monogram(display)
    }

    /** Soft red pill behind the reason text. */
    private fun riskChip(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 40f
        setColor(ContextCompat.getColor(context, R.color.danger_soft))
    }

    /**
     * Turns the classifier's internal tags into one short phrase the user can
     * act on, in order of what matters most.
     */
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

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 40f
        setColor(color)
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: Exception) {
        Color.GRAY
    }
}
