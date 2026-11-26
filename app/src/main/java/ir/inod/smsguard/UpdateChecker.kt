package ir.inod.smsguard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * بررسی نسخهٔ جدید از GitHub Releases.
 *
 * قواعد رفتار (طبق خواستهٔ محصول):
 *  - حداکثر یک بررسی در ۲۴ ساعت، در رشتهٔ پس‌زمینه؛ هرگز روی مسیر شروع اپ نمی‌ماند.
 *  - اگر کاربر «بعداً» بزند: ۳ روز سکوت؛ اگر باز «بعداً» بزند: ۷ روز سکوت.
 *  - تا وقتی نسخهٔ جدید نصب نشود، دکمهٔ سبز «به‌روزرسانی فوری» در ابزارها می‌ماند.
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/turboboy2006/sms-guard/releases/latest"
    private const val PREFS = "update_state"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SNOOZE_UNTIL = "snooze_until"
    private const val KEY_POSTPONES = "postpones"
    private const val KEY_LATEST = "latest_version"
    private const val KEY_URL = "latest_url"

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "update-check").apply { isDaemon = true }
    }

    data class Info(val version: String, val url: String)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun currentVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    fun cachedLatest(context: Context): Info? {
        val p = prefs(context)
        val v = p.getString(KEY_LATEST, null) ?: return null
        val u = p.getString(KEY_URL, null) ?: return null
        return Info(v, u)
    }

    fun hasPendingUpdate(context: Context): Boolean =
        cachedLatest(context)?.let { isNewer(it.version, currentVersion(context)) } == true

    /** نسخهٔ «v1.0.140» یا «1.0.140» را با نسخهٔ نصب‌شده مقایسه می‌کند. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = numbers(latest)
        val b = numbers(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.', '-', '+')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            .filterIndexed { index, _ -> index < 4 }

    private fun fetchLatest(): Info? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "PayamBan-Updater")
            }
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            if (tag.isBlank()) return null
            val assets = json.optJSONArray("assets")
            var url = json.optString("html_url")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val candidate = assets.optJSONObject(i)?.optString("browser_download_url").orEmpty()
                    if (candidate.endsWith(".apk", ignoreCase = true)) { url = candidate; break }
                }
            }
            Info(tag, url)
        } catch (t: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * بررسی پس‌زمینه؛ اگر نسخهٔ جدید بود و کاربر در حالت سکوت نیست، [onNewer] روی رشتهٔ UI صدا زده می‌شود.
     */
    fun checkInBackground(context: Context, force: Boolean = false, onNewer: ((Info) -> Unit)? = null, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        val p = prefs(app)
        val now = System.currentTimeMillis()
        val last = p.getLong(KEY_LAST_CHECK, 0L)
        if (!force && now - last < 24L * 60 * 60 * 1000) return
        worker.execute {
            val info = fetchLatest()
            if (info == null) { post(app, onDone); return@execute }
            p.edit().putLong(KEY_LAST_CHECK, now)
                .putString(KEY_LATEST, info.version)
                .putString(KEY_URL, info.url)
                .apply()
            val newer = isNewer(info.version, currentVersion(app))
            val snoozed = now < p.getLong(KEY_SNOOZE_UNTIL, 0L)
            if (newer && !snoozed && onNewer != null) post(app) { onNewer(info) } else post(app, onDone)
        }
    }

    private fun post(context: Context, block: (() -> Unit)?) {
        if (block == null) return
        android.os.Handler(context.mainLooper).post(block)
    }

    /** «بعداً» → ۳ روز، و اگر دوباره «بعداً» بزند → ۷ روز. */
    fun postpone(context: Context) {
        val p = prefs(context)
        val count = p.getInt(KEY_POSTPONES, 0) + 1
        val days = if (count <= 1) 3 else 7
        p.edit()
            .putInt(KEY_POSTPONES, count)
            .putLong(KEY_SNOOZE_UNTIL, System.currentTimeMillis() + days * 24L * 60 * 60 * 1000)
            .apply()
    }

    fun resetPostpone(context: Context) {
        prefs(context).edit().remove(KEY_POSTPONES).remove(KEY_SNOOZE_UNTIL).apply()
    }

    /** دانلود APK و اجرای نصب. فقط از یک Activity زنده صدا زده می‌شود. */
    fun downloadAndInstall(activity: Activity, info: Info, onProgress: (Int) -> Unit, onError: (String) -> Unit) {
        worker.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(info.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "PayamBan-Updater")
                }
                if (connection.responseCode !in 200..299) {
                    post(activity) { onError("HTTP ${'$'}{connection.responseCode}") }
                    return@execute
                }
                val total = connection.contentLength.toLong()
                val target = File(activity.cacheDir, "payamban-update.apk")
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) post(activity) { onProgress(((written * 100) / total).toInt()) }
                        }
                    }
                }
                post(activity) { install(activity, target, onError) }
            } catch (t: Throwable) {
                post(activity) { onError(t.message ?: "network error") }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun install(activity: Activity, apk: File, onError: (String) -> Unit) {
        try {
            val uri: Uri = androidx.core.content.FileProvider.getUriForFile(
                activity, activity.packageName + ".files", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (t: Throwable) {
            onError(t.message ?: "install failed")
        }
    }
}
