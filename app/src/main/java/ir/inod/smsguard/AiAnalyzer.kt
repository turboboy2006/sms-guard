package ir.inod.smsguard

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal OpenAI-compatible client. Works with DeepSeek, OpenRouter, OpenAI or
 * any gateway exposing POST {base}/chat/completions.
 *
 * Uses HttpURLConnection + org.json so no extra dependency is required.
 * Every failure path returns null: the AI stage must never break SMS handling.
 */
class AiAnalyzer(private val settings: SettingsStore) {

    fun analyze(sender: String, body: String): AiVerdict? {
        if (!settings.aiEnabled) return null
        if (settings.aiApiKey.isBlank()) return null

        val endpoint = settings.aiBaseUrl.trimEnd('/') + "/chat/completions"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = settings.aiTimeoutMs
                readTimeout = settings.aiTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer " + settings.aiApiKey)
                // OpenRouter asks for these; harmless for other providers.
                setRequestProperty("HTTP-Referer", "https://github.com/turboboy2006/sms-guard")
                setRequestProperty("X-Title", "PayamBan")
            }

            val payload = JSONObject().apply {
                put("model", settings.aiModel)
                put("temperature", 0)
                put("max_tokens", 120)
                put(
                    "messages",
                    JSONArray().apply {
                        put(
                            JSONObject().apply {
                                put("role", "system")
                                put("content", SYSTEM_PROMPT)
                            }
                        )
                        put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", "sender: $sender\nmessage: $body")
                            }
                        )
                    }
                )
            }

            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) return null

            val text = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            parse(text)
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun parse(raw: String): AiVerdict? {
        return try {
            val content = JSONObject(raw)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content", "")
            if (content.isBlank()) return null

            // The model may wrap JSON in prose or code fences; take the first object.
            val start = content.indexOf('{')
            val end = content.lastIndexOf('}')
            if (start < 0 || end <= start) return null

            val obj = JSONObject(content.substring(start, end + 1))
            val rawCat = obj.optString("category", Cat.OTHER).lowercase().trim()
            val score = obj.optInt("score", 0).coerceIn(0, 100)
            val reason = obj.optString("reason", "").take(120)

            val mapped = when (rawCat) {
                "banking", "bank" -> Cat.BANKING
                "otp", "verification" -> Cat.OTP
                "notification", "informational" -> Cat.NOTIFICATION
                "promotion", "promotional", "ads" -> Cat.PROMOTION
                "personal" -> Cat.PERSONAL
                "spam", "scam", "fraud", "phishing" -> Cat.SPAM
                "suspicious" -> Cat.SUSPICIOUS
                else -> Cat.OTHER
            }
            AiVerdict(mapped, score, reason)
        } catch (e: Exception) {
            null
        }
    }

    /** Asks the model for one short-lived connection test. */
    fun testConnection(): String {
        val verdict = analyze("TEST", "This is a connectivity test from the SMS app.")
        return if (verdict != null) "OK: ${verdict.categoryId} (${verdict.score})" else "FAILED"
    }

    private companion object {
        const val SYSTEM_PROMPT =
            "You classify SMS messages for a Persian-speaking user in Iran. " +
                "Reply with ONLY a compact JSON object and nothing else, using this exact shape: " +
                "{\"category\":\"banking|otp|notification|promotion|personal|suspicious|spam|other\"," +
                "\"score\":0-100,\"reason\":\"max 8 words\"}. " +
                "score is the probability that the message is unwanted promotional or fraudulent content. " +
                "Banking and one-time-password messages are never spam. " +
                "Never include explanations outside the JSON."
    }
}
