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
 *   - Smooth zoom anchored at the pinch point (no jitter)
 *   - Single-finger pan when zoomed in
 *   - Zoom out below initial scale resets to FIT_CENTER
 *   - Correctly passes swipe events to ViewPager2 when at 1x
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val mMatrix  = Matrix()
    private val mValues  = FloatArray(9)
    private var mMinScale = 1f        // initial FIT_CENTER scale (captured on first touch)
    private var mInitialized = false  // has the matrix been seeded from FIT_CENTER?

    private var mLastX = 0f
    private var mLastY = 0f
    private var mPointerCount = 0

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cur = getMatrixScale()
                // Clamp so scale never goes below initial fit or above 5x
                val desired = cur * detector.scaleFactor
                val clamped = desired.coerceIn(mMinScale, mMinScale * MAX_ZOOM)
                val factor  = clamped / cur

                // Zoom anchored exactly at the pinch midpoint
                mMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                applyMatrix()
                return true
            }
        }
    )

    // -------------------------------------------------------------------------
    // Reset (call before Glide loads a new image into this view)
    // -------------------------------------------------------------------------
    fun resetForNewImage() {
        mInitialized = false
        mMinScale    = 1f
        scaleType    = ScaleType.FIT_CENTER
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    // -------------------------------------------------------------------------
    // Touch
    // -------------------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Always let ScaleGestureDetector see events first
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ensureMatrixInitialized()      // seed matrix from FIT_CENTER on first touch
                mLastX = event.x
                mLastY = event.y
                mPointerCount = 1
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                mPointerCount++
                // Block ViewPager2 as soon as second finger lands
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                // Single-finger pan — only when zoomed in and no pinch in progress
                if (mPointerCount == 1 && !scaleDetector.isInProgress) {
                    val scale = getMatrixScale()
                    if (scale > mMinScale * 1.05f) {
                        mMatrix.postTranslate(event.x - mLastX, event.y - mLastY)
                        clampTranslation()
                        applyMatrix()
                    }
                }
                // Always update last position from first pointer
                if (event.pointerCount > 0) {
                    mLastX = event.getX(0)
                    mLastY = event.getY(0)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                mPointerCount = max(0, mPointerCount - 1)
                // Avoid jump: seed last position from the surviving pointer
                val survivingIdx = if (event.actionIndex == 0) 1 else 0
                if (survivingIdx < event.pointerCount) {
                    mLastX = event.getX(survivingIdx)
                    mLastY = event.getY(survivingIdx)
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                mPointerCount = 0
                clampTranslation()
                applyMatrix()

                // If scale is back to initial (or below), reset to FIT_CENTER cleanly
                if (getMatrixScale() <= mMinScale * 1.05f) {
                    mInitialized = false
                    scaleType    = ScaleType.FIT_CENTER
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }

        // Keep ViewPager2 blocked while zoomed
        val zoomed = mInitialized && getMatrixScale() > mMinScale * 1.05f
        parent?.requestDisallowInterceptTouchEvent(mPointerCount >= 2 || zoomed)
        return true
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * On the first touch, the view is in FIT_CENTER mode.
     * Capture that matrix so we can apply zoom on top of it.
     */
    private fun ensureMatrixInitialized() {
        if (mInitialized) return
        // imageMatrix returns the current display matrix even in FIT_CENTER mode
        mMatrix.set(imageMatrix)
        mMatrix.getValues(mValues)
        mMinScale    = mValues[Matrix.MSCALE_X].coerceAtLeast(0.01f)
        scaleType    = ScaleType.MATRIX
        imageMatrix  = mMatrix
        mInitialized = true
    }

    private fun applyMatrix() {
        imageMatrix = mMatrix
    }

    private fun getMatrixScale(): Float {
        if (!mInitialized) return mMinScale
        mMatrix.getValues(mValues)
        return mValues[Matrix.MSCALE_X]
    }

    /**
     * Keep the image inside the view bounds after panning.
     * If image is smaller than view in any dimension, center it in that dimension.
     */
    private fun clampTranslation() {
        val d = drawable ?: return
        mMatrix.getValues(mValues)
        val scale = mValues[Matrix.MSCALE_X]
        val tx    = mValues[Matrix.MTRANS_X]
        val ty    = mValues[Matrix.MTRANS_Y]

        val imgW = d.intrinsicWidth  * scale
        val imgH = d.intrinsicHeight * scale

        val dx: Float = when {
            imgW <= width  -> (width  - imgW) / 2f - tx     // center horizontally
            tx > 0         -> -tx                            // left edge gone past
            tx + imgW < width -> width  - (tx + imgW)       // right edge gone past
            else           -> 0f
        }
        val dy: Float = when {
            imgH <= height -> (height - imgH) / 2f - ty     // center vertically
            ty > 0         -> -ty                            // top edge gone past
            ty + imgH < height -> height - (ty + imgH)      // bottom edge gone past
            else           -> 0f
        }
        if (dx != 0f || dy != 0f) mMatrix.postTranslate(dx, dy)
    }

    companion object {
        private const val MAX_ZOOM = 5f   // maximum zoom multiplier over initial fit
    }
}
