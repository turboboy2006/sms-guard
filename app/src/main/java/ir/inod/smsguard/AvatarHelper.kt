package ir.inod.smsguard

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.ContactsContract
import java.io.InputStream

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

    private val photoCache = HashMap<String, Bitmap?>()

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

    /** Contact photo, cached per address. Null when the contact has none. */
    fun photo(context: Context, address: String): Bitmap? {
        if (address.isBlank()) return null
        if (photoCache.containsKey(address)) return photoCache[address]
        val bmp = loadPhoto(context, address)
        photoCache[address] = bmp
        return bmp
    }

    private fun loadPhoto(context: Context, address: String): Bitmap? {
        var cursor: android.database.Cursor? = null
        var stream: InputStream? = null
        try {
            val lookup = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            cursor = context.contentResolver.query(
                lookup, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val contactId = cursor.getLong(0)
                val contactUri = ContentUris.withAppendedId(
                    ContactsContract.Contacts.CONTENT_URI, contactId
                )
                stream = ContactsContract.Contacts
                    .openContactPhotoInputStream(context.contentResolver, contactUri)
                if (stream != null) return BitmapFactory.decodeStream(stream)
            }
        } catch (e: Exception) {
            // READ_CONTACTS missing or provider unavailable
        } finally {
            try {
                stream?.close()
            } catch (e: Exception) {
                // ignore
            }
            cursor?.close()
        }
        return null
    }

    fun clearCache() = photoCache.clear()

    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
}
