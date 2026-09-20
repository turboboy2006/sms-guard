package ir.inod.smsguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Receives incoming SMS while this app holds the default-SMS role.
 *
 * Because the platform does not write messages to the provider for the default
 * app, this receiver decides what happens to each message:
 *   - a rule matches  -> message is dropped, logged locally, no notification
 *   - otherwise       -> message is written to the inbox and a notification is shown
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

        // Work must finish after onReceive returns, so keep the process alive.
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
        val rule = RuleStore(context).blockingRuleFor(address, body)

        if (rule != null) {
            BlockedStore(context).add(
                BlockedMessage(
                    address = address,
                    body = body,
                    date = timestamp,
                    rulePattern = rule.pattern
                )
            )
            // Deliberately no provider insert and no notification.
            Log.i(TAG, "Blocked SMS from $address by rule '${rule.pattern}'")
            return
        }

        val threadId = SmsRepository(context).storeIncoming(address, body, timestamp)
        if (threadId >= 0) {
            Notifier(context).notifyIncoming(threadId, address, body)
        }
    }

    /**
     * A multipart SMS arrives as several SmsMessage objects. Some platform
     * versions return the fully reassembled body for every part, others return
     * a single segment each. Detect the duplicated case before joining,
     * otherwise the stored body would be repeated N times.
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
