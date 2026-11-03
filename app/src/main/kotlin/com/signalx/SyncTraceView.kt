package com.signalX

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class SyncTraceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ======================================================
    // CONFIG (yahan se style change kar sakte ho)
    // ======================================================

    private val strokeWidthPx = dpToPx(2f)
    private val cornerRadiusPx = dpToPx(6f)      // apne bg_sync_button ke corner radius se match kar lena
    private val traceColor = Color.parseColor("#00A859")

    private val growDurationMs = 900L             // ROUND 1: poora border cover hone mein lagne wala time
    private val shrinkDurationMs = 900L           // ROUND 2: poora border khaali hone mein lagne wala time


    private val path = Path()
    private val segmentPath = Path()
    private val measure = PathMeasure()

    private var totalLength = 0f

    private var drawStart = 0f
    private var drawEnd = 0f

    private var animatorSet: AnimatorSet? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.BUTT
        color = traceColor
    }


    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        buildPath(w.toFloat(), h.toFloat())
    }


    private fun buildPath(w: Float, h: Float) {

        val inset = strokeWidthPx / 2f
        val rect = RectF(inset, inset, w - inset, h - inset)
        val r = cornerRadiusPx

        path.reset()

        path.moveTo(rect.left + r, rect.top)
        path.lineTo(rect.right - r, rect.top)

        path.arcTo(
            RectF(rect.right - 2 * r, rect.top, rect.right, rect.top + 2 * r),
            -90f, 90f, false
        )

        path.lineTo(rect.right, rect.bottom - r)

        path.arcTo(
            RectF(rect.right - 2 * r, rect.bottom - 2 * r, rect.right, rect.bottom),
            0f, 90f, false
        )

        path.lineTo(rect.left + r, rect.bottom)

        path.arcTo(
            RectF(rect.left, rect.bottom - 2 * r, rect.left + 2 * r, rect.bottom),
            90f, 90f, false
        )

        path.lineTo(rect.left, rect.top + r)

        path.arcTo(
            RectF(rect.left, rect.top, rect.left + 2 * r, rect.top + 2 * r),
            180f, 90f, false
        )

        measure.setPath(path, false)
        totalLength = measure.length
    }


    // ======================================================
    // ANIMATION START — isse call karke trigger karo
    // onComplete: animation poori khatam hone ke baad call hota hai
    // ======================================================

    fun startTraceAnimation(onComplete: () -> Unit = {}) {

        animatorSet?.cancel()

        drawStart = 0f
        drawEnd = 0f


        val growAnimator = ValueAnimator.ofFloat(0f, totalLength).apply {

            duration = growDurationMs
            interpolator = LinearInterpolator()

            addUpdateListener {

                drawEnd = it.animatedValue as Float
                invalidate()
            }
        }


        val shrinkAnimator = ValueAnimator.ofFloat(0f, totalLength).apply {

            duration = shrinkDurationMs
            interpolator = LinearInterpolator()

            addUpdateListener {

                drawStart = it.animatedValue as Float
                invalidate()
            }
        }


        animatorSet = AnimatorSet().apply {

            playSequentially(growAnimator, shrinkAnimator)

            addListener(object : AnimatorListenerAdapter() {

                override fun onAnimationEnd(animation: Animator) {

                    onComplete()
                }
            })

            start()
        }
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (totalLength <= 0f) return

        if (drawEnd <= drawStart) return

        segmentPath.reset()

        measure.getSegment(drawStart, drawEnd, segmentPath, true)

        canvas.drawPath(segmentPath, paint)
    }


    private fun dpToPx(dp: Float): Float =
        dp * resources.displayMetrics.density
}