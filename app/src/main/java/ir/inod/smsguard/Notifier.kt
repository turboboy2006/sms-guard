package ir.inod.smsguard

import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.net.Uri
import androidx.core.app.TaskStackBuilder

/**
 * Posts "new message" notifications. A single notification per thread is
 * reused, so a conversation collapses into one entry instead of many.
 */
class Notifier(private val context: Context) {

    private val nm = NotificationManagerCompat.from(context)

    fun notifyIncoming(threadId: Long, address: String, body: String, categoryId: String) {
        if (SenderStore(context).notificationsMuted(address)) return
        val categorySettings = CategoryNotificationStore(context).get(categoryId)
        if (categorySettings.mode == CategoryAlertMode.OFF) return
        val channelId = ensureChannel(address, categoryId, categorySettings)
        val intent = Intent(context, ConversationActivity::class.java).apply {
            putExtra(ConversationActivity.EXTRA_THREAD_ID, threadId)
            putExtra(ConversationActivity.EXTRA_ADDRESS, address)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = TaskStackBuilder.create(context)
            .addNextIntent(Intent(context, MainActivity::class.java))
            .addNextIntent(intent)
            .getPendingIntent(threadId.toInt(), android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE) ?: return
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

        val builder = NotificationCompat.Builder(context, channelId)
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
            .setVisibility(if (categorySettings.showOnLockScreen)
                NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            .setSilent(categorySettings.mode == CategoryAlertMode.SILENT)
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
            if (categorySettings.wakeScreen) wakeScreenBriefly()
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS was not granted; nothing useful to do here.
        }
    }

    private fun ensureChannel(address: String, categoryId: String,
                              settings: CategoryNotificationSettings): String {
        val senderSound = SenderStore(context).notificationSound(address)
        val sound = senderSound ?: settings.soundUri.takeIf { settings.mode == CategoryAlertMode.CUSTOM }
        val senderSuffix = if (senderSound == null) "" else
            "_${address.hashCode().toUInt().toString(16)}_${senderSound.hashCode().toUInt().toString(16)}"
        val id = "sms_cat_${categoryId.hashCode().toUInt().toString(16)}_${settings.revision}$senderSuffix"
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
            nm.createNotificationChannel(NotificationChannel(
                id, CategoryStore(context).byId(categoryId)?.label(context) ?: categoryId,
                if (settings.mode == CategoryAlertMode.SILENT) NotificationManager.IMPORTANCE_LOW
                else NotificationManager.IMPORTANCE_HIGH
            ).apply {
                if (settings.mode == CategoryAlertMode.SILENT || settings.mode == CategoryAlertMode.VIBRATE_ONLY) setSound(null, null)
                else if (!sound.isNullOrBlank()) setSound(Uri.parse(sound), attrs)
                enableVibration(settings.mode == CategoryAlertMode.VIBRATE_ONLY ||
                    (settings.vibrate && settings.mode != CategoryAlertMode.SILENT))
                enableLights(settings.mode != CategoryAlertMode.SILENT)
                lockscreenVisibility = if (settings.showOnLockScreen)
                    android.app.Notification.VISIBILITY_PRIVATE else android.app.Notification.VISIBILITY_SECRET
            })
        }
        return id
    }

    fun resetSenderChannel(address: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(senderChannelId(address))
        val senderKey = "_${address.hashCode().toUInt().toString(16)}_"
        manager.notificationChannels.filter { it.id.startsWith("sms_cat_") && it.id.contains(senderKey) }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }

    fun resetCategoryChannels(categoryId: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val prefix = "sms_cat_${categoryId.hashCode().toUInt().toString(16)}_"
        manager.notificationChannels.filter { it.id.startsWith(prefix) }
            .forEach { manager.deleteNotificationChannel(it.id) }
    }

    private fun wakeScreenBriefly() {
        runCatching {
            val power = context.getSystemService(android.os.PowerManager::class.java)
            @Suppress("DEPRECATION")
            power.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "SmsGuard:categoryAlert")
                .acquire(2500L)
        }
    }

    private fun senderChannelId(address: String) = "sms_sender_${address.hashCode().toUInt().toString(16)}"

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
