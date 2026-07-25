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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

private const val TAG = "SonyCam"

data class ContentItem(
    val uri: String,
    val contentKind: String,
    val title: String,
    val createdTime: String,
    val thumbnailUrl: String?,
    val largeThumbnailUrl: String?,
    val originalUrl: String?,
    val fileSize: Long
)

class SonyCameraClient {

    private var client: OkHttpClient = buildClient(null)
    private var cameraEndpoint: String? = null
    private var avContentEndpoint: String? = null
    private var requestId = 1

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

    // ── Get Camera IP from WiFi DHCP ─────────────────────────────────
    // When phone connects to camera's WiFi AP, the gateway = camera IP.

    fun getGatewayIp(context: Context): String? {
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wm.dhcpInfo ?: return null
            val gw = dhcp.gateway
            if (gw == 0) return null
            // Android stores IP as little-endian int
            val ip = "${gw and 0xFF}.${(gw shr 8) and 0xFF}.${(gw shr 16) and 0xFF}.${(gw shr 24) and 0xFF}"
            Log.i(TAG, "DHCP gateway (camera IP): $ip")
            return ip
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get gateway IP: ${e.message}")
            return null
        }
    }

    // ── SSDP Discovery ───────────────────────────────────────────────

    suspend fun discover(context: Context, timeoutMs: Long = 6000): String? =
        withContext(Dispatchers.IO) {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val multicastLock = wifiManager.createMulticastLock("ssdp_discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
            try {
                val msg = ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 3\r\n" +
                        "ST: urn:schemas-sony-com:service:ScalarWebAPI:1\r\n" +
                        "\r\n").toByteArray()

                val socket = DatagramSocket().apply {
                    soTimeout = timeoutMs.toInt()
                    broadcast = true
                }
                val dest = InetAddress.getByName("239.255.255.250")
                repeat(3) {
                    socket.send(DatagramPacket(msg, msg.size, dest, 1900))
                    Thread.sleep(80)
                }
                val buf = ByteArray(4096)
                val recv = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(recv)
                    val response = String(recv.data, 0, recv.length)
                    Log.i(TAG, "SSDP response: $response")
                    Regex("LOCATION:\\s*(.+?)\\r?\\n", RegexOption.IGNORE_CASE)
                        .find(response)?.groupValues?.get(1)?.trim()
                } catch (_: SocketTimeoutException) {
                    Log.w(TAG, "SSDP timed out")
                    null
                } finally {
                    socket.close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "SSDP error: ${e.message}")
                null
            } finally {
                try { multicastLock.release() } catch (_: Exception) {}
            }
        }

    // ── Smart Fallback: Gateway IP + Port Scan ───────────────────────

    /**
     * Try to find the camera using the WiFi gateway IP + common ports.
     * This is more reliable than SSDP on many Chinese ROM devices.
     *
     * Returns: the device description URL if found, or null.
     */
    suspend fun tryGatewayDiscovery(context: Context): String? = withContext(Dispatchers.IO) {
        val gatewayIp = getGatewayIp(context) ?: return@withContext null
        Log.i(TAG, "Trying gateway IP: $gatewayIp")

        // Sony cameras commonly use these ports
        val ports = listOf(10000, 8080, 64321, 80)
        // Common device description paths
        val descPaths = listOf(
            "/sony",                          // most common
            "/sony/accessControl/DDService",  // some older models
            ""                                // root
        )

        for (port in ports) {
            // Strategy A: Try to fetch device description XML
            for (path in descPaths) {
                try {
                    val url = "http://$gatewayIp:$port$path"
                    Log.d(TAG, "Trying device description at: $url")
                    val req = Request.Builder().url(url)
                        .header("Connection", "close")
                        .build()
                    val resp = client.newCall(req).execute()
                    val body = resp.body?.string() ?: continue
                    // Check if response looks like Sony device description XML
                    if (body.contains("ScalarWebAPI") || body.contains("sony")) {
                        Log.i(TAG, "Found device description at $url")
                        return@withContext url
                    }
                } catch (_: Exception) {
                    continue
                }
            }

            // Strategy B: Try direct JSON-RPC API call
            try {
                val apiBase = "http://$gatewayIp:$port/sony/"
                val testReq = Request.Builder()
                    .url("${apiBase}camera")
                    .post(
                        """{"method":"getAvailableApiList","params":[],"id":1,"version":"1.0"}"""
                            .toRequestBody("application/json".toMediaType())
                    ).build()
                val resp = client.newCall(testReq).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: continue
                    if (body.contains("\"result\"")) {
                        Log.i(TAG, "Found API at $apiBase via direct call")
                        // Initialize directly since we confirmed the API works
                        initFromBaseUrl(apiBase)
                        return@withContext "DIRECT:$apiBase"
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }

        Log.w(TAG, "Gateway discovery failed for $gatewayIp")
        null
    }

    /**
     * Legacy fallback: try hardcoded common camera IPs.
     */
    suspend fun tryHardcodedAddresses(): String? = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "http://10.0.0.1:10000/sony/",
            "http://192.168.122.1:10000/sony/",
            "http://192.168.1.1:10000/sony/"
        )
        for (base in candidates) {
            try {
                val req = Request.Builder()
                    .url("${base}camera")
                    .post(
                        """{"method":"getAvailableApiList","params":[],"id":1,"version":"1.0"}"""
                            .toRequestBody("application/json".toMediaType())
                    ).build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: continue
                    if (body.contains("\"result\"")) return@withContext base
                }
            } catch (_: Exception) { continue }
        }
        null
    }

    // ── Device Description Parsing ───────────────────────────────────

    suspend fun initFromDescription(locationUrl: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(locationUrl).build()
                val resp = client.newCall(req).execute()
                val xml = resp.body?.string()
                    ?: return@withContext Result.failure(Exception("Empty device description"))

                Log.d(TAG, "Device description XML (first 500 chars): ${xml.take(500)}")

                val modelName = Regex("<modelName>(.+?)</modelName>")
                    .find(xml)?.groupValues?.get(1) ?: "Sony Camera"

                val blockRx = Regex(
                    "<av:X_ScalarWebAPI_Service>(.+?)</av:X_ScalarWebAPI_Service>",
                    RegexOption.DOT_MATCHES_ALL
                )
                val typeRx =
                    Regex("<av:X_ScalarWebAPI_ServiceType>(.+?)</av:X_ScalarWebAPI_ServiceType>")
                val urlRx =
                    Regex("<av:X_ScalarWebAPI_ActionList_URL>(.+?)</av:X_ScalarWebAPI_ActionList_URL>")

                for (block in blockRx.findAll(xml)) {
                    val content = block.groupValues[1]
                    val type = typeRx.find(content)?.groupValues?.get(1) ?: continue
                    val actionUrl = urlRx.find(content)?.groupValues?.get(1) ?: continue
                    when (type) {
                        "camera" -> cameraEndpoint = "$actionUrl/camera"
                        "avContent" -> avContentEndpoint = "$actionUrl/avContent"
                    }
                }

                if (cameraEndpoint == null && avContentEndpoint == null) {
                    Result.failure(Exception("No API services found in XML"))
                } else {
                    Log.i(TAG, "Endpoints: camera=$cameraEndpoint avContent=$avContentEndpoint")
                    Result.success(modelName)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun initFromBaseUrl(baseUrl: String) {
        cameraEndpoint = "${baseUrl}camera"
        avContentEndpoint = "${baseUrl}avContent"
        Log.i(TAG, "initFromBaseUrl: camera=$cameraEndpoint avContent=$avContentEndpoint")
    }

    // ── JSON-RPC ─────────────────────────────────────────────────────

    private fun rpc(
        endpoint: String, method: String,
        params: JSONArray = JSONArray(), version: String = "1.0"
    ): JSONObject {
        val body = JSONObject().apply {
            put("method", method)
            put("params", params)
            put("id", requestId++)
            put("version", version)
        }
        val req = Request.Builder()
            .url(endpoint)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = client.newCall(req).execute()
        val text = resp.body?.string() ?: throw Exception("Empty API response")
        val json = JSONObject(text)
        if (json.has("error")) {
            val err = json.getJSONArray("error")
            throw Exception("Camera API error ${err.optInt(0)}: ${err.optString(1)}")
        }
        return json
    }

    // ── Camera API Methods ───────────────────────────────────────────

    suspend fun switchToTransferMode(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ep = cameraEndpoint
                ?: return@withContext Result.failure(Exception("Camera endpoint unavailable"))
            rpc(ep, "setCameraFunction", JSONArray().put("Contents Transfer"))
            Result.success(Unit)
        } catch (_: Exception) {
            Result.success(Unit) // Camera may already be in transfer mode
        }
    }

    suspend fun getContentCount(type: String = "still"): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                val ep = avContentEndpoint
                    ?: return@withContext Result.failure(Exception("avContent unavailable"))
                val params = JSONArray().put(JSONObject().apply {
                    put("uri", "storage:memoryCard1")
                    put("type", JSONArray().put(type))
                    put("view", "flat")
                    put("target", "all")
                })
                val result = rpc(ep, "getContentCount", params, "1.2")
                val count = result.getJSONArray("result").getJSONObject(0).getInt("count")
                Result.success(count)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getContentList(
        startIndex: Int = 0,
        count: Int = 50,
        type: String = "still"
    ): Result<List<ContentItem>> = withContext(Dispatchers.IO) {
        try {
            val ep = avContentEndpoint
                ?: return@withContext Result.failure(Exception("avContent unavailable"))

            val params = JSONArray().put(JSONObject().apply {
                put("uri", "storage:memoryCard1")
                put("stIdx", startIndex)
                put("cnt", count)
                put("view", "flat")
                put("sort", "descending")
                put("type", JSONArray().put(type))
            })

            val result = rpc(ep, "getContentList", params, "1.3")
            val items = mutableListOf<ContentItem>()
            val arr = result.getJSONArray("result").getJSONArray(0)

            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                var originalUrl: String? = null
                var fileSize: Long = 0
                val content = obj.optJSONObject("content")
                if (content != null) {
                    val original = content.optJSONArray("original")
                    if (original != null && original.length() > 0) {
                        val orig = original.getJSONObject(0)
                        originalUrl = orig.optString("url", "").ifEmpty { null }
                        fileSize = orig.optLong("fileSize", 0)
                    }
                }
                items.add(
                    ContentItem(
                        uri = obj.optString("uri", ""),
                        contentKind = obj.optString("contentKind", "still"),
                        title = obj.optString("title", "Untitled"),
                        createdTime = obj.optString("createdTime", ""),
                        thumbnailUrl = obj.optString("thumbnailUrl", "").ifEmpty { null },
                        largeThumbnailUrl = obj.optString("largeThumbnailUrl", "").ifEmpty { null },
                        originalUrl = originalUrl,
                        fileSize = fileSize
                    )
                )
            }
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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

    fun hasAvContent(): Boolean = avContentEndpoint != null
}
