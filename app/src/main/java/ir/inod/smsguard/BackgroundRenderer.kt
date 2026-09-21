package ir.inod.smsguard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.BitmapFactory
import android.view.View

object BackgroundRenderer {
    fun apply(view: View, context: Context, style: String?, imageUri: String? = null) {
        val colors = when (style ?: BackgroundStyle.CLEAN) {
            BackgroundStyle.MIST -> intArrayOf(Color.parseColor("#F4F8FF"), Color.parseColor("#EAF2FF"))
            BackgroundStyle.AURORA -> intArrayOf(Color.parseColor("#EFFCF8"), Color.parseColor("#E6F4FF"))
            BackgroundStyle.DUSK -> intArrayOf(Color.parseColor("#F8F2FF"), Color.parseColor("#EEF2FF"))
            BackgroundStyle.BLOOM -> intArrayOf(Color.parseColor("#FFF5F7"), Color.parseColor("#F3F0FF"))
            else -> intArrayOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.screen_bg),
                androidx.core.content.ContextCompat.getColor(context, R.color.screen_bg)
            )
        }
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        val photo = imageUri?.let { uri -> runCatching {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { stream ->
                BitmapFactory.decodeStream(stream)?.let { bitmap ->
                    BitmapDrawable(context.resources, bitmap).apply { alpha = 34 }
                }
            }
        }.getOrNull() }
        view.background = if (photo == null) base else LayerDrawable(arrayOf(base, photo))
    }
}
