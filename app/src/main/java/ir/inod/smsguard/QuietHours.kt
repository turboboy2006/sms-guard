package ir.inod.smsguard

import java.util.Calendar

/**
 * Quiet hours: the window in which a promotional or suspicious message is
 * stored silently instead of buzzing.
 *
 * The design rule is that this can only ever hide noise, never something the
 * user is waiting for. One-time codes, bank traffic and messages from a real
 * contact always notify, at any hour — those are exactly the messages that
 * matter at 3am. Only promotional, suspicious and spam categories are
 * suppressed.
 *
 * It is off by default. A messaging app that quietly withholds a notification
 * is a bug until the user explicitly asks for it.
 */
object QuietHours {

    /**
     * True when [categoryId] should be delivered without a notification right
     * now. Everything not listed here notifies as usual.
     */
    fun shouldSuppress(context: android.content.Context, categoryId: String): Boolean {
        val settings = SettingsStore(context)
        if (!settings.quietHoursEnabled) return false
        if (!isSuppressible(categoryId)) return false
        return isQuietNow(settings.quietFrom, settings.quietTo)
    }

    /** Categories that may be silenced. */
    fun isSuppressible(categoryId: String): Boolean =
        categoryId == Cat.PROMOTION || categoryId == Cat.SUSPICIOUS || categoryId == Cat.SPAM

    /**
     * Whether [hour] falls inside the window.
     *
     * A window normally wraps around midnight (`23` to `7`), but a window that
     * does not wrap (`1` to `5`) has to work too, which is why the two cases are
     * separate rather than one clever comparison.
     */
    fun isQuietHour(hour: Int, from: Int, to: Int): Boolean {
        if (from == to) return false
        return if (from < to) hour in from until to else hour >= from || hour < to
    }

    fun isQuietNow(from: Int, to: Int): Boolean =
        isQuietHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), from, to)

    /**
     * "23:00 - 07:00" as a plain label. The hours are written by the app rather
     * than through a locale formatter, so a Persian and an English screen read
     * the same way and no locale can make the window ambiguous; only the digits
     * follow the language.
     */
    fun windowLabel(context: android.content.Context, from: Int, to: Int): String {
        val text = "%02d:00 - %02d:00".format(from, to)
        return if (Dates.isPersian(context)) Dates.faDigits(text) else text
    }

    /** Same, for a single hour on its own ("07:00"). */
    fun hourLabel(context: android.content.Context, hour: Int): String {
        val text = "%02d:00".format(hour)
        return if (Dates.isPersian(context)) Dates.faDigits(text) else text
    }
}
