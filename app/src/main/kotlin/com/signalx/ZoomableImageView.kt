package com.signalx

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max

/**
 * ImageView with proper pinch-to-zoom + pan:
 *   - Matrix-based zoom anchored at the pinch midpoint (no jitter)
 *   - Single-finger pan when zoomed in
 *   - Zoom out below initial scale resets to FIT_CENTER
 *   - ViewPager2 swipe when at 1x; blocked when zoomed
 *   - [onZoomStateChanged] fires whenever the zoomed/unzoomed state changes
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    /** Called with true when image becomes zoomed, false when it returns to 1x. */
    var onZoomStateChanged: ((zoomed: Boolean) -> Unit)? = null

    private val mMatrix  = Matrix()
    private val mValues  = FloatArray(9)
    private var mMinScale  = 1f       // initial FIT_CENTER scale
    private var mInitialized = false  // has matrix been seeded from FIT_CENTER?
    private var mIsZoomed    = false  // last reported zoom state

    private var mLastX = 0f; private var mLastY = 0f
    private var mPointerCount = 0

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cur     = getMatrixScale()
                val desired = cur * detector.scaleFactor
                val clamped = desired.coerceIn(mMinScale, mMinScale * MAX_ZOOM)
                val factor  = clamped / cur

                // Zoom anchored exactly at the pinch midpoint — no jitter
                mMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                applyMatrix()
                notifyZoomState()
                return true
            }
        }
    )

    // -------------------------------------------------------------------------
    // Reset — call before Glide loads a new image
    // -------------------------------------------------------------------------
    fun resetForNewImage() {
        mInitialized = false
        mMinScale    = 1f
        scaleType    = ScaleType.FIT_CENTER
        mIsZoomed    = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    /** Programmatically reset zoom to 1x (called by the zoom-out button). */
    fun resetZoom() {
        mInitialized = false
        scaleType    = ScaleType.FIT_CENTER
        notifyZoomState()
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    // -------------------------------------------------------------------------
    // Touch
    // -------------------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ensureMatrixInitialized()
                mLastX = event.x; mLastY = event.y
                mPointerCount = 1
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                mPointerCount++
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                // Single-finger pan — only when zoomed and no pinch in progress
                if (mPointerCount == 1 && !scaleDetector.isInProgress) {
                    val scale = getMatrixScale()
                    if (scale > mMinScale * 1.05f) {
                        mMatrix.postTranslate(event.x - mLastX, event.y - mLastY)
                        clampTranslation()
                        applyMatrix()
                    }
                }
                if (event.pointerCount > 0) {
                    mLastX = event.getX(0); mLastY = event.getY(0)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                mPointerCount = max(0, mPointerCount - 1)
                // Avoid position jump: seed from the surviving pointer
                val survivingIdx = if (event.actionIndex == 0) 1 else 0
                if (survivingIdx < event.pointerCount) {
                    mLastX = event.getX(survivingIdx)
                    mLastY = event.getY(survivingIdx)
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                mPointerCount = 0
                clampTranslation(); applyMatrix()
                // If back at initial scale, clean-reset to FIT_CENTER
                if (getMatrixScale() <= mMinScale * 1.05f) {
                    mInitialized = false
                    scaleType    = ScaleType.FIT_CENTER
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                notifyZoomState()
            }
        }

        val zoomed = mInitialized && getMatrixScale() > mMinScale * 1.05f
        parent?.requestDisallowInterceptTouchEvent(mPointerCount >= 2 || zoomed)
        return true
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun ensureMatrixInitialized() {
        if (mInitialized) return
        mMatrix.set(imageMatrix)
        mMatrix.getValues(mValues)
        mMinScale    = mValues[Matrix.MSCALE_X].coerceAtLeast(0.01f)
        scaleType    = ScaleType.MATRIX
        imageMatrix  = mMatrix
        mInitialized = true
    }

    private fun applyMatrix() { imageMatrix = mMatrix }

    private fun getMatrixScale(): Float {
        if (!mInitialized) return mMinScale
        mMatrix.getValues(mValues)
        return mValues[Matrix.MSCALE_X]
    }

    private fun notifyZoomState() {
        val nowZoomed = mInitialized && getMatrixScale() > mMinScale * 1.05f
        if (nowZoomed != mIsZoomed) {
            mIsZoomed = nowZoomed
            onZoomStateChanged?.invoke(nowZoomed)
        }
    }

    /**
     * Keep image inside the view after panning.
     * If image is smaller than the view in any dimension, center it.
     */
    private fun clampTranslation() {
        val d = drawable ?: return
        mMatrix.getValues(mValues)
        val scale = mValues[Matrix.MSCALE_X]
        val tx    = mValues[Matrix.MTRANS_X]
        val ty    = mValues[Matrix.MTRANS_Y]
        val imgW  = d.intrinsicWidth  * scale
        val imgH  = d.intrinsicHeight * scale

        val dx: Float = when {
            imgW <= width  -> (width  - imgW) / 2f - tx
            tx > 0         -> -tx
            tx + imgW < width  -> width  - (tx + imgW)
            else -> 0f
        }
        val dy: Float = when {
            imgH <= height -> (height - imgH) / 2f - ty
            ty > 0         -> -ty
            ty + imgH < height -> height - (ty + imgH)
            else -> 0f
        }
        if (dx != 0f || dy != 0f) mMatrix.postTranslate(dx, dy)
    }

    companion object {
        private const val MAX_ZOOM = 5f
    }
}
