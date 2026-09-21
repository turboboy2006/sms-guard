package ir.inod.smsguard

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * Posts "new message" notifications. A single notification per thread is
 * reused, so a conversation collapses into one entry instead of many.
 */
class Notifier(private val context: Context) {

    private val nm = NotificationManagerCompat.from(context)

    fun notifyIncoming(threadId: Long, address: String, body: String) {
        if (SenderStore(context).notificationsMuted(address)) return
        val intent = Intent(context, ConversationActivity::class.java).apply {
            putExtra(ConversationActivity.EXTRA_THREAD_ID, threadId)
            putExtra(ConversationActivity.EXTRA_ADDRESS, address)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = android.app.PendingIntent.getActivity(
            context,
            threadId.toInt(),
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            putExtra(NotificationReplyReceiver.EXTRA_ADDRESS, address)
            putExtra(NotificationReplyReceiver.EXTRA_THREAD_ID, threadId)
        }
        val replyPi = android.app.PendingIntent.getBroadcast(
            context,
            (threadId xor 0x5245504cL).toInt(),
            replyIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder(NotificationReplyReceiver.KEY_REPLY)
            .setLabel(context.getString(R.string.quick_reply))
            .build()
        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_send,
            context.getString(R.string.quick_reply),
            replyPi
        ).addRemoteInput(remoteInput).build()

        val readIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_MARK_READ
            putExtra(NotificationActionReceiver.EXTRA_THREAD_ID, threadId)
        }
        val readPi = android.app.PendingIntent.getBroadcast(
            context, (threadId xor 0x52454144L).toInt(), readIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val readAction = NotificationCompat.Action.Builder(
            R.drawable.ic_notification, context.getString(R.string.mark_read), readPi
        ).build()

        val otp = extractOtp(body)

        val builder = NotificationCompat.Builder(context, SmsApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ContactNames.displayName(context, address))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(replyAction)
            .addAction(readAction)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (otp != null) {
            val copyIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = NotificationActionReceiver.ACTION_COPY
                putExtra(NotificationActionReceiver.EXTRA_TEXT, otp)
            }
            val copyPi = android.app.PendingIntent.getBroadcast(
                context, (threadId xor otp.hashCode().toLong()).toInt(), copyIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    context.getString(R.string.copy_otp, otp),
                    copyPi
                ).build()
            )
        }
        val notification = builder.build()

        try {
            nm.notify(threadId.toInt(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was not granted; nothing useful to do here.
        }
    }

    private fun extractOtp(body: String): String? {
        val normalized = body
            .replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3')
            .replace('۴', '4').replace('۵', '5').replace('۶', '6').replace('۷', '7')
            .replace('۸', '8').replace('۹', '9')
        val hasHint = Regex("(?i)(otp|code|verification|password|رمز|کد|تایید|تأیید)").containsMatchIn(body)
        if (!hasHint) return null
        return Regex("(?<!\\d)\\d{4,8}(?!\\d)").find(normalized)?.value
    }

    fun cancel(threadId: Long) {
        nm.cancel(threadId.toInt())
    }

    companion object {
        @Suppress("unused")
        const val TAG = "Notifier"
    }
}

/** Best-effort contact lookup. Falls back to the raw address. */
object ContactNames {

    /**
     * The stored contact name, or the address itself when there is no contact.
     *
     * Resolution goes through [ContactsIndex]: the whole address book is read
     * once and matched in memory, so a listed inbox does not run a provider
     * query per visible row.
     */
    fun displayName(context: Context, address: String): String {
        if (address.isBlank()) return address
        return ContactsIndex.nameFor(context, address) ?: address
    }

    /**
     * The same, but never reads the provider itself.
     *
     * Used by anything that runs while the user is looking at a frame — a row
     * bind, a filter pass. If the index is not in memory yet the address is
     * shown as-is for this frame, and the next build after the background index
     * finishes shows the name. A brief phone number is a much better outcome
     * than a frozen list.
     */
    fun displayNameUi(address: String): String {
        if (address.isBlank()) return address
        return ContactsIndex.readyEntryFor(address)?.name ?: address
    }
}
