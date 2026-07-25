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
    val modelName: String = "",
    val errorMessage: String? = null,
    val statusMessage: String = "Ready to connect",
    val contents: List<ContentItem> = emptyList(),
    val selectedIndices: Set<Int> = emptySet(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val isLoadingMore: Boolean = false,
    // Download state
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

    // ── WiFi Network Binding (3-tier fallback) ───────────────────────
    //
    // Problem: On camera WiFi AP (no internet), Android prefers mobile data.
    // We must force HTTP traffic through WiFi. But `requestNetwork()` often
    // fails on Chinese ROMs (ColorOS, HyperOS, MIUI) for no-internet WiFi.
    //
    // Strategy:
    //   Tier 1: Find existing WiFi via getAllNetworks() → bind directly
    //   Tier 2: registerNetworkCallback() → wait for WiFi
    //   Tier 3: Skip binding entirely → try raw connection (works on some devices)

    private fun getConnectivityManager(): ConnectivityManager {
        return getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    /**
     * Tier 1: Scan all active networks for a WiFi one and bind to it.
     */
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

    /**
     * Tier 2: Register a network callback and wait for WiFi.
     */
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

            // Timeout after 5s
            viewModelScope.launch {
                delay(5000)
                if (cont.isActive) {
                    Log.w(TAG, "Tier 2 timed out")
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
                // Tier 3: Skip binding, proceed without it
                Log.w(TAG, "Tier 3: Skipping WiFi binding, trying raw connection")
                _uiState.update { it.copy(statusMessage = "Skipped WiFi binding, trying direct…") }
                // Don't return — let SSDP/fallback try anyway
            }

            // === Camera Discovery (4-tier) ===
            // Tier 1: SSDP multicast
            // Tier 2: Gateway IP from DHCP + port scan
            // Tier 3: Hardcoded common IPs
            // Tier 4: Give up with helpful error

            val ctx = getApplication<Application>()
            var discovered = false

            // Tier 1: SSDP
            _uiState.update { it.copy(statusMessage = "SSDP searching…") }
            val locationUrl = cameraClient.discover(ctx)

            if (locationUrl != null) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.CONNECTING,
                        statusMessage = "Found via SSDP, reading info…"
                    )
                }
                val initResult = cameraClient.initFromDescription(locationUrl)
                if (initResult.isSuccess) {
                    _uiState.update { it.copy(modelName = initResult.getOrDefault("Sony Camera")) }
                    discovered = true
                }
            }

            // Tier 2: Gateway IP + port scan (most reliable on Chinese ROMs)
            if (!discovered) {
                val gwIp = cameraClient.getGatewayIp(ctx)
                _uiState.update {
                    it.copy(statusMessage = "Trying gateway IP ${gwIp ?: "N/A"}…")
                }
                val gwResult = cameraClient.tryGatewayDiscovery(ctx)
                if (gwResult != null) {
                    if (gwResult.startsWith("DIRECT:")) {
                        // Already initialized via direct API call
                        _uiState.update {
                            it.copy(
                                connectionState = ConnectionState.CONNECTING,
                                modelName = "Sony Camera",
                                statusMessage = "Found camera at $gwIp!"
                            )
                        }
                        discovered = true
                    } else {
                        // Got device description URL, parse it
                        val initResult = cameraClient.initFromDescription(gwResult)
                        if (initResult.isSuccess) {
                            _uiState.update {
                                it.copy(
                                    connectionState = ConnectionState.CONNECTING,
                                    modelName = initResult.getOrDefault("Sony Camera"),
                                    statusMessage = "Found camera!"
                                )
                            }
                            discovered = true
                        }
                    }
                }
            }

            // Tier 3: Hardcoded common IPs
            if (!discovered) {
                _uiState.update { it.copy(statusMessage = "Trying common IPs…") }
                val fallbackUrl = cameraClient.tryHardcodedAddresses()
                if (fallbackUrl != null) {
                    cameraClient.initFromBaseUrl(fallbackUrl)
                    _uiState.update {
                        it.copy(
                            connectionState = ConnectionState.CONNECTING,
                            modelName = "Sony Camera",
                            statusMessage = "Found camera via fallback!"
                        )
                    }
                    discovered = true
                }
            }

            // Tier 4: Give up
            if (!discovered) {
                val gwIp = cameraClient.getGatewayIp(ctx)
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = buildString {
                            append("Camera not found.\n\n")
                            if (gwIp != null) {
                                append("WiFi gateway: $gwIp\n")
                                append("(API ports 10000/8080/64321 all unreachable)\n\n")
                            } else {
                                append("⚠ Cannot read WiFi gateway IP.\n\n")
                            }
                            if (!wifiBound) {
                                append("⚠ WiFi binding also failed.\n")
                                append("→ Turn OFF mobile data, then retry.\n\n")
                            }
                            append("Steps:\n")
                            append("1. Camera: Menu → Network → Send to Smartphone\n")
                            append("2. Phone: Connect to camera WiFi\n")
                            append("3. Turn off mobile data\n")
                            append("4. Tap Retry")
                        },
                        statusMessage = "Camera not found"
                    )
                }
                return@launch
            }

            // === Switch to Contents Transfer mode ===
            _uiState.update { it.copy(statusMessage = "Switching to transfer mode…") }
            cameraClient.switchToTransferMode()

            if (!cameraClient.hasAvContent()) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = "Content transfer not supported (avContent service missing).\n\n" +
                                "Make sure the camera is in 'Send to Smartphone' mode, not 'Remote Ctrl'.",
                        statusMessage = "Not supported"
                    )
                }
                return@launch
            }

            // === Load photos ===
            _uiState.update { it.copy(statusMessage = "Loading photos…") }
            val totalCount = cameraClient.getContentCount().getOrDefault(0)
            val listResult = cameraClient.getContentList(startIndex = 0, count = 100)

            if (listResult.isFailure) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = "Failed to load photos:\n${listResult.exceptionOrNull()?.message}",
                        statusMessage = "Loading failed"
                    )
                }
                return@launch
            }

            val contents = listResult.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.READY,
                    contents = contents,
                    totalCount = if (totalCount > 0) totalCount else contents.size,
                    hasMore = totalCount > 0 && contents.size < totalCount,
                    statusMessage = if (totalCount > 0) "$totalCount photos on camera"
                    else "${contents.size} photos loaded"
                )
            }
        }
    }

    // ── Pagination ───────────────────────────────────────────────────

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMore) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val result = cameraClient.getContentList(
                startIndex = state.contents.size, count = 100
            )
            if (result.isSuccess) {
                val newItems = result.getOrDefault(emptyList())
                val all = state.contents + newItems
                _uiState.update {
                    it.copy(
                        contents = all,
                        hasMore = all.size < it.totalCount,
                        isLoadingMore = false
                    )
                }
            } else {
                _uiState.update { it.copy(isLoadingMore = false) }
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
        val cm = getConnectivityManager()
        cm.bindProcessToNetwork(null)
        networkCallback?.let {
            try {
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        _uiState.update { UiState() }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}
