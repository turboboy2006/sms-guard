package ir.inod.smsguard

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Consistent confirmation for potentially destructive actions. */
object ConfirmSheet {
    fun show(context: Context, title: String, message: String, icon: Int,
             confirmLabel: Int = R.string.confirm, dangerous: Boolean = true,
             onConfirm: () -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        body.addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(if (dangerous)
                ContextCompat.getColor(context, R.color.danger) else ThemePrefs(context).accentColor())
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply { bottomMargin = dp(14) })
        body.addView(TextView(context).apply {
            text = message
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        })
        MaterialAlertDialogBuilder(context).setTitle(title).setView(body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() }.show()
    }
}
