package com.signalX

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MultiSelectHeaderController(
    private val context: Context,
    private val normalHeader: View,
    private val multiSelectHeader: View,
    private val tvSelectedCount: TextView,
    private val btnSelectAll: View,
    private val actionsContainer: LinearLayout,
    private val btnExit: View
) {

    var onSelectAllClick: (() -> Unit)? = null
    var onExitClick: (() -> Unit)? = null

    init {
        btnExit.setOnClickListener { onExitClick?.invoke() }
        btnSelectAll.setOnClickListener { onSelectAllClick?.invoke() }
    }

    fun show() {
        normalHeader.visibility = View.GONE
        multiSelectHeader.visibility = View.VISIBLE
    }

    fun hide() {
        multiSelectHeader.visibility = View.GONE
        normalHeader.visibility = View.VISIBLE
    }

    fun update(
        count: Int,
        actions: List<Pair<ChatMenuAction, Boolean>>,
        onActionClick: (ChatMenuAction) -> Unit
    ) {

        tvSelectedCount.text = "$count selected"
        actionsContainer.removeAllViews()

        val density = context.resources.displayMetrics.density
        val sizePx = (40 * density).toInt()
        val marginPx = (4 * density).toInt()
        val paddingPx = (8 * density).toInt()

        for ((action, isFaded) in actions) {

            val iv = ImageView(context).apply {

                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    marginStart = marginPx
                    marginEnd = marginPx
                }

                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                setImageResource(action.iconRes)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                alpha = if (isFaded) 0.35f else 1f
                isClickable = !isFaded
                isFocusable = !isFaded

                if (!isFaded) {

                    setOnClickListener { onActionClick(action) }

                    setOnLongClickListener {
                        Toast.makeText(context, action.label, Toast.LENGTH_SHORT).show()
                        true
                    }
                }
            }

            actionsContainer.addView(iv)
        }
    }
}