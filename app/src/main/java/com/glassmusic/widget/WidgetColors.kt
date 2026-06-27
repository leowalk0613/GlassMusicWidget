package com.glassmusic.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WidgetColors {

    private const val TAG = "WidgetColors"
    private val DEFAULT_COLOR = Color.parseColor("#888888")

    fun buildTintColor(color: Int, tintAlpha: Int): Int {
        val vivid = boostSaturation(darkenColor(color, 0.62f), 1.35f)
        return Color.argb(
            tintAlpha.coerceIn(0, 255),
            Color.red(vivid),
            Color.green(vivid),
            Color.blue(vivid)
        )
    }

    suspend fun extractDominantColor(bitmap: Bitmap, tintAlpha: Int): Int = withContext(Dispatchers.Default) {
        extractDominantColorBlocking(bitmap, tintAlpha)
    }

    fun extractDominantRgbBlocking(bitmap: Bitmap): Int {
        if (bitmap.isRecycled) {
            return DEFAULT_COLOR
        }
        var sample: Bitmap? = null
        try {
            sample = paletteBitmap(bitmap)
            val palette = Palette.from(sample).generate()
            return pickColorfulSwatch(palette)
        } catch (e: Exception) {
            Log.e(TAG, "Palette extraction error", e)
            return DEFAULT_COLOR
        } finally {
            if (sample != null && sample !== bitmap) {
                sample.recycle()
            }
        }
    }

    fun extractDominantColorBlocking(bitmap: Bitmap, tintAlpha: Int): Int {
        return buildTintColor(extractDominantRgbBlocking(bitmap), tintAlpha)
    }

    fun fallbackTint(tintAlpha: Int): Int = buildTintColor(DEFAULT_COLOR, tintAlpha)

    fun fallbackRgb(): Int = DEFAULT_COLOR

    private fun boostSaturation(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = (hsv[1] * factor).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    private fun darkenColor(color: Int, factor: Float): Int {
        return Color.rgb(
            (Color.red(color) * factor).toInt().coerceIn(0, 255),
            (Color.green(color) * factor).toInt().coerceIn(0, 255),
            (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        )
    }

    private fun colorSaturation(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return if (max <= 0f) 0f else (max - min) / max
    }

    private fun pickColorfulSwatch(palette: Palette): Int {
        val swatches = listOfNotNull(
            palette.vibrantSwatch,
            palette.lightVibrantSwatch,
            palette.darkVibrantSwatch,
            palette.dominantSwatch,
            palette.mutedSwatch,
            palette.lightMutedSwatch,
            palette.darkMutedSwatch
        )
        if (swatches.isEmpty()) {
            return DEFAULT_COLOR
        }
        val mostVivid = swatches.maxByOrNull { colorSaturation(it.rgb) }
        if (mostVivid != null && colorSaturation(mostVivid.rgb) >= 0.15f) {
            return mostVivid.rgb
        }
        return swatches.maxByOrNull { it.population }?.rgb ?: DEFAULT_COLOR
    }

    private fun paletteBitmap(source: Bitmap): Bitmap {
        val argb = toArgb8888(source)
        val target = 160
        val largest = maxOf(argb.width, argb.height)
        if (largest <= target) return argb
        val scale = target.toFloat() / largest
        val width = (argb.width * scale).toInt().coerceAtLeast(1)
        val height = (argb.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(argb, width, height, false)
        if (scaled !== argb) {
            argb.recycle()
        }
        return scaled
    }

    private fun toArgb8888(source: Bitmap): Bitmap {
        if (!source.isRecycled && source.config == Bitmap.Config.ARGB_8888) {
            return source
        }
        val output = Bitmap.createBitmap(
            source.width.coerceAtLeast(1),
            source.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        Canvas(output).drawBitmap(source, 0f, 0f, null)
        return output
    }
}
