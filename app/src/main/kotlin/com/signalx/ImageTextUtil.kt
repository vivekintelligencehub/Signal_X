package com.signalx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Editor mein jo text boxes image ke upar daale gaye the, unhe asli image bitmap
 * par PERMANENTLY burn karta hai (Canvas se draw), aur ek nayi file save karta hai.
 *
 * Har entry ka apna size, bold/italic/underline, aur color (palette ya custom)
 * respect hota hai — editor preview mein jo dikh raha tha, wahi final image mein.
 *
 * ImageRotationUtil jaisa hi pattern: original image kabhi edit nahi hoti, hamesha
 * ek nayi copy cache mein banti hai. Agar koi text nahi hai to original Uri hi
 * wapas mil jaata hai.
 */
object ImageTextUtil {

    /**
     * @param sourceUri jis image par text burn karna hai (already-rotated ho sakti hai)
     * @param entries   us image ke text overlays (fraction coordinates mein)
     * @param sizeBasisFraction editor preview mein font-size (sp) image-width (px) ke
     *        kitne fraction ke barabar dikh rahi thi — isi ratio se final bitmap par
     *        scale hota hai, taaki jo size screen par tha wahi (proportionally) final mein ho
     */
    fun burnTextAndSave(
        context: Context,
        sourceUri: Uri,
        entries: List<EditorTextEntry>,
        sizeBasisFraction: Float = EDITOR_PREVIEW_WIDTH_PX_PER_SP
    ): Uri {
        if (entries.isEmpty() || entries.all { it.text.isBlank() }) return sourceUri

        return try {
            val original: Bitmap = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return sourceUri

            val mutable = original.copy(Bitmap.Config.ARGB_8888, true)
            if (mutable !== original) original.recycle()

            val canvas = Canvas(mutable)
            val w = mutable.width.toFloat()
            val h = mutable.height.toFloat()

            for (entry in entries) {
                if (entry.text.isBlank()) continue

                // sp → bitmap-pixel scale: editor preview ek fixed-width container tha,
                // final bitmap uससे bada/chhota ho sakta hai — isi fraction se scale karo
                val scaledTextSize = entry.sizeSp * (w / sizeBasisFraction)

                val style = when {
                    entry.isBold && entry.isItalic -> Typeface.BOLD_ITALIC
                    entry.isBold -> Typeface.BOLD
                    entry.isItalic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }

                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = scaledTextSize
                    typeface = Typeface.create(Typeface.DEFAULT, style)
                    isUnderlineText = entry.isUnderline
                    color = entry.resolvedColor()
                }

                // Halka saya (shadow) taaki text kisi bhi background par padhne mein aasan rahe
                val shadowPaint = Paint(paint).apply {
                    color = Color.BLACK
                    alpha = 140
                }

                val px = entry.x * w
                val py = entry.y * h

                canvas.drawText(entry.text, px + 2f, py + 2f, shadowPaint)
                canvas.drawText(entry.text, px, py, paint)
            }

            val outDir = File(context.cacheDir, "text_images").apply { mkdirs() }
            val outFile = File(outDir, "txt_${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
            FileOutputStream(outFile).use { out ->
                mutable.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            mutable.recycle()

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
        } catch (e: Exception) {
            // Text burn fail ho jaaye (corrupt image, OOM, etc.) to original image bhej do—
            // crash kabhi nahi, bas text skip ho jaayega
            sourceUri
        }
    }

    /**
     * Editor preview ka reference width (dp, density-independent approx), jisse
     * sp-based font size ko final bitmap-pixel size mein convert karte waqt
     * sahi ratio mile. AttachmentPanelView mein jo edImage/edOverlayContainer
     * ki typical screen-width hoti hai, usi ke hisaab se calibrate kiya hai.
     */
    const val EDITOR_PREVIEW_WIDTH_PX_PER_SP = 360f
}
