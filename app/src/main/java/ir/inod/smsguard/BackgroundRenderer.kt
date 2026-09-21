package ir.inod.smsguard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.LruCache
import android.view.Gravity
import android.view.View

object BackgroundRenderer {
    private val photos = object : LruCache<String, Bitmap>(4) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private fun softPhoto(context: Context, uri: String): Bitmap? {
        synchronized(photos) { photos.get(uri)?.let { return it } }
        val source = runCatching {
            context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { stream ->
                val options = BitmapFactory.Options().apply { inSampleSize = 8 }
                BitmapFactory.decodeStream(stream, null, options)
            }
        }.getOrNull() ?: return null
        // A very small bitmap stretched over the screen is deliberately soft.
        // It also avoids loading a full camera photo into the UI's memory.
        val blurred = Bitmap.createScaledBitmap(source, 48, 80, true)
        if (blurred !== source) source.recycle()
        synchronized(photos) { photos.put(uri, blurred) }
        return blurred
    }

    fun apply(view: View, context: Context, style: String?, imageUri: String? = null) {
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val colors = when (style ?: BackgroundStyle.CLEAN) {
            BackgroundStyle.MIST -> if (night) intArrayOf(Color.parseColor("#182331"), Color.parseColor("#202D3C")) else intArrayOf(Color.parseColor("#F4F8FF"), Color.parseColor("#EAF2FF"))
            BackgroundStyle.AURORA -> if (night) intArrayOf(Color.parseColor("#142C2C"), Color.parseColor("#182A3A")) else intArrayOf(Color.parseColor("#EFFCF8"), Color.parseColor("#E6F4FF"))
            BackgroundStyle.DUSK -> if (night) intArrayOf(Color.parseColor("#2A2039"), Color.parseColor("#1B2639")) else intArrayOf(Color.parseColor("#F8F2FF"), Color.parseColor("#EEF2FF"))
            BackgroundStyle.BLOOM -> if (night) intArrayOf(Color.parseColor("#352432"), Color.parseColor("#27233A")) else intArrayOf(Color.parseColor("#FFF5F7"), Color.parseColor("#F3F0FF"))
            else -> intArrayOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.screen_bg),
                androidx.core.content.ContextCompat.getColor(context, R.color.screen_bg)
            )
        }
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        val photo = imageUri?.let { uri -> softPhoto(context, uri)?.let { bitmap ->
            BitmapDrawable(context.resources, bitmap).apply {
                alpha = if (night) 22 else 34
                gravity = Gravity.FILL
                isFilterBitmap = true
            }
        } }
        view.background = if (photo == null) base else LayerDrawable(arrayOf(base, photo))
    }
}
