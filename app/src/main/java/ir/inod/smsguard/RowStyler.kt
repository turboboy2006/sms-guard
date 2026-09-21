package ir.inod.smsguard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import androidx.core.content.ContextCompat

/**
 * The visual furniture of one inbox row.
 *
 * Every background is built in code rather than shipped as a drawable because
 * the settings screen lets the user pick one of ten looks and tune its corner
 * radius, padding and spacing live; a static XML per combination would be
 * dozens of files that all say the same thing.
 *
 * All of it is cheap: a handful of GradientDrawables per bind, and nothing is
 * allocated for rows that are only being re-bound.
 */
object RowStyler {

    /** Radius of the "pill" look, in dp. */
    private const val PILL_RADIUS = 22

    /** The Accent look draws a solid bar; the adapter needs to know how wide. */
    const val STRIPE_DP = 4

    /**
     * Background for one row, or null for the classic look, where the row is a
     * flat surface closed by a hairline divider.
     */
    fun background(
        context: Context,
        layout: RowLayout,
        position: Int,
        unread: Boolean
    ): Drawable? {
        val density = context.resources.displayMetrics.density
        val radius = layout.bubbleRadius * density
        val unreadColor = ContextCompat.getColor(context, R.color.unread_bg)
        val surface = ContextCompat.getColor(context, R.color.surface_elevated)
        val sunken = ContextCompat.getColor(context, R.color.surface_sunken)
        val border = ContextCompat.getColor(context, R.color.border_soft)

        return when (layout.style) {
            RowStyle.CARD -> InsetDrawable(
                rounded(when {
                    unread -> unreadColor
                    else -> surface
                }, radius, 1, border),
                dp(density, 8), dp(density, 4), dp(density, 8), dp(density, 4)
            )

            RowStyle.SOFT -> InsetDrawable(
                rounded(if (unread) unreadColor else sunken, radius),
                dp(density, 8), dp(density, 3), dp(density, 8), dp(density, 3)
            )

            RowStyle.BUBBLE -> rounded(
                if (unread) unreadColor else surface,
                if (radius <= 0f) 2f else radius,
                1, border
            )

            RowStyle.PILL -> rounded(
                if (unread) unreadColor else sunken,
                PILL_RADIUS * density
            )

            RowStyle.OUTLINE -> rounded(
                if (unread) unreadColor else Color.TRANSPARENT,
                radius, 1, border
            )

            RowStyle.STRIPED -> rounded(
                when {
                    unread -> unreadColor
                    position % 2 == 1 -> sunken
                    else -> Color.TRANSPARENT
                },
                0f
            )

            // Accent, Compact, Flat and Classic all keep the surface plain; the
            // coloured bar, the avatar and the divider do the distinguishing.
            else -> if (unread) rounded(unreadColor, 0f) else null
        }
    }

    /** Colour of the Accent look's bar: the sender's own colour when it has one. */
    fun accentColor(context: Context, colorHex: String?): Int =
        colorHex?.let {
            try {
                Color.parseColor(it)
            } catch (e: Exception) {
                null
            }
        } ?: ContextCompat.getColor(context, R.color.colorPrimary)

    /**
     * Writes the background of a row.
     *
     * `setBackgroundColor` on every bind would defeat the recycled-view cache
     * and repaint the row on each scroll step, so the previous value is read
     * first and an unchanged row is left alone.
     */
    fun apply(view: android.view.View, drawable: Drawable?, color: Int?) {
        when {
            drawable != null -> if (view.background !== drawable) view.background = drawable
            color != null && color != Color.TRANSPARENT -> {
                if (view.background != null) view.background = null
                view.setBackgroundColor(color)
            }
            else -> if (view.background != null) view.background = null
        }
    }

    private fun rounded(
        fill: Int,
        radius: Float,
        stroke: Int = 0,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = if (radius <= 0f) 0f else radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    private fun dp(density: Float, value: Int): Int = (value * density).toInt()
}
