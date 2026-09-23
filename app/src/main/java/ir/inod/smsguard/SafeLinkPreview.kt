package ir.inod.smsguard

import android.content.Context
import android.text.Html
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Small, bounded metadata preview. Never requests spam, IP hosts, HTTP or redirects. */
object SafeLinkPreview {
    data class Card(val title: String, val description: String, val host: String)

    private val cache = ConcurrentHashMap<String, Card>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val workers = Executors.newFixedThreadPool(2)

    fun candidate(context: Context, message: SmsMessage): String? {
        val category = CategoryStore(context).byId(message.categoryId)
        if (category?.spamFolder == true || message.categoryId == Cat.TRASH ||
            message.categoryId == Cat.OTP) return null
        val feature = UrlIntel.extract(message.body).firstOrNull() ?: return null
        if (feature.isIp || feature.isShortener || feature.isPunycode || feature.hasRedirectParam ||
            feature.riskyTld || feature.subdomains > 2 ||
            BlockStore(context).matchesDomain(feature.host)) return null
        val raw = if (feature.raw.startsWith("https://", true)) feature.raw else
            if (feature.raw.startsWith("http://", true)) return null else "https://${feature.raw}"
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 ||
            uri.rawQuery != null || uri.rawFragment != null ||
            uri.host?.lowercase() != feature.host) return null
        return raw
    }

    fun cached(url: String): Card? = cache[url]

    fun request(url: String, callback: (Card?) -> Unit) {
        cache[url]?.let { callback(it); return }
        if (!pending.add(url)) return
        workers.execute {
            val card = fetch(url)
            if (card != null) cache[url] = card
            pending.remove(url)
            callback(card)
        }
    }

    private fun fetch(raw: String): Card? {
        val uri = URI(raw)
        val host = uri.host ?: return null
        if (!publicHost(host)) return null
        val connection = (URL(raw).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2200
            connection.readTimeout = 2200
            connection.setRequestProperty("Accept", "text/html")
            connection.setRequestProperty("User-Agent", "SmsGuard/1.0")
            if (connection.responseCode != 200 ||
                !connection.contentType.orEmpty().lowercase().startsWith("text/html") ||
                connection.contentLengthLong > 65536L) return null
            val bytes = connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (out.size() < 65536) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, 65536 - out.size()))
                    if (count <= 0) break
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val html = bytes.toString(Charsets.UTF_8)
            fun meta(key: String): String? {
                val tag = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)
                    .firstOrNull { Regex("(?:property|name)\\s*=\\s*['\\\"]$key['\\\"]", RegexOption.IGNORE_CASE)
                        .containsMatchIn(it.value) }?.value ?: return null
                return Regex("content\\s*=\\s*['\\\"]([^'\\\"]{1,300})['\\\"]", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.getOrNull(1)
            }
            val title = (meta("og:title") ?: Regex("<title[^>]*>(.*?)</title>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.getOrNull(1)).orEmpty().decode().take(100)
            if (title.isBlank()) return null
            Card(title, (meta("og:description") ?: meta("description")).orEmpty().decode().take(150), host)
        } catch (_: Exception) { null } finally { connection.disconnect() }
    }

    private fun String.decode(): String = Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY)
        .toString().trim().replace(Regex("\\s+"), " ")

    private fun publicHost(host: String): Boolean = try {
        InetAddress.getAllByName(host).all { address ->
            !address.isAnyLocalAddress && !address.isLoopbackAddress &&
                !address.isLinkLocalAddress && !address.isSiteLocalAddress &&
                !address.isMulticastAddress
        }
    } catch (_: Exception) { false }
}
