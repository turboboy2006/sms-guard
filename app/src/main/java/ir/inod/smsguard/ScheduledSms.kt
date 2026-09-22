package ir.inod.smsguard

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import org.json.JSONArray
import org.json.JSONObject

data class ScheduledSms(val id: Int, val address: String, val body: String, val at: Long)

class ScheduledSmsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("scheduled_sms", Context.MODE_PRIVATE)
    fun all(): List<ScheduledSms> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let {
            ScheduledSms(it.getInt("id"), it.getString("address"), it.getString("body"), it.getLong("at"))
        } }.sortedBy { it.at }
    }.getOrDefault(emptyList())
    private fun save(items: List<ScheduledSms>) {
        val arr = JSONArray(); items.forEach { item -> arr.put(JSONObject().apply {
            put("id", item.id); put("address", item.address); put("body", item.body); put("at", item.at)
        }) }; prefs.edit().putString("items", arr.toString()).apply()
    }
    fun schedule(address: String, body: String, at: Long): Boolean {
        val id = ((System.currentTimeMillis() xor address.hashCode().toLong()) and 0x7fffffff).toInt().coerceAtLeast(1000)
        val item = ScheduledSms(id, address, body, at)
        val extras = PersistableBundle().apply { putInt("id", id); putString("address", address); putString("body", body) }
        val job = JobInfo.Builder(id, ComponentName(context, ScheduledSmsJob::class.java))
            .setMinimumLatency((at - System.currentTimeMillis()).coerceAtLeast(0))
            .setPersisted(true).setExtras(extras).build()
        val ok = context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS
        if (ok) save(all() + item)
        return ok
    }
    /** Recreates Android jobs after a portable backup is restored into a fresh install. */
    fun restoreJobs() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        all().forEach { item ->
            if (item.at <= System.currentTimeMillis()) return@forEach
            val extras = PersistableBundle().apply {
                putInt("id", item.id); putString("address", item.address); putString("body", item.body)
            }
            scheduler.schedule(JobInfo.Builder(item.id, ComponentName(context, ScheduledSmsJob::class.java))
                .setMinimumLatency(item.at - System.currentTimeMillis())
                .setPersisted(true).setExtras(extras).build())
        }
    }
    fun remove(id: Int, cancel: Boolean = true) {
        if (cancel) context.getSystemService(JobScheduler::class.java).cancel(id)
        save(all().filterNot { it.id == id })
    }
}

class ScheduledSmsJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val id = params.extras.getInt("id")
            SmsRepository(this).send(params.extras.getString("address", ""), params.extras.getString("body", ""))
            ScheduledSmsStore(this).remove(id, cancel = false)
            jobFinished(params, false)
        }.start(); return true
    }
    override fun onStopJob(params: JobParameters): Boolean = true
}
