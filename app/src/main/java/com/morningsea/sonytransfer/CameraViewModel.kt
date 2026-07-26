package com.morningsea.sonytransfer

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "CameraVM"

enum class ConnectionState {
    DISCONNECTED, DISCOVERING, CONNECTING, READY, ERROR
}

data class UiState(
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val cameraIp: String = "",
    val errorMessage: String? = null,
    val statusMessage: String = "Ready to connect",
    val contents: List<ContentItem> = emptyList(),
    val selectedIndices: Set<Int> = emptySet(),
    val totalCount: Int = 0,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadCurrent: Int = 0,
    val downloadTotal: Int = 0,
    val downloadedCount: Int = 0
)

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val cameraClient = SonyCameraClient()
    private var downloadJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun getConnectivityManager(): ConnectivityManager {
        return getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    // ── WiFi Network Binding (3-tier fallback) ───────────────────────

    private fun tryBindExistingWifi(): Boolean {
        val cm = getConnectivityManager()
        try {
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val bound = cm.bindProcessToNetwork(network)
                    Log.i(TAG, "Tier 1: bindProcessToNetwork returned: $bound")
                    if (bound) {
                        cameraClient.bindToNetwork(network)
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 failed: ${e.message}")
        }
        return false
    }

    private suspend fun tryRegisterCallback(): Boolean {
        val cm = getConnectivityManager()
        return suspendCancellableCoroutine { cont ->
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val bound = cm.bindProcessToNetwork(network)
                    Log.i(TAG, "Tier 2: bindProcessToNetwork returned: $bound")
                    cameraClient.bindToNetwork(network)
                    networkCallback = this
                    if (bound && cont.isActive) cont.resumeWith(Result.success(true))
                }

                override fun onLost(network: Network) {
                    cm.bindProcessToNetwork(null)
                    _uiState.update {
                        it.copy(
                            connectionState = ConnectionState.DISCONNECTED,
                            statusMessage = "WiFi disconnected"
                        )
                    }
                }
            }
            try {
                cm.registerNetworkCallback(request, callback)
            } catch (e: Exception) {
                Log.w(TAG, "Tier 2 registerNetworkCallback failed: ${e.message}")
                if (cont.isActive) cont.resumeWith(Result.success(false))
                return@suspendCancellableCoroutine
            }

            viewModelScope.launch {
                delay(5000)
                if (cont.isActive) {
                    try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
                    cont.resumeWith(Result.success(false))
                }
            }
        }
    }

    // ── Connection Flow ──────────────────────────────────────────────

    fun connect() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.DISCOVERING,
                    statusMessage = "Binding to WiFi…",
                    errorMessage = null,
                    contents = emptyList(),
                    selectedIndices = emptySet()
                )
            }

            // === WiFi Binding (3 tiers) ===
            var wifiBound = tryBindExistingWifi()
            if (!wifiBound) {
                _uiState.update { it.copy(statusMessage = "Waiting for WiFi callback…") }
                wifiBound = tryRegisterCallback()
            }
            if (!wifiBound) {
                Log.w(TAG, "Tier 3: Skipping WiFi binding, trying raw connection")
                _uiState.update { it.copy(statusMessage = "Skipped WiFi binding, trying direct…") }
            }

            // === Get Camera IP from DHCP gateway ===
            val ctx = getApplication<Application>()
            val gatewayIp = cameraClient.getGatewayIp(ctx)
            val cameraIp = gatewayIp ?: "192.168.122.1"

            _uiState.update {
                it.copy(
                    cameraIp = cameraIp,
                    connectionState = ConnectionState.CONNECTING,
                    statusMessage = "Connecting to $cameraIp:15740 (PTP/IP)…"
                )
            }

            // === Connect via PTP/IP ===
            val connectResult = cameraClient.connectToCamera(cameraIp)
            if (connectResult.isFailure) {
                val errorDetail = connectResult.exceptionOrNull()?.let { e ->
                    "${e.javaClass.simpleName}: ${e.message}"
                } ?: "Unknown error"
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = buildString {
                            append("Failed to connect to camera at $cameraIp:15740\n\n")
                            append("Error: $errorDetail\n\n")
                            if (!wifiBound) {
                                append("⚠ WiFi binding failed.\n")
                                append("→ Turn OFF mobile data, then retry.\n\n")
                            }
                            append("Steps:\n")
                            append("1. Camera: Playback → MENU → Network → Send to Smartphone\n")
                            append("2. Select a photo → camera shows QR code\n")
                            append("3. Phone: Connect to camera WiFi\n")
                            append("4. Turn off mobile data\n")
                            append("5. Tap Retry")
                        },
                        statusMessage = "Connection failed"
                    )
                }
                return@launch
            }

            val modelName = connectResult.getOrDefault("Sony Camera")

            // === List Photos ===
            _uiState.update {
                it.copy(
                    statusMessage = "Connected to $modelName, listing photos…"
                )
            }

            val listResult = cameraClient.getPhotoList()
            if (listResult.isFailure) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = "Failed to list photos:\n${listResult.exceptionOrNull()?.message}",
                        statusMessage = "List failed"
                    )
                }
                cameraClient.disconnect()
                return@launch
            }

            val contents = listResult.getOrDefault(emptyList())
            if (contents.isEmpty()) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = "No photos found on camera.\n\nMake sure there are JPEG photos on the SD card.",
                        statusMessage = "No photos"
                    )
                }
                cameraClient.disconnect()
                return@launch
            }

            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.READY,
                    contents = contents,
                    totalCount = contents.size,
                    statusMessage = "${contents.size} photos on $modelName"
                )
            }
        }
    }

    // ── Selection ────────────────────────────────────────────────────

    fun toggleSelection(index: Int) {
        _uiState.update { state ->
            val new = state.selectedIndices.toMutableSet()
            if (index in new) new.remove(index) else new.add(index)
            state.copy(selectedIndices = new)
        }
    }

    fun selectAll() {
        _uiState.update { it.copy(selectedIndices = it.contents.indices.toSet()) }
    }

    fun deselectAll() {
        _uiState.update { it.copy(selectedIndices = emptySet()) }
    }

    // ── Download ─────────────────────────────────────────────────────

    fun downloadSelected() {
        val state = _uiState.value
        val items = state.selectedIndices.sorted().mapNotNull { idx ->
            state.contents.getOrNull(idx)
        }
        if (items.isEmpty()) return

        downloadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloading = true,
                    downloadCurrent = 0,
                    downloadTotal = items.size,
                    downloadedCount = 0,
                    downloadProgress = 0f
                )
            }

            var ok = 0
            for ((i, item) in items.withIndex()) {
                if (!isActive) break

                _uiState.update {
                    it.copy(downloadCurrent = i + 1, downloadProgress = 0f)
                }

                val totalSize = cameraClient.getObjectSize(item.handle)
                val filename = item.filename.ifEmpty { "IMG_${item.handle}" }
                val (safeName, mediaType) = when (item.photoType) {
                    PhotoType.JPEG -> if ('.' in filename) filename to MediaSaver.MediaType.IMAGE
                                     else "$filename.JPG" to MediaSaver.MediaType.IMAGE
                    PhotoType.RAW -> if (filename.contains(".arw", true)) filename to MediaSaver.MediaType.IMAGE
                                     else "$filename.ARW" to MediaSaver.MediaType.IMAGE
                    PhotoType.VIDEO -> if ('.' in filename) filename to MediaSaver.MediaType.VIDEO
                                       else "$filename.MP4" to MediaSaver.MediaType.VIDEO
                    PhotoType.OTHER -> filename to MediaSaver.MediaType.IMAGE
                }

                // Use streaming for large files (> 20MB) to avoid OOM
                val useStreaming = totalSize > 20L * 1024 * 1024
                Log.i("CameraVM", "Downloading ${item.filename} size=$totalSize streaming=$useStreaming")

                val success = if (useStreaming) {
                    // Stream download — chunks written directly to MediaStore
                    val streamResult = MediaSaver.openOutputStream(getApplication(), safeName, mediaType)
                    if (streamResult.isFailure) {
                        Log.w("CameraVM", "Failed to open stream: ${streamResult.exceptionOrNull()?.message}")
                        false
                    } else {
                        val handle = streamResult.getOrThrow()
                        val dlResult = cameraClient.downloadPhotoToStream(
                            item.handle, totalSize, handle.stream
                        ) { read, total ->
                            if (total > 0) {
                                _uiState.update { it.copy(downloadProgress = read.toFloat() / total) }
                            }
                        }
                        try { handle.stream.close() } catch (_: Exception) {}
                        if (dlResult.isSuccess) {
                            MediaSaver.finalizePendingUri(getApplication(), handle.uri)
                            true
                        } else {
                            Log.w("CameraVM", "Stream download failed: ${dlResult.exceptionOrNull()?.message}")
                            false
                        }
                    }
                } else {
                    // In-memory download for small files
                    val result = cameraClient.downloadPhoto(item.handle) { read, total ->
                        val t = if (total > 0) total else totalSize
                        if (t > 0) {
                            _uiState.update { it.copy(downloadProgress = read.toFloat() / t) }
                        }
                    }
                    if (result.isSuccess) {
                        MediaSaver.saveFile(getApplication(), result.getOrThrow(), safeName, mediaType).isSuccess
                    } else false
                }

                if (success) {
                    ok++
                    _uiState.update { it.copy(downloadedCount = ok) }
                }
            }

            _uiState.update {
                it.copy(
                    isDownloading = false,
                    statusMessage = "✅ Downloaded $ok / ${items.size} photos to DCIM/SonyTransfer",
                    selectedIndices = emptySet()
                )
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _uiState.update {
            it.copy(
                isDownloading = false,
                statusMessage = "Download cancelled (${it.downloadedCount} saved)"
            )
        }
    }

    // ── Thumbnail Loading (for UI) ──────────────────────────────────

    private val _thumbnailCache = mutableMapOf<Long, ByteArray>()

    suspend fun loadThumbnail(handle: Long): ByteArray? {
        _thumbnailCache[handle]?.let { return it }
        val result = cameraClient.getThumbnail(handle)
        if (result.isSuccess) {
            val thumb = result.getOrThrow()
            _thumbnailCache[handle] = thumb
            return thumb
        }
        return null
    }

    // ── Disconnect ───────────────────────────────────────────────────

    fun disconnect() {
        cameraClient.disconnect()
        _thumbnailCache.clear()
        val cm = getConnectivityManager()
        cm.bindProcessToNetwork(null)
        networkCallback?.let {
            try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
        _uiState.update { UiState() }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
