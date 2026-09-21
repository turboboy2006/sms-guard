package ir.inod.smsguard

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors

/** User-started, bounded inbox pass. AI results are converted into local learning. */
object AiInboxScanner {
    private const val MAX_CANDIDATES = 1000
    private val executor = Executors.newSingleThreadExecutor()

    fun scan(context: Context, onProgress: (done: Int, total: Int, learned: Int) -> Unit, onDone: (learned: Int) -> Unit) {
        val app = context.applicationContext
        val settings = SettingsStore(app)
        if (!settings.aiEnabled || settings.aiApiKey.isBlank()) { onDone(0); return }
        executor.execute {
            val candidates = SmsRepository(app).aiScanCandidates(MAX_CANDIDATES)
            val analyzer = AiAnalyzer(settings)
            var learned = 0
            candidates.forEachIndexed { index, message ->
                try {
                    val verdict = analyzer.analyze(message.address, message.body) ?: return@forEachIndexed
                    val unwanted = verdict.categoryId in setOf(Cat.SPAM, Cat.SUSPICIOUS, Cat.PROMOTION)
                    if (unwanted) {
                        MessageCategoryStore(app).set(message.id, verdict.categoryId)
                        LearnedWeights(app).record(message.body, true)
                        SenderProfileStore(app).recordFeedback(message.address, true)
                        if (verdict.categoryId == Cat.SPAM) CampaignStore(app).markSpam(message.id)
                        learned++
                    } else if (verdict.categoryId == Cat.OTHER || verdict.categoryId == Cat.NOTIFICATION) {
                        // A verified normal pattern is also a valuable local signal.
                        LearnedWeights(app).record(message.body, false)
                    }
                } catch (t: Throwable) {
                    Log.w("AiInboxScanner", "Skipping one AI scan item", t)
                }
                onProgress(index + 1, candidates.size, learned)
            }
            Classifier.invalidateCaches()
            ThreadCache.clear(app)
            MessageBus.notifyChanged()
            onDone(learned)
        }
    }
}
