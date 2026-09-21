package ir.inod.smsguard

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Receives real modem sent/delivery reports and mirrors them into the SMS provider. */
class DeliveryStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_MESSAGE_ID, -1L)
        if (id < 0) return
        val total = intent.getIntExtra(EXTRA_TOTAL, 1).coerceAtLeast(1)
        val prefs = context.getSharedPreferences("delivery_parts", Context.MODE_PRIVATE)
        val failed = resultCode != Activity.RESULT_OK
        val prefix = if (intent.action == ACTION_DELIVERED) "delivered" else "sent"
        val countKey = "$prefix:$id"
        val failKey = "failed:$prefix:$id"
        val count = prefs.getInt(countKey, 0) + 1
        prefs.edit().putInt(countKey, count).putBoolean(failKey, prefs.getBoolean(failKey, false) || failed).apply()
        if (count < total) return

        val anyFailed = prefs.getBoolean(failKey, false)
        val values = ContentValues()
        if (intent.action == ACTION_SENT) {
            values.put(
                Telephony.Sms.TYPE,
                if (anyFailed) Telephony.Sms.MESSAGE_TYPE_FAILED else Telephony.Sms.MESSAGE_TYPE_SENT
            )
            values.put(
                Telephony.Sms.STATUS,
                if (anyFailed) Telephony.Sms.STATUS_FAILED else Telephony.Sms.STATUS_PENDING
            )
            if (anyFailed) values.put(Telephony.Sms.ERROR_CODE, resultCode)
        } else {
            values.put(
                Telephony.Sms.STATUS,
                if (anyFailed) Telephony.Sms.STATUS_FAILED else Telephony.Sms.STATUS_COMPLETE
            )
        }
        try {
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI, values,
                "${Telephony.Sms._ID} = ?", arrayOf(id.toString())
            )
        } catch (_: Exception) { }
        prefs.edit().remove(countKey).remove(failKey).apply()
        context.sendBroadcast(Intent(ACTION_UPDATED).setPackage(context.packageName))
    }

    companion object {
        const val ACTION_SENT = "ir.inod.smsguard.SMS_SENT"
        const val ACTION_DELIVERED = "ir.inod.smsguard.SMS_DELIVERED"
        const val ACTION_UPDATED = "ir.inod.smsguard.DELIVERY_UPDATED"
        const val EXTRA_MESSAGE_ID = "message_id"
        const val EXTRA_PART = "part"
        const val EXTRA_TOTAL = "total"
    }
}
