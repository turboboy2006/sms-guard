package ir.inod.smsguard

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Keeps the stored inbox current while the app is closed.
 *
 * The receiver already patches the cache for a message that arrives live, so
 * what is left for the background is the work nobody is waiting for: noticing
 * that a conversation was deleted in another app, and letting the optional AI
 * stage take another look at what is still pending.
 *
 * This is a plain `JobService` rather than WorkManager on purpose. The whole
 * dependency list of this app is five AndroidX artefacts and a build runs in
 * the cloud; a periodic job that does one bounded thing does not justify a new
 * dependency chain, and JobScheduler has been on every supported API level
 * since before this app's minSdk.
 */
class InboxSyncJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val appContext = applicationContext
        Thread {
            var ok = false
            try {
                val repo = SmsRepository(appContext)
                val threads = repo.loadThreads()
                ok = threads.isNotEmpty()
                // A phone with no messages at all is not a failure, it is an
                // empty inbox; the cache is simply left alone.
                Log.d(TAG, "background sync finished with ${threads.size} conversations")
            } catch (t: Throwable) {
                Log.w(TAG, "background sync failed", t)
            } finally {
                jobFinished(params, !ok)
            }
        }.start()
        // Work is happening on our own thread, so the job stays alive.
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val TAG = "InboxSyncJob"
        private const val JOB_ID = 4211

        /** Six hours: often enough to stay warm, rare enough to cost nothing. */
        private const val PERIOD_MS = 6L * 60L * 60L * 1000L

        /**
         * Schedules the periodic sync. Called once when the process starts;
         * JobScheduler replaces an existing job with the same id, so calling it
         * again is harmless.
         */
        fun schedule(context: Context) {
            try {
                val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
                val component = ComponentName(context, InboxSyncJob::class.java)
                // Already scheduled with the same period: leave it alone.
                scheduler.allPendingJobs.firstOrNull { it.id == JOB_ID }?.let { existing ->
                    if (existing.intervalMillis == PERIOD_MS) return
                }
                val job = JobInfo.Builder(JOB_ID, component)
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .setRequiresBatteryNotLow(true)
                    .build()
                scheduler.schedule(job)
            } catch (t: Throwable) {
                // A device that refuses the job still works; it just stays warm
                // through the receiver and the next launch instead.
                Log.w(TAG, "could not schedule the inbox sync", t)
            }
        }
    }
}
