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
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
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
    private var transferStarted = false

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
                    cm.bindProcessToNetwork(network)
                    cameraClient.bindToNetwork(network)
                    Log.i(TAG, "Tier 1: Bound to existing WiFi network")
                    return true
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
                    cm.bindProcessToNetwork(network)
                    cameraClient.bindToNetwork(network)
                    networkCallback = this
                    Log.i(TAG, "Tier 2: WiFi callback fired, bound")
                    if (cont.isActive) cont.resumeWith(Result.success(true))
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
            if (gatewayIp != null) {
                cameraClient.setBaseUrl(gatewayIp, 64321)
                _uiState.update {
                    it.copy(
                        cameraIp = gatewayIp,
                        statusMessage = "Camera at $gatewayIp:64321, checking…"
                    )
                }
            } else {
                // Fallback to default Sony IP
                cameraClient.setBaseUrl("192.168.122.1", 64321)
                _uiState.update {
                    it.copy(
                        cameraIp = "192.168.122.1 (default)",
                        statusMessage = "Using default IP, checking…"
                    )
                }
            }

            // === Check camera reachability ===
            val reachable = cameraClient.checkReachable()
            if (!reachable) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = buildString {
                            append("Camera not reachable at ${if (gatewayIp != null) "$gatewayIp:64321" else "192.168.122.1:64321"}.\n\n")
                            if (!wifiBound) {
                                append("⚠ WiFi binding failed.\n")
                                append("→ Turn OFF mobile data, then retry.\n\n")
                            }
                            append("Steps:\n")
                            append("1. Camera: MENU → Network → Send to Smartphone\n")
                            append("2. Select images (or 'This Image') to start WiFi AP\n")
                            append("3. Phone: Connect to camera's WiFi\n")
                            append("4. Turn off mobile data\n")
                            append("5. Tap Retry")
                        },
                        statusMessage = "Camera not reachable"
                    )
                }
                return@launch
            }

            // === SOAP: Start Transfer Session ===
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.CONNECTING,
                    statusMessage = "Starting transfer session…"
                )
            }
            val startResult = cameraClient.startTransfer()
            if (startResult.isFailure) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = "Failed to start transfer:\n${startResult.exceptionOrNull()?.message}",
                        statusMessage = "Transfer start failed"
                    )
                }
                return@launch
            }
            transferStarted = true

            // === Browse Photos ===
            _uiState.update { it.copy(statusMessage = "Browsing photos…") }

            // Try PhotoRoot first (browse all from phone), fallback to PushRoot
            var browseResult = cameraClient.browseAll(ROOT_DIR_PULL)
            if (browseResult.isFailure || browseResult.getOrDefault(emptyList()).isEmpty()) {
                Log.i(TAG, "PhotoRoot empty/failed, trying PushRoot")
                _uiState.update { it.copy(statusMessage = "Trying PushRoot…") }
                browseResult = cameraClient.browseAll(ROOT_DIR_PUSH)
            }

            if (browseResult.isFailure) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = "Failed to browse photos:\n${browseResult.exceptionOrNull()?.message}",
                        statusMessage = "Browse failed"
                    )
                }
                // End transfer session on error
                cameraClient.endTransfer()
                transferStarted = false
                return@launch
            }

            val contents = browseResult.getOrDefault(emptyList())
            if (contents.isEmpty()) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = "No photos found on camera.\n\n" +
                                "Make sure there are photos on the SD card, and the camera is in 'Send to Smartphone' mode.",
                        statusMessage = "No photos"
                    )
                }
                cameraClient.endTransfer()
                transferStarted = false
                return@launch
            }

            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.READY,
                    contents = contents,
                    totalCount = contents.size,
                    hasMore = false,
                    statusMessage = "${contents.size} photos on camera"
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
        }.filter { it.originalUrl != null }
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

                val url = item.originalUrl ?: continue
                val result = cameraClient.downloadPhoto(url) { read, total ->
                    if (total > 0) {
                        _uiState.update { it.copy(downloadProgress = read.toFloat() / total) }
                    }
                }

                if (result.isSuccess) {
                    val data = result.getOrThrow()
                    val filename = if ('.' in item.title) item.title
                    else "${item.title}.JPG"

                    MediaSaver.saveImage(getApplication(), data, filename)
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

    // ── Disconnect ───────────────────────────────────────────────────

    fun disconnect() {
        // End SOAP transfer session (fire-and-forget on background thread)
        if (transferStarted) {
            CoroutineScope(Dispatchers.IO).launch { cameraClient.endTransfer() }
            transferStarted = false
        }
        // Unbind WiFi
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
