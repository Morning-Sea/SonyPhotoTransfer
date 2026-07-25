package com.morningsea.sonytransfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest

// ═══════════════════════════════════════════════════════════════════
//  Color Theme — Sony α inspired dark theme with orange accent
// ═══════════════════════════════════════════════════════════════════

private val SonyOrange = Color(0xFFFF7700)
private val SonyOrangeDark = Color(0xFFCC5500)
private val DarkSurface = Color(0xFF1C1C1E)
private val DarkBackground = Color(0xFF121214)
private val DarkCard = Color(0xFF2C2C2E)

private val SonyDarkColorScheme = darkColorScheme(
    primary = SonyOrange,
    onPrimary = Color.Black,
    primaryContainer = SonyOrangeDark,
    secondary = Color(0xFF8AB4F8),
    surface = DarkSurface,
    background = DarkBackground,
    surfaceVariant = DarkCard,
    onSurface = Color.White,
    onBackground = Color.White,
    onSurfaceVariant = Color(0xFFB0B0B0),
    error = Color(0xFFCF6679)
)

// ═══════════════════════════════════════════════════════════════════
//  Activity
// ═══════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = SonyDarkColorScheme) {
                SonyTransferApp()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Root Composable
// ═══════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonyTransferApp(viewModel: CameraViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // ── Permission ───────────────────────────────────────────────
    var hasPermission by remember { mutableStateOf(checkPermission(context)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            requiredPermission()?.let { launcher.launch(it) }
                ?: run { hasPermission = true }
        }
    }

    // ── Coil ImageLoader bound to camera WiFi ────────────────────
    val imageLoader = remember(viewModel.cameraClient.getHttpClient()) {
        ImageLoader.Builder(context)
            .okHttpClient(viewModel.cameraClient.getHttpClient())
            .crossfade(true)
            .build()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("SonyTransfer", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        AnimatedVisibility(visible = state.cameraIp.isNotEmpty()) {
                            Text(
                                state.cameraIp,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (state.connectionState == ConnectionState.READY) {
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Disconnect")
                        }
                    }
                },
                actions = {
                    if (state.connectionState == ConnectionState.READY) {
                        // Status text
                        Text(
                            state.statusMessage,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (state.connectionState == ConnectionState.READY && state.contents.isNotEmpty()) {
                SelectionBottomBar(
                    selectedCount = state.selectedIndices.size,
                    totalCount = state.contents.size,
                    onSelectAll = {
                        if (state.selectedIndices.size == state.contents.size)
                            viewModel.deselectAll()
                        else viewModel.selectAll()
                    },
                    onDownload = { viewModel.downloadSelected() },
                    isAllSelected = state.selectedIndices.size == state.contents.size
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.connectionState) {
                ConnectionState.DISCONNECTED -> ConnectScreen { viewModel.connect() }
                ConnectionState.DISCOVERING,
                ConnectionState.CONNECTING -> LoadingScreen(state.statusMessage)

                ConnectionState.READY -> GalleryGrid(
                    contents = state.contents,
                    selectedIndices = state.selectedIndices,
                    imageLoader = imageLoader,
                    onToggleSelect = { viewModel.toggleSelection(it) }
                )

                ConnectionState.ERROR -> ErrorScreen(
                    message = state.errorMessage ?: "Unknown error",
                    onRetry = { viewModel.connect() }
                )
            }

            // Download overlay dialog
            if (state.isDownloading) {
                DownloadDialog(
                    current = state.downloadCurrent,
                    total = state.downloadTotal,
                    progress = state.downloadProgress,
                    downloaded = state.downloadedCount,
                    onCancel = { viewModel.cancelDownload() }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Screen: Connect
// ═══════════════════════════════════════════════════════════════════

@Composable
fun ConnectScreen(onConnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Camera icon
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            modifier = Modifier.size(100.dp)
        ) {
            Icon(
                Icons.Default.CameraAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(24.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Sony Photo Transfer",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Wirelessly transfer photos from your Sony camera",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp
        )

        Spacer(Modifier.height(36.dp))

        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(0.75f).height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Wifi, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Connect to Camera", fontSize = 16.sp)
        }

        Spacer(Modifier.height(28.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "📋 Before connecting:",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(10.dp))
                StepText("1", "Camera: MENU → Network → Send to Smartphone")
                Spacer(Modifier.height(6.dp))
                StepText("2", "Select any photo to start the WiFi hotspot")
                Spacer(Modifier.height(6.dp))
                StepText("3", "Phone: Connect to camera's WiFi (DIRECT-xxxx)")
                Spacer(Modifier.height(6.dp))
                StepText("4", "Turn OFF mobile data, then tap Connect above")
            }
        }
    }
}

@Composable
private fun StepText(num: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
            modifier = Modifier.size(22.dp)
        ) {
            Text(
                num,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Screen: Loading
// ═══════════════════════════════════════════════════════════════════

@Composable
fun LoadingScreen(message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Screen: Error
// ═══════════════════════════════════════════════════════════════════

@Composable
fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("⚠️", fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            message,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.error,
            lineHeight = 22.sp,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onRetry,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Retry")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Screen: Photo Gallery Grid
// ═══════════════════════════════════════════════════════════════════

@Composable
fun GalleryGrid(
    contents: List<ContentItem>,
    selectedIndices: Set<Int>,
    imageLoader: ImageLoader,
    onToggleSelect: (Int) -> Unit
) {
    val gridState = rememberLazyGridState()

    if (contents.isEmpty()) {
        // Empty state
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.PhotoLibrary, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text("No photos found", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(
            items = contents,
            key = { index, item -> "${index}_${item.id}" }
        ) { index, item ->
            PhotoGridItem(
                item = item,
                isSelected = index in selectedIndices,
                imageLoader = imageLoader,
                onClick = { onToggleSelect(index) }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Component: Photo Grid Item
// ═══════════════════════════════════════════════════════════════════

@Composable
fun PhotoGridItem(
    item: ContentItem,
    isSelected: Boolean,
    imageLoader: ImageLoader,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .padding(2.dp)
            .aspectRatio(1f)
            .clip(shape)
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.border(
                    3.dp,
                    MaterialTheme.colorScheme.primary,
                    shape
                )
                else Modifier
            )
    ) {
        // Thumbnail
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.thumbnailUrl)
                .crossfade(true)
                .build(),
            contentDescription = item.title,
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Selection indicator (top-right)
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            Surface(
                shape = CircleShape,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.size(24.dp)
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.padding(3.dp)
                    )
                }
            }
        }

        // Bottom gradient with filename
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f))
                    )
                )
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(
                item.title,
                fontSize = 10.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Component: Selection Bottom Bar
// ═══════════════════════════════════════════════════════════════════

@Composable
fun SelectionBottomBar(
    selectedCount: Int,
    totalCount: Int,
    isAllSelected: Boolean,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit
) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection count
            Text(
                "$selectedCount / $totalCount",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (selectedCount > 0) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Select all / deselect all
                FilledTonalButton(
                    onClick = onSelectAll,
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Icon(
                        if (isAllSelected) Icons.Default.Close else Icons.Default.SelectAll,
                        null, modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (isAllSelected) "Deselect" else "All",
                        fontSize = 13.sp
                    )
                }

                // Download button
                Button(
                    onClick = onDownload,
                    enabled = selectedCount > 0,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Download ($selectedCount)")
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
//  Component: Download Progress Dialog
// ═══════════════════════════════════════════════════════════════════

@Composable
fun DownloadDialog(
    current: Int, total: Int, progress: Float,
    downloaded: Int, onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* non-dismissable */ },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Download, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Downloading Photos")
            }
        },
        text = {
            Column {
                Text(
                    "Photo $current of $total",
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${(progress * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CheckCircle, null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "$downloaded saved",
                            fontSize = 12.sp,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════
//  Permission Helpers
// ═══════════════════════════════════════════════════════════════════

private fun checkPermission(context: android.content.Context): Boolean = when {
    Build.VERSION.SDK_INT >= 33 ->
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED

    Build.VERSION.SDK_INT >= 29 -> true  // scoped storage, no permission needed
    else ->
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
}

private fun requiredPermission(): String? = when {
    Build.VERSION.SDK_INT >= 33 -> Manifest.permission.READ_MEDIA_IMAGES
    Build.VERSION.SDK_INT >= 29 -> null
    else -> Manifest.permission.WRITE_EXTERNAL_STORAGE
}
