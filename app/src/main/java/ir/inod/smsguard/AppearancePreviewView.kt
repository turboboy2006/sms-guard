package ir.inod.smsguard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * A live preview of the inbox row, shown at the top of the appearance screen.
 *
 * Appearance settings are the kind that are impossible to judge from a label —
 * "row spacing 12" means nothing until it is seen — so every control on that
 * screen redraws this one sample row. It reuses [RowStyler], the same code the
 * real list uses, which means the preview cannot drift away from the result.
 */
class AppearancePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val rowContent: LinearLayout
    private val divider: View
    private val avatar: com.google.android.material.card.MaterialCardView
    private val avatarLetter: TextView
    private val title: TextView
    private val snippet: TextView
    private val time: TextView
    private val badge: TextView

    init {
        orientation = VERTICAL
        setPadding(0, 0, 0, 0)

        rowContent = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // The preview must use the same avatar palette as the real list,
        // otherwise the one thing this screen exists to show is wrong.
        val (container, ink) = AvatarHelper.softPair(context.getString(R.string.preview_sender))
        avatar = com.google.android.material.card.MaterialCardView(context).apply {
            radius = 100f
            cardElevation = 0f
            setCardBackgroundColor(container)
            val size = dp(48)
            layoutParams = LayoutParams(size, size)
        }
        avatarLetter = TextView(context).apply {
            text = "ب"
            setTextColor(ink)
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        avatar.addView(
            avatarLetter,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }

        title = TextView(context).apply {
            text = context.getString(R.string.preview_sender)
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTypeface(null, Typeface.BOLD)
            maxLines = 1
        }
        snippet = TextView(context).apply {
            text = context.getString(R.string.preview_body)
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            maxLines = 2
        }
        column.addView(title)
        column.addView(snippet)

        val meta = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.END
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        }
        time = TextView(context).apply {
            text = "۱۲:۳۰"
            setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            gravity = Gravity.END
        }
        badge = TextView(context).apply {
            text = context.getString(R.string.cat_banking)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setTextColor(ContextCompat.getColor(context, R.color.badge_text))
            background = pill(ContextCompat.getColor(context, R.color.badge_bg))
        }
        meta.addView(time)
        meta.addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        rowContent.addView(avatar)
        rowContent.addView(column)
        rowContent.addView(meta)

        divider = View(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
            setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
        }

        addView(rowContent, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(divider)
    }

    /** Redraws the sample row from the current preferences. */
    fun bind(layout: RowLayout) {
        val density = resources.displayMetrics.density
        val compact = layout.style == RowStyle.COMPACT
        val showAvatar = layout.style != RowStyle.FLAT && !compact

        val vertical = (layout.padding * density).toInt()
        val horizontal = (16 * density).toInt()
        rowContent.setPadding(horizontal, vertical, horizontal, vertical)
        rowContent.minimumHeight = (64 * density).toInt()

        // Spacing is shown as a gap under the sample row, and the row inset as
        // a margin around it, so the effect of both is visible rather than
        // described.
        val bottom = (layout.spacing * density).toInt()
        val side = (layout.inset * density).toInt()
        val rowParams = rowContent.layoutParams as? MarginLayoutParams
        if (rowParams != null) {
            rowParams.setMargins(side, 0, side, bottom)
            rowContent.layoutParams = rowParams
        }

        val avatarSize = dp(if (compact) 40 else 48)
        avatar.layoutParams = avatar.layoutParams.apply {
            width = avatarSize
            height = avatarSize
        }
        avatar.visibility = if (showAvatar) VISIBLE else GONE

        title.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (if (compact) 14.5f else 16f) * layout.listFont
        )
        snippet.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (if (compact) 12.5f else 14f) * layout.listFont
        )
        time.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            11.5f * layout.listFont.coerceAtMost(1.3f)
        )
        badge.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            11f * layout.listFont.coerceAtMost(1.3f)
        )

        badge.visibility = if (compact) GONE else VISIBLE
        divider.visibility = if (layout.showDividers && layout.style != RowStyle.CARD) {
            VISIBLE
        } else {
            GONE
        }
        // Keep the divider aligned under the text column, like the real list.
        val dividerParams = divider.layoutParams as? MarginLayoutParams
        if (dividerParams != null) {
            dividerParams.marginStart = dp(76)
            divider.layoutParams = dividerParams
        }

        val background = RowStyler.background(context, layout, 0, false)
        RowStyler.apply(rowContent, background, Color.TRANSPARENT)
    }

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 40f
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
