package ir.inod.smsguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedMessage(val id: Long, val address: String, val body: String, val date: Long, val note: String, val starred: Boolean = false)

/** A local vault for messages the user wants to retain without forwarding them. */
class SavedMessageStore(context: Context) {
    companion object { const val SELF_ADDRESS = "__sms_guard_self__" }
    private val prefs = context.applicationContext.getSharedPreferences("saved_messages", Context.MODE_PRIVATE)
    fun all(): List<SavedMessage> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { o ->
            SavedMessage(o.getLong("id"), o.getString("address"), o.getString("body"), o.getLong("date"), o.optString("note"), o.optBoolean("starred"))
        } }.sortedByDescending { it.date }
    }.getOrDefault(emptyList())
    fun save(message: SmsMessage, note: String) {
        val list = all().filterNot { it.id == message.id }.toMutableList()
        list += SavedMessage(message.id, message.address, message.body, message.date, note, all().firstOrNull { it.id == message.id }?.starred == true)
        val arr = JSONArray(); list.take(1000).forEach { m -> arr.put(JSONObject().apply {
            put("id", m.id); put("address", m.address); put("body", m.body); put("date", m.date); put("note", m.note); put("starred", m.starred)
        }) }
        prefs.edit().putString("items", arr.toString()).apply()
    }
    fun remove(id: Long) = saveRaw(all().filterNot { it.id == id })
    fun addNote(body: String) {
        val trimmed = body.trim()
        if (trimmed.isBlank()) return
        val now = System.currentTimeMillis()
        saveRaw((listOf(SavedMessage(-now, SELF_ADDRESS, trimmed, now, "")) + all()).take(1000))
    }
    fun updateNote(id: Long, note: String) = saveRaw(all().map { if (it.id == id) it.copy(note = note) else it })
    fun setStarred(id: Long, starred: Boolean) = saveRaw(all().map { if (it.id == id) it.copy(starred = starred) else it })
    private fun saveRaw(list: List<SavedMessage>) {
        val arr = JSONArray(); list.forEach { m -> arr.put(JSONObject().apply {
            put("id", m.id); put("address", m.address); put("body", m.body); put("date", m.date); put("note", m.note); put("starred", m.starred)
        }) }; prefs.edit().putString("items", arr.toString()).apply()
    }
}

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

    /** -1 follows Android's current default SMS subscription. */
    var defaultSimId: Int
        get() = prefs.getInt("default_sim_id", -1)
        set(v) = prefs.edit().putInt("default_sim_id", v).apply()

    /** 0 keeps trash forever; otherwise conversations older than this are purged. */
    var trashRetentionDays: Int
        get() = prefs.getInt("trash_retention_days", 0)
        set(v) = prefs.edit().putInt("trash_retention_days", v.coerceAtLeast(0)).apply()

    var swipeEnabled: Boolean
        get() = prefs.getBoolean("swipe_enabled", true)
        set(v) = prefs.edit().putBoolean("swipe_enabled", v).apply()

    var swipeRightAction: String
        get() = prefs.getString("swipe_right", SwipeAction.READ) ?: SwipeAction.READ
        set(v) = prefs.edit().putString("swipe_right", SwipeAction.valid(v)).apply()

    var swipeLeftAction: String
        get() = prefs.getString("swipe_left", SwipeAction.SPAM) ?: SwipeAction.SPAM
        set(v) = prefs.edit().putString("swipe_left", SwipeAction.valid(v)).apply()

    /**
     * Quiet hours. Off by default: silently withholding a notification is a
     * surprising thing for a messaging app to do on its own, so the user has to
     * ask for it. See [QuietHours] for what is and is not suppressed.
     */
    var quietHoursEnabled: Boolean
        get() = prefs.getBoolean("quiet_enabled", false)
        set(v) = prefs.edit().putBoolean("quiet_enabled", v).apply()

    /** Hour the quiet window starts, inclusive. */
    var quietFrom: Int
        get() = prefs.getInt("quiet_from", 23)
        set(v) = prefs.edit().putInt("quiet_from", v.coerceIn(0, 23)).apply()

    /** Hour the quiet window ends, exclusive. */
    var quietTo: Int
        get() = prefs.getInt("quiet_to", 7)
        set(v) = prefs.edit().putInt("quiet_to", v.coerceIn(0, 23)).apply()

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

