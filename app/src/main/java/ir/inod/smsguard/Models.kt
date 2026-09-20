package ir.inod.smsguard

import android.content.Context

/** Ids of the built-in categories. */
object Cat {
    const val PERSONAL = "personal"
    const val BANKING = "banking"
    const val OTP = "otp"
    const val NOTIFICATION = "notification"
    const val PROMOTION = "promotion"
    const val SUSPICIOUS = "suspicious"
    const val SPAM = "spam"
    const val TRASH = "trash"
    const val OTHER = "other"
}

/** Where a blocking rule looks for its pattern. */
enum class RuleTarget { SENDER, BODY, BOTH }

/**
 * A blocking rule. Matching is case-insensitive and an invalid regex simply
 * never matches, so a bad rule can never crash the SMS receiver.
 */
data class Rule(
    val id: Long,
    val pattern: String,
    val target: RuleTarget = RuleTarget.BOTH,
    val isRegex: Boolean = false,
    val enabled: Boolean = true
) {
    fun matches(address: String, body: String): Boolean {
        if (!enabled || pattern.isBlank()) return false
        val addr = address.lowercase()
        return try {
            if (isRegex) {
                val re = Regex(pattern, RegexOption.IGNORE_CASE)
                when (target) {
                    RuleTarget.SENDER -> re.containsMatchIn(addr)
                    RuleTarget.BODY -> re.containsMatchIn(body)
                    RuleTarget.BOTH -> re.containsMatchIn(addr) || re.containsMatchIn(body)
                }
            } else {
                val p = pattern.trim().lowercase()
                when (target) {
                    RuleTarget.SENDER -> addr.contains(p)
                    RuleTarget.BODY -> body.lowercase().contains(p)
                    RuleTarget.BOTH -> addr.contains(p) || body.lowercase().contains(p)
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * A message category. Built-in categories carry a string resource id; user
 * categories carry a name typed by the user.
 *
 * @param spamFolder   messages land in the Spam tab
 * @param skipAi       never send messages of this category to the AI
 * @param protected    the category can never be re-labelled as spam
 */
data class Category(
    val id: String,
    val nameRes: Int = 0,
    val customName: String = "",
    val colorHex: String,
    val isSystem: Boolean = true,
    val spamFolder: Boolean = false,
    val skipAi: Boolean = false,
    val protectedCat: Boolean = false,
    val order: Int = 0
) {
    fun label(context: Context): String =
        if (nameRes != 0) context.getString(nameRes) else customName
}

object Categories {

    fun system(): List<Category> = listOf(
        Category(Cat.PERSONAL, R.string.cat_personal, "", "#2E7D32", true, false, true, true, 0),
        Category(Cat.BANKING, R.string.cat_banking, "", "#1565C0", true, false, true, true, 1),
        Category(Cat.OTP, R.string.cat_otp, "", "#00838F", true, false, true, true, 2),
        Category(Cat.NOTIFICATION, R.string.cat_notification, "", "#6A1B9A", true, false, true, true, 3),
        Category(Cat.PROMOTION, R.string.cat_promotion, "", "#EF6C00", true, false, false, false, 4),
        Category(Cat.SUSPICIOUS, R.string.cat_suspicious, "", "#D32F2F", true, false, false, false, 5),
        Category(Cat.SPAM, R.string.cat_spam, "", "#B71C1C", true, true, true, false, 6),
        // Trash is its own place: hidden from Every, but not counted as spam.
        Category(Cat.TRASH, R.string.tab_trash, "", "#546E7A", true, false, true, false, 7),
        Category(Cat.OTHER, R.string.cat_other, "", "#616161", true, false, false, false, 8)
    )

    /** Palette offered by the long-press colour picker. */
    val PALETTE = listOf(
        "#2E7D32", "#43A047", "#00838F", "#1565C0", "#3949AB",
        "#6A1B9A", "#AD1457", "#D32F2F", "#EF6C00", "#F9A825",
        "#616161", "#37474F"
    )
}

/** How a sender should be treated by the AI stage. */
enum class SenderPolicy { UNKNOWN, ALWAYS_ANALYZE, NEVER_ANALYZE }

/** Result of the local (offline) scoring pass. */
data class LocalVerdict(
    val categoryId: String,
    val score: Int,
    val reasons: List<String>,
    val isSuspicious: Boolean,
    /** 50..99 — heuristic distance from the decision boundary, not a calibrated probability. */
    val confidence: Int = 50
)

/** Result returned by the AI stage. */
data class AiVerdict(
    val categoryId: String,
    val score: Int,
    val reason: String
)

/** One row in the conversation list. */
data class ThreadSummary(
    val threadId: Long,
    val messageId: Long,
    val address: String,
    val snippet: String,
    val date: Long,
    val unreadCount: Int,
    val categoryId: String,
    val colorHex: String
)

/** One message inside a conversation. */
data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val isIncoming: Boolean,
    val categoryId: String
)

/** A message a rule blocked outright. */
data class BlockedMessage(
    val address: String,
    val body: String,
    val date: Long,
    val rulePattern: String
)
