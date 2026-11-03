package com.signalX

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.viewpager2.widget.ViewPager2

class TouchLockContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var cachedPager: ViewPager2? = null


    // ======================================================
    // Yeh function har touch (button ho ya khaali gap, dono
    // jagah) ke liye SABSE PEHLE fire hota hai — bacchon
    // (buttons) ke touch consume karne se bhi pehle.
    // ======================================================

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {

        if (ev.action == MotionEvent.ACTION_DOWN) {

            if (cachedPager == null) {
                cachedPager = rootView.findViewById(R.id.viewPager)
            }

            // ViewPager2 ka swipe poori tarah band — jab tak finger uthe nahi

            cachedPager?.isUserInputEnabled = false
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        val handled = super.dispatchTouchEvent(ev)

        if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {

            // Finger uth gaya — wapas normal

            cachedPager?.isUserInputEnabled = true
            parent?.requestDisallowInterceptTouchEvent(false)
        }

        return handled
    }
}