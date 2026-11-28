package ir.inod.smsguard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * One bounded, offline-only mailbox pass after first setup.
 *
 * The result is written as a per-message override, never as a sender-wide
 * decision. It is safe to run without network access and is committed in
 * batches, so an interrupted pass keeps the work it already did instead of
 * starting over the next time.
 *
 * Bumping [VERSION] makes the next launch re-label the whole mailbox with the
 * current rules: an inbox labelled by yesterday's classifier would otherwise
 * keep its old 「خدماتی」 tags forever, because a stored verdict is read before
 * anything is re-classified.
 */
object OfflineInboxClassifier {
    private const val TAG = "SmsGuardClassify"
    private const val PREFS = "offline_initial_classification"
    private const val KEY_VERSION = "completed_version"
    private const val KEY_STATUS = "last_status"
    private const val VERSION = 4
    private const val BATCH = 400
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun scheduleIfNeeded(context: Context) {
        val app = context.applicationContext
        if (app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VERSION, 0) >= VERSION) return
        executor.execute { run(app, announce = false) }
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
        executor.execute { run(app, announce = true) }
    }

    /** گزارش آخرین اجرا، برای نمایش در «ابزارها». */
    fun lastStatus(context: Context): String? =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, null)

    private fun run(context: Context, announce: Boolean) {
        val startedAt = System.currentTimeMillis()
        Log.i(TAG, "reclassify start v$VERSION announce=$announce")
        val status = try {
            pass(context)
        } catch (t: Throwable) {
            Log.e(TAG, "reclassify failed", t)
            "ناموفق: ${t.javaClass.simpleName} ${t.message.orEmpty()}".trim()
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATUS, status).apply()
        Log.i(TAG, "reclassify done in ${System.currentTimeMillis() - startedAt}ms: $status")
        if (announce) {
            main.post {
                Toast.makeText(context, status, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * برچسب‌های ماشین از نو نوشته می‌شوند؛ انتخاب دستی کاربر دست‌نخورده
     * می‌ماند. پیامی که با قواعد جدید «دسته‌بندی‌نشده» شد برچسب قبلی‌اش پاک
     * می‌شود تا دفعهٔ بعد هم دوباره سنجیده شود.
     *
     * @return خلاصهٔ متنی نتیجه، برای نمایش به کاربر.
     */
    private fun pass(context: Context): String {
        val store = MessageCategoryStore(context)
        val userChoices = store.userChoices()
        val fresh = HashMap<Long, String>(8192)
        var scanned = 0
        var sinceCommit = 0
        val projection = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY)
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, null, null, "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val address = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            while (cursor.moveToNext()) {
                scanned++
                val messageId = cursor.getLong(id)
                if (userChoices.containsKey(messageId)) continue
                val sender = cursor.getString(address).orEmpty()
                val text = cursor.getString(body).orEmpty()
                val category = Classifier.classifyLocal(
                    context, sender, text, withReasons = false
                ).categoryId
                if (category != Cat.OTHER) fresh[messageId] = category else fresh.remove(messageId)
                if (++sinceCommit >= BATCH) {
                    commit(context, store, fresh)
                    sinceCommit = 0
                }
            }
        } ?: return "ناموفق: صندوق پیام خوانده نشد"

        commit(context, store, fresh)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_VERSION, VERSION).apply()
        val spam = fresh.values.count { it == Cat.SPAM }
        return "بازدسته‌بندی انجام شد: $scanned پیام بررسی شد، $spam مورد اسپم/تبلیغ"
    }

    private fun commit(context: Context, store: MessageCategoryStore, fresh: Map<Long, String>) {
        store.replaceAll(fresh)
        Classifier.invalidateCaches()
        ThreadCache.clear(context)
        MessageBus.notifyChanged()
        Log.i(TAG, "reclassify commit: ${fresh.size} labelled")
    }
}
