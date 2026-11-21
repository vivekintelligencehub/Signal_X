package com.signalx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Rotate editor mein jo "rotate" button dabaya gaya hai, uska asli kaam yahan hota hai:
 * original image file ko utne degree rotate karke app ke cache folder mein ek NAYI file
 * ke roop mein save karna, aur uska content:// Uri wapas dena.
 *
 * Original image (jo gallery mein hai) kabhi edit nahi hoti — hum sirf ek copy banate hain.
 * Agar rotation 0 hai to original Uri hi wapas mil jaata hai (koi extra kaam nahi).
 */
object ImageRotationUtil {

    /**
     * @param degrees 0, 90, 180 ya 270 — clockwise rotation jo apply karna hai
     * @return rotated image ka naya Uri, ya original Uri agar degrees == 0 ya rotation fail ho jaaye
     */
    fun rotateAndSave(context: Context, sourceUri: Uri, degrees: Int): Uri {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return sourceUri

        return try {
            val original: Bitmap = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: return sourceUri

            val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
            val rotated = Bitmap.createBitmap(
                original, 0, 0, original.width, original.height, matrix, true
            )
            if (rotated !== original) original.recycle()

            val outDir = File(context.cacheDir, "rotated_images").apply { mkdirs() }
            val outFile = File(outDir, "rot_${System.currentTimeMillis()}_${(0..9999).random()}.jpg")
            FileOutputStream(outFile).use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            rotated.recycle()

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
        } catch (e: Exception) {
            // Rotation fail ho jaaye (corrupt image, out of memory, etc.) to
            // original image hi bhej do — crash kabhi nahi, bas rotation skip ho jaayega
            sourceUri
        }
    }
}
