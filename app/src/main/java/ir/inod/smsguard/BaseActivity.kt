package ir.inod.smsguard

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

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
        applySystemBars()
        // Read after super.onCreate: only then is the Activity's context fully
        // attached and safe to hand to a store.
        fontRevisionAtCreate = SettingsStore(this).revision
    }

    /**
     * Keep the operating-system chrome visually attached to our surface.
     * Material's default light-status-bar flag is theme dependent, whereas this
     * app owns a deliberately fixed blue-and-white palette, so set the icon
     * contrast explicitly on every screen.
     */
    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        val surface = ContextCompat.getColor(this, R.color.card_bg)
        window.statusBarColor = surface
        window.navigationBarColor = surface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            val lightSurface = ColorUtils.calculateLuminance(surface) > 0.5
            flags = if (lightSurface) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = if (lightSurface) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
            }
            window.decorView.systemUiVisibility = flags
        }
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
