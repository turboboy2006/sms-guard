package ir.inod.smsguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/**
 * Conversation avatars, matching the familiar messaging pattern:
 *
 *   - a contact photo when the address has one
 *   - otherwise a coloured circle carrying the first letter of the stored name
 *   - otherwise a blank silhouette for unknown senders
 *
 * The circle always sits on the *start* side, so RTL layouts mirror it to the
 * right automatically.
 */
object AvatarHelper {

    /** Monogram palette, chosen so white text stays readable on every entry. */
    private val COLORS = listOf(
        "#7B1FA2", "#F9A825", "#D81B60", "#E64A19", "#3949AB",
        "#00897B", "#5E35B1", "#C2185B", "#00838F", "#43A047"
    )

    private val GRAY = "#8A9BB0"

    /**
     * First *letter* of the stored name, or null when there is nothing useful.
     * A sender ID such as "20009000" deliberately yields null: a "2" would be
     * noise, and the silhouette reads better.
     */
    fun monogram(name: String): String? {
        val first = name.trim().firstOrNull { it.isLetter() } ?: return null
        return first.toString().uppercase()
    }

    /**
     * A soft, deterministic pair for a monogram: a pastel container and a deep
     * text tone from the same hue family.
     *
     * Soft tints rather than saturated fills because every sender that is not a
     * known brand lands here, and a list of twenty strong-coloured circles reads
     * as noise. The hash makes a name keep the same colour forever, which is
     * what lets a reader recognise a conversation before reading it.
     */
    private val MONOGRAM_PASTELS = listOf(
        "#DBEAFE" to "#1E40AF",
        "#DCFCE7" to "#166534",
        "#FEF3C7" to "#92400E",
        "#FCE7F3" to "#9D174D",
        "#EDE9FE" to "#5B21B6",
        "#CFFAFE" to "#155E75",
        "#FFEDD5" to "#9A3412",
        "#F1F5F9" to "#334155"
    )

    fun softPair(key: String): Pair<Int, Int> {
        val entry = MONOGRAM_PASTELS[indexFor(key, MONOGRAM_PASTELS.size)]
        return Color.parseColor(entry.first) to Color.parseColor(entry.second)
    }

    /**
     * A non-negative bucket for a key.
     *
     * `abs(hashCode())` is wrong: for `Int.MIN_VALUE` the negation overflows and
     * stays negative, which indexes the palette out of bounds. `floorMod` cannot.
     */
    private fun indexFor(key: String, size: Int): Int = Math.floorMod(key.hashCode(), size)

    fun isUnknown(name: String): Boolean = name.trim().isEmpty() || monogram(name) == null

    fun colorFor(key: String): Int = Color.parseColor(COLORS[indexFor(key, COLORS.size)])

    fun placeholderColor(): Int = Color.parseColor(GRAY)

    /**
     * Contact photo, or null when the contact has none.
     *
     * The lookup and the bitmap cache both live in [ContactsIndex]; keeping the
     * cache there means a photo is fetched once even though three screens ask
     * for it.
     */
    fun photo(context: Context, address: String): Bitmap? = ContactsIndex.photo(context, address)

    fun clearCache() = ContactsIndex.invalidate()

    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
