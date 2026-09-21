package ir.inod.smsguard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Message bubble backgrounds.
 *
 * Five treatments are offered because the bubble is the single most visible
 * surface in the app and there is no one right answer: some people want the
 * outgoing message to shout, others want both sides quiet and a border to do
 * the work.
 *
 * The default is the calm pairing from the reference — a neutral grey incoming
 * bubble and a light blue outgoing one — because both sides then sit inside the
 * same palette as the rest of the app instead of introducing a second one.
 */
object MessageStyler {

    const val TEXT_PADDING_DP = 11

    fun background(
        context: Context,
        style: String,
        radiusDp: Int,
        outgoing: Boolean
    ): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val radius = radiusDp * density
        val border = ContextCompat.getColor(context, R.color.border_soft)
        val accent = ThemePrefs(context).accentColor()
        val theme = ThemePrefs(context)
        val alpha = if (theme.hasWallpaper()) (255 * theme.surfaceOpacity / 100f).toInt() else 255
        val surface = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.card_bg), alpha)
        val accentSoft = ColorUtils.blendARGB(surface, accent, 0.16f)

        return when (style) {
            MessageStyle.CONTRAST -> rounded(
                fill = ColorUtils.setAlphaComponent(ContextCompat.getColor(context,
                    if (outgoing) R.color.bubble_outgoing else R.color.card_bg), alpha),
                radius = radius,
                stroke = if (outgoing) 0 else 1,
                strokeColor = border
            )

            MessageStyle.OUTLINE -> rounded(
                fill = Color.TRANSPARENT,
                radius = radius,
                stroke = 1,
                strokeColor = if (outgoing) {
                    ThemePrefs(context).accentColor()
                } else {
                    border
                }
            )

            MessageStyle.SOFT -> rounded(
                fill = ColorUtils.setAlphaComponent(ContextCompat.getColor(context,
                    if (outgoing) R.color.blue_100 else R.color.surface_sunken), alpha),
                radius = radius
            )

            MessageStyle.CLEAN -> rounded(
                fill = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.surface_elevated), alpha),
                radius = radius,
                stroke = 1,
                strokeColor = ContextCompat.getColor(context, R.color.divider)
            )

            else -> rounded(
                fill = ColorUtils.setAlphaComponent(if (outgoing) accentSoft else ContextCompat.getColor(
                    context, R.color.bubble_incoming
                ), alpha),
                radius = radius
            )
        }
    }

    fun textColor(context: Context, outgoing: Boolean): Int = ContextCompat.getColor(
        context,
        if (outgoing) R.color.bubble_out_text else R.color.bubble_in_text
    )

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
}
