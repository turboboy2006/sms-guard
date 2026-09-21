package ir.inod.smsguard

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** Safe, process-local notification actions that do not open an Activity. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_COPY -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isBlank()) return
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.otp_code), text))
                Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            ACTION_MARK_READ -> {
                val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
                if (threadId < 0) return
                val pending = goAsync()
                Thread {
                    try {
                        SmsRepository(context).markThreadRead(threadId)
                        Notifier(context).cancel(threadId)
                    } finally { pending.finish() }
                }.start()
            }
        }
    }

    companion object {
        const val ACTION_COPY = "ir.inod.smsguard.COPY_NOTIFICATION_CODE"
        const val ACTION_MARK_READ = "ir.inod.smsguard.MARK_NOTIFICATION_READ"
        const val EXTRA_TEXT = "text"
        const val EXTRA_THREAD_ID = "thread_id"
    }
}
