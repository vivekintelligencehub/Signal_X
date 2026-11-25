package com.signalx

/**
 * Ek text box jo user ne editor mein image ke upar daala hai.
 *
 * x, y: container ke top-left se FRACTION (0f..1f) mein position, pixel mein nahi.
 *       Fraction isliye taaki jab actual bitmap par burn karna ho (jiski size
 *       screen preview se alag hogi), position proportionally sahi baithe.
 * text: jo likha gaya hai
 * colorIndex: palette ka index, ya -1 agar customColor use ho raha hai
 * customColor: color-wheel se chuna hua exact color (colorIndex == -1 ho tab use hota hai)
 * sizeSp: font size (sp mein), professional range 14..48
 * isBold / isItalic / isUnderline: style toggles — har ek independent hai
 */
data class EditorTextEntry(
    var x: Float,
    var y: Float,
    var text: String,
    var colorIndex: Int = 0,
    var customColor: Int? = null,
    var sizeSp: Float = 20f,
    var isBold: Boolean = true,
    var isItalic: Boolean = false,
    var isUnderline: Boolean = false
) {
    /** Jo bhi color abhi active hai — customColor ho to wahi, warna palette se. */
    fun resolvedColor(): Int =
        customColor ?: TEXT_COLORS.getOrElse(colorIndex) { 0xFFFFFFFF.toInt() }

    companion object {
        /** Quick-pick palette (floating toolbar ke dots). */
        val TEXT_COLORS = intArrayOf(
            0xFFFFFFFF.toInt(), // white
            0xFF000000.toInt(), // black
            0xFFFF5252.toInt(), // red
            0xFF25D366.toInt(), // green
            0xFFFFEB3B.toInt(), // yellow
            0xFF42A5F5.toInt(), // blue
            0xFFFF9800.toInt(), // orange
            0xFFE040FB.toInt()  // magenta
        )

        const val MIN_SIZE_SP = 14f
        const val MAX_SIZE_SP = 48f
    }
}
