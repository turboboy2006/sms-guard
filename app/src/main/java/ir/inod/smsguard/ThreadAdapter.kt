package ir.inod.smsguard

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

        val display = ContactNames.displayName(context, item.address)
        b.textAddress.text = display
        b.textSnippet.text = item.snippet
        b.textDate.text = Dates.listLabel(item.date)

        TextDir.apply(b.textAddress, display)
        TextDir.apply(b.textSnippet, item.snippet)

        b.colorStripe.setBackgroundColor(parseColor(item.colorHex))

        val category = categories(context)[item.categoryId]
        b.textCategory.text = category?.label(context).orEmpty()
        b.textCategory.setBackgroundColor(parseColor(category?.colorHex ?: "#616161"))
        b.textCategory.visibility =
            if (category == null || item.categoryId == Cat.OTHER) View.GONE else View.VISIBLE

        // The confirmation prompt: a red badge until the user decides.
        b.iconWarning.visibility =
            if (item.categoryId == Cat.SUSPICIOUS) View.VISIBLE else View.GONE

        if (item.unreadCount > 0) {
            b.textUnread.visibility = View.VISIBLE
            b.textUnread.text = item.unreadCount.toString()
        } else {
            b.textUnread.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    private fun parseColor(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: Exception) {
        Color.GRAY
    }
}
