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
 *
 * Bumping [VERSION] makes the next launch re-label the whole mailbox with the
 * current rules: an inbox labelled by yesterday's classifier would otherwise
 * keep its old 「خدماتی」 tags forever, because a stored verdict is read before
 * anything is re-classified.
 */
object OfflineInboxClassifier {
    private const val PREFS = "offline_initial_classification"
    private const val KEY_VERSION = "completed_version"
    private const val VERSION = 3
    private val executor = Executors.newSingleThreadExecutor()

    fun scheduleIfNeeded(context: Context) {
        val app = context.applicationContext
        if (app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, 0) >= VERSION) return
        executor.execute { run(app) }
    }

    /**
     * بازدسته‌بندی دستی کل صندوق با قواعد فعلی.
     * پیام‌های قدیمی برچسب زمان دریافتشان را دارند و با قوانین جدید
     * (مثل بلک‌فرایدی) به‌روز نمی‌شوند؛ این تابع همان‌ها را دوباره می‌سنجد.
     */
    fun runManually(context: Context) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_VERSION).apply()
        Classifier.invalidateCaches()
        executor.execute { run(app) }
    }

    /**
     * هر پیام صندوق با قواعد فعلی دوباره سنجیده می‌شود.
     *
     * برچسب‌هایی که کاربر دستی گذاشته دست‌نخورده می‌مانند؛ بقیه از نو
     * نوشته می‌شوند. پیامی که با قواعد جدید «دسته‌بندی‌نشده» شد برچسب
     * قبلی‌اش پاک می‌شود تا دفعهٔ بعد هم دوباره سنجیده شود.
     */
    private fun run(context: Context) {
        val store = MessageCategoryStore(context)
        val userChoices = store.userChoices()
        val fresh = mutableMapOf<Long, String>()
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
                    if (userChoices.containsKey(messageId)) continue
                    val sender = cursor.getString(address).orEmpty()
                    val text = cursor.getString(body).orEmpty()
                    val category = Classifier.classifyLocal(context, sender, text).categoryId
                    // Only preserve a stable semantic result. Uncategorized
                    // messages remain free for future, improved classifiers.
                    if (category != Cat.OTHER) fresh[messageId] = category
                }
            } ?: run { completed = false }
        } catch (_: SecurityException) {
            completed = false
        } catch (_: Exception) {
            completed = false
        }
        if (completed) {
            store.replaceAll(fresh)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_VERSION, VERSION).apply()
            Classifier.invalidateCaches()
            ThreadCache.clear(context)
            MessageBus.notifyChanged()
        }
    }
}
