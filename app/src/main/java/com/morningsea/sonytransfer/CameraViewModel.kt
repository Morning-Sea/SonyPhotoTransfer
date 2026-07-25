package com.morningsea.sonytransfer

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

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

    // ── Connection Flow ──────────────────────────────────────────────

    fun connect() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    connectionState = ConnectionState.DISCOVERING,
                    statusMessage = "Binding to WiFi network…",
                    errorMessage = null,
                    contents = emptyList(),
                    selectedIndices = emptySet()
                )
            }

            // Step 1: Bind process to WiFi (critical for camera AP without internet)
            val cm = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val bound = suspendCancellableCoroutine { cont: CancellableContinuation<Boolean> ->
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()

                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        cm.bindProcessToNetwork(network)
                        cameraClient.bindToNetwork(network)
                        networkCallback = this
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
                    cm.requestNetwork(request, callback)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
                // Timeout after 8s
                viewModelScope.launch {
                    delay(8000)
                    if (cont.isActive) cont.resumeWith(Result.success(false))
                }
            }

            if (!bound) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = "Cannot bind to WiFi.\nMake sure you're connected to the camera's WiFi network.",
                        statusMessage = "WiFi binding failed"
                    )
                }
                return@launch
            }

            // Step 2: SSDP Discovery
            _uiState.update { it.copy(statusMessage = "Searching for camera…") }
            val ctx = getApplication<Application>()
            val locationUrl = cameraClient.discover(ctx)

            if (locationUrl == null) {
                // Fallback: try common IPs
                _uiState.update { it.copy(statusMessage = "SSDP timeout, trying common addresses…") }
                val fallbackUrl = cameraClient.tryFallbackAddresses()
                if (fallbackUrl != null) {
                    cameraClient.initFromBaseUrl(fallbackUrl)
                    _uiState.update {
                        it.copy(
                            connectionState = ConnectionState.CONNECTING,
                            modelName = "Sony Camera",
                            statusMessage = "Found camera via fallback, connecting…"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            connectionState = ConnectionState.ERROR,
                            errorMessage = "Camera not found.\n\n" +
                                    "1. On camera: Menu → Network → Send to Smartphone\n" +
                                    "2. Connect phone to camera's WiFi\n" +
                                    "3. Tap Retry",
                            statusMessage = "Camera not found"
                        )
                    }
                    return@launch
                }
            } else {
                // Parse device description XML
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.CONNECTING,
                        statusMessage = "Found camera, reading device info…"
                    )
                }
                val initResult = cameraClient.initFromDescription(locationUrl)
                if (initResult.isFailure) {
                    _uiState.update {
                        it.copy(
                            connectionState = ConnectionState.ERROR,
                            errorMessage = "Failed to read camera info:\n${initResult.exceptionOrNull()?.message}",
                            statusMessage = "Connection failed"
                        )
                    }
                    return@launch
                }
                _uiState.update { it.copy(modelName = initResult.getOrDefault("Sony Camera")) }
            }

            // Step 3: Switch to Contents Transfer mode
            _uiState.update { it.copy(statusMessage = "Switching to transfer mode…") }
            cameraClient.switchToTransferMode()

            if (!cameraClient.hasAvContent()) {
                _uiState.update {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        errorMessage = "This camera does not support content transfer via WiFi API (avContent service missing).",
                        statusMessage = "Not supported"
                    )
                }
                return@launch
            }

            // Step 4: Get content count & first page
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
                    // Determine filename with extension
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
        val cm = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
