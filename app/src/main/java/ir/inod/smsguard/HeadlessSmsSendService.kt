package ir.inod.smsguard

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Backs the system "quick reply" flow (for example replying to a call with a
 * message). Android requires this service for default-SMS-app eligibility.
 */
class HeadlessSmsSendService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Captured into a local val so the null check smart-casts.
        val command = intent
        if (command != null && command.action == ACTION_RESPOND_VIA_MESSAGE) {
            val recipients = command.getStringArrayExtra(Intent.EXTRA_EMAIL).orEmpty()
            val body = command.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (recipients.isNotEmpty() && body.isNotBlank()) {
                val repo = SmsRepository(this)
                for (recipient in recipients) {
                    repo.send(recipient, body)
                }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private companion object {
        /**
         * The platform constant for this action is not exposed on all SDK
         * levels, so the documented action string is used directly.
         */
        const val ACTION_RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE"
    }
}
