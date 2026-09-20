package ir.inod.smsguard

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager

/**
 * All interaction with the system SMS provider lives here.
 *
 * As the default SMS app this app is responsible for writing incoming messages
 * into the provider itself: the platform does not do it for us.
 */
class SmsRepository(private val context: Context) {

    private val resolver get() = context.contentResolver

    // ---------------------------------------------------------------- reading

    /**
     * Builds the conversation list by paging the SMS table newest-first and
     * grouping rows by thread_id. The first row seen for a thread is therefore
     * the newest message, which becomes the snippet.
     */
    fun loadThreads(scanLimit: Int = 4000): List<ThreadSummary> {
        val projection = arrayOf(
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
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                var scanned = 0
                val iThread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)

                while (c.moveToNext() && scanned < scanLimit) {
                    scanned++
                    val threadId = c.getLong(iThread)
                    val address = c.getString(iAddr) ?: ""
                    val body = c.getString(iBody) ?: ""
                    val date = c.getLong(iDate)
                    val read = c.getInt(iRead)
                    val type = c.getInt(iType)

                    val existing = byThread[threadId]
                    if (existing == null) {
                        byThread[threadId] = ThreadSummary(
                            threadId = threadId,
                            address = address,
                            snippet = body,
                            date = date,
                            unreadCount = if (isUnreadInbox(type, read)) 1 else 0
                        )
                    } else if (isUnreadInbox(type, read)) {
                        byThread[threadId] = existing.copy(unreadCount = existing.unreadCount + 1)
                    }
                }
            }
        } catch (e: Exception) {
            // Return whatever was collected instead of crashing the UI.
        }

        return byThread.values.toList()
    }

    private fun isUnreadInbox(type: Int, read: Int): Boolean =
        type == Telephony.Sms.MESSAGE_TYPE_INBOX && read == 0

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
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)

                while (c.moveToNext()) {
                    val type = c.getInt(iType)
                    out.add(
                        SmsMessage(
                            id = c.getLong(iId),
                            address = c.getString(iAddr) ?: "",
                            body = c.getString(iBody) ?: "",
                            date = c.getLong(iDate),
                            isIncoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return if (out.size > limit) out.takeLast(limit) else out
    }

    fun addressForThread(threadId: Long): String {
        try {
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                if (c.moveToFirst()) return c.getString(0) ?: ""
            }
        } catch (e: Exception) {
            // ignore
        }
        return ""
    }

    fun markThreadRead(threadId: Long) {
        try {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            resolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString())
            )
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------------------------------------------------------------- writing

    /**
     * Stores an incoming message and returns the thread it landed in,
     * or -1 when it could not be stored.
     */
    fun storeIncoming(address: String, body: String, timestamp: Long): Long {
        return try {
            val threadId = threadIdFor(address)
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.DATE_SENT, timestamp)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.THREAD_ID, threadId)
            }
            resolver.insert(Telephony.Sms.CONTENT_URI, values)
            threadId
        } catch (e: Exception) {
            -1L
        }
    }

    private fun storeSent(address: String, body: String, timestamp: Long) {
        try {
            val threadId = threadIdFor(address)
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.THREAD_ID, threadId)
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

    /** Resolves (creating if needed) the provider thread id for a raw address. */
    fun threadIdFor(address: String): Long {
        return try {
            Telephony.Threads.getOrCreateThreadId(context, address)
        } catch (e: Exception) {
            -1L
        }
    }
}
