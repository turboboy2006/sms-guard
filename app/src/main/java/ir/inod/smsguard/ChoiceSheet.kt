package ir.inod.smsguard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** A consistent, touch-friendly menu for message and sender actions. */
object ChoiceSheet {
    data class Option(
        val label: String,
        val icon: Int,
        val color: Int? = null,
        val detail: String? = null
    )

    fun show(context: Context, title: String, choices: List<Option>, onPick: (Int) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }
        val scroll = ScrollView(context).apply { addView(container) }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(title)
            .setView(scroll).setNegativeButton(R.string.cancel, null).create()
        val accent = ThemePrefs(context).accentColor()
        val surface = ContextCompat.getColor(context, R.color.card_bg)
        choices.forEachIndexed { index, option ->
            val ink = option.color ?: accent
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                setPadding(dp(12), dp(8), dp(12), dp(8))
                minimumHeight = dp(56)
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(ColorUtils.blendARGB(surface, ink, 0.08f))
                }
                isClickable = true
                isFocusable = true
            }
            row.addView(ImageView(context).apply {
                setImageResource(option.icon)
                imageTintList = ColorStateList.valueOf(ink)
                contentDescription = option.label
            }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(14) })
            val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(TextView(context).apply {
                text = option.label
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            })
            option.detail?.let { detail -> labels.addView(TextView(context).apply {
                text = detail
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }) }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener { dialog.dismiss(); onPick(index) }
            container.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }
        dialog.show()
    }
}
