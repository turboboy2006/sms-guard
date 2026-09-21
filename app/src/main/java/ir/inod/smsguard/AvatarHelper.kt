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

    fun isUnknown(name: String): Boolean = name.trim().isEmpty() || monogram(name) == null

    fun colorFor(key: String): Int {
        val hash = key.hashCode().let { if (it < 0) -it else it }
        return Color.parseColor(COLORS[hash % COLORS.size])
    }

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
