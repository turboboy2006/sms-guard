package ir.inod.smsguard

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
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
    private val categories by lazy { CategoryStore(context) }

    private fun activeCategory(id: String, active: Set<String>): String =
        if (id in active) id else categories.disabledDestination(id)
            ?.takeIf { it in active } ?: Cat.OTHER

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
    fun loadThreads(
        scanLimit: Int = Int.MAX_VALUE,
        progressEvery: Int = 0,
        onProgress: ((List<ThreadSummary>) -> Unit)? = null,
        useCache: Boolean = true
    ): List<ThreadSummary> {
        val activeCategories = CategoryStore(context).active().mapTo(HashSet()) { it.id }
        val cached = if (useCache) ThreadCache.read(context) else emptyList()
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
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS
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
                val iStatus = c.getColumnIndex(Telephony.Sms.STATUS)

                // The newest row decides for the whole pass: if the cache
                // already knows it, nothing above it in the list can be new.
                val cacheCurrent = c.moveToFirst() && useCache && ThreadCache.newestMessageId(context) == c.getLong(iId)

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
                        val rawCategory = previous?.categoryId ?: categoryFor(address, body, messageId)
                        val categoryId = activeCategory(rawCategory, activeCategories)
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
                            known = true,
                            delivery = if (type == Telephony.Sms.MESSAGE_TYPE_INBOX) null else
                                deliveryState(type, if (iStatus >= 0) c.getInt(iStatus) else Telephony.Sms.STATUS_NONE)
                        )
                    } else if (unread) {
                        byThread[threadId] = existing.copy(unreadCount = existing.unreadCount + 1)
                    }
                    // Large mailboxes must become useful before the full scan
                    // ends. Emit immutable snapshots from this worker; the UI
                    // can paint them while the same cursor continues older.
                    if (progressEvery > 0 &&
                        (scanned == 500 || scanned % progressEvery == 0)
                    ) {
                        onProgress?.invoke(byThread.values.toList())
                    }
                    if (!c.moveToNext()) break
                }
            }
        } catch (e: Exception) {
            // return whatever was collected
        }

        val fresh = byThread.values.toList()
        if (fresh.isNotEmpty() && scanLimit == Int.MAX_VALUE) {
            ThreadCache.writeUnlessChanged(context, stamp, fresh.map { row ->
                val cachedRaw = cachedById[row.threadId]
                if (row.categoryId == Cat.OTHER && cachedRaw != null &&
                    cachedRaw.messageId == row.messageId && cachedRaw.categoryId !in activeCategories
                ) row.toCached().copy(categoryId = cachedRaw.categoryId) else row.toCached()
            })
        }
        return fresh
    }

    /**
     * The whole mailbox as the cache last saw it, without touching the provider.
     * Used to paint the inbox on the very first frame of a launch.
     */
    fun cachedThreads(): List<ThreadSummary> =
        CategoryStore(context).active().mapTo(HashSet()) { it.id }.let { active ->
            ThreadCache.read(context).map { cached ->
                cached.toSummary().let { row ->
                    if (row.categoryId in active) row else row.copy(categoryId = activeCategory(row.categoryId, active))
                }
            }
        }

    /** Searches the provider directly, so results are not limited to the inbox
     * cache or to the latest message shown for each conversation. */
    fun searchThreads(
        query: String,
        resultLimit: Int = Int.MAX_VALUE,
        categoryId: String? = null,
        since: Long = 0L,
        subscriptionId: Int = -1,
        onProgress: ((List<ThreadSummary>) -> Unit)? = null
    ): List<ThreadSummary> {
        val needle = query.trim()
        val out = ArrayList<ThreadSummary>()
        val activeCategories = CategoryStore(context).active().mapTo(HashSet()) { it.id }
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS,
            "sub_id"
        )
        try {
            val clauses = mutableListOf<String>()
            val args = mutableListOf<String>()
            if (needle.isNotEmpty()) {
                // Also resolve a typed contact name to its saved numbers. The
                // provider itself only knows addresses and message bodies.
                val contactNumbers = runCatching {
                    ContactsIndex.ensure(context)
                    ContactsIndex.search(needle, 20).map { it.digits.takeLast(7) }
                        .filter { it.length == 7 }.distinct()
                }.getOrDefault(emptyList())
                val addressMatches = contactNumbers.joinToString("") { " OR ${Telephony.Sms.ADDRESS} LIKE ?" }
                clauses += "(${Telephony.Sms.BODY} LIKE ? OR ${Telephony.Sms.ADDRESS} LIKE ?$addressMatches)"
                args += "%$needle%"; args += "%$needle%"
                contactNumbers.forEach { args += "%$it%" }
            }
            if (since > 0) { clauses += "${Telephony.Sms.DATE} >= ?"; args += since.toString() }
            if (subscriptionId >= 0) { clauses += "sub_id = ?"; args += subscriptionId.toString() }
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
                args.takeIf { it.isNotEmpty() }?.toTypedArray(),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iThread = c.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val iStatus = c.getColumnIndex(Telephony.Sms.STATUS)
                while (c.moveToNext() && out.size < resultLimit) {
                    val threadId = c.getLong(iThread)
                    val id = c.getLong(iId)
                    val address = c.getString(iAddr) ?: ""
                    val body = c.getString(iBody) ?: ""
                    val category = activeCategory(categoryFor(address, body, id), activeCategories)
                    if (categoryId != null && category != categoryId) continue
                    out += ThreadSummary(
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
                        delivery = if (c.getInt(iType) == Telephony.Sms.MESSAGE_TYPE_INBOX) null else
                            deliveryState(c.getInt(iType),
                                if (iStatus >= 0) c.getInt(iStatus) else Telephony.Sms.STATUS_NONE),
                        riskLabel = if (category == Cat.SUSPICIOUS) {
                            Classifier.riskLabel(context, address, body)
                        } else null
                    )
                    if (out.size == 25 || out.size == 100 || out.size % 300 == 0) {
                        onProgress?.invoke(out.toList())
                    }
                }
            }
        } catch (_: Exception) {
            // An unavailable provider produces an empty result, not a crash.
        }
        return out
    }

    fun loadMessages(threadId: Long, limit: Int = 500): List<SmsMessage> {
        val out = mutableListOf<SmsMessage>()
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS,
            Telephony.Sms.ERROR_CODE,
            "sub_id"
        )
        try {
            resolver.query(
                Telephony.Sms.CONTENT_URI, projection,
                "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val iStatus = c.getColumnIndex(Telephony.Sms.STATUS)
                val iError = c.getColumnIndex(Telephony.Sms.ERROR_CODE)
                val iSub = c.getColumnIndex("sub_id")

                while (out.size < limit && c.moveToNext()) {
                    val id = c.getLong(iId)
                    val address = c.getString(iAddr) ?: ""
                    val body = c.getString(iBody) ?: ""
                    val type = c.getInt(iType)
                    val providerStatus = if (iStatus >= 0) c.getInt(iStatus) else Telephony.Sms.STATUS_NONE
                    out.add(
                        SmsMessage(
                            id = id,
                            address = address,
                            body = body,
                            date = c.getLong(iDate),
                            isIncoming = type == Telephony.Sms.MESSAGE_TYPE_INBOX,
                            categoryId = categoryFor(address, body, id),
                            delivery = deliveryState(type, providerStatus),
                            errorCode = if (iError >= 0) c.getInt(iError) else 0,
                            subscriptionId = if (iSub >= 0) c.getInt(iSub) else -1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return out.asReversed()
    }

    /** Size the initial conversation page so an old search hit is included. */
    fun countMessagesSince(threadId: Long, date: Long): Int = runCatching {
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.DATE} >= ?",
            arrayOf(threadId.toString(), date.toString()), null
        )?.use { it.count } ?: 0
    }.getOrDefault(0)

    /** A bounded preview for the rule builder; it never changes a message. */
    fun countMatches(rule: Rule, limit: Int = 10_000): Int {
        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY)
        var count = 0
        var scanned = 0
        try {
            resolver.query(Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC")?.use { c ->
                val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                while (c.moveToNext() && scanned++ < limit) {
                    if (rule.matches(c.getString(address).orEmpty(), c.getString(body).orEmpty())) count++
                }
            }
        } catch (_: Exception) { }
        return count
    }

    fun messageCount(threadId: Long): Int = try {
        resolver.query(
            Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
            "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), null
        )?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

    /** Candidate messages for the user-triggered one-time AI learning pass. */
    fun aiScanCandidates(limit: Int = 1000): List<SmsMessage> {
        val out = mutableListOf<SmsMessage>()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE
        )
        try {
            resolver.query(
                Telephony.Sms.CONTENT_URI, projection, null, null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                val id = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val type = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                while (c.moveToNext() && out.size < limit) {
                    if (c.getInt(type) != Telephony.Sms.MESSAGE_TYPE_INBOX) continue
                    val sender = c.getString(address).orEmpty()
                    val text = c.getString(body).orEmpty()
                    val local = Classifier.classifyLocal(context, sender, text)
                    if (local.categoryId in setOf(Cat.BANKING, Cat.OTP, Cat.PERSONAL)) continue
                    // Restrict cloud requests to messages worth another opinion.
                    if (local.isSuspicious || local.categoryId == Cat.PROMOTION) {
                        out += SmsMessage(c.getLong(id), sender, text, c.getLong(date), true, local.categoryId)
                    }
                }
            }
        } catch (_: Exception) { }
        return out
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
        known = known,
        delivery = delivery
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

    fun markThreadUnread(threadId: Long) {
        try {
            var latestId = -1L
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.THREAD_ID} = ?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c -> if (c.moveToFirst()) latestId = c.getLong(0) }
            if (latestId < 0) return
            resolver.update(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Sms.READ, 0) },
                "${Telephony.Sms._ID} = ?",
                arrayOf(latestId.toString())
            )
        } catch (_: Exception) {
            // Some providers reject subqueries. The operation is best effort.
        }
    }

    /** Stores an incoming message and returns its row id, or -1 on failure. */
    fun storeIncoming(address: String, body: String, timestamp: Long, subscriptionId: Int = -1): Long {
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
                if (subscriptionId >= 0) put("sub_id", subscriptionId)
            }
            resolver.insert(Telephony.Sms.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    private fun storePending(address: String, body: String, timestamp: Long, subscriptionId: Int): Long {
        return try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, timestamp)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
                put(Telephony.Sms.THREAD_ID, threadIdFor(address))
                if (subscriptionId >= 0) put("sub_id", subscriptionId)
            }
            resolver.insert(Telephony.Sms.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    fun send(address: String, body: String, subscriptionOverride: Int? = null): Boolean {
        if (address.isBlank() || body.isBlank()) return false
        var pendingId = -1L
        return try {
            val now = System.currentTimeMillis()
            val chosenSub = subscriptionOverride ?: (
                SenderStore(context).simFor(address).takeIf { it >= 0 }
                    ?: SettingsStore(context).defaultSimId
                )
            val messageId = storePending(address, body, now, chosenSub)
            if (messageId < 0) return false
            pendingId = messageId
            val sm = smsManager(address, chosenSub)
            val parts = sm.divideMessage(body)
            val sent = ArrayList<PendingIntent>(parts.size)
            val delivered = ArrayList<PendingIntent>(parts.size)
            parts.indices.forEach { part ->
                sent += statusIntent(DeliveryStatusReceiver.ACTION_SENT, messageId, part, parts.size)
                delivered += statusIntent(DeliveryStatusReceiver.ACTION_DELIVERED, messageId, part, parts.size)
            }
            if (parts.size > 1) {
                sm.sendMultipartTextMessage(address, null, parts, sent, delivered)
            } else {
                sm.sendTextMessage(address, null, body, sent[0], delivered[0])
            }
            patchCacheForSentMessage(address, body, now)
            true
        } catch (e: Exception) {
            if (pendingId >= 0) {
                try {
                    resolver.update(
                        Telephony.Sms.CONTENT_URI,
                        ContentValues().apply {
                            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_FAILED)
                        },
                        "${Telephony.Sms._ID} = ?", arrayOf(pendingId.toString())
                    )
                } catch (_: Exception) { }
            }
            false
        }
    }

    private fun statusIntent(action: String, messageId: Long, part: Int, total: Int): PendingIntent {
        val intent = Intent(context, DeliveryStatusReceiver::class.java).apply {
            this.action = action
            putExtra(DeliveryStatusReceiver.EXTRA_MESSAGE_ID, messageId)
            putExtra(DeliveryStatusReceiver.EXTRA_PART, part)
            putExtra(DeliveryStatusReceiver.EXTRA_TOTAL, total)
        }
        val request = ((messageId xor (messageId ushr 32)).toInt() * 31 + part * 2 + if (action == DeliveryStatusReceiver.ACTION_DELIVERED) 1 else 0)
        return PendingIntent.getBroadcast(
            context, request, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun deliveryState(type: Int, status: Int): DeliveryState = when {
        type == Telephony.Sms.MESSAGE_TYPE_INBOX -> DeliveryState.RECEIVED
        type == Telephony.Sms.MESSAGE_TYPE_FAILED -> DeliveryState.FAILED
        type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || type == Telephony.Sms.MESSAGE_TYPE_QUEUED -> DeliveryState.SENDING
        status == Telephony.Sms.STATUS_COMPLETE -> DeliveryState.DELIVERED
        status == Telephony.Sms.STATUS_FAILED -> DeliveryState.FAILED
        else -> DeliveryState.SENT
    }

    private fun smsManager(address: String, explicitSubscription: Int? = null): SmsManager {
        val senderSim = SenderStore(context).simFor(address)
        val chosen = explicitSubscription ?: if (senderSim >= 0) senderSim else SettingsStore(context).defaultSimId
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
        if (chosen < 0) return base
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base.createForSubscriptionId(chosen)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(chosen)
        }
    }

    fun threadIdFor(address: String): Long = try {
        Telephony.Threads.getOrCreateThreadId(context, address)
    } catch (e: Exception) {
        -1L
    }
}
