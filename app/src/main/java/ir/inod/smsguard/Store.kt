package ir.inod.smsguard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rules and the blocked-message log are both small, so they live in
 * SharedPreferences as JSON. No database dependency keeps the build simple.
 */
class RuleStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_rules", Context.MODE_PRIVATE)

    fun all(): MutableList<Rule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return mutableListOf()
        val out = mutableListOf<Rule>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Rule(
                        id = o.optLong("id"),
                        pattern = o.optString("pattern"),
                        target = runCatching {
                            RuleTarget.valueOf(o.optString("target", "BOTH"))
                        }.getOrDefault(RuleTarget.BOTH),
                        isRegex = o.optBoolean("isRegex", false),
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
        } catch (e: Exception) {
            // Corrupted store: start clean rather than crash.
        }
        return out
    }

    fun save(rules: List<Rule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("pattern", r.pattern)
                    put("target", r.target.name)
                    put("isRegex", r.isRegex)
                    put("enabled", r.enabled)
                }
            )
        }
        prefs.edit().putString(KEY_RULES, arr.toString()).apply()
    }

    fun add(pattern: String, target: RuleTarget, isRegex: Boolean) {
        val list = all()
        list.add(
            Rule(
                id = System.currentTimeMillis(),
                pattern = pattern.trim(),
                target = target,
                isRegex = isRegex,
                enabled = true
            )
        )
        save(list)
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        val list = all()
        for (i in list.indices) {
            if (list[i].id == id) list[i] = list[i].copy(enabled = enabled)
        }
        save(list)
    }

    fun delete(id: Long) {
        save(all().filterNot { it.id == id })
    }

    /** First enabled rule that matches, or null when the message should be kept. */
    fun blockingRuleFor(address: String, body: String): Rule? =
        all().firstOrNull { it.matches(address, body) }

    private companion object {
        const val KEY_RULES = "rules"
    }
}

/** A capped, newest-first log of blocked messages. */
class BlockedStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sms_guard_blocked", Context.MODE_PRIVATE)

    fun add(msg: BlockedMessage) {
        val list = all().toMutableList()
        list.add(0, msg)
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
        save(list)
    }

    fun all(): List<BlockedMessage> {
        val raw = prefs.getString(KEY_BLOCKED, null) ?: return emptyList()
        val out = mutableListOf<BlockedMessage>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    BlockedMessage(
                        address = o.optString("address"),
                        body = o.optString("body"),
                        date = o.optLong("date"),
                        rulePattern = o.optString("rule")
                    )
                )
            }
        } catch (e: Exception) {
            // ignore
        }
        return out
    }

    fun count(): Int = all().size

    fun clear() = prefs.edit().remove(KEY_BLOCKED).apply()

    private fun save(list: List<BlockedMessage>) {
        val arr = JSONArray()
        for (m in list) {
            arr.put(
                JSONObject().apply {
                    put("address", m.address)
                    put("body", m.body)
                    put("date", m.date)
                    put("rule", m.rulePattern)
                }
            )
        }
        prefs.edit().putString(KEY_BLOCKED, arr.toString()).apply()
    }

    private companion object {
        const val KEY_BLOCKED = "blocked"
        const val MAX_ENTRIES = 300
    }
}
