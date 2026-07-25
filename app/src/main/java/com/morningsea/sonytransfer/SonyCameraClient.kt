package com.morningsea.sonytransfer

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

private const val TAG = "SonyCam"

const val ROOT_DIR_PUSH = "PushRoot"   // images user selected on camera
const val ROOT_DIR_PULL = "PhotoRoot"  // all images, browse from phone

data class ContentItem(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val largeUrl: String?,
    val originalUrl: String?,
    val fileSize: Long
)

data class BrowseResult(
    val items: List<ContentItem>,
    val containerIds: List<String>,
    val numberReturned: Int,
    val totalMatches: Int
)

/**
 * Client for Sony camera's DLNA/UPnP SOAP protocol (port 64321).
 *
 * This is the protocol used in "Send to Smartphone" camera mode.
 * The camera exposes itself as a UPnP MediaServer (DMS-1.50).
 *
 * Flow:
 * 1. GET /DmsDescPush.xml → device description
 * 2. SOAP X_TransferStart → begin transfer session
 * 3. SOAP Browse on ContentDirectory → list photos (DIDL-Lite)
 * 4. HTTP GET on res URLs → download photos
 * 5. SOAP X_TransferEnd → end session
 */
class SonyCameraClient {

    private var client: OkHttpClient = buildClient(null)
    private var baseUrl: String = "http://192.168.122.1:64321"

    private val contentDirectoryUrl get() = "$baseUrl/upnp/control/ContentDirectory"
    private val xPushListUrl get() = "$baseUrl/upnp/control/XPushList"

    private val nsSoap = "http://schemas.xmlsoap.org/soap/envelope/"
    private val nsContentDirectory = "urn:schemas-upnp-org:service:ContentDirectory:1"
    private val nsXPushList = "urn:schemas-sony-com:service:XPushList:1"

    fun bindToNetwork(network: Network) { client = buildClient(network) }
    fun getHttpClient(): OkHttpClient = client

    private fun buildClient(network: Network?): OkHttpClient {
        return OkHttpClient.Builder().apply {
            if (network != null) socketFactory(network.socketFactory)
            connectTimeout(10, TimeUnit.SECONDS)
            readTimeout(120, TimeUnit.SECONDS)
            writeTimeout(60, TimeUnit.SECONDS)
        }.build()
    }

    fun setBaseUrl(ip: String, port: Int = 64321) {
        baseUrl = "http://$ip:$port"
    }

    fun getBaseUrl(): String = baseUrl

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

    // ── Reachability Check ───────────────────────────────────────────

    suspend fun checkReachable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$baseUrl/DmsDescPush.xml").build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext false
            resp.isSuccessful && body.contains("SonyDigitalMediaServer")
        } catch (e: Exception) {
            Log.w(TAG, "Reachability check failed: ${e.message}")
            false
        }
    }

    // ── SOAP: X_TransferStart ─────────────────────────────────────────

    suspend fun startTransfer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="$nsSoap" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:X_TransferStart xmlns:u="$nsXPushList"></u:X_TransferStart>
