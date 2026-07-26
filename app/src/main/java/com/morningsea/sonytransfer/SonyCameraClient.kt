package com.morningsea.sonytransfer

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import com.fimagena.libptp.PtpConnection
import com.fimagena.libptp.PtpDataType
import com.fimagena.libptp.PtpSession
import com.fimagena.libptp.ptpip.PtpIpConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.Date

private const val TAG = "SonyCam"

/** PTP/IP standard port */
private const val PTP_PORT = 15740

/** Any 16-byte GUID works for Sony cameras (pairing unnecessary) */
private val PTP_GUID = shortArrayOf(
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0xff.toShort(), 0xff.toShort(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
)

private const val FRIENDLY_NAME = "SonyTransfer"

// PTP Object Format Codes
private const val FMT_ASSOCIATION = 0x3001  // folder
private const val FMT_JPEG = 0x3801        // JPEG/EXIF
private const val FMT_TIFF = 0x3802        // TIFF
private const val FMT_RAW_SONY = 0xB101   // Sony ARW RAW
private const val FMT_MP4 = 0x300D         // MP4 video
private const val FMT_AVCHD = 0x3004       // AVCHD video
// MTP video format codes (Sony may use these)
private const val FMT_MTP_MP4 = 0xB981
private const val FMT_MTP_3GP = 0xB988
private const val FMT_MTP_3G2 = 0xB989
private const val FMT_MTP_AVCHD = 0xB98A

enum class PhotoType { JPEG, RAW, VIDEO, OTHER }

data class ContentItem(
    val handle: Long,
    val filename: String,
    val fileSize: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val thumbFormat: Int,
    val formatCode: Int,
    val captureDate: Date?,
    val photoType: PhotoType
)

/** Classify by format code + filename extension (extension is fallback) */
private fun classifyFormat(code: Int, filename: String): PhotoType {
    // Check format code first
    when (code) {
        FMT_JPEG, FMT_TIFF -> return PhotoType.JPEG
        FMT_RAW_SONY -> return PhotoType.RAW
        FMT_MP4, FMT_AVCHD, FMT_MTP_MP4, FMT_MTP_3GP, FMT_MTP_3G2, FMT_MTP_AVCHD -> return PhotoType.VIDEO
        FMT_ASSOCIATION -> return PhotoType.OTHER // folder
    }
    // Fallback: check filename extension
    val lower = filename.lowercase()
    return when {
        lower.endsWith(".mp4") || lower.endsWith(".mov") ||
        lower.endsWith(".mts") || lower.endsWith(".m2ts") ||
        lower.endsWith(".avi") -> PhotoType.VIDEO
        lower.endsWith(".arw") -> PhotoType.RAW
        lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> PhotoType.JPEG
        // If format code has IMAGE bit (0x0800), treat as image
        (code and 0x0800) != 0 -> PhotoType.JPEG
        else -> PhotoType.OTHER
    }
}

class SonyCameraClient {

    private var ptpConnection: PtpConnection? = null
    private var ptpSession: PtpSession? = null

    fun bindToNetwork(network: Network) {
        // No-op: process-level binding via ConnectivityManager covers raw sockets
    }

    fun getGatewayIp(context: Context): String? {
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wm.dhcpInfo ?: return null
            val gw = dhcp.gateway
            if (gw == 0) return null
            return "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
        } catch (e: Exception) {
            return null
        }
    }

    // ── Connect to Camera via PTP/IP ─────────────────────────────────

    suspend fun connectToCamera(ip: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Connecting to PTP/IP at $ip:$PTP_PORT")
            val address = PtpIpConnection.PtpIpAddress(InetAddress.getByName(ip), PTP_PORT)
            val hostId = PtpIpConnection.PtpIpHostId(PTP_GUID, FRIENDLY_NAME)
            val transport = PtpIpConnection()
            val connection = PtpConnection(transport)
            connection.connect(address, hostId)
            ptpConnection = connection
            Log.i(TAG, "PTP/IP connected, opening session...")
            val session = connection.openSession()
            ptpSession = session
            Log.i(TAG, "PTP session opened")
            val deviceInfo = connection.deviceInfo
            val modelName = deviceInfo?.mModel?.mString ?: "Sony Camera"
            Result.success(modelName)
        } catch (e: Exception) {
            Log.e(TAG, "PTP connect failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        }
    }

    // ── List All Photos (JPEG + RAW + Video) ────────────────────────

    suspend fun getPhotoList(): Result<List<ContentItem>> = withContext(Dispatchers.IO) {
        val session = ptpSession
            ?: return@withContext Result.failure(Exception("No PTP session"))
        try {
            val storageIds = session.storageIDs
            Log.i(TAG, "Found ${storageIds.size} storage(s)")

            val allItems = mutableListOf<ContentItem>()

            for (storageId in storageIds) {
                val sid = storageId.mValue
                Log.i(TAG, "Storage $sid: getting ALL handles (no format filter)...")

                // Get ALL objects (no format filter) - includes JPEG, RAW, video, folders
                val handles = session.getObjectHandles(storageId)
                Log.i(TAG, "Storage $sid: ${handles.size} total objects")

                for (handle in handles) {
                    try {
                        val info = session.getObjectInfo(handle)
                        val fmtCode = info.mObjectFormatCode.mValue
                        val fname = info.mFilename.mString
                        val pType = classifyFormat(fmtCode, fname)

                        // Skip folders (associations) only — keep everything else
                        if (fmtCode == FMT_ASSOCIATION) {
                            Log.d(TAG, "Skipping folder: $fname")
                            continue
                        }

                        Log.d(TAG, "Found: $fname fmt=0x${fmtCode.toString(16)} type=$pType size=${info.mObjectCompressedSize.mValue}")

                        val capDate = info.mCaptureDate.mDate

                        allItems.add(
                            ContentItem(
                                handle = handle.mValue,
                                filename = fname.ifEmpty {
                                    "IMG_${handle.mValue}"
                                },
                                fileSize = info.mObjectCompressedSize.mValue,
                                imageWidth = info.mImagePixWidth.mValue.toInt(),
                                imageHeight = info.mImagePixHeight.mValue.toInt(),
                                thumbFormat = info.mThumbFormat.mValue,
                                formatCode = fmtCode,
                                captureDate = capDate,
                                photoType = pType
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get info for handle ${handle.mValue}: ${e.message}")
                    }
                }
            }

            // Sort by capture date descending (newest first)
            allItems.sortByDescending { it.captureDate?.time ?: 0L }

            Log.i(TAG, "Total: ${allItems.size} photos/videos")
            Result.success(allItems)
        } catch (e: Exception) {
            Log.e(TAG, "getPhotoList failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ── Download Thumbnail ───────────────────────────────────────────

    suspend fun getThumbnail(handle: Long): Result<ByteArray> = withContext(Dispatchers.IO) {
        val session = ptpSession
            ?: return@withContext Result.failure(Exception("No PTP session"))
        try {
            val thumb = session.getThumb(PtpDataType.ObjectHandle(handle))
            if (thumb == null || thumb.isEmpty()) {
                Result.failure(Exception("Empty thumbnail"))
            } else {
                Result.success(thumb)
            }
        } catch (e: Exception) {
            // Videos often don't have thumbnails — that's fine
            Log.d(TAG, "getThumb failed for $handle: ${e.message}")
            Result.failure(e)
        }
    }

    // ── Download Full Photo (in-memory, for small files) ─────────────

    suspend fun downloadPhoto(
        handle: Long,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val session = ptpSession
            ?: return@withContext Result.failure(Exception("No PTP session"))
        try {
            val data = session.getObject(
                PtpDataType.ObjectHandle(handle),
                object : PtpSession.DataLoadListener {
                    override fun onDataLoaded(loaded: Long, expected: Long) {
                        onProgress(loaded, expected)
                    }
                }
            )
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Stream Download to OutputStream (for large files like video) ──
    // Uses PtpSession.getObjectToStream() which writes PTP/IP data directly
    // to the OutputStream without buffering in memory. Supports files of
    // any size (e.g. 2GB video) without OOM.

    suspend fun downloadPhotoToStream(
        handle: Long,
        totalSize: Long,
        outputStream: java.io.OutputStream,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val session = ptpSession
            ?: return@withContext Result.failure(Exception("No PTP session"))
        try {
            Log.i(TAG, "Streaming GetObject for $handle (${totalSize / 1024 / 1024}MB)")
            session.getObjectToStream(
                PtpDataType.ObjectHandle(handle),
                outputStream,
                object : PtpSession.DataLoadListener {
                    override fun onDataLoaded(loaded: Long, expected: Long) {
                        onProgress(loaded, if (expected > 0) expected else totalSize)
                    }
                }
            )
            outputStream.flush()
            Log.i(TAG, "Stream GetObject done")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Stream download failed: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getObjectSize(handle: Long): Long = withContext(Dispatchers.IO) {
        val session = ptpSession ?: return@withContext 0L
        try {
            val info = session.getObjectInfo(PtpDataType.ObjectHandle(handle))
            info.mObjectCompressedSize.mValue
        } catch (_: Exception) { 0L }
    }

    fun disconnect() {
        try { ptpSession?.close() } catch (_: Exception) {}
        try { ptpConnection?.close() } catch (_: Exception) {}
        ptpSession = null
        ptpConnection = null
        Log.i(TAG, "PTP disconnected")
    }
}
