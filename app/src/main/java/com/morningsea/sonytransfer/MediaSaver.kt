package com.morningsea.sonytransfer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Saves downloaded photos to the device gallery under DCIM/SonyTransfer.
 * Handles MediaStore for Android 10+ and direct file access for older versions.
 */
object MediaSaver {

    fun saveImage(context: Context, data: ByteArray, filename: String): Result<String> {
        return try {
            val mimeType = when {
                filename.endsWith(".arw", true) -> "image/x-sony-arw"
                filename.endsWith(".png", true) -> "image/png"
                else -> "image/jpeg"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ — MediaStore (no WRITE_EXTERNAL_STORAGE needed)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/SonyTransfer")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: return Result.failure(Exception("MediaStore insert failed"))

                resolver.openOutputStream(uri)?.use { it.write(data) }
                    ?: return Result.failure(Exception("Cannot open output stream"))

                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)

                Result.success(uri.toString())
            } else {
                // Android 9 and below — direct file
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "SonyTransfer"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                FileOutputStream(file).use { it.write(data) }
                MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), null, null
                )
                Result.success(file.absolutePath)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
