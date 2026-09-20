package ir.inod.smsguard

/** Where a rule looks for its pattern. */
enum class RuleTarget { SENDER, BODY, BOTH }

/**
 * A single blocking rule. Matching is case-insensitive.
 * An invalid regex never throws: it simply does not match, so a bad rule
 * can never crash the SMS receiver.
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

/** One row in the conversation list. */
data class ThreadSummary(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val date: Long,
    val unreadCount: Int
)

/** One message inside a conversation. */
data class SmsMessage(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val isIncoming: Boolean
)

/** A message that a rule blocked, kept locally so false positives are reviewable. */
data class BlockedMessage(
    val address: String,
    val body: String,
    val date: Long,
    val rulePattern: String
)
