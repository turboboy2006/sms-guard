package ir.inod.smsguard

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts "new message" notifications. A single notification per thread is
 * reused, so a conversation collapses into one entry instead of many.
 */
class Notifier(private val context: Context) {

    private val nm = NotificationManagerCompat.from(context)

    fun notifyIncoming(threadId: Long, address: String, body: String) {
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

        val notification = NotificationCompat.Builder(context, SmsApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ContactNames.displayName(context, address))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            nm.notify(threadId.toInt(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was not granted; nothing useful to do here.
        }
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
