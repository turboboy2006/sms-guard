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
        createChannel()
        watchContacts()
        InboxSyncJob.schedule(this)
        OfflineInboxClassifier.scheduleIfNeeded(this)
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
    }
}
