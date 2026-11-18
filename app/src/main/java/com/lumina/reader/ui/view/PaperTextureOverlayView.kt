package com.lumina.reader.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.util.Random

/**
 * Custom overlay view that renders an authentic Kindle Paperwhite / physical book paper
 * micro-fiber texture and gentle page edge vignette over the reading canvas.
 */
class PaperTextureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var isPaperTextureEnabled: Boolean = true
        set(value) {
            field = value
            visibility = if (value) VISIBLE else GONE
            invalidate()
        }

    init {
        isClickable = false
        isFocusable = false
        alpha = 0.12f // Subtle tactile paper grain without obscuring text
        generatePaperTextureShader()
    }

    private fun generatePaperTextureShader() {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val random = Random(42) // Fixed seed for stable deterministic fiber pattern

        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                // Base subtle noise
                val baseGrain = random.nextInt(28) - 14 // -14..+14 variation
                // Subtle directional organic paper fiber simulation
                val fiberH = if (random.nextInt(60) == 0) (random.nextInt(30) - 15) else 0
                val fiberV = if (random.nextInt(75) == 0) (random.nextInt(25) - 12) else 0

                val intensity = (128 + baseGrain + fiberH + fiberV).coerceIn(90, 165)
                val alphaVal = 130 + random.nextInt(40)
                pixels[y * size + x] = Color.argb(alphaVal, intensity, intensity, intensity)
            }
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)

        val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        paperPaint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        if (!isPaperTextureEnabled) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. Draw organic tactile paper texture
        canvas.drawRect(0f, 0f, w, h, paperPaint)

        // 2. Subtle soft book binding vignette on left/right edges (0.5% opacity)
        vignettePaint.shader = LinearGradient(
            0f, 0f, 24f, 0f,
            Color.argb(35, 0, 0, 0), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, 24f, h, vignettePaint)

        vignettePaint.shader = LinearGradient(
            w - 24f, 0f, w, 0f,
            Color.TRANSPARENT, Color.argb(35, 0, 0, 0),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(w - 24f, 0f, w, h, vignettePaint)
    }
}
