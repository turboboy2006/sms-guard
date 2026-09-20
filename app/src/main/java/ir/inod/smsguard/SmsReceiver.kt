package ir.inod.smsguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Receives incoming SMS while this app holds the default-SMS role.
 *
 * Latency contract: everything up to and including the notification is local
 * and synchronous. The AI stage is submitted only afterwards, so a slow or
 * unreachable provider can never delay delivery or delay an OTP.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val address = messages.firstNotNullOfOrNull { it.originatingAddress }
            ?: messages.firstOrNull()?.displayOriginatingAddress
            ?: return

        val body = joinBodies(messages.mapNotNull { it.displayMessageBody })
        if (body.isBlank()) return

        val timestamp = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        val pending = goAsync()
        Thread {
            try {
                handle(context, address, body, timestamp)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to handle incoming SMS", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(context: Context, address: String, body: String, timestamp: Long) {
        // 1. Hard blocking rules come first.
        val rule = RuleStore(context).blockingRuleFor(address, body)
        if (rule != null) {
            BlockedStore(context).add(BlockedMessage(address, body, timestamp, rule.pattern))
            Log.i(TAG, "Blocked SMS from $address by rule '${rule.pattern}'")
            return
        }

        // 2. Local classification. No network, so this is instant.
        val localCategory = Classifier.rememberSender(context, address, body)

        // 2b. Feed the behavioural profile: volume, burst and recency. This is
        // what later lets the classifier trust or distrust a sender.
        SenderProfileStore(context).recordIncoming(address, timestamp)
        Classifier.invalidateCaches()

        // 3. Persist and notify: this is the point the user sees the message.
        val repo = SmsRepository(context)
        val messageId = repo.storeIncoming(address, body, timestamp)
        val threadId = repo.threadIdFor(address)
        if (messageId >= 0 && threadId >= 0) {
            Notifier(context).notifyIncoming(threadId, address, body)
        }

        // 4. Fold the message into a campaign cluster. Local and cheap.
        if (messageId >= 0) {
            CampaignStore(context).record(messageId, address, body, timestamp)
        }

        // 5. Only now, off the critical path, may the AI look at it.
        if (messageId >= 0) {
            AnalysisPipeline.submitIfNeeded(
                context = context,
                messageId = messageId,
                address = address,
                body = body,
                localCategory = localCategory
            )
        }
    }

    /**
     * A multipart SMS arrives as several SmsMessage objects. Some platform
     * versions return the fully reassembled body for every part, others return
     * one segment each. Detect the duplicated case before joining.
     */
    private fun joinBodies(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return parts[0]
        return if (parts.all { it == parts[0] }) parts[0] else parts.joinToString("")
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
