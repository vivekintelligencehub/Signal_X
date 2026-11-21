package com.signalx

/**
 * Ek text box jo user ne editor mein image ke upar daala hai.
 *
 * x, y: container ke top-left se FRACTION (0f..1f) mein position, pixel mein nahi.
 *       Fraction isliye taaki jab actual bitmap par burn karna ho (jiski size
 *       screen preview se alag hogi), position proportionally sahi baithe.
 * text: jo likha gaya hai
 * colorIndex: TEXT_COLORS array mein se kaunsa color chuna gaya hai
 */
data class EditorTextEntry(
    var x: Float,
    var y: Float,
    var text: String,
    var colorIndex: Int = 0
) {
    companion object {
        /** Cycle karne ke liye available text colors (tap par badalta hai). */
        val TEXT_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), // white
            0xFF000000.toInt(), // black
            0xFFFF5252.toInt(), // red
            0xFF25D366.toInt(), // green
            0xFFFFEB3B.toInt(), // yellow
            0xFF42A5F5.toInt()  // blue
        )
    }
}
