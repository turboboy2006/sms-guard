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

    /**
     * Loads the conversation list, reusing [ThreadCache] for everything that has
     * not changed.
     *
     * The provider is still the source of truth — the cache only removes the
     * repeated work. When the newest message in the mailbox is the same one the
     * cache recorded, every row keeps its stored category and risk label, so a
     * relaunch costs one cursor pass instead of one classification per sender.
     * Any message the classifier has never seen is scored normally.
     *
     * @return the rows, newest first, and whether anything changed since the
     *         previous call (the caller uses that to skip a re-render).
     */
    fun loadThreads(scanLimit: Int = Int.MAX_VALUE): List<ThreadSummary> {
        val cached = ThreadCache.read(context)
        val cachedById = HashMap<Long, CachedThread>(cached.size * 2)
        for (row in cached) cachedById[row.threadId] = row
        // Taken before the scan: if the receiver patches in a message while the
        // provider is being read, that newer row must not be overwritten here.
        val stamp = ThreadCache.stateStamp(context)

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

                // The newest row decides for the whole pass: if the cache
                // already knows it, nothing above it in the list can be new.
                val cacheCurrent = c.moveToFirst() && ThreadCache.newestMessageId(context) == c.getLong(iId)

                while (!c.isAfterLast && scanned < scanLimit) {
                    scanned++
                    val threadId = c.getLong(iThread)
                    val existing = byThread[threadId]
                    val type = c.getInt(iType)
                    val unread = type == Telephony.Sms.MESSAGE_TYPE_INBOX && c.getInt(iRead) == 0

                    if (existing == null) {
                        val messageId = c.getLong(iId)
                        val address = c.getString(iAddr) ?: ""
                        val body = c.getString(iBody) ?: ""
                        val previous = if (cacheCurrent) cachedById[threadId] else null
                        val categoryId = previous?.categoryId
                            ?: categoryFor(address, body, messageId)
                        byThread[threadId] = ThreadSummary(
                            threadId = threadId,
                            messageId = messageId,
                            address = address,
                            snippet = body,
                            date = c.getLong(iDate),
                            unreadCount = if (unread) 1 else 0,
                            categoryId = categoryId,
                            colorHex = previous?.colorHex ?: colorFor(address, categoryId),
                            riskLabel = previous?.riskLabel
                                ?: if (categoryId == Cat.SUSPICIOUS) {
                                    Classifier.riskLabel(context, address, body)
                                } else {
                                    null
                                },
                            known = true
                        )
                    } else if (unread) {
                        byThread[threadId] = existing.copy(unreadCount = existing.unreadCount + 1)
                    }
                    if (!c.moveToNext()) break
                }
            }
        } catch (e: Exception) {
            // return whatever was collected
        }

        val fresh = byThread.values.toList()
        if (fresh.isNotEmpty()) {
            ThreadCache.writeUnlessChanged(context, stamp, fresh.map { it.toCached() })
        }
        return fresh
    }

    /**
     * The whole mailbox as the cache last saw it, without touching the provider.
     * Used to paint the inbox on the very first frame of a launch.
     */
    fun cachedThreads(): List<ThreadSummary> =
        ThreadCache.read(context).map { it.toSummary() }

    /** Searches the provider directly, so results are not limited to the inbox
     * cache or to the latest message shown for each conversation. */
    fun searchThreads(query: String, resultLimit: Int = 500): List<ThreadSummary> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val out = LinkedHashMap<Long, ThreadSummary>()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ,
            Telephony.Sms.TYPE
        )
        try {
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?",
                arrayOf("%$needle%", "%$needle%"),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iThread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                while (c.moveToNext() && out.size < resultLimit) {
                    val threadId = c.getLong(iThread)
                    if (out.containsKey(threadId)) continue
                    val id = c.getLong(iId)
                    val address = c.getString(iAddr) ?: ""
                    val body = c.getString(iBody) ?: ""
                    val category = categoryFor(address, body, id)
                    out[threadId] = ThreadSummary(
                        threadId = threadId,
                        messageId = id,
                        address = address,
                        snippet = body,
                        date = c.getLong(iDate),
                        unreadCount = if (
                            c.getInt(iType) == Telephony.Sms.MESSAGE_TYPE_INBOX &&
                            c.getInt(iRead) == 0
                        ) 1 else 0,
                        categoryId = category,
                        colorHex = colorFor(address, category),
                        riskLabel = if (category == Cat.SUSPICIOUS) {
                            Classifier.riskLabel(context, address, body)
                        } else null
                    )
                }
            }
        } catch (_: Exception) {
            // An unavailable provider produces an empty result, not a crash.
        }
        return out.values.toList()
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

    /**
     * Called from the SMS receiver for a single new message, so the stored inbox
     * is already correct the next time the app is opened — even if the app
     * itself is never launched in between.
     */
    fun patchCacheForNewMessage(
        address: String,
        body: String,
        date: Long,
        messageId: Long = -1L
    ) {
        try {
            val threadId = threadIdFor(address)
            if (threadId < 0) return
            val categoryId = categoryFor(address, body, messageId)
            ThreadCache.update(context) { current ->
                val updated = current.toMutableList()
                val index = updated.indexOfFirst { it.threadId == threadId }
                val previous = if (index >= 0) updated[index] else null
                val row = CachedThread(
                    threadId = threadId,
                    messageId = messageId,
                    address = address,
                    snippet = body,
                    date = date,
                    unreadCount = 1,
                    categoryId = categoryId,
                    colorHex = colorFor(address, categoryId),
                    riskLabel = if (categoryId == Cat.SUSPICIOUS) {
                        Classifier.riskLabel(context, address, body)
                    } else {
                        null
                    },
                    // keyed on the real row id when the provider gave us one
                    known = messageId >= 0
                )
                if (index >= 0) updated[index] = row else updated.add(0, row)
                // A sender can be promoted into a category that remembers it,
                // in which case the rest of its rows belong in the new place too.
                if (previous != null && previous.categoryId != categoryId) {
                    for (i in updated.indices) {
                        if (updated[i].threadId == threadId) {
                            updated[i] = updated[i].copy(categoryId = categoryId)
                        }
                    }
                }
                updated
            }
        } catch (e: Exception) {
            // A cache miss is never worth crashing a receiver over.
        }
    }

    /** Keeps the inbox order right after the user sends a message. */
    fun patchCacheForSentMessage(address: String, body: String, date: Long) {
        try {
            val threadId = threadIdFor(address)
            if (threadId < 0) return
            ThreadCache.update(context) { current ->
                val updated = current.toMutableList()
                val index = updated.indexOfFirst { it.threadId == threadId }
                if (index < 0) return@update current
                updated[index] = updated[index].copy(
                    snippet = body,
                    date = date,
                    unreadCount = 0
                )
                val row = updated.removeAt(index)
                updated.add(0, row)
                updated
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Cheap per-row resolution.
     *
     * The expensive classification is memoised per *address*, because a
     * conversation has one sender. Doing a full pass per row (normalisation
     * plus roughly twenty regexes) pinned the CPU and caused an ANR.
     */
    private fun categoryFor(address: String, body: String, messageId: Long): String {
        if (messageId >= 0) Classifier.overrideFor(context, messageId)?.let { return it }
        return categoryCache.getOrPut(address) {
            Classifier.senderOrLocalCategory(context, address, body)
        }
    }

    private fun colorFor(address: String, categoryId: String): String =
        colorCache.getOrPut(address + "#" + categoryId) {
            Classifier.colorFor(context, address, categoryId)
        }

    private fun ThreadSummary.toCached(): CachedThread = CachedThread(
        threadId = threadId,
        messageId = messageId,
        address = address,
        snippet = snippet,
        date = date,
        unreadCount = unreadCount,
        categoryId = categoryId,
        colorHex = colorHex,
        riskLabel = riskLabel,
        known = known
    )


    /**
     * Removes one message from the provider for good.
     *
     * There is deliberately no undo for this: a real deletion is the only
     * irreversible action in the app, which is why every call site confirms
     * first and why "trash" exists as a reversible staging step.
     */
    fun deleteMessage(messageId: Long): Boolean = try {
        resolver.delete(
            Telephony.Sms.CONTENT_URI,
            "${Telephony.Sms._ID} = ?",
            arrayOf(messageId.toString())
        ) > 0
    } catch (e: Exception) {
        false
    }

    /** Removes a whole conversation from the provider for good. */
    fun deleteThread(threadId: Long): Boolean = try {
        resolver.delete(
            Telephony.Sms.CONTENT_URI,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString())
        ) > 0
    } catch (e: Exception) {
        false
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
            val now = System.currentTimeMillis()
            storeSent(address, body, now)
            patchCacheForSentMessage(address, body, now)
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
