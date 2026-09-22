package ir.inod.smsguard

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ScheduledMessagesActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@ScheduledMessagesActivity, R.color.screen_bg))
        }
        root.addView(MaterialToolbar(this).apply {
            title = getString(R.string.scheduled_messages)
            setNavigationIcon(android.R.drawable.ic_media_previous)
            setNavigationOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, resources.getDimensionPixelSize(R.dimen.appbar_height)))
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val items = ScheduledSmsStore(this).all()
        if (items.isEmpty()) list.addView(TextView(this).apply {
            text = getString(R.string.no_scheduled)
            gravity = Gravity.CENTER
            textSize = 16f
            setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_calendar, 0, 0)
            compoundDrawablePadding = dp(16)
            setPadding(dp(20), dp(80), dp(20), dp(80))
            setTextColor(ContextCompat.getColor(this@ScheduledMessagesActivity, R.color.text_secondary))
        })
        items.forEach { item ->
            val card = MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                strokeWidth = dp(1)
                strokeColor = ContextCompat.getColor(this@ScheduledMessagesActivity, R.color.border_soft)
            }
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(12))
            }
            content.addView(TextView(this).apply {
                text = ContactNames.displayNameUi(item.address)
                textSize = 17f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@ScheduledMessagesActivity, R.color.text_primary))
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_person, 0, 0, 0)
                compoundDrawablePadding = dp(8)
            })
            content.addView(TextView(this).apply {
                text = Dates.full(this@ScheduledMessagesActivity, item.at)
                textSize = 13f
                setPadding(0, dp(8), 0, dp(8))
                setTextColor(ThemePrefs(this@ScheduledMessagesActivity).accentColor())
                setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_calendar, 0, 0, 0)
                compoundDrawablePadding = dp(8)
            })
            content.addView(TextView(this).apply {
                text = item.body
                textSize = 15f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setTextColor(ContextCompat.getColor(this@ScheduledMessagesActivity, R.color.text_primary))
                background = MessageStyler.background(this@ScheduledMessagesActivity, MessageStyle.FILLED, 16, true)
            })
            content.addView(MaterialButton(this).apply {
                text = getString(R.string.cancel_scheduled)
                setIconResource(R.drawable.ic_tab_trash)
                setOnClickListener {
                    ConfirmSheet.show(this@ScheduledMessagesActivity, getString(R.string.cancel_scheduled),
                        getString(R.string.confirm_delete_message), R.drawable.ic_tab_trash) {
                            ScheduledSmsStore(this@ScheduledMessagesActivity).remove(item.id)
                            render()
                        }
                }
            })
            card.addView(content)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }
}
