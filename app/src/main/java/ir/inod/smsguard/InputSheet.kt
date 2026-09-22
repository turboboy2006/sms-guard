package ir.inod.smsguard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Icon-led text editor used instead of bare EditText dialogs. */
object InputSheet {
    fun show(
        context: Context,
        title: String,
        hint: String,
        initial: String = "",
        icon: Int,
        multiline: Boolean = false,
        confirmLabel: Int = R.string.save,
        onSave: (String) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val accent = ThemePrefs(context).accentColor()
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        body.addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(accent, 24))
            }
            setPadding(dp(11), dp(11), dp(11), dp(11))
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) })
        val input = EditText(context).apply {
            this.hint = hint
            setText(initial)
            setSelection(text?.length ?: 0)
            minHeight = dp(if (multiline) 96 else 52)
            maxLines = if (multiline) 5 else 1
            isSingleLine = !multiline
            imeOptions = if (multiline) EditorInfo.IME_FLAG_NO_ENTER_ACTION else EditorInfo.IME_ACTION_DONE
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(ContextCompat.getColor(context, R.color.card_bg))
                setStroke(dp(1), ColorUtils.setAlphaComponent(accent, 95))
            }
        }
        body.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title).setView(body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(confirmLabel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString().orEmpty()
                onSave(value)
                dialog.dismiss()
            }
        }
        dialog.show()
    }
}
