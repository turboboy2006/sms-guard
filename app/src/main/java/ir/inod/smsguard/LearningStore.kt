package ir.inod.smsguard

import android.content.Context
import org.json.JSONObject
import kotlin.math.ln

/**
 * On-device learning. Nothing here leaves the phone and no external model is
 * involved: the app improves only from the user's own corrections.
 *
 * Two independent things are learned:
 *   1. a behavioural profile per sender — volume, burst, spam/ham ratio
 *   2. a log-odds weight per token, from messages the user actually labelled
 */

/** Behavioural history for one sender address. */
data class SenderProfile(
    val address: String,
    val total: Int = 0,
    val spam: Int = 0,
    val ham: Int = 0,
    val firstSeen: Long = 0L,
    val lastSeen: Long = 0L,
    val burst: Int = 1
) {
    /** 0..1 — how consistently this sender produces unwanted mail. */
    val reputation: Double
        get() = if (spam + ham == 0) 0.0 else spam.toDouble() / (spam + ham)

    /** The user has told us repeatedly this sender is fine. */
    val trusted: Boolean get() = ham >= 3 && reputation < 0.25

    /** The user has told us repeatedly this sender is junk. */
    val hostile: Boolean get() = spam >= 4 && reputation > 0.75
}

class SenderProfileStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_profiles", Context.MODE_PRIVATE)

    fun snapshot(): Map<String, SenderProfile> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        val out = HashMap<String, SenderProfile>()
        try {
            val root = JSONObject(raw)
            for (addr in root.keys()) {
                val o = root.getJSONObject(addr)
                out[addr] = SenderProfile(
                    address = addr,
                    total = o.optInt("total"),
                    spam = o.optInt("spam"),
                    ham = o.optInt("ham"),
                    firstSeen = o.optLong("first"),
                    lastSeen = o.optLong("last"),
                    burst = o.optInt("burst", 1)
                )
            }
        } catch (e: Exception) {
            // corrupted store: start clean
        }
        return out
    }

    private fun write(map: Map<String, SenderProfile>) {
        val root = JSONObject()
        for ((addr, p) in map) {
            root.put(
                addr,
                JSONObject().apply {
                    put("total", p.total); put("spam", p.spam); put("ham", p.ham)
                    put("first", p.firstSeen); put("last", p.lastSeen); put("burst", p.burst)
                }
            )
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    /** Called for every stored incoming message. */
    fun recordIncoming(address: String, timestamp: Long) {
        if (address.isBlank()) return
        val map = snapshot().toMutableMap()
        val old = map[address]
        val burst = if (old != null && timestamp - old.lastSeen <= BURST_WINDOW_MS) {
            old.burst + 1
        } else {
            1
        }
        map[address] = SenderProfile(
            address = address,
            total = (old?.total ?: 0) + 1,
            spam = old?.spam ?: 0,
            ham = old?.ham ?: 0,
            firstSeen = old?.firstSeen?.takeIf { it > 0 } ?: timestamp,
            lastSeen = timestamp,
            burst = burst
        )
        trim(map)
        write(map)
    }

    /** Called when the user confirms or rejects a message from this sender. */
    fun recordFeedback(address: String, isSpam: Boolean) {
        if (address.isBlank()) return
        val map = snapshot().toMutableMap()
        val old = map[address] ?: SenderProfile(address, firstSeen = System.currentTimeMillis())
        map[address] = old.copy(
            spam = old.spam + if (isSpam) 1 else 0,
            ham = old.ham + if (isSpam) 0 else 1
        )
        trim(map)
        write(map)
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    /** Keeps the store bounded: drop the least recently seen senders. */
    private fun trim(map: MutableMap<String, SenderProfile>) {
        if (map.size <= MAX_SENDERS) return
        val keep = map.values.sortedByDescending { it.lastSeen }.take(MAX_SENDERS)
        map.clear()
        keep.forEach { map[it.address] = it }
    }

    private companion object {
        const val KEY = "profiles"
        const val MAX_SENDERS = 1000
        const val BURST_WINDOW_MS = 10 * 60 * 1000L
    }
}

/**
 * Token weights learned from user feedback using a log-odds ratio with Laplace
 * smoothing. This is the small, honest model: it has no hidden parameters, it
 * updates instantly, and every weight can be explained back to the user.
 */
class LearnedWeights(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_weights", Context.MODE_PRIVATE)

    private data class Counts(val spam: Int, val ham: Int)

    private fun load(): Pair<MutableMap<String, Counts>, IntArray> {
        val map = mutableMapOf<String, Counts>()
        val totals = intArrayOf(0, 0) // [spamMessages, hamMessages]
        val raw = prefs.getString(KEY, null) ?: return map to totals
        try {
            val root = JSONObject(raw)
            totals[0] = root.optInt("total_spam")
            totals[1] = root.optInt("total_ham")
            val tokens = root.optJSONObject("tokens") ?: return map to totals
            for (t in tokens.keys()) {
                val o = tokens.getJSONObject(t)
                map[t] = Counts(o.optInt("s"), o.optInt("h"))
            }
        } catch (e: Exception) {
            // ignore
        }
        return map to totals
    }

    private fun save(map: Map<String, Counts>, totals: IntArray) {
        val tokens = JSONObject()
        for ((t, c) in map) {
            tokens.put(t, JSONObject().apply { put("s", c.spam); put("h", c.ham) })
        }
        prefs.edit().putString(
            KEY,
            JSONObject().apply {
                put("total_spam", totals[0]); put("total_ham", totals[1]); put("tokens", tokens)
            }.toString()
        ).apply()
    }

    /** Learns from one labelled message. */
    fun record(body: String, isSpam: Boolean) {
        val (map, totals) = load()
        for (t in Learning.tokens(body)) {
            val old = map[t] ?: Counts(0, 0)
            map[t] = if (isSpam) old.copy(spam = old.spam + 1) else old.copy(ham = old.ham + 1)
        }
        if (isSpam) totals[0]++ else totals[1]++
        // Keep only the most informative tokens.
        if (map.size > MAX_TOKENS) {
            val keep = map.entries.sortedByDescending { (_, c) -> c.spam + c.ham }.take(MAX_TOKENS)
            map.clear()
            keep.forEach { map[it.key] = it.value }
        }
        save(map, totals)
    }

    /**
     * Log-odds weight per token. Positive means "seen more in spam".
     * The +1/+2 terms are Laplace smoothing, so a token seen once in spam does
     * not instantly become a strong signal.
     */
    fun weights(): Map<String, Double> {
        val (map, totals) = load()
        if (totals[0] == 0 && totals[1] == 0) return emptyMap()
        val out = HashMap<String, Double>(map.size)
        for ((t, c) in map) {
            val pSpam = (c.spam + 1.0) / (totals[0] + 2.0)
            val pHam = (c.ham + 1.0) / (totals[1] + 2.0)
            out[t] = ln(pSpam / pHam)
        }
        return out
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "weights"
        const val MAX_TOKENS = 600
    }
}

object Learning {

    private const val MIN_TOKEN_LEN = 3

    /** Normalised, de-duplicated word tokens used as learning features. */
    fun tokens(body: String): List<String> =
        Normalizer.normalize(body)
            .split(' ')
            .filter { it.length >= MIN_TOKEN_LEN && it.any { c -> c.isLetter() } }
            .distinct()
}
