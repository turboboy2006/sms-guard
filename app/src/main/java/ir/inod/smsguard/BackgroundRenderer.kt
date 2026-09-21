package ir.inod.smsguard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.util.LruCache
import android.view.View

object BackgroundRenderer {
    private val photos = object : LruCache<String, Bitmap>(4) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }
    private val bundled = object : LruCache<Int, Bitmap>(12) {
        override fun sizeOf(key: Int, value: Bitmap): Int = 1
    }

    /** Crops like an ImageView CENTER_CROP; portrait artwork is never stretched. */
    private class CropPhoto(private val bitmap: Bitmap, opacity: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = opacity }
        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            val targetRatio = b.width().toFloat() / b.height()
            val sourceRatio = bitmap.width.toFloat() / bitmap.height
            val src = if (sourceRatio > targetRatio) {
                val width = (bitmap.height * targetRatio).toInt().coerceAtLeast(1)
                Rect((bitmap.width - width) / 2, 0, (bitmap.width + width) / 2, bitmap.height)
            } else {
                val height = (bitmap.width / targetRatio).toInt().coerceAtLeast(1)
                Rect(0, (bitmap.height - height) / 2, bitmap.width, (bitmap.height + height) / 2)
            }
            canvas.drawBitmap(bitmap, src, b, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Suppress("DEPRECATION") override fun getOpacity() = PixelFormat.TRANSLUCENT
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
        val blurred = Bitmap.createScaledBitmap(source, 144, 256, true)
        if (blurred !== source) source.recycle()
        synchronized(photos) { photos.put(uri, blurred) }
        return blurred
    }

    fun apply(view: View, context: Context, style: String?, imageUri: String? = null, preset: String? = null) {
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
        val customPhoto = imageUri?.let { uri -> softPhoto(context, uri) }
        val bundledPhoto = BuiltInWallpaper.drawable(preset).takeIf { it != 0 }?.let { res ->
            synchronized(bundled) { bundled.get(res) } ?: BitmapFactory.decodeResource(
                context.resources, res, BitmapFactory.Options().apply { inSampleSize = 2 }
            )?.also { bitmap -> synchronized(bundled) { bundled.put(res, bitmap) } }
        }
        val photo = (customPhoto ?: bundledPhoto)?.let { bitmap ->
            CropPhoto(bitmap, if (night) 160 else 184)
        }
        view.background = if (photo == null) base else LayerDrawable(arrayOf(base, photo))
    }
}
