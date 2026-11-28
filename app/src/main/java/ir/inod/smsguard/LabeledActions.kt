package ir.inod.smsguard

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * دکمه‌های نوار بالا، به همان سبک نوار انتخاب پیام: آیکن بالا، نام ریز زیرش.
 *
 * نوار بالای برنامه فقط آیکن داشت و از خود آیکن معلوم نبود کدام «حذف» است و
 * کدام «سطل زباله»، یا آن قلم کجاست. اینجا هر آیکن به یک دکمهٔ کوچک تبدیل
 * می‌شود تا زبان بصری نوار انتخاب، در بالای صفحه هم تکرار شود.
 */
object LabeledActions {

    /**
     * آیتم منو را به یک دکمهٔ برچسب‌دار تبدیل می‌کند.
     * `onClick` همان کاری را می‌کند که لمس آن آیتم در منو می‌کرد.
     */
    fun attach(
        context: Context,
        item: MenuItem,
        iconRes: Int,
        labelRes: Int,
        tint: Int,
        onClick: () -> Unit
    ) {
        item.actionView = build(context, iconRes, context.getString(labelRes), tint, onClick)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
    }

    fun build(
        context: Context,
        iconRes: Int,
        label: CharSequence,
        tint: Int,
        onClick: () -> Unit
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val ripple = TypedValue()
        context.theme.resolveAttribute(
            android.R.attr.selectableItemBackgroundBorderless, ripple, true
        )

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(5), dp(3), dp(5), dp(3))
            isClickable = true
            isFocusable = true
            if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
            contentDescription = label
            setOnClickListener { onClick() }
        }
        column.addView(ImageView(context).apply {
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(21), dp(21)))
        column.addView(TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(tint)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(1) })
        return column
    }
}
