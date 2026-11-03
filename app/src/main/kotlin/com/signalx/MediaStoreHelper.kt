package com.signalX

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class MediaImage(
    val uri: Uri,
    val name: String,
    val dateAdded: Long,
    val bucketId: String,
    val bucketName: String,
    val size: Long
)

data class MediaBucket(
    val id: String,
    val name: String,
    val coverUri: Uri?,
    val count: Int
)

object MediaStoreHelper {

    fun getAllImages(context: Context): List<MediaImage> {
        val images = mutableListOf<MediaImage>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val bucketIdCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = Uri.withAppendedPath(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                )
                images.add(
                    MediaImage(
                        uri = uri,
                        name = cursor.getString(nameCol) ?: "",
                        dateAdded = cursor.getLong(dateCol),
                        bucketId = cursor.getString(bucketIdCol) ?: "",
                        bucketName = cursor.getString(bucketNameCol) ?: "Unknown",
                        size = cursor.getLong(sizeCol)
                    )
                )
            }
        }
        return images
    }

    fun getBuckets(images: List<MediaImage>): List<MediaBucket> {
        val map = mutableMapOf<String, MutableList<MediaImage>>()
        for (img in images) map.getOrPut(img.bucketId) { mutableListOf() }.add(img)

        return map.map { (id, list) ->
            MediaBucket(
                id = id,
                name = list.firstOrNull()?.bucketName ?: "Unknown",
                coverUri = list.firstOrNull()?.uri,
                count = list.size
            )
        }.sortedByDescending { it.count }
    }
}