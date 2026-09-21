package ir.inod.smsguard

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.ContactsContract
import java.io.InputStream

/** One phone contact as far as this app cares about it. */
data class ContactEntry(
    val name: String,
    val photoId: Long,
    /** The number as the address book stores it, digits only. */
    val digits: String
)

/**
 * The whole address book, read once and kept in memory.
 *
 * Before this existed, every inbox row performed two `PhoneLookup` queries —
 * one for the name and one for the photo — so a 60-conversation inbox did 120
 * provider round trips while the user was scrolling. Reading the address book in
 * one pass and matching in memory removes all of that work from the frame, and
 * it is also what makes the Contacts tab possible: "is this sender a contact?"
 * becomes a map lookup.
 *
 * Matching happens on digits, not on strings. A number stored as
 * `+98 912 345 6789` and a message arriving from `09123456789` are the same
 * person, and comparing the last ten digits is what makes them agree.
 */
object ContactsIndex {

    /** Matching the last 7 digits is enough to link a formatted sender ID. */
    private const val SUFFIX = 7

    private val lock = Any()

    @Volatile
    private var index: Index? = null

    @Volatile
    private var builtAt = 0L

    /** A contact added while the app is open shows up within this window. */
    private const val TTL_MS = 10 * 60 * 1000L

    class Index(
        /** last 10 digits -> contact */
        val byNumber: Map<String, ContactEntry>,
        /** last 7 digits -> contact, for senders the provider formats differently */
        val bySuffix: Map<String, ContactEntry>
    ) {
        val isEmpty: Boolean get() = byNumber.isEmpty()
    }

    /** Builds the index if it is missing or stale. Safe to call from any thread. */
    fun ensure(context: Context): Index {
        index?.let { if (System.currentTimeMillis() - builtAt < TTL_MS) return it }
        synchronized(lock) {
            index?.let { if (System.currentTimeMillis() - builtAt < TTL_MS) return it }
            val built = build(context.applicationContext)
            index = built
            builtAt = System.currentTimeMillis()
            return built
        }
    }

    fun invalidate() {
        synchronized(lock) {
            index = null
            builtAt = 0L
            photoCache.clear()
        }
    }

    private fun lookup(context: Context): Index? = try {
        ensure(context)
    } catch (e: Exception) {
        null
    }

    fun entryFor(context: Context, address: String): ContactEntry? {
        val idx = lookup(context) ?: return null
        return match(idx, address)
    }

    /**
     * True only when the address book is already in memory and still fresh.
     *
     * Used by anything that runs on the main thread: reading the address book is
     * a background job here, and if it has not finished yet the right answer is
     * "not yet", not a frozen frame.
     */
    fun isReady(): Boolean {
        val current = index ?: return false
        return !current.isEmpty && System.currentTimeMillis() - builtAt < TTL_MS
    }

    /** The matching entry without ever building the index. */
    fun readyEntryFor(address: String): ContactEntry? {
        val idx = index ?: return null
        return match(idx, address)
    }

    fun isKnownContact(address: String): Boolean = readyEntryFor(address) != null

    fun nameFor(context: Context, address: String): String? = entryFor(context, address)?.name

    /** True when the sender exists in the phone's address book. */
    fun isContact(context: Context, address: String): Boolean = entryFor(context, address) != null

    fun size(context: Context): Int = lookup(context)?.byNumber?.size ?: 0

    /** True when the READ_CONTACTS permission is held and the book is not empty. */
    fun isAvailable(context: Context): Boolean = lookup(context)?.isEmpty == false

    /**
     * Whether a *message body* names one of the stored contacts.
     *
     * Used for the "a contact was mentioned" signal, so it demands a full
     * number: matching a 7-digit tail inside free text would fire on any long
     * reference number.
     */
    fun mentionsContact(context: Context, body: String): Boolean {
        val idx = lookup(context) ?: return false
        if (idx.isEmpty) return false
        for (token in phoneTokens(body)) {
            if (idx.byNumber.containsKey(digitsOf(token).takeLast(10))) return true
        }
        return false
    }

    // --------------------------------------------------------------- matching

    private fun match(idx: Index, address: String): ContactEntry? {
        val digits = digitsOf(address)
        if (digits.length >= 7) {
            idx.byNumber[digits.takeLast(10)]?.let { return it }
            if (digits.length < 10) idx.bySuffix[digits.takeLast(SUFFIX)]?.let { return it }
        }
        // SIP addresses and other non-numeric senders never match a contact.
        return null
    }

    private fun digitsOf(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) if (c.isDigit()) sb.append(c)
        return sb.toString()
    }

    private fun phoneTokens(body: String): List<String> =
        Regex("\\+?\\d[\\d\\s\\-()]{9,20}\\d").findAll(body).map { it.value }.toList()

    // ------------------------------------------------------------------ photos

    private val photoCache = HashMap<Long, Bitmap?>()

    /** A contact photo, or null when the contact has none or cannot be read. */
    fun photo(context: Context, address: String): Bitmap? {
        val entry = entryFor(context, address) ?: return null
        if (entry.photoId <= 0) return null
        synchronized(photoCache) { photoCache[entry.photoId]?.let { return it } }
        val bitmap = loadPhoto(context, entry.photoId)
        synchronized(photoCache) { photoCache[entry.photoId] = bitmap }
        return bitmap
    }

    private fun loadPhoto(context: Context, contactId: Long): Bitmap? {
        var stream: InputStream? = null
        try {
            val uri = ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId)
            stream = ContactsContract.Contacts
                .openContactPhotoInputStream(context.contentResolver, uri)
            if (stream != null) return BitmapFactory.decodeStream(stream)
        } catch (e: Exception) {
            // No READ_CONTACTS, or the photo is unreadable.
        } finally {
            try {
                stream?.close()
            } catch (e: Exception) {
                // ignore
            }
        }
        return null
    }

    // ------------------------------------------------------------------- build

    private fun build(context: Context): Index {
        val byNumber = HashMap<String, ContactEntry>(256)
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_ID
        )
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )
            if (cursor == null) return Index(emptyMap(), emptyMap())

            val iName = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val iNumber = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val iPhoto = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_ID)

            while (cursor.moveToNext()) {
                val name = cursor.getString(iName)?.trim().orEmpty()
                val digits = digitsOf(cursor.getString(iNumber).orEmpty())
                if (name.isEmpty() || digits.length < SUFFIX) continue
                val key = digits.takeLast(10)
                // A duplicate number in two contacts: the first one wins, and
                // re-reading the same contact never overwrites a filled entry.
                val existing = byNumber[key]
                if (existing == null || existing.name.isBlank()) {
                    byNumber[key] = ContactEntry(name, cursor.getLong(iPhoto), digits)
                }
            }
        } catch (e: Exception) {
            // Missing permission: an empty index is a valid state, not an error.
            return Index(emptyMap(), emptyMap())
        } finally {
            cursor?.close()
        }

        // Only needed when a sender arrives shorter than ten digits.
        val bySuffix = HashMap<String, ContactEntry>(byNumber.size * 2)
        for (entry in byNumber.values) {
            if (entry.digits.length >= SUFFIX) {
                bySuffix.putIfAbsent(entry.digits.takeLast(SUFFIX), entry)
            }
        }
        return Index(byNumber, bySuffix)
    }
}
