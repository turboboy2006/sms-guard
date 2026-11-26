package ir.inod.smsguard

import android.content.Context
import android.net.Uri
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * پشتیبان کامل داده‌های اپ در یک فایل JSON:
 * تنظیمات، دسته‌ها، قوانین، سیاست فرستنده‌ها، وزن‌های یادگرفته‌شده،
 * پیام‌های ذخیره‌شده و سطل زباله.
 *
 * پیامک‌های خود گوشی در provider اندروید هستند و در این فایل نمی‌آیند
 * (نه لازم است و نه امن).
 */
object FullBackup {

    private const val FORMAT = 2
    private const val TRASH_FILE = "trash_messages.json"

    /** @return تعداد بخش‌های ذخیره‌شده، یا null اگر خطا رخ داد. */
    fun export(context: Context, uri: Uri): Int? = try {
        val prefsDir = File(context.applicationContext.dataDir, "shared_prefs")
        val sections = JSONObject()
        prefsDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".xml")) {
                sections.put(file.name, file.readText())
            }
        }
        val files = JSONObject()
        File(context.filesDir, TRASH_FILE).takeIf { it.exists() }?.let {
            files.put(TRASH_FILE, it.readText())
        }
        val root = JSONObject().apply {
            put("app", "payamban")
            put("format", FORMAT)
            put("createdAt", System.currentTimeMillis())
            put("version", UpdateChecker.currentVersion(context))
            put("prefs", sections)
            put("files", files)
        }
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(root.toString(2).toByteArray(Charsets.UTF_8))
        } ?: return null
        sections.length() + files.length()
    } catch (t: Throwable) {
        null
    }

    /** @return تعداد بخش‌های بازگردانی‌شده، یا null اگر فایل معتبر نبود. */
    fun import(context: Context, uri: Uri): Int? = try {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return null
        val root = JSONObject(text)
        if (root.optString("app") != "payamban") return null
        val prefsDir = File(context.applicationContext.dataDir, "shared_prefs")
        if (!prefsDir.exists()) prefsDir.mkdirs()
        var restored = 0
        val sections = root.optJSONObject("prefs")
        if (sections != null) {
            val names = sections.keys()
            while (names.hasNext()) {
                val name = names.next()
                if (!name.endsWith(".xml")) continue
                File(prefsDir, name).writeText(sections.optString(name))
                restored++
            }
        }
        val files = root.optJSONObject("files")
        if (files != null) {
            val names = files.keys()
            while (names.hasNext()) {
                val name = names.next()
                File(context.filesDir, name).writeText(files.optString(name))
                restored++
            }
        }
        restored
    } catch (t: Throwable) {
        null
    }

    /** خلاصهٔ محتوای پشتیبان برای نمایش پیش از بازگردانی. */
    fun describe(context: Context, uri: Uri): String? = try {
        val text = context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: return null
        val root = JSONObject(text)
        if (root.optString("app") != "payamban") return null
        val prefs = root.optJSONObject("prefs")?.length() ?: 0
        val files = root.optJSONObject("files")?.length() ?: 0
        val version = root.optString("version", "?")
        val created = root.optLong("createdAt", 0L)
        val stamp = if (created > 0) java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(created)) else "?"
        "$version · $stamp · $prefs section(s) · $files file(s)"
    } catch (t: Throwable) {
        null
    }
}
