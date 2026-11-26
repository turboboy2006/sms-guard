package ir.inod.smsguard

import android.app.Application
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * گزارش خطای محلی. هیچ‌چیز خودکار ارسال نمی‌شود؛ فقط آخرین کرش روی خود گوشی
 * ذخیره می‌شود تا کاربر با یک دکمه آن را ایمیل کند.
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter()
                error.printStackTrace(PrintWriter(trace))
                File(app.filesDir, FILE_NAME).writeText(
                    buildString {
                        append("app=").append(app.packageName).append('\n')
                        append("version=").append(versionName(app)).append('\n')
                        append("device=").append(android.os.Build.MANUFACTURER).append(' ')
                        append(android.os.Build.MODEL).append(" · Android ")
                        append(android.os.Build.VERSION.RELEASE).append('\n')
                        append("time=").append(System.currentTimeMillis()).append("\n\n")
                        append(trace.toString())
                    }
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(context: Context): String? = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.readText() else null
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")
}
