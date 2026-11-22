package ir.inod.smsguard

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * پیام‌هایی که با «حذف» از مکالمه برداشته شده‌اند و هنوز برای بازگردانی
 * نگه داشته می‌شوند. تا وقتی اینجا هستند، حذف واقعی نشده‌اند.
 */
class TrashedMessagesActivity : BaseActivity() {

    private lateinit var container: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView
    private val store by lazy { TrashStore(this) }
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@TrashedMessagesActivity, R.color.screen_bg))
        }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.trash_deleted_messages)
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.trash_messages)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_empty_trash -> {
                        emptyTrash()
                        true
                    }
                    else -> false
                }
            }
        }, LinearLayout.LayoutParams(-1, resources.getDimensionPixelSize(R.dimen.appbar_height)))

        countView = TextView(this).apply {
            textSize = 13f
            val p = dp(16)
            setPadding(p, dp(6), p, dp(6))
            setTextColor(ContextCompat.getColor(this@TrashedMessagesActivity, R.color.text_muted))
        }
        root.addView(countView)

        emptyView = TextView(this).apply {
            text = getString(R.string.trash_empty)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(48), dp(24), dp(24))
            setTextColor(ContextCompat.getColor(this@TrashedMessagesActivity, R.color.text_muted))
        }
        root.addView(emptyView)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(12)
            setPadding(p, 0, p, p)
        }
        root.addView(ScrollView(this).apply { addView(container) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun render() {
        val items = store.all().sortedByDescending { it.deletedAt }
        container.removeAllViews()
        countView.text = getString(R.string.trash_count, items.size)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        items.forEach { item -> container.addView(row(item)) }
    }

    private fun row(item: TrashedMessage): View = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(8) }
        radius = dp(14).toFloat()
        setCardBackgroundColor(ContextCompat.getColor(this@TrashedMessagesActivity, R.color.card_bg))
        addView(LinearLayout(this@TrashedMessagesActivity).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(12)
            setPadding(p, p, p, p)
            addView(TextView(this@TrashedMessagesActivity).apply {
                text = item.address.ifBlank { getString(R.string.unknown_sender) }
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@TrashedMessagesActivity, R.color.text_muted))
            })
            addView(TextView(this@TrashedMessagesActivity).apply {
                text = item.body
                textSize = 15f
                maxLines = 4
                setTextColor(ContextCompat.getColor(this@TrashedMessagesActivity, R.color.text_primary))
            })
            addView(TextView(this@TrashedMessagesActivity).apply {
                text = dateFormat.format(Date(item.deletedAt))
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@TrashedMessagesActivity, R.color.text_muted))
            })
            addView(LinearLayout(this@TrashedMessagesActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(MaterialButton(this@TrashedMessagesActivity).apply {
                    text = getString(R.string.restore_message)
                    setOnClickListener { restore(item) }
                }, LinearLayout.LayoutParams(-2, dp(40)).also { it.marginEnd = dp(8) })
                addView(MaterialButton(this@TrashedMessagesActivity).apply {
                    text = getString(R.string.delete_forever)
                    setOnClickListener { deleteForever(item) }
                }, LinearLayout.LayoutParams(-2, dp(40)))
            })
        })
    }

    private fun restore(item: TrashedMessage) {
        val ok = store.restore(item)
        android.widget.Toast.makeText(
            this,
            if (ok) R.string.restored else R.string.send_failed,
            android.widget.Toast.LENGTH_SHORT
        ).show()
        ThreadCache.clear(this)
        Classifier.invalidateCaches()
        render()
    }

    private fun deleteForever(item: TrashedMessage) {
        ConfirmSheet.show(this, getString(R.string.delete_forever),
            getString(R.string.confirm_delete_forever), R.drawable.ic_tab_trash) {
            store.remove(item.messageId)
            render()
        }
    }

    private fun emptyTrash() {
        if (store.count() == 0) return
        ConfirmSheet.show(this, getString(R.string.empty_trash),
            getString(R.string.confirm_empty_trash), R.drawable.ic_tab_trash) {
            store.clear()
            render()
        }
    }
}