object SwipeAction {
    const val READ = "read"
    const val SPAM = "spam"
    const val TRASH = "trash"
    const val ARCHIVE = "archive"
    val IDS = listOf(READ, SPAM, TRASH, ARCHIVE)
    fun valid(value: String) = value.takeIf { it in IDS } ?: READ
}

/** Rounds a length down to the nearest multiple of [step], never below it. */
private fun snap(value: Int, step: Int): Int = (value / step) * step

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
        get() = prefs.getInt("row_pad", 8)
        // Snapped to the slider's step: Slider.setValue throws when a restored
        // value does not sit on valueFrom + k*stepSize.
        set(v) = prefs.edit().putInt("row_pad", snap(v.coerceIn(4, 28), 2)).apply()

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
        set(v) = prefs.edit().putInt("list_pad", snap(v.coerceIn(0, 40), 2)).apply()

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
        set(v) = prefs.edit().putInt("bubble_radius", snap(v.coerceIn(0, 24), 2)).apply()

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

    /** Colour personality shared by previews, rows, bubbles and key actions. */
    var colorScheme: String
        get() = prefs.getString("color_scheme", ThemePalette.OCEAN) ?: ThemePalette.OCEAN
        set(v) = prefs.edit().putString(
            "color_scheme", if (v in ThemePalette.IDS) v else ThemePalette.OCEAN
        ).apply()

    var backgroundStyle: String
        get() = prefs.getString("background_style", BackgroundStyle.CLEAN) ?: BackgroundStyle.CLEAN
        set(v) = prefs.edit().putString("background_style", BackgroundStyle.valid(v)).apply()

    /** Persisted document URI chosen by the user for the global chat backdrop. */
    var backgroundImageUri: String?
        get() = prefs.getString("background_image_uri", null)
        set(v) = prefs.edit().putString("background_image_uri", v).apply()

    var backgroundPreset: String?
        get() = prefs.getString("background_preset", null)
        set(v) = prefs.edit().putString("background_preset", BuiltInWallpaper.validOrNull(v)).apply()

    /** Keep enough surface opacity for SMS text to stay legible on busy photos. */
    var surfaceOpacity: Int
        get() = prefs.getInt("surface_opacity", 90).coerceIn(85, 100)
        set(v) = prefs.edit().putInt("surface_opacity", v.coerceIn(85, 100)).apply()

    fun hasWallpaper(): Boolean = backgroundImageUri != null || backgroundPreset != null

    fun accentColor(): Int = android.graphics.Color.parseColor(ThemePalette.hex(colorScheme))

    fun snapshot(): RowLayout = RowLayout(
        style = rowStyle,
        padding = rowPadding,
        spacing = rowSpacing,
        inset = rowInset,
        listPadding = listPadding,
        listFont = listFontScale,
        messageFont = messageFontScale,
        bubbleRadius = bubbleRadius,
        showDividers = showDividers,
        colorScheme = colorScheme
    )

    /** The conversation screen's own snapshot, read once per list build. */
    fun messageLayout(): MessageLayout = MessageLayout(
        fontScale = messageFontScale,
        spacing = messageSpacing,
        radius = bubbleRadius,
        style = messageStyle,
        colorScheme = colorScheme
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

object BackgroundStyle {
    const val CLEAN = "clean"
    const val MIST = "mist"
    const val AURORA = "aurora"
    const val DUSK = "dusk"
    const val BLOOM = "bloom"
    val IDS = listOf(CLEAN, MIST, AURORA, DUSK, BLOOM)
    fun valid(value: String) = value.takeIf { it in IDS } ?: CLEAN
}

object BuiltInWallpaper {
    const val MOUNTAINS = "mountains"
    const val EUCALYPTUS = "eucalyptus"
    const val LAKE = "lake"
    const val DESERT = "desert"
    const val LAVENDER = "lavender"
    const val RAIN = "rain"
    const val OCEAN = "ocean"
    const val PASTEL = "pastel"
    const val NEON = "neon"
    const val CORAL = "coral"
    val IDS = listOf(MOUNTAINS, EUCALYPTUS, LAKE, DESERT, LAVENDER, RAIN, OCEAN, PASTEL, NEON, CORAL)
    fun validOrNull(value: String?) = value?.takeIf { it in IDS }
    fun drawable(id: String?): Int = when (id) {
        MOUNTAINS -> R.drawable.wallpaper_misty_mountains
        EUCALYPTUS -> R.drawable.wallpaper_eucalyptus
        LAKE -> R.drawable.wallpaper_alpine_lake
        DESERT -> R.drawable.wallpaper_desert_sunset
        LAVENDER -> R.drawable.wallpaper_lavender
        RAIN -> R.drawable.wallpaper_rainy_city
        OCEAN -> R.drawable.wallpaper_ocean
        PASTEL -> R.drawable.wallpaper_pastel_rainbow
        NEON -> R.drawable.wallpaper_neon_bokeh
        CORAL -> R.drawable.wallpaper_coral_aqua
        else -> 0
    }
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
    val bubbleRadius: Int,
    val showDividers: Boolean,
    val colorScheme: String
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

/** Curated high-contrast palettes; appearance presets and density stay separate. */
object ThemePalette {
    const val OCEAN = "ocean"
    const val EMERALD = "emerald"
    const val VIOLET = "violet"
    const val ROSE = "rose"
    const val AMBER = "amber"
    const val INDIGO = "indigo"
    const val CORAL = "coral"
    const val LIME = "lime"
    const val SKY = "sky"
    const val SUNSET = "sunset"
    val IDS = listOf(OCEAN, EMERALD, VIOLET, ROSE, AMBER, INDIGO, CORAL, LIME, SKY, SUNSET)

    fun hex(id: String): String = when (id) {
        EMERALD -> "#059669"
        VIOLET -> "#7C3AED"
        ROSE -> "#E11D48"
        AMBER -> "#D97706"
        INDIGO -> "#4F46E5"
        CORAL -> "#F43F5E"
        LIME -> "#65A30D"
        SKY -> "#0284C7"
        SUNSET -> "#EA580C"
        else -> "#2563EB"
    }
}

/** Everything the conversation screen needs to draw one message list. */
data class MessageLayout(
    val fontScale: Float,
    val spacing: Int,
    val radius: Int,
    val style: String,
    val colorScheme: String
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
                        enabled = o.optBoolean("enabled", true),
                        excluded = o.optString("excluded", ""),
                        join = runCatching { RuleJoin.valueOf(o.optString("join", "ANY")) }
                            .getOrDefault(RuleJoin.ANY),
                        action = runCatching { RuleAction.valueOf(o.optString("action", "BLOCK")) }
                            .getOrDefault(RuleAction.BLOCK)
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
                    put("excluded", r.excluded); put("join", r.join.name); put("action", r.action.name)
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

    fun addSimple(
        included: String,
        excluded: String,
        join: RuleJoin,
        target: RuleTarget,
        action: RuleAction
    ) {
        val list = all()
        list.add(
            Rule(
                id = System.currentTimeMillis(), pattern = included.trim(), target = target,
                excluded = excluded.trim(), join = join, action = action
            )
        )
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

    private fun systemCategories(): List<Category> {
        val overrides = runCatching { JSONObject(prefs.getString(SYSTEM_KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        return Categories.system().map { base ->
            val o = overrides.optJSONObject(base.id)
            if (o == null) base else base.copy(
                customName = o.optString("name").ifBlank { base.customName },
                nameRes = if (o.optString("name").isNotBlank()) 0 else base.nameRes,
                colorHex = o.optString("color", base.colorHex),
                order = o.optInt("order", base.order),
                iconId = o.optString("icon").ifBlank { base.iconId },
                enabled = o.optBoolean("enabled", true)
            )
        }
    }

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
                        order = o.optInt("order", 100 + i),
                        iconId = o.optString("icon").ifBlank { null },
                        enabled = o.optBoolean("enabled", true)
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
                    put("order", c.order); put("icon", c.iconId ?: ""); put("enabled", c.enabled)
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
            isSystem = false,
            order = (all().maxOfOrNull { it.order } ?: 0) + 1,
            iconId = "unknown"
        )
        list.add(c)
        save(list)
        return c
    }

    fun delete(id: String) = save(custom().filterNot { it.id == id })

    fun update(category: Category) = save(custom().map { if (it.id == category.id) category else it })

    fun all(): List<Category> = (systemCategories() + custom()).sortedBy { it.order }

    // "Uncategorized" is the safe landing place for a disabled category, so it
    // must always remain reachable even if somebody toggled it off previously.
    fun active(): List<Category> = all().filter { it.enabled || it.id == Cat.OTHER }

    fun byId(id: String): Category? = all().firstOrNull { it.id == id }

    fun updateAny(category: Category) {
        if (!category.isSystem) { update(category); return }
        val root = runCatching { JSONObject(prefs.getString(SYSTEM_KEY, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        root.put(category.id, JSONObject().apply {
            put("name", if (category.nameRes == 0) category.customName else "")
            put("color", category.colorHex)
            put("order", category.order)
            put("icon", category.iconId ?: "")
            put("enabled", category.enabled)
        })
        prefs.edit().putString(SYSTEM_KEY, root.toString()).apply()
    }

    fun move(id: String, delta: Int) {
        val list = all().toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from < 0 || from == to) return
        val moved = list.removeAt(from); list.add(to, moved)
        list.forEachIndexed { index, category -> updateAny(category.copy(order = index)) }
    }

    fun reorder(ids: List<String>) {
        val byId = all().associateBy { it.id }
        ids.forEachIndexed { index, id -> byId[id]?.let { updateAny(it.copy(order = index)) } }
    }

    private companion object { const val KEY = "custom"; const val SYSTEM_KEY = "system_overrides" }
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

    fun simFor(sender: String): Int = entry(sender).optInt("sim", -1)

    fun setSim(sender: String, subscriptionId: Int) {
        val o = entry(sender)
        if (subscriptionId < 0) o.remove("sim") else o.put("sim", subscriptionId)
        put(sender, o)
    }

    fun notificationsMuted(sender: String): Boolean = entry(sender).optBoolean("muted", false)

    fun setNotificationsMuted(sender: String, muted: Boolean) {
        put(sender, entry(sender).apply { put("muted", muted) })
    }

    fun backgroundFor(sender: String): String? = entry(sender).optString("background").ifBlank { null }
    fun setBackground(sender: String, style: String?) {
        val o = entry(sender)
        if (style.isNullOrBlank()) o.remove("background") else o.put("background", style)
        put(sender, o)
    }
    fun backgroundImageFor(sender: String): String? = entry(sender).optString("background_image").ifBlank { null }
    fun setBackgroundImage(sender: String, uri: String?) {
        val o = entry(sender)
        if (uri.isNullOrBlank()) o.remove("background_image") else o.put("background_image", uri)
        put(sender, o)
    }
    fun backgroundPresetFor(sender: String): String? = BuiltInWallpaper.validOrNull(entry(sender).optString("background_preset"))
    fun setBackgroundPreset(sender: String, preset: String?) {
        val o = entry(sender)
        val valid = BuiltInWallpaper.validOrNull(preset)
        if (valid == null) o.remove("background_preset") else o.put("background_preset", valid)
        put(sender, o)
    }

    fun notificationSound(sender: String): String? = entry(sender).optString("sound").ifBlank { null }
    fun setNotificationSound(sender: String, uri: String?) {
        val o = entry(sender); if (uri.isNullOrBlank()) o.remove("sound") else o.put("sound", uri); put(sender, o)
    }

    fun bannerDismissed(sender: String): Boolean = entry(sender).optBoolean("banner_seen", false)

    fun setBannerDismissed(sender: String, dismissed: Boolean = true) {
        put(sender, entry(sender).apply { put("banner_seen", dismissed) })
    }

    fun isPinned(sender: String): Boolean = entry(sender).optBoolean("pinned", false)
    fun setPinned(sender: String, value: Boolean) = put(sender, entry(sender).apply { put("pinned", value) })

    fun isArchived(sender: String): Boolean = entry(sender).optBoolean("archived", false)
    /** One JSON parse per inbox render, not one parse for every sort comparison. */
    fun inboxFlags(): Map<String, Pair<Boolean, Boolean>> = load().mapValues { (_, value) ->
        value.optBoolean("pinned", false) to value.optBoolean("archived", false)
    }
    fun setArchived(sender: String, value: Boolean) = put(sender, entry(sender).apply { put("archived", value) })

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

    fun clearOverride(sender: String) {
        val map = load()
        map.remove(sender)
        save(map)
    }

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
