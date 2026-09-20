package ir.inod.smsguard

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager

/**
 * All interaction with the system SMS provider.
 *
 * The read path resolves a category for every row, so it must stay cheap:
 * [Classifier.resolveRead] does no writes and caches contact lookups.
 */
class SmsRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    /** Categories are resolved once per distinct sender during a load. */
    private val categoryCache = HashMap<String, String>()
    private val colorCache = HashMap<String, String>()

    fun loadThreads(scanLimit: Int = 3000): List<ThreadSummary> {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE
        )
        val byThread = LinkedHashMap<Long, ThreadSummary>()

        try {
            resolver.query(
                Telephony.Sms.CONTENT_URI, projection, null, null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                var scanned = 0
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iThread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)

                while (c.moveToNext() && scanned < scanLimit) {
                    scanned++
                    val threadId = c.getLong(iThread)
                    val existing = byThread[threadId]
                    val type = c.getInt(iType)
                    val unread = type == Telephony.Sms.MESSAGE_TYPE_INBOX && c.getInt(iRead) == 0

                    if (existing == null) {
                        val messageId = c.getLong(iId)
                        val address = c.getString(iAddr) ?: ""
                        val body = c.getString(iBody) ?: ""
                        val categoryId = categoryFor(address, body, messageId)
                        byThread[threadId] = ThreadSummary(
                            threadId = threadId,
                            messageId = messageId,
                            address = address,
                            snippet = body,
                            date = c.getLong(iDate),
                            unreadCount = if (unread) 1 else 0,
                            categoryId = categoryId,
                            colorHex = colorFor(address, categoryId)
                        )
                    } else if (unread) {
                        byThread[threadId] = existing.copy(unreadCount = existing.unreadCount + 1)
                    }
                }
            }
        } catch (e: Exception) {
            // return whatever was collected
        }
        return byThread.values.toList()
    }

    fun loadMessages(threadId: Long, limit: Int = 500): List<SmsMessage> {
        val out = mutableListOf<SmsMessage>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        try {
            resolver.query(
                Telephony.Sms.CONTENT_URI, projection,
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)

                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val address = c.getString(iAddr) ?: ""
                    val body = c.getString(iBody) ?: ""
                    out.add(
                        SmsMessage(
                            id = id,
                            address = address,
                            body = body,
                            date = c.getLong(iDate),
                            isIncoming = c.getInt(iType) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                            categoryId = categoryFor(address, body, id)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return if (out.size > limit) out.takeLast(limit) else out
    }

    private fun categoryFor(address: String, body: String, messageId: Long): String =
        categoryCache.getOrPut(address + "#" + messageId) {
            Classifier.resolveRead(context, address, body, messageId)
        }

    private fun colorFor(address: String, categoryId: String): String =
        colorCache.getOrPut(address + "#" + categoryId) {
            Classifier.colorFor(context, address, categoryId)
        }

    fun addressForThread(threadId: Long): String {
        try {
            resolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS),
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "" }
        } catch (e: Exception) {
            // ignore
        }
        return ""
    }

    fun markThreadRead(threadId: Long) {
        try {
            resolver.update(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Sms.READ, 1) },
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Stores an incoming message and returns its row id, or -1 on failure. */
    fun storeIncoming(address: String, body: String, timestamp: Long): Long {
        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.THREAD_ID, threadIdFor(address))
            }
            resolver.insert(Telephony.Sms.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private fun storeSent(address: String, body: String, timestamp: Long) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.THREAD_ID, threadIdFor(address))
            }
            resolver.insert(Telephony.Sms.CONTENT_URI, values)
        } catch (e: Exception) {
            // ignore
        }
    }

    fun send(address: String, body: String): Boolean {
        if (address.isBlank() || body.isBlank()) return false
        return try {
            val sm = smsManager()
            val parts = sm.divideMessage(body)
            if (parts.size > 1) {
                sm.sendMultipartTextMessage(address, null, parts, null, null)
            } else {
                sm.sendTextMessage(address, null, body, null, null)
            }
            storeSent(address, body, System.currentTimeMillis())
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    fun threadIdFor(address: String): Long = try {
        Telephony.Threads.getOrCreateThreadId(context, address)
    } catch (e: Exception) {
        -1L
    }
}
