package com.morningsea.sonytransfer

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Saves downloaded photos/videos to device storage under DCIM/SonyTransfer.
 * Handles MediaStore for Android 10+ and direct file access for older versions.
 * Supports both images (JPEG/RAW) and video (MP4).
 */
object MediaSaver {

    enum class MediaType { IMAGE, VIDEO }

    fun saveFile(context: Context, data: ByteArray, filename: String, type: MediaType): Result<String> {
        return try {
            val mimeType = when {
                filename.endsWith(".arw", true) -> "image/x-sony-arw"
                filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
                filename.endsWith(".png", true) -> "image/png"
                filename.endsWith(".mp4", true) || filename.endsWith(".mov", true) -> "video/mp4"
                filename.endsWith(".avi", true) -> "video/x-msvideo"
                else -> if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ — MediaStore
                val collectionUri = if (type == MediaType.VIDEO)
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/SonyTransfer")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(collectionUri, values)
                    ?: return Result.failure(Exception("MediaStore insert failed"))

                resolver.openOutputStream(uri)?.use { it.write(data) }
                    ?: return Result.failure(Exception("Cannot open output stream"))

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
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
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                Result.success(file.absolutePath)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Convenience for images */
    fun saveImage(context: Context, data: ByteArray, filename: String): Result<String> =
        saveFile(context, data, filename, MediaType.IMAGE)

    // ── Streaming support for large files (avoids OOM) ──────────────

    /**
     * Opens an OutputStream to MediaStore for streaming writes.
     * Returns (uri, outputStream) pair. Call finalizePendingUri() when done.
     */
    data class StreamHandle(val uri: String, val stream: java.io.OutputStream)

    fun openOutputStream(context: Context, filename: String, type: MediaType): Result<StreamHandle> {
        return try {
            val mimeType = when {
                filename.endsWith(".arw", true) -> "image/x-sony-arw"
                filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
                filename.endsWith(".mp4", true) || filename.endsWith(".mov", true) -> "video/mp4"
                filename.endsWith(".mts", true) || filename.endsWith(".m2ts", true) -> "video/mp2t"
                else -> if (type == MediaType.VIDEO) "video/mp4" else "image/jpeg"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collectionUri = if (type == MediaType.VIDEO)
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/SonyTransfer")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(collectionUri, values)
                    ?: return Result.failure(Exception("MediaStore insert failed"))
                val stream = resolver.openOutputStream(uri)
                    ?: return Result.failure(Exception("Cannot open output stream"))
                Result.success(StreamHandle(uri.toString(), stream))
            } else {
                // Android 9 and below — direct file
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    "SonyTransfer"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, filename)
                Result.success(StreamHandle(file.absolutePath, file.outputStream()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Mark the pending file as complete (Android 10+) */
    fun finalizePendingUri(context: Context, uriStr: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uriStr.startsWith("content://")) {
            try {
                val uri = Uri.parse(uriStr)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, values, null, null)
            } catch (_: Exception) {}
        } else {
            // Android 9: scan file for media index
            try {
                MediaScannerConnection.scanFile(context, arrayOf(uriStr), null, null)
            } catch (_: Exception) {}
        }
    }
}