</s:Body>
</s:Envelope>""".trimIndent()

            val req = Request.Builder()
                .url(xPushListUrl)
                .header("SOAPACTION", "\"$nsXPushList#X_TransferStart\"")
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build()

            val resp = client.newCall(req).execute()
            Log.i(TAG, "TransferStart: HTTP ${resp.code}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "TransferStart failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ── SOAP: X_TransferEnd ───────────────────────────────────────────

    suspend fun endTransfer(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="$nsSoap" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:X_TransferEnd xmlns:u="$nsXPushList">
<SourceType>0</SourceType>
</u:X_TransferEnd>
</s:Body>
</s:Envelope>""".trimIndent()

            val req = Request.Builder()
                .url(xPushListUrl)
                .header("SOAPACTION", "\"$nsXPushList#X_TransferEnd\"")
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build()

            val resp = client.newCall(req).execute()
            Log.i(TAG, "TransferEnd: HTTP ${resp.code}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "TransferEnd failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ── SOAP: Browse (ContentDirectory) ───────────────────────────────

    suspend fun browseDirectory(
        objectId: String,
        startingIndex: Int = 0,
        requestedCount: Int = 100
    ): Result<BrowseResult> = withContext(Dispatchers.IO) {
        try {
            val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="$nsSoap" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body>
<u:Browse xmlns:u="$nsContentDirectory">
<ObjectID>$objectId</ObjectID>
<BrowseFlag>BrowseDirectChildren</BrowseFlag>
<Filter>*</Filter>
<StartingIndex>$startingIndex</StartingIndex>
<RequestedCount>$requestedCount</RequestedCount>
<SortCriteria></SortCriteria>
</u:Browse>
</s:Body>
</s:Envelope>""".trimIndent()

            val req = Request.Builder()
                .url(contentDirectoryUrl)
                .header("SOAPACTION", "\"$nsContentDirectory#Browse\"")
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .post(body.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build()

            val resp = client.newCall(req).execute()
            val xml = resp.body?.string()
                ?: return@withContext Result.failure(Exception("Empty Browse response"))
            Log.d(TAG, "Browse raw (first 300): ${xml.take(300)}")

            parseBrowseResponse(xml)
        } catch (e: Exception) {
            Log.w(TAG, "Browse failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Recursively browse a directory tree, collecting all photo items.
     */
    suspend fun browseAll(rootId: String): Result<List<ContentItem>> =
        withContext(Dispatchers.IO) {
            try {
                val allItems = mutableListOf<ContentItem>()
                val queue = ArrayDeque<String>()
                queue.add(rootId)

                while (queue.isNotEmpty()) {
                    val currentId = queue.removeFirst()
                    var startIndex = 0

                    // Paginate through all results in this directory
                    while (true) {
                        val result = browseDirectory(currentId, startIndex)
                        if (result.isFailure) {
                            Log.w(TAG, "Browse failed for $currentId at $startIndex: ${result.exceptionOrNull()?.message}")
                            break
                        }
                        val br = result.getOrThrow()
                        allItems.addAll(br.items)
                        queue.addAll(br.containerIds)
                        startIndex += br.numberReturned
                        if (startIndex >= br.totalMatches || br.numberReturned == 0) break
                    }
                }

                Log.i(TAG, "browseAll: collected ${allItems.size} items from $rootId")
                Result.success(allItems)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── DIDL-Lite Parsing ─────────────────────────────────────────────

    private fun parseBrowseResponse(soapXml: String): Result<BrowseResult> {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
            }
            val builder = factory.newDocumentBuilder()

            // Parse outer SOAP envelope
            val doc = builder.parse(ByteArrayInputStream(soapXml.toByteArray(Charsets.UTF_8)))

            // Extract <Result> which contains escaped DIDL-Lite XML
            val resultNodes = doc.getElementsByTagName("Result")
            if (resultNodes.length == 0) {
                return Result.failure(Exception("No <Result> in Browse response"))
            }
            val didlLiteXml = resultNodes.item(0).textContent
            Log.d(TAG, "DIDL-Lite (first 500): ${didlLiteXml.take(500)}")

            // Parse the DIDL-Lite
            val didlDoc = builder.parse(ByteArrayInputStream(didlLiteXml.toByteArray(Charsets.UTF_8)))

            val items = mutableListOf<ContentItem>()
            val containerIds = mutableListOf<String>()

            // Collect containers (subdirectories)
            val containerNodes = didlDoc.getElementsByTagName("container")
            for (i in 0 until containerNodes.length) {
                val elem = containerNodes.item(i) as Element
                val id = elem.getAttribute("id")
                if (id.isNotEmpty()) {
                    containerIds.add(id)
                    Log.d(TAG, "Found container: $id")
                }
            }

            // Collect items (photos)
            val itemNodes = didlDoc.getElementsByTagName("item")
            for (i in 0 until itemNodes.length) {
                val itemElem = itemNodes.item(i) as Element
                val id = itemElem.getAttribute("id")

                // Get title
                val titleNodes = itemElem.getElementsByTagName("dc:title")
                val title = if (titleNodes.length > 0)
                    titleNodes.item(0).textContent else "Untitled"

                // Get all <res> elements and find best URLs
                val resNodes = itemElem.getElementsByTagName("res")
                var bestUrl: String? = null
                var bestSize: Long = 0
                var thumbUrl: String? = null
                var largeUrl: String? = null

                for (j in 0 until resNodes.length) {
                    val resElem = resNodes.item(j) as Element
                    val url = resElem.textContent?.trim() ?: continue
                    val size = resElem.getAttribute("size").toLongOrNull() ?: 0
                    val protocolInfo = resElem.getAttribute("protocolInfo")

                    // Classify by protocolInfo suffix
                    when {
                        protocolInfo.contains("_TN") -> thumbUrl = url
                        protocolInfo.contains("_LRG") -> largeUrl = url
                    }

                    // Track largest = original
                    if (size > bestSize) {
                        bestSize = size
                        bestUrl = url
                    }
                }

                // Fallbacks for best URL
                if (bestUrl == null) bestUrl = largeUrl ?: thumbUrl
                if (thumbUrl == null) thumbUrl = largeUrl

                items.add(ContentItem(
                    id = id,
                    title = title,
                    thumbnailUrl = thumbUrl,
                    largeUrl = largeUrl,
                    originalUrl = bestUrl,
                    fileSize = bestSize
                ))
            }

            // Pagination info
            val nrNodes = doc.getElementsByTagName("NumberReturned")
            val tmNodes = doc.getElementsByTagName("TotalMatches")
            val numberReturned = if (nrNodes.length > 0)
                nrNodes.item(0).textContent.toIntOrNull() ?: 0 else 0
            val totalMatches = if (tmNodes.length > 0)
                tmNodes.item(0).textContent.toIntOrNull() ?: 0 else 0

            Log.i(TAG, "Parsed: ${items.size} items, $numberReturned returned, $totalMatches total")
            Result.success(BrowseResult(items, containerIds, numberReturned, totalMatches))
        } catch (e: Exception) {
            Log.w(TAG, "Parse failed: ${e.message}")
            Result.failure(e)
        }
    }

    // ── Download Photo ────────────────────────────────────────────────

    suspend fun downloadPhoto(
        url: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP ${resp.code}"))
            }
            val body = resp.body
                ?: return@withContext Result.failure(Exception("Empty response body"))
            val totalBytes = body.contentLength()
            val input = body.byteStream()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Long = 0
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                output.write(buffer, 0, n)
                bytesRead += n
                onProgress(bytesRead, totalBytes)
            }
            Result.success(output.toByteArray())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
