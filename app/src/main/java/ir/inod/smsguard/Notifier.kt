package ir.inod.smsguard

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
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

    fun displayName(context: Context, address: String): String {
        if (address.isBlank()) return address
        var cursor: Cursor? = null
        try {
            val uri: Uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(address)
            )
            cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val name = cursor.getString(0)
                if (!name.isNullOrBlank()) return name
            }
        } catch (e: Exception) {
            // READ_CONTACTS missing or provider unavailable.
        } finally {
            cursor?.close()
        }
        return address
    }
}
