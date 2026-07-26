package com.morningsea.sonytransfer

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import com.fimagena.libptp.PtpConnection
import com.fimagena.libptp.PtpDataType
import com.fimagena.libptp.PtpSession
import com.fimagena.libptp.PtpTransport
import com.fimagena.libptp.ptpip.PtpIpConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

private const val TAG = "SonyCam"

/** JPEG object format code in PTP standard */
private const val FORMAT_JPEG = 0xB101

/** PTP/IP standard port (confirmed open on ZV-E10) */
private const val PTP_PORT = 15740

/**
 * Any 16-byte GUID works for Sony cameras (pairing unnecessary).
 * Using the same pattern as libptp's PtpTester.
 */
private val PTP_GUID = shortArrayOf(
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0xff.toShort(), 0xff.toShort(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
)

private const val FRIENDLY_NAME = "SonyTransfer"

data class ContentItem(
    val handle: Long,           // PTP object handle
    val filename: String,       // e.g. "DSC00123.JPG"
    val fileSize: Long,         // compressed size in bytes
    val imageWidth: Int,        // pixels
    val imageHeight: Int,       // pixels
    val thumbFormat: Int        // format code for thumbnail
)

/**
 * Sony Camera Client using PTP/IP protocol (port 15740).
 *
 * The ZV-E10 (firmware 2.02) uses PTP/IP (ISO 15740) for photo transfer,
 * NOT the SOAP/UPnP protocol on port 64321 (which returns 404 on newer firmware).
 *
 * Protocol flow (via libptp library):
 * 1. PtpIpConnection → connect to camera at ip:15740
 * 2. openSession()
 * 3. getStorageIDs() → get SD card storage IDs
 * 4. getObjectHandles(storageId, FORMAT_JPEG) → get all JPEG photo handles
 * 5. getObjectInfo(handle) → get filename, size, dimensions
 * 6. getThumb(handle) → download thumbnail bytes
 * 7. getObject(handle) → download full photo bytes
 */
class SonyCameraClient {

    private var ptpConnection: PtpConnection? = null
    private var ptpSession: PtpSession? = null

    // ── WiFi Network Binding ────────────────────────────────────────
    // bindProcessToNetwork() is called by the ViewModel.
    // PTP/IP uses raw java.net.Socket which respects process binding.

    fun bindToNetwork(network: Network) {
        // No-op: process-level binding via ConnectivityManager.bindProcessToNetwork()
        // in the ViewModel covers raw sockets too.
    }

    // ── Get Camera IP from WiFi DHCP ─────────────────────────────────

    fun getGatewayIp(context: Context): String? {
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wm.dhcpInfo ?: return null
            val gw = dhcp.gateway
            if (gw == 0) return null
            return "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get gateway IP: ${e.message}")
            return null
        }
    }

    // ── Connect to Camera via PTP/IP ─────────────────────────────────

    suspend fun connectToCamera(ip: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Connecting to PTP/IP at $ip:$PTP_PORT")

            val address = PtpIpConnection.PtpIpAddress(
                InetAddress.getByName(ip), PTP_PORT
            )
            val hostId = PtpIpConnection.PtpIpHostId(
                PTP_GUID, FRIENDLY_NAME
            )
            val transport = PtpIpConnection()
            val connection = PtpConnection(transport)

            connection.connect(address, hostId)
            ptpConnection = connection
            Log.i(TAG, "PTP/IP connected, opening session...")

            val session = connection.openSession()
            ptpSession = session
            Log.i(TAG, "PTP session opened")

            // Get device info for model name
            val deviceInfo = connection.deviceInfo
            val modelName = deviceInfo?.mModel?.mString ?: "Sony Camera"
            Log.i(TAG, "Camera model: $modelName")

            Result.success(modelName)
        } catch (e: Exception) {
            Log.e(TAG, "PTP connect failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        }
    }

    // ── List All Photos ──────────────────────────────────────────────

    suspend fun getPhotoList(): Result<List<ContentItem>> = withContext(Dispatchers.IO) {
        val session = ptpSession
            ?: return@withContext Result.failure(Exception("No PTP session"))

        try {
            val storageIds = session.storageIDs
            Log.i(TAG, "Found ${storageIds.size} storage(s)")

            val allItems = mutableListOf<ContentItem>()

            for (storageId in storageIds) {
                val sid = storageId.mValue
                Log.i(TAG, "Storage $sid: getting handles...")

                // Get JPEG photo handles (format 0xB101)
                val handles = session.getObjectHandles(
                    storageId,
                    PtpDataType.ObjectFormatCode(FORMAT_JPEG)
                )
                Log.i(TAG, "Storage $sid: ${handles.size} JPEG objects")

                for (handle in handles) {
                    try {
                        val info = session.getObjectInfo(handle)
                        allItems.add(
                            ContentItem(
                                handle = handle.mValue,
                                filename = info.mFilename.mString.ifEmpty {
                                    "IMG_${handle.mValue}"
                                },
                                fileSize = info.mObjectCompressedSize.mValue,
                                imageWidth = info.mImagePixWidth.mValue.toInt(),
                                imageHeight = info.mImagePixHeight.mValue.toInt(),
                                thumbFormat = info.mThumbFormat.mValue
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to get info for handle ${handle.mValue}: ${e.message}")
                    }
                }
            }

            Log.i(TAG, "Total: ${allItems.size} photos")
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
            Result.success(thumb)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Download Full Photo ──────────────────────────────────────────

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

    // ── Get file size for a handle (for progress) ────────────────────

    suspend fun getObjectSize(handle: Long): Long = withContext(Dispatchers.IO) {
        val session = ptpSession ?: return@withContext 0L
        try {
            val info = session.getObjectInfo(PtpDataType.ObjectHandle(handle))
            info.mObjectCompressedSize.mValue
        } catch (_: Exception) { 0L }
    }

    // ── Disconnect ───────────────────────────────────────────────────

    fun disconnect() {
        try {
            ptpSession?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Session close error: ${e.message}")
        }
        try {
            ptpConnection?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Connection close error: ${e.message}")
        }
        ptpSession = null
        ptpConnection = null
        Log.i(TAG, "PTP disconnected")
    }
}
