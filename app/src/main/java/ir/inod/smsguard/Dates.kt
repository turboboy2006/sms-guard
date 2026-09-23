package ir.inod.smsguard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Date labels.
 *
 * Persian uses the Jalali calendar with Persian day names and Persian digits;
 * every other locale falls back to the platform formatter. The active locale
 * comes from the resources configuration, so it follows the in-app language
 * picker rather than only the device setting.
 */
object Dates {

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val SECONDS_CUTOFF = 100_000_000_000L

    /** Imported/provider rows occasionally carry Unix seconds, not millis. */
    fun normalizedMillis(value: Long): Long = when {
        value <= 0L -> 0L
        value < SECONDS_CUTOFF -> value * 1000L
        else -> value
    }
    private fun stamp(value: Long) = normalizedMillis(value)

    private val FA_MONTHS = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    /** Calendar.DAY_OF_WEEK is 1=Sunday, so index 0 is Sunday. */
    private val FA_DAYS = arrayOf(
        "یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه"
    )

    fun isPersian(context: Context): Boolean =
        localeOf(context).language == "fa"

    private fun localeOf(context: Context): Locale =
        context.resources.configuration.locales[0]

    fun listLabel(context: Context, millis: Long): String {
        val date = stamp(millis)
        if (date == 0L) return context.getString(R.string.date_unknown)
        val age = System.currentTimeMillis() - date
        if (age in 0 until 60_000L) return if (isPersian(context)) "چند لحظه پیش" else "Just now"
        if (age in 60_000L until 60L * 60_000L) {
            val minutes = (age / 60_000L).toInt()
            return if (isPersian(context)) "${minutes} دقیقه پیش" else "${minutes} min"
        }
        val today = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = date }
        val sameDay = today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        if (isPersian(context)) {
            return when {
                sameDay -> faTime(date)
                age < 7 * DAY_MS -> faDayName(date)
                else -> faShortDate(date)
            }
        }
        val pattern = when {
            sameDay -> "HH:mm"
            age < 7 * DAY_MS -> "EEE"
            else -> "yyyy/MM/dd"
        }
        return SimpleDateFormat(pattern, localeOf(context)).format(Date(date))
    }

    /** Full stamp shown under each message bubble. */
    fun full(context: Context, millis: Long): String {
        val date = stamp(millis)
        if (date == 0L) return context.getString(R.string.date_unknown)
        return if (isPersian(context)) {
            faDate(date) + " · " + faTime(date)
        } else {
            SimpleDateFormat("yyyy/MM/dd HH:mm", localeOf(context)).format(Date(date))
        }
    }

    // ------------------------------------------------------------ Jalali

    /** Gregorian -> Jalali, returns [year, month, day]. */
    private fun jalali(millis: Long): IntArray {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val gy = cal.get(Calendar.YEAR)
        val gm = cal.get(Calendar.MONTH) + 1
        val gd = cal.get(Calendar.DAY_OF_MONTH)

        val gDaysInMonth = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) +
            ((gy2 + 399) / 400) + gd + gDaysInMonth[gm - 1]

        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + (days / 31)
            jd = 1 + (days % 31)
        } else {
            jm = 7 + ((days - 186) / 30)
            jd = 1 + ((days - 186) % 30)
        }
        return intArrayOf(jy, jm, jd)
    }

    private fun faDate(millis: Long): String {
        val j = jalali(millis)
        return faDigits("${j[2]} ${FA_MONTHS[j[1] - 1]} ${j[0]}")
    }

    fun faShortDate(millis: Long): String {
        val j = jalali(millis)
        return faDigits("${j[2]} ${FA_MONTHS[j[1] - 1]}")
    }

    fun year(context: Context, millis: Long): Int =
        if (isPersian(context)) jalali(stamp(millis))[0]
        else Calendar.getInstance().apply { timeInMillis = stamp(millis) }.get(Calendar.YEAR)

    fun yearLabel(context: Context, millis: Long): String {
        val value = year(context, millis).toString()
        return if (isPersian(context)) faDigits(value) else value
    }

    fun conversationDay(context: Context, millis: Long): String {
        val date = stamp(millis)
        if (date == 0L) return context.getString(R.string.date_unknown)
        val age = System.currentTimeMillis() - date
        val today = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = date }
        val sameDay = today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        today.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
        return when {
            sameDay -> context.getString(R.string.today)
            yesterday -> context.getString(R.string.yesterday)
            isPersian(context) && age < 7 * DAY_MS -> faDayName(date)
            isPersian(context) -> faShortDate(date)
            else -> SimpleDateFormat("MMM d", localeOf(context)).format(Date(date))
        }
    }

    private fun faDayName(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return FA_DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }

    private fun faTime(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return faDigits(
            String.format(
                Locale.US, "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
            )
        )
    }

    /** Latin digits -> Persian digits. */
    fun faDigits(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            sb.append(if (ch in '0'..'9') ('\u06F0' + (ch - '0')) else ch)
        }
        return sb.toString()
    }

    /** A small count rendered the way the active language writes numbers. */
    fun count(context: Context, value: Int): String =
        if (isPersian(context)) faDigits(value.toString()) else value.toString()
}
