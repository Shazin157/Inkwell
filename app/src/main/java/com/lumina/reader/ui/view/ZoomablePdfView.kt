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
    private val matrixValues = FloatArray(9)

    private var scaleDetector: ScaleGestureDetector
    private var gestureDetector: GestureDetector

    private var currentScale = 1.0f
    private val minScale = 1.0f
    private val maxScale = 4.0f

    private var isFitWidth = true
    private var currentBitmap: Bitmap? = null

    var onSingleTapListener: (() -> Unit)? = null
    var onPageSwipeListener: ((direction: Int) -> Unit)? = null // -1 for prev, +1 for next

    init {
        scaleType = ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val targetScale = currentScale * scaleFactor

                if (targetScale in minScale..maxScale) {
                    currentScale = targetScale
                    imageMatrixInternal.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                    checkMatrixBounds()
                    imageMatrix = imageMatrixInternal
                }
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTapListener?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > 1.2f) {
                    resetZoom()
                } else {
                    val targetScale = 2.2f
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
                // If not zoomed in, allow left/right swipe to flip pages
                if (currentScale <= 1.15f && e1 != null) {
                    val diffX = e2.x - e1.x
                    val diffY = e2.y - e1.y
                    if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > 100 && Math.abs(velocityX) > 200) {
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

    fun setPdfBitmap(bitmap: Bitmap) {
        currentBitmap = bitmap
        setImageBitmap(bitmap)
        post {
            applyInitialScale()
        }
    }

    fun setFitWidthMode(fitWidth: Boolean) {
        this.isFitWidth = fitWidth
        applyInitialScale()
    }

    fun zoomIn() {
        val targetScale = (currentScale * 1.3f).coerceAtMost(maxScale)
        val factor = targetScale / currentScale
        currentScale = targetScale
        imageMatrixInternal.postScale(factor, factor, width / 2f, height / 2f)
        checkMatrixBounds()
        imageMatrix = imageMatrixInternal
    }

    fun zoomOut() {
        val targetScale = (currentScale / 1.3f).coerceAtLeast(minScale)
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

        if (isFitWidth) {
            // Scale so image width fits screen width completely (0 horizontal gaps)
            val scale = viewW / bmpW
            imageMatrixInternal.postScale(scale, scale)
            val scaledH = bmpH * scale
            val dy = if (scaledH < viewH) (viewH - scaledH) / 2f else 40f // start slightly below top
            imageMatrixInternal.postTranslate(0f, dy)
        } else {
            // Fit entire page on screen
            val scale = Math.min(viewW / bmpW, viewH / bmpH)
            imageMatrixInternal.postScale(scale, scale)
            val scaledW = bmpW * scale
            val scaledH = bmpH * scale
            val dx = (viewW - scaledW) / 2f
            val dy = (viewH - scaledH) / 2f
            imageMatrixInternal.postTranslate(dx, dy)
        }

        currentScale = 1.0f
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

        // Center horizontally if smaller than view, otherwise clamp edges
        if (rect.width() <= viewW) {
            deltaX = (viewW - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0f) {
                deltaX = -rect.left
            } else if (rect.right < viewW) {
                deltaX = viewW - rect.right
            }
        }

        // Center vertically if smaller than view, otherwise clamp edges
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
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        return handled || super.onTouchEvent(event)
    }
}
