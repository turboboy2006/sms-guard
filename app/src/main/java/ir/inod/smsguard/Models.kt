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

    /** Product-facing aliases: older stored ids remain readable after upgrades. */
    const val SERVICES = NOTIFICATION
    const val UNCATEGORIZED = OTHER
}

/** Where a blocking rule looks for its pattern. */
enum class RuleTarget { SENDER, BODY, BOTH }

/** How a plain-language rule combines its included words. */
enum class RuleJoin { ANY, ALL }

/** A rule can hide a message outright or file it where the user expects. */
enum class RuleAction(val categoryId: String?) {
    BLOCK(null), SPAM(Cat.SPAM), PROMOTION(Cat.NOTIFICATION)
}

/**
 * A blocking rule. Matching is case-insensitive and an invalid regex simply
 * never matches, so a bad rule can never crash the SMS receiver.
 */
data class Rule(
    val id: Long,
    val pattern: String,
    val target: RuleTarget = RuleTarget.BOTH,
    val isRegex: Boolean = false,
    val enabled: Boolean = true,
    val excluded: String = "",
    val join: RuleJoin = RuleJoin.ANY,
    val action: RuleAction = RuleAction.BLOCK
) {
    private fun words(value: String): List<String> = value
        .split(',', '،', '\n')
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }

    fun matches(address: String, body: String): Boolean {
        if (!enabled || pattern.isBlank()) return false
        val addr = address.lowercase()
        val haystack = when (target) {
            RuleTarget.SENDER -> addr
            RuleTarget.BODY -> body.lowercase()
            RuleTarget.BOTH -> "$addr\n${body.lowercase()}"
        }
        return try {
            if (isRegex) {
                val re = Regex(pattern, RegexOption.IGNORE_CASE)
                re.containsMatchIn(haystack)
            } else {
                val included = words(pattern)
                val positive = when (join) {
                    RuleJoin.ANY -> included.any(haystack::contains)
                    RuleJoin.ALL -> included.all(haystack::contains)
                }
                positive && words(excluded).none(haystack::contains)
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
    val order: Int = 0,
    /**
     * Whether a conversation in this category wears a pill in the inbox.
     *
     * Every category can print a label; only some are worth the ink. A promo is
     * a promo because the text already reads like one, so a grey "تبلیغاتی" pill
     * on each of them is noise — while "بانکی" or a risk reason is a real
     * summary. This mirrors the reference, which badges the categories a reader
     * actually scans for.
     */
    val showBadge: Boolean = true,
    /** Pill colours; -1 means "use the shared neutral/badge palette". */
    val badgeBgColor: Int = -1,
    val badgeTextColor: Int = -1,
    val iconId: String? = null,
    /** Disabled categories fall back to Uncategorized without losing messages. */
    val enabled: Boolean = true
) {
    fun label(context: Context): String =
        if (nameRes != 0) context.getString(nameRes) else customName

    companion object {
        /** Sentinel for "no opinion"; the adapter substitutes the neutral pair. */
        const val NO_COLOR = -1
    }
}

object Categories {

    /**
     * Built-in categories.
     *
     * The badge decision is part of the data rather than the adapter, because
     * it is a product judgement about each category:
     *
     *   banking / one-time codes  a tinted pill — the reader scans for these;
     *   service / contacts        a neutral grey pill;
     *   promotional / other       no pill, the message already reads as one;
     *   spam / trash              never seen in the list in the first place.
     *
     * Suspicious is handled separately by the adapter, which prints the risk
     * *reason* there instead of the word "suspicious".
     */
    fun system(): List<Category> = listOf(
        Category(
            Cat.PERSONAL, R.string.cat_personal, "", "#15803D", true, false, true, true, 0,
            showBadge = true
        ),
        Category(
            Cat.BANKING, R.string.cat_banking, "", "#1D4ED8", true, false, true, true, 1,
            badgeBgColor = 0xFFDBEAFE.toInt(), badgeTextColor = 0xFF1E40AF.toInt()
        ),
        Category(
            Cat.OTP, R.string.cat_otp, "", "#B45309", true, false, true, true, 2,
            badgeBgColor = 0xFFFEF3C7.toInt(), badgeTextColor = 0xFF92400E.toInt()
        ),
        Category(
            Cat.NOTIFICATION, R.string.cat_notification, "", "#7C3AED", true, false, true, true, 3,
            showBadge = true
        ),
        // A promotional pill said nothing the message did not: gone from the
        // list, which is exactly what the reference does.
        // Promotion and suspicious are retained as legacy IDs so backups made by
        // earlier versions still open, but the public taxonomy is deliberately
        // small: service, spam and uncategorized are easier to understand.
        Category(Cat.PROMOTION, R.string.cat_promotion, "", "#EA580C", true, false, false, false, 40,
            showBadge = false, enabled = false),
        Category(Cat.SUSPICIOUS, R.string.cat_suspicious, "", "#E11D48", true, false, false, false, 41,
            showBadge = false, enabled = false),
        Category(Cat.SPAM, R.string.cat_spam, "", "#B91C1C", true, true, true, false, 4, showBadge = false),
        // Trash is its own place: hidden from Every, but not counted as spam.
        Category(Cat.TRASH, R.string.tab_trash, "", "#475467", true, false, true, false, 7, showBadge = false),
        Category(Cat.OTHER, R.string.cat_other, "", "#667085", true, false, false, false, 5, showBadge = false)
    )

    /** Palette offered by the long-press colour picker. */
    val PALETTE = listOf(
        "#2E7D32", "#43A047", "#00838F", "#1565C0", "#3949AB",
        "#6A1B9A", "#AD1457", "#D32F2F", "#EF6C00", "#F9A825",
        "#616161", "#37474F", "#0D9488", "#06B6D4", "#0284C7",
        "#4F46E5", "#7C3AED", "#A855F7", "#DB2777", "#F43F5E",
        "#FB7185", "#F97316", "#EAB308", "#84CC16", "#16A34A",
        "#14B8A6", "#64748B", "#8B5CF6", "#EC4899", "#B45309"
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
    val colorHex: String,
    /**
     * Ready-made "why is this suspicious" label. Computed once while the row is
     * classified rather than on every bind: it runs the whole local classifier,
     * which is far too heavy for a scroll.
     */
    val riskLabel: String? = null,
    /**
     * false while a row is a placeholder restored from the inbox cache before
     * the provider pass has confirmed it.
     */
    val known: Boolean = true,
    val pinned: Boolean = false,
    val delivery: DeliveryState? = null
)

/** One message inside a conversation. */
data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val isIncoming: Boolean,
    val categoryId: String,
    val delivery: DeliveryState = DeliveryState.RECEIVED,
    val errorCode: Int = 0,
    val subscriptionId: Int = -1
)

/** Delivery lifecycle for outgoing SMS rows. */
enum class DeliveryState {
    RECEIVED, SENDING, SENT, DELIVERED, FAILED
}

/** A message a rule blocked outright. */
data class BlockedMessage(
    val address: String,
    val body: String,
    val date: Long,
    val rulePattern: String
)
