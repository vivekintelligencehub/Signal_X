package com.signalx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Editor mein jo text boxes image ke upar daale gaye the, unhe asli image bitmap
 * par PERMANENTLY burn karta hai (Canvas se draw), aur ek nayi file save karta hai.
 *
 * ImageRotationUtil jaisa hi pattern: original image kabhi edit nahi hoti, hamesha
 * ek nayi copy cache mein banti hai. Agar koi text nahi hai to original Uri hi
 * wapas mil jaata hai.
 */
object ImageTextUtil {

    /**
     * @param sourceUri   jis image par text burn karna hai (already-rotated ho sakti hai)
     * @param entries     us image ke text overlays (fraction coordinates mein)
     * @param textSizeFraction ek text entry ka font size, image width ke fraction mein
     *                         (editor preview mein jo relative size dikh rahi thi, wahi)
     */
    fun burnTextAndSave(
        context: Context,
        sourceUri: Uri,
        entries: List<EditorTextEntry>,
        textSizeFraction: Float = 0.055f
    ): Uri {
        if (entries.isEmpty()) return sourceUri

        return try {
            val original: Bitmap = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return sourceUri

            // Mutable copy banao taaki Canvas se draw kar saken
            val mutable = original.copy(Bitmap.Config.ARGB_8888, true)
            if (mutable !== original) original.recycle()

            val canvas = Canvas(mutable)
            val w = mutable.width.toFloat()
            val h = mutable.height.toFloat()

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = w * textSizeFraction
                isFakeBoldText = true
            }
            val shadowPaint = Paint(paint).apply {
                color = Color.BLACK
                alpha = 140
            }

            for (entry in entries) {
                if (entry.text.isBlank()) continue
                val color = EditorTextEntry.TEXT_COLORS.getOrElse(entry.colorIndex) { Color.WHITE }
                val px = entry.x * w
                val py = entry.y * h

                // Halka saya (shadow) taaki text kisi bhi background par padhne mein aasan rahe
                canvas.drawText(entry.text, px + 2f, py + 2f, shadowPaint)
                paint.color = color
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
}
