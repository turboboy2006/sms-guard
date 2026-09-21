package ir.inod.smsguard

import android.content.Context
import java.io.File

/**
 * One conversation as it was last seen.
 *
 * This is deliberately a separate, flat type instead of a persisted
 * [ThreadSummary]: the cache must not depend on how the classifier or the
 * appearance layer evolve, so it stores only the fields that are expensive to
 * rebuild (the resolved category and the computed risk reason).
 */
data class CachedThread(
    val threadId: Long,
    val messageId: Long,
    val address: String,
    val snippet: String,
    val date: Long,
    val unreadCount: Int,
    val categoryId: String,
    val colorHex: String,
    val riskLabel: String?,
    /** false only for rows written by a background patch that never saw the provider. */
    val known: Boolean = true
) {

    fun toSummary(): ThreadSummary = ThreadSummary(
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
}

/**
 * Persists the inbox between launches.
 *
 * Why this exists: reading and classifying the SMS provider takes seconds on a
 * large mailbox, and paying that on every cold start is what made the app feel
 * slow. The cache is written on a background thread after every successful
 * sync, and read on the very next start so the list is on screen immediately;
 * the provider pass then only has to update what actually changed.
 *
 * The file is line-oriented rather than JSON. A cache is written whole and read
 * whole, it is never queried, and a damaged line can simply be skipped — which
 * is exactly the failure mode a chatty JSON array handles badly.
 */
object ThreadCache {

    private const val FILE_NAME = "inbox-cache.tsv"
    private const val MAGIC = "SMSCACHE1"
    private const val MAX_ROWS = 4000

    /** The separator unit: U+001F, never present in a sender ID or an SMS body. */
    private const val SEP = "\u001F"

    private val lock = Any()
    private var memory: List<CachedThread>? = null
    private var memoryNewestId: Long = -1L

    fun read(context: Context): List<CachedThread> = synchronized(lock) { readLocked(context) }

    /** Caller must already hold [lock]. */
    private fun readLocked(context: Context): List<CachedThread> {
        memory?.let { return it }
        val loaded = runCatching { parse(file(context).readText()) }.getOrDefault(emptyList())
        memory = loaded
        memoryNewestId = loaded.firstOrNull()?.messageId ?: -1L
        return loaded
    }

    /**
     * Row id of the newest cached message. Compared against the provider's first
     * row to decide whether a full re-classification is needed at all.
     */
    fun newestMessageId(context: Context): Long {
        synchronized(lock) { if (memory != null) return memoryNewestId }
        read(context)
        return synchronized(lock) { memoryNewestId }
    }

    fun write(context: Context, threads: List<CachedThread>) {
        synchronized(lock) {
            memory = threads
            memoryNewestId = threads.firstOrNull()?.messageId ?: -1L
        }
        persist(context, threads)
    }

    /**
     * Read-modify-write under one lock.
     *
     * The SMS receiver patches the cache from its own thread while a sync or a
     * background job may be writing the whole list. Doing `read`, mutate, then
     * `write` at the call site loses whichever update landed in between — which
     * shows up as a message that quietly is not in the inbox until the next
     * provider pass. This closes that window.
     */
    fun update(context: Context, transform: (List<CachedThread>) -> List<CachedThread>) {
        val updated = synchronized(lock) {
            val current = readLocked(context)
            val next = transform(current)
            memory = next
            memoryNewestId = next.firstOrNull()?.messageId ?: -1L
            next
        }
        persist(context, updated)
    }

    fun clear(context: Context) {
        synchronized(lock) {
            memory = null
            memoryNewestId = -1L
        }
        runCatching { file(context).delete() }
    }

    fun size(context: Context): Int = read(context).size

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    /**
     * Writes the file, outside the lock so a slow disk cannot stall the SMS
     * receiver. Write beside the real file and swap, so a crash mid-write
     * leaves the previous cache intact instead of a truncated one.
     */
    private fun persist(context: Context, threads: List<CachedThread>) {
        runCatching {
            val target = file(context)
            val temp = File(target.parentFile, "$FILE_NAME.tmp")
            temp.writeText(serialize(threads))
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }
    }

    // ------------------------------------------------------------- serialising

    private fun serialize(threads: List<CachedThread>): String {
        val sb = StringBuilder(MAGIC).append('\n')
        for (t in threads.take(MAX_ROWS)) {
            sb.append(t.threadId).append(SEP)
                .append(t.messageId).append(SEP)
                .append(t.date).append(SEP)
                .append(t.unreadCount).append(SEP)
                .append(t.categoryId).append(SEP)
                .append(t.colorHex).append(SEP)
                .append(if (t.known) "1" else "0").append(SEP)
                .append(escape(t.address)).append(SEP)
                .append(escape(t.snippet)).append(SEP)
                .append(escape(t.riskLabel.orEmpty()))
                .append('\n')
        }
        return sb.toString()
    }

    private fun parse(text: String): List<CachedThread> {
        val out = ArrayList<CachedThread>()
        var first = true
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            if (first) {
                first = false
                if (line.trim() == MAGIC) continue
            }
            val f = line.split(SEP)
            if (f.size < 10) continue
            val threadId = f[0].toLongOrNull() ?: continue
            val messageId = f[1].toLongOrNull() ?: continue
            out.add(
                CachedThread(
                    threadId = threadId,
                    messageId = messageId,
                    address = unescape(f[7]),
                    snippet = unescape(f[8]),
                    date = f[2].toLongOrNull() ?: 0L,
                    unreadCount = f[3].toIntOrNull() ?: 0,
                    categoryId = f[4],
                    colorHex = f[5],
                    riskLabel = unescape(f[9]).ifBlank { null },
                    known = f[6] != "0"
                )
            )
        }
        return out
    }

    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "")

    private fun unescape(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    'n' -> sb.append('\n')
                    '\\' -> sb.append('\\')
                    else -> sb.append(value[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
