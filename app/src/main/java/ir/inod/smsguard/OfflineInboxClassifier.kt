package ir.inod.smsguard

import android.content.Context
import android.provider.Telephony
import java.util.concurrent.Executors

/**
 * One bounded, offline-only mailbox pass after first setup.
 *
 * The result is written as a per-message override, never as a sender-wide
 * decision. It is safe to run without network access and can be resumed after
 * interruption because unclassified messages are simply considered next time.
 */
object OfflineInboxClassifier {
    private const val PREFS = "offline_initial_classification"
    private const val KEY_VERSION = "completed_version"
    private const val VERSION = 2
    private val executor = Executors.newSingleThreadExecutor()

    fun scheduleIfNeeded(context: Context) {
        val app = context.applicationContext
        if (app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, 0) >= VERSION) return
        executor.execute { run(app) }
    }

    private fun run(context: Context) {
        val overrides = MessageCategoryStore(context)
        val indexed = overrides.all()
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY)
        var completed = true
        try {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                while (cursor.moveToNext()) {
                    val messageId = cursor.getLong(id)
                    if (indexed.containsKey(messageId)) continue
                    val sender = cursor.getString(address).orEmpty()
                    val text = cursor.getString(body).orEmpty()
                    val category = Classifier.classifyLocal(context, sender, text).categoryId
                    // Only preserve a stable semantic result. Uncategorized
                    // messages remain free for future, improved classifiers.
                    if (category != Cat.OTHER) indexed[messageId] = category
                }
            } ?: run { completed = false }
        } catch (_: SecurityException) {
            completed = false
        } catch (_: Exception) {
            completed = false
        }
        if (completed) {
            overrides.replaceAll(indexed)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_VERSION, VERSION).apply()
            Classifier.invalidateCaches()
            ThreadCache.clear(context)
            MessageBus.notifyChanged()
        }
    }
}
