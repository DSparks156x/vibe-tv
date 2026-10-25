package com.vibe.tvlauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import coil.size.Size
import coil.transform.Transformation

/**
 * Bakes the Google TV backdrop scrims (dark left side, dark bottom) into the bitmap once,
 * so each frame draws a single full-screen image instead of stacking gradient layers.
 * The TV's GPU is fill-rate limited, so this matters more than anything else on screen.
 */
class ScrimTransformation(private val dim: Boolean = false) : Transformation {
    override val cacheKey = "scrim-v1-$dim"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val out = input.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(argb(0.82f), argb(0.35f), argb(0.05f)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(argb(0.45f), argb(0f), argb(0.1f), argb(0.75f), argb(0.92f)),
            floatArrayOf(0f, 0.2f, 0.42f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, paint)
        if (dim) {
            paint.shader = null
            paint.color = argb(0.45f)
            c.drawRect(0f, 0f, w, h, paint)
        }
        return out
    }

    private fun argb(alpha: Float) = (alpha * 255).toInt() shl 24

    override fun equals(other: Any?) = other is ScrimTransformation && other.dim == dim
    override fun hashCode() = cacheKey.hashCode()
}
