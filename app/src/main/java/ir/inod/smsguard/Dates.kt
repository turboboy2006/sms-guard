package ir.inod.smsguard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Date/time labels used by the lists. */
object Dates {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun listLabel(millis: Long): String {
        val now = System.currentTimeMillis()
        val age = now - millis
        val pattern = when {
            age < DAY_MS -> "HH:mm"
            age < 7 * DAY_MS -> "EEE"
            else -> "yyyy/MM/dd"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
    }

    fun full(millis: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(millis))
}
