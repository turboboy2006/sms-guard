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
        if (intent?.action == Intent.ACTION_RESPOND_VIA_MESSAGE) {
            val recipients = intent.getStringArrayExtra(Intent.EXTRA_EMAIL).orEmpty()
            val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
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
}
