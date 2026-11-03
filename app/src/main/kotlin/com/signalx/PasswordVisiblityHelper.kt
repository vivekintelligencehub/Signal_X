package com.signalX

import android.text.InputType
import android.widget.EditText
import android.widget.TextView

object PasswordVisibilityHelper {

    fun attach(editText: EditText, toggleView: TextView) {

        toggleView.text = "👁"
        var isVisible = false

        toggleView.setOnClickListener {

            isVisible = !isVisible

            editText.inputType = if (isVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }

            toggleView.text = if (isVisible) "🙈" else "👁"

            editText.setSelection(editText.text.length)
        }
    }
}