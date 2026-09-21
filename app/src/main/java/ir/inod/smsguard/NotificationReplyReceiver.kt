package ir.inod.smsguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Sends a notification reply without opening the conversation screen. */
class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        if (address.isBlank() || text.isBlank()) return

        val pending = goAsync()
        Thread {
            try {
                if (SmsRepository(context).send(address, text)) {
                    val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
                    if (threadId >= 0) Notifier(context).cancel(threadId)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val KEY_REPLY = "sms_guard_inline_reply"
        const val EXTRA_ADDRESS = "reply_address"
        const val EXTRA_THREAD_ID = "reply_thread_id"
    }
}
