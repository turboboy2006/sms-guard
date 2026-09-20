package ir.inod.smsguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Declared in the manifest because Android requires a WAP_PUSH_DELIVER receiver
 * for an app to be eligible as the default SMS app.
 *
 * This version does not download MMS: the payload is acknowledged and dropped.
 * See the README for what that means in practice.
 */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally empty.
    }
}
