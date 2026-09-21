package ir.inod.smsguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Long.bitCount

/**
 * Near-duplicate detection without any external model.
 *
 * Bulk senders rarely send byte-identical text; they send variants of one
 * template ("فروش ویژه فروشگاه ما" / "فروش ویژه فروشگاه ما!"). A 64-bit SimHash
 * over character trigrams collapses those variants to almost the same
 * fingerprint, so campaign members can be found with a cheap XOR + popcount.
 *
 * Two things fall out of this:
 *   - a template seen many times from several numbers is a campaign
 *   - once the user marks one member as spam, the whole campaign is known bad,
 *     which catches the next number running the same campaign
 */
object SimHash {

    /** FNV-1a 64. String.hashCode() is only 32 bits, too narrow to be safe here. */
    private fun fnv64(s: String): Long {
        var h = -3750763034362895579L // 0xcbf29ce484222325
        for (c in s) {
            h = h xor c.code.toLong()
            h *= 1099511628211L // 0x100000001b3
        }
        return h
    }

    /** Character trigrams are what make this robust to small wording changes. */
    fun ngrams(text: String, n: Int = 3): List<String> {
        val t = Normalizer.normalize(text).replace(" ", "")
        if (t.length < n) return if (t.isEmpty()) emptyList() else listOf(t)
        val out = ArrayList<String>(t.length)
        for (i in 0..t.length - n) out.add(t.substring(i, i + n))
        return out
    }

    fun of(text: String): Long {
        val v = IntArray(64)
        val grams = ngrams(text)
        if (grams.isEmpty()) return 0L
        for (g in grams) {
            val h = fnv64(g)
            for (i in 0 until 64) {
                if ((h ushr i) and 1L == 1L) v[i]++ else v[i]--
            }
        }
        var out = 0L
        for (i in 0 until 64) if (v[i] > 0) out = out or (1L shl i)
        return out
    }

    fun distance(a: Long, b: Long): Int = bitCount(a xor b)
}

data class Campaign(
    val hash: Long,
    val members: Int,
    val senders: Set<String>,
    val spamFlagged: Boolean,
    val lastSeen: Long
)

class CampaignStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_campaigns", Context.MODE_PRIVATE)

    private class Snapshot(
        val campaigns: MutableList<Campaign>,
        val messageHashes: MutableMap<Long, Long>
    )

    /**
     * The list path asks for a campaign signal on every row, so the parsed
     * document is cached process-wide and dropped on every write.
     */
    private fun snapshot(): Snapshot {
        cache?.let { return it }
        val list = mutableListOf<Campaign>()
        val msgs = mutableMapOf<Long, Long>()
        val raw = prefs.getString(KEY, null)
        if (raw != null) {
            try {
                val root = JSONObject(raw)
                root.optJSONArray("campaigns")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val senders = mutableSetOf<String>()
                        o.optJSONArray("senders")?.let { s ->
                            for (j in 0 until s.length()) senders.add(s.getString(j))
                        }
                        list.add(
                            Campaign(
                                hash = o.optLong("h"),
                                members = o.optInt("n"),
                                senders = senders,
                                spamFlagged = o.optBoolean("spam"),
                                lastSeen = o.optLong("last")
                            )
                        )
                    }
                }
                root.optJSONObject("messages")?.let { obj ->
                    for (k in obj.keys()) msgs[k.toLongOrNull() ?: continue] = obj.getLong(k)
                }
            } catch (e: Exception) {
                // corrupted store: start clean
            }
        }
        return Snapshot(list, msgs).also { cache = it }
    }

    private fun persist(s: Snapshot) {
        val arr = JSONArray()
        for (c in s.campaigns) {
            arr.put(
                JSONObject().apply {
                    put("h", c.hash); put("n", c.members); put("spam", c.spamFlagged)
                    put("last", c.lastSeen)
                    put("senders", JSONArray().apply { c.senders.forEach { put(it) } })
                }
            )
        }
        val msgs = JSONObject()
        for ((id, h) in s.messageHashes) msgs.put(id.toString(), h)
        prefs.edit().putString(
            KEY,
            JSONObject().apply { put("campaigns", arr); put("messages", msgs) }.toString()
        ).apply()
        cache = s
    }

    /**
     * Called once per stored incoming message: fold it into the nearest
     * campaign, or start a new one.
     */
    fun record(messageId: Long, sender: String, body: String, timestamp: Long) {
        val hash = SimHash.of(body)
        if (hash == 0L) return
        val s = snapshot()

        var best: Campaign? = null
        var bestDistance = Int.MAX_VALUE
        for (c in s.campaigns) {
            val d = SimHash.distance(c.hash, hash)
            if (d <= MATCH_DISTANCE && d < bestDistance) {
                best = c
                bestDistance = d
            }
        }

        if (best != null) {
            s.campaigns.remove(best)
            s.campaigns.add(
                best.copy(
                    members = best.members + 1,
                    senders = best.senders + sender,
                    lastSeen = timestamp
                )
            )
        } else {
            s.campaigns.add(Campaign(hash, 1, setOf(sender), false, timestamp))
        }

        s.messageHashes[messageId] = hash
        trim(s)
        persist(s)
    }

    /** The user labelled this message spam: mark its whole campaign. */
    fun markSpam(messageId: Long) {
        val s = snapshot()
        val hash = s.messageHashes[messageId] ?: return
        val idx = s.campaigns.indexOfFirst { SimHash.distance(it.hash, hash) <= MATCH_DISTANCE }
        if (idx < 0) return
        s.campaigns[idx] = s.campaigns[idx].copy(spamFlagged = true)
        persist(s)
    }

    /**
     * Extra suspicion contributed by campaign evidence.
     *
     * A known-bad template is the strongest offline signal available: it is the
     * user's own verdict, generalised to numbers they have never seen.
     */
    fun campaignSignal(body: String): Int {
        val hash = SimHash.of(body)
        if (hash == 0L) return 0
        var best: Campaign? = null
        var bestDistance = Int.MAX_VALUE
        for (c in snapshot().campaigns) {
            val d = SimHash.distance(c.hash, hash)
            if (d <= MATCH_DISTANCE && d < bestDistance) {
                best = c
                bestDistance = d
            }
        }
        val c = best ?: return 0
        return when {
            c.spamFlagged -> 45
            // One template rotating through several numbers is the strongest
            // structural signal short of a user verdict.
            c.senders.size >= 3 && c.members >= 3 -> 30
            c.members >= 5 && c.senders.size >= 2 -> 25
            c.members >= 8 -> 18
            else -> 0
        }
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
        cache = null
    }

    fun allCampaigns(): List<Campaign> = snapshot().campaigns
        .filter { it.members >= 2 }
        .sortedByDescending { it.lastSeen }

    /** Bounded: keep the campaigns seen most recently. */
    private fun trim(s: Snapshot) {
        if (s.campaigns.size > MAX_CAMPAIGNS) {
            val keep = s.campaigns.sortedByDescending { it.lastSeen }.take(MAX_CAMPAIGNS)
            s.campaigns.clear()
            s.campaigns.addAll(keep)
        }
        if (s.messageHashes.size > MAX_MESSAGES) {
            val keep = s.messageHashes.entries.sortedByDescending { it.key }.take(MAX_MESSAGES)
            s.messageHashes.clear()
            keep.forEach { s.messageHashes[it.key] = it.value }
        }
    }

    private companion object {
        const val KEY = "campaigns"

        /** Up to six differing bits still counts as the same template. */
        const val MATCH_DISTANCE = 6
        const val MAX_CAMPAIGNS = 400
        const val MAX_MESSAGES = 2000

        @Volatile
        var cache: Snapshot? = null
    }
}
