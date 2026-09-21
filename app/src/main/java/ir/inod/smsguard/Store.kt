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

    /** 0.85 small · 1.0 normal · 1.15 large · 1.3 extra large. */
    var fontScale: Float
        get() = prefs.getFloat("font_scale", 1f)
        set(v) {
            val clamped = v.coerceIn(0.85f, 1.5f)
            prefs.edit().putFloat("font_scale", clamped).apply()
            bumpRevision()
        }

    /**
     * The system font scale cannot be swapped out of a live Activity, so a
     * change here is applied by rebuilding the screens: each Activity remembers
     * this counter at `onCreate` and compares it in `onResume`.
     */
    val revision: Int
        get() = prefs.getInt("revision", 0)

    private fun bumpRevision() {
        prefs.edit().putInt("revision", revision + 1).apply()
    }

    companion object {
        const val DEFAULT_BASE = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
    }
}

/**
 * Appearance preferences.
 *
 * These are kept apart from [SettingsStore] on purpose: the detection settings
 * are about what the app *decides*, these are about what it *looks like*, and
 * the inbox reads them on every bind.
 *
 * The two font scales are independent because the two screens have different
 * jobs: the conversation is mostly reading long text, while the inbox is mostly
 * scanning many short rows, and users want to tune them separately.
 */
class ThemePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_theme", Context.MODE_PRIVATE)

    /** 0.85 · 1.0 · 1.15 · 1.3 · 1.5 — scales the conversation list text. */
    var listFontScale: Float
        get() = prefs.getFloat("list_font", 1f)
        set(v) = prefs.edit().putFloat("list_font", v.coerceIn(0.85f, 1.5f)).apply()

    /** Same scale for message bubbles and, from there, MessageAdapter. */
    var messageFontScale: Float
        get() = prefs.getFloat("msg_font", 1f)
        set(v) = prefs.edit().putFloat("msg_font", v.coerceIn(0.85f, 1.5f)).apply()

    /** Vertical padding inside a conversation row. */
    var rowPadding: Int
        get() = prefs.getInt("row_pad", 12)
        set(v) = prefs.edit().putInt("row_pad", v.coerceIn(4, 28)).apply()

    /** Gap between consecutive conversation rows. */
    var rowSpacing: Int
        get() = prefs.getInt("row_gap", 0)
        set(v) = prefs.edit().putInt("row_gap", v.coerceIn(0, 20)).apply()

    /** Start/end margin of a conversation row. */
    var rowInset: Int
        get() = prefs.getInt("row_inset", 0)
        set(v) = prefs.edit().putInt("row_inset", v.coerceIn(0, 16)).apply()

    /** Space kept above and below the whole list, so content is not edge-bound. */
    var listPadding: Int
        get() = prefs.getInt("list_pad", 8)
        set(v) = prefs.edit().putInt("list_pad", v.coerceIn(0, 40)).apply()

    /** One of [RowStyle.IDS]. Unknown values fall back to the classic look. */
    var rowStyle: String
        get() = prefs.getString("row_style", RowStyle.CLASSIC) ?: RowStyle.CLASSIC
        set(v) = prefs.edit()
            .putString("row_style", if (v in RowStyle.IDS) v else RowStyle.CLASSIC)
            .apply()

    /**
     * Banner, chips, search and the default-app card can each be hidden. A
     * denser inbox was an explicit request, and the banner is noise once the
     * role has been granted.
     */
    var showChips: Boolean
        get() = prefs.getBoolean("show_chips", true)
        set(v) = prefs.edit().putBoolean("show_chips", v).apply()

    var showSearch: Boolean
        get() = prefs.getBoolean("show_search", true)
        set(v) = prefs.edit().putBoolean("show_search", v).apply()

    var showDividers: Boolean
        get() = prefs.getBoolean("show_dividers", true)
        set(v) = prefs.edit().putBoolean("show_dividers", v).apply()

    /** Current bubble corner radius; the row styles that use cards share it. */
    var bubbleRadius: Int
        get() = prefs.getInt("bubble_radius", 14)
        set(v) = prefs.edit().putInt("bubble_radius", v.coerceIn(0, 24)).apply()

    /** Vertical gap between two messages inside a conversation. */
    var messageSpacing: Int
        get() = prefs.getInt("msg_gap", 2)
        set(v) = prefs.edit().putInt("msg_gap", v.coerceIn(0, 16)).apply()

    /** Background style of the message bubbles. One of [MessageStyle.IDS]. */
    var messageStyle: String
        get() = prefs.getString("msg_style", MessageStyle.FILLED) ?: MessageStyle.FILLED
        set(v) = prefs.edit()
            .putString("msg_style", if (v in MessageStyle.IDS) v else MessageStyle.FILLED)
            .apply()

    fun snapshot(): RowLayout = RowLayout(
        style = rowStyle,
        padding = rowPadding,
        spacing = rowSpacing,
        inset = rowInset,
        listPadding = listPadding,
        listFont = listFontScale,
        messageFont = messageFontScale,
        showDividers = showDividers
    )

    /** The conversation screen's own snapshot, read once per list build. */
    fun messageLayout(): MessageLayout = MessageLayout(
        fontScale = messageFontScale,
        spacing = messageSpacing,
        radius = bubbleRadius,
        style = messageStyle
    )

    /**
     * Bumped on every appearance write. An Activity compares the value it saw
     * at `onCreate` with the current one in `onResume`, so a change made on the
     * settings screen rebuilds the screen behind it instead of waiting for the
     * next launch.
     */
    var revision: Int
        get() = prefs.getInt("revision", 0)
        private set(v) = prefs.edit().putInt("revision", v).apply()

    fun touch() = revision.also { revision = it + 1 }
}

