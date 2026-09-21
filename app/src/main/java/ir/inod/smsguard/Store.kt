package ir.inod.smsguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * All persistence. Everything is small keyed data, so SharedPreferences + JSON
 * is used throughout and the build stays free of database dependencies.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_settings", Context.MODE_PRIVATE)

    /** Off by default: enabling it sends message text to a third-party API. */
    var aiEnabled: Boolean
        get() = prefs.getBoolean("ai_enabled", false)
        set(v) = prefs.edit().putBoolean("ai_enabled", v).apply()

    var aiBaseUrl: String
        get() = prefs.getString("ai_base", DEFAULT_BASE) ?: DEFAULT_BASE
        set(v) = prefs.edit().putString("ai_base", v.trim()).apply()

    /**
     * The API key is encrypted with an Android Keystore key; only ciphertext is
     * stored. A plaintext value written by an earlier build is migrated on first
     * read, so existing users are not asked to re-enter it.
     */
    var aiApiKey: String
        get() {
            val stored = prefs.getString("ai_key", "") ?: ""
            if (stored.isEmpty()) return ""
            if (!stored.startsWith(SecureKeyStore.PREFIX)) {
                val encrypted = SecureKeyStore.encrypt(stored)
                if (encrypted != null) {
                    prefs.edit().putString("ai_key", encrypted).apply()
                    return stored
                }
                return stored // Keystore unavailable: keep working, do not lose it
            }
            return SecureKeyStore.decrypt(stored) ?: ""
        }
        set(v) {
            val trimmed = v.trim()
            if (trimmed.isEmpty()) {
                prefs.edit().remove("ai_key").apply()
                return
            }
            val encrypted = SecureKeyStore.encrypt(trimmed)
            prefs.edit().putString("ai_key", encrypted ?: trimmed).apply()
        }

    var aiModel: String
        get() = prefs.getString("ai_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("ai_model", v.trim()).apply()

    var aiTimeoutMs: Int
        get() = prefs.getInt("ai_timeout", 8000)
        set(v) = prefs.edit().putInt("ai_timeout", v).apply()

    /** Local score at or above which a message counts as suspicious. */
    var threshold: Int
        get() = prefs.getInt("threshold", 40)
        set(v) = prefs.edit().putInt("threshold", v).apply()

    /** "" = follow the device language, otherwise "fa" or "en". */
    var language: String
        get() = prefs.getString("language", "") ?: ""
        set(v) = prefs.edit().putString("language", v).apply()

    companion object {
        const val DEFAULT_BASE = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}

/** Blocking rules (the original rule engine, still fully supported). */
class RuleStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_rules", Context.MODE_PRIVATE)

    fun all(): MutableList<Rule> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        val out = mutableListOf<Rule>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Rule(
                        id = o.optLong("id"),
                        pattern = o.optString("pattern"),
                        target = runCatching { RuleTarget.valueOf(o.optString("target", "BOTH")) }
                            .getOrDefault(RuleTarget.BOTH),
                        isRegex = o.optBoolean("isRegex", false),
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
        } catch (e: Exception) {
            // corrupted store: start clean rather than crash
        }
        return out
    }

    fun save(rules: List<Rule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id); put("pattern", r.pattern); put("target", r.target.name)
                    put("isRegex", r.isRegex); put("enabled", r.enabled)
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(pattern: String, target: RuleTarget, isRegex: Boolean) {
        val list = all()
        list.add(Rule(System.currentTimeMillis(), pattern.trim(), target, isRegex, true))
        save(list)
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        save(all().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun delete(id: Long) = save(all().filterNot { it.id == id })

    fun blockingRuleFor(address: String, body: String): Rule? =
        all().firstOrNull { it.matches(address, body) }

    private companion object { const val KEY = "rules" }
}

/** User-created categories layered on top of the built-in ones. */
class CategoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_categories", Context.MODE_PRIVATE)

    fun custom(): MutableList<Category> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        val out = mutableListOf<Category>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Category(
                        id = o.optString("id"),
                        customName = o.optString("name"),
                        colorHex = o.optString("color", "#616161"),
                        isSystem = false,
                        spamFolder = o.optBoolean("spam", false),
                        skipAi = o.optBoolean("skipAi", false),
                        protectedCat = o.optBoolean("protected", false),
                        order = 100 + i
                    )
                )
            }
        } catch (e: Exception) {
            // ignore
        }
        return out
    }

    fun save(list: List<Category>) {
        val arr = JSONArray()
        for (c in list) {
            arr.put(
                JSONObject().apply {
                    put("id", c.id); put("name", c.customName); put("color", c.colorHex)
                    put("spam", c.spamFolder); put("skipAi", c.skipAi); put("protected", c.protectedCat)
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun add(name: String, colorHex: String): Category {
        val list = custom()
        val c = Category(
            id = "user_" + System.currentTimeMillis(),
            customName = name.trim(),
            colorHex = colorHex,
            isSystem = false
        )
        list.add(c)
        save(list)
        return c
    }

    fun delete(id: String) = save(custom().filterNot { it.id == id })

    fun update(category: Category) = save(custom().map { if (it.id == category.id) category else it })

    fun all(): List<Category> = Categories.system() + custom()

    fun byId(id: String): Category? = all().firstOrNull { it.id == id }

    private companion object { const val KEY = "custom" }
}

/** Per-sender memory: category, colour and whether the AI may ever see it. */
class SenderStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_senders", Context.MODE_PRIVATE)

    private fun load(): MutableMap<String, JSONObject> {
        val map = mutableMapOf<String, JSONObject>()
        val raw = prefs.getString(KEY, null) ?: return map
        try {
            val o = JSONObject(raw)
            for (k in o.keys()) map[k] = o.getJSONObject(k)
        } catch (e: Exception) {
            // ignore
        }
        return map
    }

    private fun save(map: Map<String, JSONObject>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private fun entry(sender: String): JSONObject = load()[sender] ?: JSONObject()

    private fun put(sender: String, o: JSONObject) {
        val map = load()
        map[sender] = o
        save(map)
    }

    fun categoryFor(sender: String): String? =
        entry(sender).optString("cat").ifBlank { null }

    fun setCategory(sender: String, categoryId: String) {
        put(sender, entry(sender).apply { put("cat", categoryId) })
    }

    fun policyFor(sender: String): SenderPolicy {
        val v = entry(sender).optString("policy", "")
        return runCatching { SenderPolicy.valueOf(v) }.getOrDefault(SenderPolicy.UNKNOWN)
    }

    fun setPolicy(sender: String, policy: SenderPolicy) {
        put(sender, entry(sender).apply { put("policy", policy.name) })
    }

    fun colorFor(sender: String): String? =
        entry(sender).optString("color").ifBlank { null }

    /**
     * Snapshot accessors. The conversation list resolves a category per row, so
     * reading the whole document per row is far too expensive.
     */
    fun allCategories(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((sender, obj) in load()) {
            val c = obj.optString("cat")
            if (c.isNotBlank()) out[sender] = c
        }
        return out
    }

    fun allColors(): Map<String, String> {
        val out = HashMap<String, String>()
        for ((sender, obj) in load()) {
            val c = obj.optString("color")
            if (c.isNotBlank()) out[sender] = c
        }
        return out
    }

    fun setColor(sender: String, hex: String?) {
        val o = entry(sender)
        if (hex == null) o.remove("color") else o.put("color", hex)
        put(sender, o)
    }

    private companion object { const val KEY = "senders" }
}

/** Per-message category overrides (AI verdicts and manual decisions). */
class MessageCategoryStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_msgcat", Context.MODE_PRIVATE)

    fun all(): MutableMap<Long, String> {
        val map = mutableMapOf<Long, String>()
        val raw = prefs.getString(KEY, null) ?: return map
        try {
            val o = JSONObject(raw)
            for (k in o.keys()) map[k.toLong()] = o.getString(k)
        } catch (e: Exception) {
            // ignore
        }
        return map
    }

    fun categoryFor(messageId: Long): String? = all()[messageId]

    fun set(messageId: Long, categoryId: String) {
        val map = all()
        map[messageId] = categoryId
        // keep the store bounded
        if (map.size > MAX) {
            val trimmed = map.entries.sortedByDescending { it.key }.take(MAX)
            map.clear()
            trimmed.forEach { map[it.key] = it.value }
        }
        val o = JSONObject()
        for ((k, v) in map) o.put(k.toString(), v)
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private companion object {
        const val KEY = "overrides"
        const val MAX = 2000
    }
}

/** A capped, newest-first log of messages blocked by a rule. */
class BlockedStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_blocked", Context.MODE_PRIVATE)

    fun add(msg: BlockedMessage) {
        val list = all().toMutableList()
        list.add(0, msg)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(list)
    }

    fun all(): List<BlockedMessage> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val out = mutableListOf<BlockedMessage>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    BlockedMessage(
                        o.optString("address"), o.optString("body"),
                        o.optLong("date"), o.optString("rule")
                    )
                )
            }
        } catch (e: Exception) {
            // ignore
        }
        return out
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private fun save(list: List<BlockedMessage>) {
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject().apply {
                    put("address", m.address); put("body", m.body)
                    put("date", m.date); put("rule", m.rulePattern)
                }
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "blocked"
        const val MAX = 300
    }
}
