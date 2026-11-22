package ir.inod.smsguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract

class SmsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appRef = this
        createChannel()
        watchContacts()
        /* کارهای زمان‌بندی روی رشتهٔ پس‌زمینه انجام می‌شوند تا شروع سرد سبک‌تر شود. */
        val app = this
        kotlin.concurrent.thread(isDaemon = true, name = "app-startup") {
            try {
                InboxSyncJob.schedule(app)
                OfflineInboxClassifier.scheduleIfNeeded(app)
            } catch (t: Throwable) {
                // زمان‌بندی نباید شروع اپ را بشکند.
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.channel_description)
            enableVibration(true)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * The inbox resolves sender names from an in-memory copy of the address
     * book. That copy is only invalidated when the address book actually
     * changes, so editing a contact is reflected on the next list build without
     * re-reading every contact on every launch.
     */
    private fun watchContacts() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                ContactsIndex.invalidate()
            }
        }
        runCatching {
            contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI, true, observer
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "sms_guard_messages"

        private var appRef: SmsApp? = null

        /** دسترسی به Context اپ برای بررسی‌های سراسری (مثل مجاز بودن شبکه). */
        fun app(): SmsApp? = appRef

        /** در flavor «offline» مقدار false است (بدون مجوز INTERNET). */
        fun networkAllowed(): Boolean {
            val a = appRef ?: return true
            return try {
                a.resources.getBoolean(R.bool.network_enabled)
            } catch (t: Throwable) {
                true
            }
        }
    }
}