/** Numeric ids for the ten row looks offered on the settings screen. */
object RowStyle {    const val CLASSIC = "classic"
    const val CARD = "card"
    const val FLAT = "flat"
    const val ACCENT = "accent"
    const val BUBBLE = "bubble"
    const val COMPACT = "compact"
    const val SOFT = "soft"
    const val OUTLINE = "outline"
    const val STRIPED = "striped"
    const val PILL = "pill"

    val IDS = listOf(
        CLASSIC, CARD, FLAT, ACCENT, BUBBLE,
        COMPACT, SOFT, OUTLINE, STRIPED, PILL
    )
}

/** An immutable read of [ThemePrefs], so one list build uses one consistent set. */
data class RowLayout(
    val style: String,
    val padding: Int,
    val spacing: Int,
    val inset: Int,
    val listPadding: Int,
    val listFont: Float,
    val messageFont: Float,
    val showDividers: Boolean
)

/** Background treatments offered for the message bubbles. */
object MessageStyle {
    const val FILLED = "filled"
    const val CONTRAST = "contrast"
    const val OUTLINE = "outline"
    const val SOFT = "soft"
    const val CLEAN = "clean"

    val IDS = listOf(FILLED, CONTRAST, OUTLINE, SOFT, CLEAN)
}

/** Everything the conversation screen needs to draw one message list. */
data class MessageLayout(
    val fontScale: Float,
    val spacing: Int,
    val radius: Int,
    val style: String
)

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

/** A user-defined appearance override for one sender or pattern. */
data class SenderOverride(
    val address: String,
    val displayName: String?,
    val categoryId: String?,
    val colorHex: String?,
    val iconId: String?
)

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

    // ---------------------------------------------------- appearance manager

    fun iconFor(sender: String): String? = entry(sender).optString("icon").ifBlank { null }

    fun setIcon(sender: String, iconId: String?) {
        val o = entry(sender)
        if (iconId == null) o.remove("icon") else o.put("icon", iconId)
        put(sender, o)
    }

    fun nameFor(sender: String): String? = entry(sender).optString("name").ifBlank { null }

    fun setName(sender: String, name: String?) {
        val o = entry(sender)
        if (name.isNullOrBlank()) o.remove("name") else o.put("name", name.trim())
        put(sender, o)
    }

    /** Every sender carrying at least one override, for the manager screen. */
    fun allOverrides(): List<SenderOverride> =
        load().map { (addr, o) ->
            SenderOverride(
                address = addr,
                displayName = o.optString("name").ifBlank { null },
                categoryId = o.optString("cat").ifBlank { null },
                colorHex = o.optString("color").ifBlank { null },
                iconId = o.optString("icon").ifBlank { null }
            )
        }.sortedBy { it.address }

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
