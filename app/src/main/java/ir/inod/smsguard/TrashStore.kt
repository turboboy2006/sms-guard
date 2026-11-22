package ir.inod.smsguard

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** یک پیام حذف‌شده که برای بازگردانی نگه داشته می‌شود. */
data class TrashedMessage(
    val messageId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val deletedAt: Long,
) {
    val isIncoming: Boolean get() = type == Telephony.Sms.MESSAGE_TYPE_INBOX
}

/**
 * سطل زبالهٔ واقعی برای پیام‌های تکی.
 *
 * پیش از این، حذف یک پیام مستقیماً روی provider انجام می‌شد و پیام برای همیشه
 * از بین می‌رفت؛ «سطل زباله» فقط یک دستهٔ فرستنده بود، بنابراین پیام حذف‌شده
 * هرگز در سطل دیده نمی‌شد. اینجا محتوای پیام پیش از حذف نگه داشته می‌شود تا
 * بازگردانی ممکن باشد. ذخیره‌سازی محلی و بدون شبکه است.
 */
class TrashStore(context: Context) {

    private val appContext = context.applicationContext
    private val file: File get() = File(appContext.filesDir, "trash_messages.json")
    private val lock = Any()

    fun all(): List<TrashedMessage> = synchronized(lock) { read() }

    fun count(): Int = all().size

    fun add(item: TrashedMessage) {
        synchronized(lock) {
            write(read().filterNot { it.messageId == item.messageId } + item)
        }
    }

    fun addAll(items: List<TrashedMessage>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            val ids = items.map { it.messageId }.toSet()
            write(read().filterNot { it.messageId in ids } + items)
        }
    }

    fun remove(messageId: Long): TrashedMessage? {
        synchronized(lock) {
            val items = read()
            val hit = items.firstOrNull { it.messageId == messageId } ?: return null
            write(items.filterNot { it.messageId == messageId })
            return hit
        }
    }

    fun clear() {
        synchronized(lock) { write(emptyList()) }
    }

    /** پاک‌سازی خودکار؛ ۰ یعنی هرگز. */
    fun pruneOlderThan(days: Int) {
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        synchronized(lock) {
            val items = read()
            val kept = items.filter { it.deletedAt >= cutoff }
            if (kept.size != items.size) write(kept)
        }
    }

    /** پیام را به provider برمی‌گرداند (ورودی یا خروجی، بسته به نوع اصلی). */
    fun restore(item: TrashedMessage): Boolean = try {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, item.address)
            put(Telephony.Sms.BODY, item.body)
            put(Telephony.Sms.DATE, item.date)
            put(Telephony.Sms.DATE_SENT, item.date)
            put(Telephony.Sms.TYPE, item.type)
            put(Telephony.Sms.READ, if (item.isIncoming) 1 else 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.LOCKED, 0)
        }
        val uri = appContext.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        if (uri != null) {
            remove(item.messageId)
            true
        } else false
    } catch (t: Throwable) {
        false
    }

    private fun read(): List<TrashedMessage> = try {
        if (!file.exists()) emptyList()
        else {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val body = obj.optString("body")
                if (body.isEmpty()) return@mapNotNull null
                TrashedMessage(
                    messageId = obj.optLong("id"),
                    address = obj.optString("address"),
                    body = body,
                    date = obj.optLong("date"),
                    type = obj.optInt("type", Telephony.Sms.MESSAGE_TYPE_INBOX),
                    deletedAt = obj.optLong("deletedAt"),
                )
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private fun write(items: List<TrashedMessage>) {
        try {
            val array = JSONArray()
            items.forEach { item ->
                array.put(JSONObject().apply {
                    put("id", item.messageId)
                    put("address", item.address)
                    put("body", item.body)
                    put("date", item.date)
                    put("type", item.type)
                    put("deletedAt", item.deletedAt)
                })
            }
            file.writeText(array.toString())
        } catch (t: Throwable) {
            // ذخیره‌سازی سطل نباید هرگز جریان حذف را بشکند.
        }
    }
}
