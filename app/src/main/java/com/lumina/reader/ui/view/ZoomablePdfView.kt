package com.lumina.reader.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomablePdfView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val imageMatrixInternal = Matrix()

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    private var currentScale = 1.0f
    private val minScale = 0.8f
    private val maxScale = 5.0f

    enum class PdfFitMode {
        FIT_WIDTH,  // 100% width edge-to-edge (0 side gaps), centered vertically if shorter
        FIT_SCREEN, // Whole screen fill (zero gaps anywhere on display)
        FIT_PAGE    // Entire page fit inside view (proportional letterboxed)
    }

    var fitMode: PdfFitMode = PdfFitMode.FIT_WIDTH

    private var currentBitmap: Bitmap? = null

    var onSingleTapListener: (() -> Unit)? = null
    var onPageSwipeListener: ((direction: Int) -> Unit)? = null // -1 for prev, +1 for next

    init {
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val targetScale = currentScale * factor

                if (targetScale in minScale..maxScale) {
                    currentScale = targetScale
                    imageMatrixInternal.postScale(factor, factor, detector.focusX, detector.focusY)
                    checkMatrixBounds()
                    imageMatrix = imageMatrixInternal
                }
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTapListener?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > 1.25f) {
                    resetZoom()
                } else {
                    val targetScale = 2.0f
                    val factor = targetScale / currentScale
                    currentScale = targetScale
                    imageMatrixInternal.postScale(factor, factor, e.x, e.y)
                    checkMatrixBounds()
                    imageMatrix = imageMatrixInternal
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (scaleDetector.isInProgress || e2.pointerCount > 1) return false
                imageMatrixInternal.postTranslate(-distanceX, -distanceY)
                checkMatrixBounds()
                imageMatrix = imageMatrixInternal
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (scaleDetector.isInProgress || e2.pointerCount > 1) return false
                // Only flip page when not zoomed in, and swipe is deliberately horizontal
                if (currentScale <= 1.08f && e1 != null) {
                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y
                    if (Math.abs(diffX) > Math.abs(diffY) * 1.8f && Math.abs(diffX) > 150 && Math.abs(velocityX) > 400) {
                        if (diffX < 0) {
                            onPageSwipeListener?.invoke(1) // Next page
                        } else {
                            onPageSwipeListener?.invoke(-1) // Prev page
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && currentBitmap != null) {
            applyInitialScale()
        }
    }

    fun setPdfBitmap(bitmap: Bitmap) {
        currentBitmap = bitmap
        setImageBitmap(bitmap)
        currentScale = 1.0f
        if (width > 0 && height > 0) {
            applyInitialScale()
        } else {
            post {
                applyInitialScale()
            }
        }
    }

    fun applyFitMode(mode: PdfFitMode) {
        this.fitMode = mode
        applyInitialScale()
    }

    fun zoomIn() {
        val targetScale = (currentScale * 1.25f).coerceAtMost(maxScale)
        val factor = targetScale / currentScale
        currentScale = targetScale
        imageMatrixInternal.postScale(factor, factor, width / 2f, height / 2f)
        checkMatrixBounds()
        imageMatrix = imageMatrixInternal
    }

    fun zoomOut() {
        val targetScale = (currentScale / 1.25f).coerceAtLeast(minScale)
        val factor = targetScale / currentScale
        currentScale = targetScale
        imageMatrixInternal.postScale(factor, factor, width / 2f, height / 2f)
        checkMatrixBounds()
        imageMatrix = imageMatrixInternal
    }

    fun resetZoom() {
        currentScale = 1.0f
        applyInitialScale()
    }

    private fun applyInitialScale() {
        val bmp = currentBitmap ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val bmpW = bmp.width.toFloat()
        val bmpH = bmp.height.toFloat()

        imageMatrixInternal.reset()

        when (fitMode) {
            PdfFitMode.FIT_WIDTH -> {
                // Width fits 100% of view width (0 side gaps)
                val scale = viewW / bmpW
                imageMatrixInternal.postScale(scale, scale)
                val scaledH = bmpH * scale
                // If shorter than screen, center vertically with balanced margins; if taller, start at top
                val dy = if (scaledH < viewH) (viewH - scaledH) / 2f else 0f
                imageMatrixInternal.postTranslate(0f, dy)
            }
            PdfFitMode.FIT_SCREEN -> {
                // Whole screen fill (zero gaps anywhere on display)
                val scale = Math.max(viewW / bmpW, viewH / bmpH)
                imageMatrixInternal.postScale(scale, scale)
                val scaledW = bmpW * scale
                val scaledH = bmpH * scale
                val dx = (viewW - scaledW) / 2f
                val dy = (viewH - scaledH) / 2f
                imageMatrixInternal.postTranslate(dx, dy)
            }
            PdfFitMode.FIT_PAGE -> {
                // Entire page fit inside display bounds (proportional letterboxed)
                val scale = Math.min(viewW / bmpW, viewH / bmpH)
                imageMatrixInternal.postScale(scale, scale)
                val scaledW = bmpW * scale
                val scaledH = bmpH * scale
                val dx = (viewW - scaledW) / 2f
                val dy = (viewH - scaledH) / 2f
                imageMatrixInternal.postTranslate(dx, dy)
            }
        }

        currentScale = 1.0f
        checkMatrixBounds()
        imageMatrix = imageMatrixInternal
    }

    private fun checkMatrixBounds() {
        val bmp = currentBitmap ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        imageMatrixInternal.mapRect(rect)

        var deltaX = 0f
        var deltaY = 0f

        // Horizontal constraints
        if (rect.width() <= viewW) {
            deltaX = (viewW - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0f) {
                deltaX = -rect.left
            } else if (rect.right < viewW) {
                deltaX = viewW - rect.right
            }
        }

        // Vertical constraints
        if (rect.height() <= viewH) {
            deltaY = (viewH - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0f) {
                deltaY = -rect.top
            } else if (rect.bottom < viewH) {
                deltaY = viewH - rect.bottom
            }
        }

        imageMatrixInternal.postTranslate(deltaX, deltaY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val scaleHandled = scaleDetector.onTouchEvent(event)
        val gestureHandled = gestureDetector.onTouchEvent(event)
        return scaleHandled || gestureHandled || super.onTouchEvent(event) || true
    }
}
