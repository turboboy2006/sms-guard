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
                val subscriptionId = intent.getIntExtra(
                    android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1
                )
                handle(context, address, body, timestamp, subscriptionId)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to handle incoming SMS", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(context: Context, address: String, body: String, timestamp: Long, subscriptionId: Int) {
        // 1. User rules come first. Only an explicit Block rule stops delivery;
        // Spam and promotional rules keep the message accessible and merely
        // file it in the requested category.
        val rule = RuleStore(context).blockingRuleFor(address, body)
        if (rule?.action == RuleAction.BLOCK) {
            BlockedStore(context).add(BlockedMessage(address, body, timestamp, rule.pattern))
            Log.i(TAG, "Blocked SMS from $address by rule '${rule.pattern}'")
            return
        }

        // 2. Local classification. No network, so this is instant.
        var localCategory = Classifier.rememberSender(context, address, body)

        // 2b. Feed the behavioural profile: volume, burst and recency. This is
        // what later lets the classifier trust or distrust a sender.
        SenderProfileStore(context).recordIncoming(address, timestamp)
        Classifier.invalidateCaches()

        // 3. Persist and notify: this is the point the user sees the message.
        val repo = SmsRepository(context)
        val messageId = repo.storeIncoming(address, body, timestamp, subscriptionId)
        val threadId = repo.threadIdFor(address)

        rule?.action?.categoryId?.let { category ->
            if (messageId >= 0) {
                MessageCategoryStore(context).set(messageId, category)
                Classifier.invalidateCaches()
                localCategory = category
            }
        }

        // The stored inbox is patched here, inside the receiver, so the next
        // launch is correct even if the app itself is never opened in between.
        repo.patchCacheForNewMessage(address, body, timestamp, messageId)

        // Quiet hours. Off unless the user turned it on, and even then it can
        // only ever silence promotional, suspicious or spam traffic — banking,
        // OTP and contact messages always notify, because those are exactly the
        // ones that matter at night. See [QuietHours].
        val suppressed = QuietHours.shouldSuppress(context, localCategory)

        if (messageId >= 0 && threadId >= 0 && !suppressed) {
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
