package ir.inod.smsguard

import android.content.Context
import org.json.JSONArray
import kotlin.math.ln

/** One extracted URL together with the offline risk features derived from it. */
data class UrlFeatures(
    val raw: String,
    val host: String,
    val length: Int,
    val subdomains: Int,
    val entropy: Double,
    val digitRatio: Double,
    val isIp: Boolean,
    val isShortener: Boolean,
    val isPunycode: Boolean,
    val hasRedirectParam: Boolean,
    val riskyTld: Boolean,
    /** Label that would carry the brand name, e.g. "digikala" in digikala.com */
    val label: String
)

/**
 * URL intelligence that needs no network.
 *
 * Domain *reputation* cannot be looked up offline, so the app instead learns
 * which domains the user has already confirmed as spam, and reasons about the
 * shape of a URL — generation patterns (random labels, digit-heavy hosts,
 * punycode, deep subdomain chains) look statistically different from real ones.
 */
object UrlIntel {

    private val URL_REGEX = Regex(
        "(?:https?://|www\\.)[^\\s]+|(?<![@\\w])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+" +
            "(?:ir|com|net|org|co|me|io|info|biz|app|dev|site|online)(?:/[^\\s]*)?",
        RegexOption.IGNORE_CASE
    )
    private val IP_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val PUNY_REGEX = Regex("(^|\\.)xn--", RegexOption.IGNORE_CASE)

    private val SHORTENERS = listOf(
        "bit.ly", "t.co", "tinyurl", "goo.gl", "is.gd", "rb.gy", "cutt.ly",
        "ow.ly", "shorturl", "linktr.ee", "t.me", "wa.me", "whatsapp.com"
    )
    private val RISKY_TLDS = listOf(
        ".xyz", ".top", ".click", ".shop", ".live", ".icu", ".buzz", ".rest",
        ".monster", ".cyou", ".sbs", ".cfd", ".loan", ".work", ".online", ".site"
    )
    private val REDIRECT_PARAMS = listOf(
        "?url=", "&url=", "?redirect", "&redirect", "?to=", "&to=",
        "?link=", "&link=", "?goto=", "?target="
    )

    fun extract(body: String): List<UrlFeatures> =
        URL_REGEX.findAll(body).map { match -> features(match.value.trimEnd('.', ',', ')')) }
            .toList()

    private fun features(raw: String): UrlFeatures {
        val host = raw.substringAfter("://", raw)
            .substringBefore('/')
            .substringBefore('?')
            .lowercase()
        val labels = host.split('.').filter { it.isNotEmpty() }
        val label = when {
            labels.size >= 2 -> labels[labels.size - 2]
            labels.isNotEmpty() -> labels[0]
            else -> ""
        }
        val digits = host.count { it.isDigit() }
        return UrlFeatures(
            raw = raw,
            host = host,
            length = raw.length,
            subdomains = (labels.size - 2).coerceAtLeast(0),
            entropy = entropy(host),
            digitRatio = if (host.isEmpty()) 0.0 else digits.toDouble() / host.length,
            isIp = IP_REGEX.matches(host),
            isShortener = SHORTENERS.any { host == it || host.endsWith(".$it") },
            isPunycode = PUNY_REGEX.containsMatchIn(host),
            hasRedirectParam = REDIRECT_PARAMS.any { raw.lowercase().contains(it) },
            riskyTld = RISKY_TLDS.any { host.endsWith(it) },
            label = label
        )
    }

    /** Shannon entropy of the host. Generated domains score noticeably higher. */
    private fun entropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val counts = HashMap<Char, Int>()
        for (c in s) counts[c] = (counts[c] ?: 0) + 1
        var h = 0.0
        for (n in counts.values) {
            val p = n.toDouble() / s.length
            h -= p * ln(p)
        }
        return h
    }

    /** Classic edit distance, used to catch typosquatted brand names. */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        // Bounded rows keep this cheap on long hosts.
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    /**
     * True when a URL's host label is one or two edits away from a brand's real
     * domain but is not that domain — the shape of a typosquat such as
     * "digikalaa.xyz" or "bankmellii.ir".
     */
    fun looksLikeImpersonation(host: String, brandDomains: List<String>): Boolean {
        if (host.isEmpty()) return false
        if (brandDomains.any { host == it || host.endsWith(".$it") }) return false
        val labels = host.split('.').filter { it.isNotEmpty() }
        val hostLabel = if (labels.size >= 2) labels[labels.size - 2] else labels.firstOrNull() ?: ""
        if (hostLabel.length < 4) return false
        return brandDomains.any { domain ->
            val brandLabel = domain.substringBefore('.')
            if (brandLabel.length < 4) return@any false
            val d = levenshtein(brandLabel, hostLabel)
            d in 1..2
        }
    }
}

/**
 * Local blocklists the user builds up over time: whole domains, and sender
 * prefixes with a trailing '*' so an entire bulk range can be blocked.
 */
class BlockStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_blocklist", Context.MODE_PRIVATE)

    fun domains(): Set<String> = readArray(KEY_DOMAINS)
    fun prefixes(): Set<String> = readArray(KEY_PREFIXES)

    private fun readArray(key: String): Set<String> {
        val raw = prefs.getString(key, null) ?: return emptySet()
        val out = HashSet<String>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) out.add(arr.getString(i))
        } catch (e: Exception) {
            // ignore
        }
        return out
    }

    private fun writeArray(key: String, values: Set<String>) {
        val arr = JSONArray()
        values.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun blockDomain(domain: String) {
        val d = domain.lowercase().trim()
        if (d.isBlank()) return
        writeArray(KEY_DOMAINS, domains() + d)
    }

    fun unblockDomain(domain: String) = writeArray(KEY_DOMAINS, domains() - domain.lowercase())

    fun blockPrefix(prefix: String) {
        val p = prefix.trim()
        if (p.isBlank()) return
        writeArray(KEY_PREFIXES, prefixes() + p)
    }

    fun unblockPrefix(prefix: String) = writeArray(KEY_PREFIXES, prefixes() - prefix.trim())

    fun matchesDomain(host: String): Boolean {
        if (host.isBlank()) return false
        return domains().any { host == it || host.endsWith(".$it") }
    }

    /** A prefix entry ending in '*' matches the whole range. */
    fun matchesPrefix(address: String): Boolean {
        val a = address.replace(" ", "")
        return prefixes().any { p ->
            val clean = p.replace(" ", "")
            when {
                clean.endsWith("*") -> a.startsWith(clean.dropLast(1))
                else -> a == clean
            }
        }
    }

    private companion object {
        const val KEY_DOMAINS = "domains"
        const val KEY_PREFIXES = "prefixes"
    }
}
