package ir.inod.smsguard

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Portable user-state backup. System SMS bodies are deliberately left in Android's SMS provider. */
object SettingsBackup {
    private val FILES = listOf(
        "sms_guard_settings", "sms_guard_theme", "sms_guard_rules",
        "sms_guard_categories", "sms_guard_senders", "sms_guard_msgcat",
        "sms_guard_profiles", "sms_guard_weights", "sms_guard_category_notifications",
        "saved_messages", "conversation_drafts", "scheduled_sms",
        "sms_guard_blocked", "sms_guard_blocklist", "sms_guard_campaigns"
    )

    fun export(context: Context): String {
        val root = JSONObject().put("version", 2).put("createdAt", System.currentTimeMillis())
        val files = JSONObject()
        FILES.forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                // Keystore ciphertext cannot be decrypted after reinstall. A portable value is
                // stored separately and encrypted again by SettingsStore during restore.
                if (name == "sms_guard_settings" && key == "ai_key") return@forEach
                values.put(key, encode(value))
            }
            files.put(name, values)
        }
        root.put("files", files)
        SettingsStore(context).aiApiKey.takeIf { it.isNotBlank() }?.let { root.put("portableAiKey", it) }
        root.put("assets", exportWallpaperAssets(context))
        return root.toString(2)
    }

    fun import(context: Context, raw: String) {
        val root = JSONObject(raw)
        val files = root.getJSONObject("files")
        val restoredUris = restoreWallpaperAssets(context, root.optJSONObject("assets"))
        FILES.forEach { name ->
            if (!files.has(name)) return@forEach
            val values = files.getJSONObject(name)
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.keys().forEach { key ->
                val item = values.getJSONObject(key)
                when (item.getString("type")) {
                    "string" -> editor.putString(key, replaceRestoredUris(item.optString("value"), restoredUris))
                    "int" -> editor.putInt(key, item.getInt("value"))
                    "long" -> editor.putLong(key, item.getLong("value"))
                    "float" -> editor.putFloat(key, item.getDouble("value").toFloat())
                    "boolean" -> editor.putBoolean(key, item.getBoolean("value"))
                    "set" -> {
                        val array = item.getJSONArray("value")
                        editor.putStringSet(key, (0 until array.length()).map { array.getString(it) }.toSet())
                    }
                }
            }
            check(editor.commit()) { "Could not restore $name" }
        }
        root.optString("portableAiKey").takeIf { it.isNotBlank() }?.let { SettingsStore(context).aiApiKey = it }
        ScheduledSmsStore(context).restoreJobs()
        clearGeneratedNotificationChannels(context)
        Classifier.invalidateCaches()
        ThreadCache.clear(context)
    }

    private fun exportWallpaperAssets(context: Context): JSONObject {
        val uris = linkedSetOf<String>()
        context.getSharedPreferences("sms_guard_theme", Context.MODE_PRIVATE)
            .getString("background_image_uri", null)?.let(uris::add)
        context.getSharedPreferences("sms_guard_senders", Context.MODE_PRIVATE).all.values
            .filterIsInstance<String>().forEach { raw ->
                runCatching { collectBackgroundUris(JSONObject(raw), uris) }
            }
        return JSONObject().also { assets ->
            uris.filter { it.startsWith("content://") || it.startsWith("file://") }.forEach { rawUri ->
                runCatching {
                    val bytes = context.contentResolver.openInputStream(Uri.parse(rawUri))?.use { it.readBytes() }
                        ?: return@runCatching
                    assets.put(rawUri, JSONObject().apply {
                        put("mime", context.contentResolver.getType(Uri.parse(rawUri)) ?: "image/*")
                        put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    })
                }
            }
        }
    }

    private fun collectBackgroundUris(value: Any?, output: MutableSet<String>) {
        when (value) {
            is JSONObject -> value.keys().forEach { key ->
                val child = value.opt(key)
                if (key == "background_image" && child is String && child.isNotBlank()) output += child
                collectBackgroundUris(child, output)
            }
            is JSONArray -> (0 until value.length()).forEach { collectBackgroundUris(value.opt(it), output) }
        }
    }

    private fun restoreWallpaperAssets(context: Context, assets: JSONObject?): Map<String, String> {
        if (assets == null) return emptyMap()
        val directory = File(context.filesDir, "restored_wallpapers").apply { mkdirs() }
        return buildMap {
            assets.keys().forEach { oldUri ->
                runCatching {
                    val data = assets.getJSONObject(oldUri).getString("data")
                    val target = File(directory, "wallpaper_${oldUri.hashCode().toUInt().toString(16)}.img")
                    target.writeBytes(Base64.decode(data, Base64.DEFAULT))
                    put(oldUri, Uri.fromFile(target).toString())
                }
            }
        }
    }

    private fun replaceRestoredUris(value: String, restored: Map<String, String>): String {
        var result = value
        restored.forEach { (old, new) -> result = result.replace(old, new) }
        return result
    }

    private fun clearGeneratedNotificationChannels(context: Context) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.notificationChannels.filter { it.id.startsWith("sms_cat_") }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }

    private fun encode(value: Any?): JSONObject = JSONObject().apply {
        when (value) {
            is String -> { put("type", "string"); put("value", value) }
            is Int -> { put("type", "int"); put("value", value) }
            is Long -> { put("type", "long"); put("value", value) }
            is Float -> { put("type", "float"); put("value", value.toDouble()) }
            is Boolean -> { put("type", "boolean"); put("value", value) }
            is Set<*> -> {
                put("type", "set")
                put("value", JSONArray(value.filterIsInstance<String>()))
            }
            else -> { put("type", "string"); put("value", value?.toString().orEmpty()) }
        }
    }
}
