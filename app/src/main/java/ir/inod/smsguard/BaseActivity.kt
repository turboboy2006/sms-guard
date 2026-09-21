package ir.inod.smsguard

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/**
 * Applies the user's font scale to every screen.
 *
 * Font size must be applied per Activity context — the Application context does
 * not carry it. Setting `Configuration.fontScale` is the same lever the
 * platform's own text-size setting pulls, so every `sp` value in the app scales
 * exactly the way users expect, including text inside adapters and dialogs.
 */
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val scale = SettingsStore(newBase).fontScale
        if (scale == 1f) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = scale
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }
}
