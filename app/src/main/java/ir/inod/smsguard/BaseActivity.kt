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

    /**
     * The font-scale revision this instance was built with. A `fontScale` is
     * baked into the Activity's resources, so a change cannot be applied in
     * place: the screen has to be rebuilt, and this is how it notices.
     */
    private var fontRevisionAtCreate = -1

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

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        // Read after super.onCreate: only then is the Activity's context fully
        // attached and safe to hand to a store.
        fontRevisionAtCreate = SettingsStore(this).revision
    }

    override fun onResume() {
        super.onResume()
        val revision = SettingsStore(this).revision
        // Coming back from the settings screen after a text-size change: rebuild
        // this screen so the new scale is actually in its resources.
        if (fontRevisionAtCreate >= 0 && revision != fontRevisionAtCreate) {
            fontRevisionAtCreate = revision
            recreate()
        }
    }
}
