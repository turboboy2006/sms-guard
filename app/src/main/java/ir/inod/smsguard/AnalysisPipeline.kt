package ir.inod.smsguard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** In-process change notifications so open screens can refresh themselves. */
object MessageBus {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun register(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun unregister(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        if (listeners.isEmpty()) return
        main.post { listeners.forEach { runCatching { it() } } }
    }
}

/**
 * Runs the AI stage strictly *after* a message has been stored and shown.
 *
 * Ordering is the whole point: storing and notifying happen on the caller's
 * thread with no network involved, so delivery latency is unaffected even when
 * the AI provider is slow or unreachable.
 */
object AnalysisPipeline {

    private const val TAG = "AnalysisPipeline"

    private val executor = Executors.newFixedThreadPool(2)

    /**
     * @param onDone invoked on the main thread when the category changed.
     */
    fun submitIfNeeded(
        context: Context,
        messageId: Long,
        address: String,
        body: String,
        localCategory: String,
        onDone: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val settings = SettingsStore(appContext)
        if (!settings.aiEnabled || settings.aiApiKey.isBlank()) return

        val senders = SenderStore(appContext)
        if (senders.policyFor(address) == SenderPolicy.NEVER_ANALYZE) return

        val cats = CategoryStore(appContext)
        val localCat = cats.byId(localCategory)
        // Bank, OTP, contacts and anything else flagged skipAi are never sent.
        if (localCat != null && localCat.skipAi) {
            senders.setPolicy(address, SenderPolicy.NEVER_ANALYZE)
            return
        }

        val verdict = Classifier.classifyLocal(appContext, address, body)
        if (!verdict.isSuspicious) return

        executor.execute {
            try {
                val ai = AiAnalyzer(settings).analyze(address, body) ?: return@execute
                // Trust the AI only when it agrees the message is unwanted.
                if (ai.categoryId == Cat.SUSPICIOUS || ai.categoryId == Cat.SPAM) {
                    MessageCategoryStore(appContext).set(messageId, ai.categoryId)
                    MessageBus.notifyChanged()
                    onDone?.invoke()
                } else if (ai.categoryId == Cat.PROMOTION) {
                    MessageCategoryStore(appContext).set(messageId, Cat.PROMOTION)
                    MessageBus.notifyChanged()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "AI stage failed for message $messageId", t)
            }
        }
    }
}
