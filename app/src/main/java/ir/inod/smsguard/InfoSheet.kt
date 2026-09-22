package ir.inod.smsguard

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Read-only details laid out as labelled rows instead of one text blob. */
object InfoSheet {
    data class Field(val label: String, val value: String, val icon: Int)
    fun show(context: Context, title: String, fields: List<Field>) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(10))
        }
        fields.forEach { field ->
            val card = MaterialCardView(context).apply {
                radius = dp(14).toFloat()
                strokeWidth = dp(1)
                strokeColor = ContextCompat.getColor(context, R.color.border_soft)
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            row.addView(ImageView(context).apply {
                setImageResource(field.icon)
                imageTintList = ColorStateList.valueOf(ThemePrefs(context).accentColor())
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) })
            val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(context).apply {
                text = field.label
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            })
            texts.addView(TextView(context).apply {
                text = field.value
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        MaterialAlertDialogBuilder(context).setTitle(title)
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton(R.string.close, null).show()
    }
}
