package ir.inod.smsguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Portable settings/rules/learning backup. Message bodies and API keys are excluded. */
object SettingsBackup {
    private val FILES = listOf(
        "sms_guard_settings", "sms_guard_theme", "sms_guard_rules",
        "sms_guard_categories", "sms_guard_senders", "sms_guard_msgcat",
        "sms_guard_profiles", "sms_guard_weights"
    )

    fun export(context: Context): String {
        val root = JSONObject().put("version", 1)
        val files = JSONObject()
        FILES.forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                values.put(key, encode(value))
            }
            files.put(name, values)
        }
        return root.put("files", files).toString(2)
    }

    fun import(context: Context, raw: String) {
        val files = JSONObject(raw).getJSONObject("files")
        FILES.forEach { name ->
            if (!files.has(name)) return@forEach
            val values = files.getJSONObject(name)
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.keys().forEach { key ->
                val item = values.getJSONObject(key)
                when (item.getString("type")) {
                    "string" -> editor.putString(key, item.optString("value"))
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
            editor.apply()
        }
        Classifier.invalidateCaches()
        ThreadCache.clear(context)
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
