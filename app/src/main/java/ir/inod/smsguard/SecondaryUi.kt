package ir.inod.smsguard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView

/** Shared presentation for smaller destinations that are built in Kotlin. */
object SecondaryUi {
    fun px(context: Context, dimension: Int): Int = context.resources.getDimensionPixelSize(dimension)

    fun toolbar(context: Context, title: String, onBack: () -> Unit): MaterialToolbar =
        MaterialToolbar(context).apply {
            this.title = title
            setTitleTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setBackgroundColor(ContextCompat.getColor(context, R.color.card_bg))
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { onBack() }
            minimumHeight = px(context, R.dimen.appbar_height)
        }

    fun search(context: Context): EditText = EditText(context).apply {
        hint = context.getString(R.string.search_hint)
        isSingleLine = true
        setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search, 0, 0, 0)
        compoundDrawableTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.text_secondary))
        compoundDrawablePadding = px(context, R.dimen.space_12)
        val gutter = px(context, R.dimen.gutter)
        setPadding(gutter, px(context, R.dimen.space_8), gutter, px(context, R.dimen.space_8))
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setHintTextColor(ContextCompat.getColor(context, R.color.text_muted))
        textSize = context.resources.getDimension(R.dimen.text_body) / context.resources.displayMetrics.scaledDensity
        background = GradientDrawable().apply {
            cornerRadius = context.resources.getDimension(R.dimen.radius_md)
            setColor(ContextCompat.getColor(context, R.color.card_bg))
            setStroke(px(context, R.dimen.card_stroke), ContextCompat.getColor(context, R.color.divider))
        }
    }

    fun empty(context: Context, icon: Int): TextView = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextAppearance(R.style.TextAppearance_SmsGuard_Body)
        setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0)
        compoundDrawablePadding = px(context, R.dimen.space_16)
        val space = px(context, R.dimen.space_24)
        setPadding(space, space, space, space)
    }

    fun listCard(context: Context): MaterialCardView = MaterialCardView(context).apply {
        radius = context.resources.getDimension(R.dimen.radius_lg)
        cardElevation = 0f
        strokeWidth = px(context, R.dimen.card_stroke)
        strokeColor = ContextCompat.getColor(context, R.color.divider)
        setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_bg))
        layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
            bottomMargin = px(context, R.dimen.space_8)
        }
    }

    fun cardContent(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(px(context, R.dimen.space_16), px(context, R.dimen.space_12),
            px(context, R.dimen.space_16), px(context, R.dimen.space_8))
    }
}
