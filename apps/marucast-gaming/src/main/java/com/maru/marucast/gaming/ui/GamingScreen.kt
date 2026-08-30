package com.maru.marucast.gaming.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.maru.marucast.gaming.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamingScreen(
    client: PcClient
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val prefs = remember { GamingPreferences(context) }


    var ipPort by remember { mutableStateOf(prefs.lastHost) }
    val audioPlayer = remember(ipPort) { com.maru.marucast.gaming.util.AudioStreamPlayer(ipPort) }
    var scanDir by remember { mutableStateOf(prefs.lastScanDir) }
    var isConnected by remember { mutableStateOf(false) }
    var pcHostname by remember { mutableStateOf("") }
    var pcService by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    // Navigation Tab: 0 = Library, 1 = File Explorer, 2 = Stream Settings
    var selectedTab by remember { mutableIntStateOf(0) }

    // Explorer State
    var availableDrives by remember { mutableStateOf<List<String>>(listOf("C:\\", "D:\\")) }
    var currentExplorerPath by remember { mutableStateOf(prefs.lastScanDir) }
    var explorerItems by remember { mutableStateOf<List<ExecutableItem>>(emptyList()) }
    var explorerSearchQuery by remember { mutableStateOf("") }

    // Library State
    var gameList by remember { mutableStateOf<List<ExecutableItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var activeSession by remember { mutableStateOf<ActiveSession?>(null) }

    // Stream & Aspect Scaling
    var scaleModeIndex by remember { mutableIntStateOf(prefs.scaleModeIndex) }
    var uiScaleFactor by remember { mutableFloatStateOf(prefs.uiScaleFactor) }
    var streamFpsTarget by remember { mutableIntStateOf(prefs.streamFpsTarget) }
    var isTrackpadMode by remember { mutableStateOf(prefs.trackpadMode) }

    // In-Game UI Controls
    var isUiHidden by remember { mutableStateOf(false) }
    var showInGameHud by remember { mutableStateOf(true) }
    var showVnActionSheet by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showAddBookmarkDialog by remember { mutableStateOf(false) }
    var newBookmarkName by remember { mutableStateOf("") }
    var newBookmarkPath by remember { mutableStateOf("") }

    // Touch Feedback & Virtual Cursor Position
    var virtualCursorPos by remember { mutableStateOf(Offset(0.5f, 0.5f)) }
    var tapFeedbackPos by remember { mutableStateOf<Offset?>(null) }
    var showTapFeedback by remember { mutableStateOf(false) }

    // Bookmarks & Pinned Games
    var bookmarks by remember { mutableStateOf(prefs.getFolderBookmarks()) }
    var pinnedGamePaths by remember { mutableStateOf(prefs.getPinnedGamePaths()) }
    val gameConfigs = remember { mutableStateMapOf<String, GameConfig>() }

    // Running Windows on PC
    var runningWindows by remember { mutableStateOf<List<RunningWindowItem>>(emptyList()) }
    var isFetchingWindows by remember { mutableStateOf(false) }

    // Bitmaps (Raw Android Bitmap for saving/recording, ImageBitmap for Compose Image)
    var rawLatestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // Auto rotate to Landscape & enter Immersive Fullscreen when streaming games
    DisposableEffect(activeSession) {
        if (activeSession != null) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            audioPlayer.start()
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            audioPlayer.stop()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Load available drives and current directory
    val loadExplorerDir: (String) -> Unit = { targetDir ->
        scope.launch {
            isLoading = true
            currentExplorerPath = targetDir
            prefs.lastScanDir = targetDir
            val res = client.scanDirectory(ipPort, targetDir)
            isLoading = false
            res.onSuccess { items ->
                explorerItems = items
            }.onFailure {
                statusMessage = "Explorer error: ${it.message}"
            }
        }
    }

    val fetchDrivesAndExplorer: () -> Unit = {
        scope.launch {
            val drivesRes = client.getDrives(ipPort)
            drivesRes.onSuccess {
                if (it.isNotEmpty()) {
                    availableDrives = it
                }
            }
            loadExplorerDir(currentExplorerPath)
        }
    }

    val fetchRunningWindows: () -> Unit = {
        scope.launch {
            isFetchingWindows = true
            val res = client.getRunningWindows(ipPort)
            res.onSuccess {
                runningWindows = it
            }.onFailure {
                Toast.makeText(context, "Failed to get windows: ${it.message}", Toast.LENGTH_SHORT).show()
            }
            isFetchingWindows = false
        }
    }

    val hookWindowToPhone: (RunningWindowItem) -> Unit = { win ->
        scope.launch {
            isLoading = true
            val res = client.hookWindow(ipPort, win.hwnd, offscreen = true)
            res.onSuccess { session ->
                activeSession = session
                Toast.makeText(context, "⚡ Cast '${win.title}' to Phone!", Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                Toast.makeText(context, "Cast failed: ${err.message}", Toast.LENGTH_SHORT).show()
            }
            isLoading = false
        }
    }

    // Auto-check connection on start
    val checkStatus: (String?) -> Unit = { targetHost ->
        val hostToTest = targetHost ?: ipPort
        scope.launch {
            isLoading = true
            val res = client.getStatus(hostToTest)
            isLoading = false
            res.onSuccess {
                isConnected = true
                ipPort = hostToTest
                prefs.lastHost = hostToTest
                prefs.addRecentHost(hostToTest)
                pcHostname = it.hostname
                pcService = it.service
                activeSession = it.activeSession
                statusMessage = "Connected to ${it.hostname}"
                fetchDrivesAndExplorer()
            }.onFailure {
                isConnected = false
                statusMessage = "Connection failed to $hostToTest: ${it.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        checkStatus(null)
    }

    // Live frame stream via MJPEG — one persistent connection, server pushes frames
    LaunchedEffect(activeSession, ipPort) {
        if (activeSession != null) {
            withContext(Dispatchers.IO) {
                var reconnectDelay = 1000L
                val maxReconnectDelay = 10000L
                while (activeSession != null) {
                    try {
                        val url = java.net.URL("http://$ipPort/api/stream")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 0
                        conn.requestMethod = "GET"
                        conn.connect()

                        val input = java.io.BufferedInputStream(conn.inputStream, 65536)
                        val boundary = "--mjpegboundary"
                        val lineBuffer = StringBuilder()
                        var inHeaders = false
                        var jpegLength = 0

                        fun readLine(): String {
                            lineBuffer.clear()
                            var c: Int
                            while (input.read().also { c = it } != -1) {
                                if (c == '\n'.code) break
                                if (c != '\r'.code) lineBuffer.append(c.toChar())
                            }
                            return lineBuffer.toString()
                        }

                        reconnectDelay = 1000L

                        while (activeSession != null) {
                            val line = readLine()
                            if (line.isEmpty() && lineBuffer.isEmpty()) {
                                break
                            }
                            when {
                                line.startsWith(boundary) -> { inHeaders = true; jpegLength = 0 }
                                inHeaders && line.startsWith("Content-Length:") -> {
                                    jpegLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                                }
                                inHeaders && line.isEmpty() -> {
                                    inHeaders = false
                                    if (jpegLength > 0) {
                                        val buf = ByteArray(jpegLength)
                                        var read = 0
                                        while (read < jpegLength) {
                                            val n = input.read(buf, read, jpegLength - read)
                                            if (n == -1) break
                                            read += n
                                        }
                                        if (read == jpegLength) {
                                            val bmp = BitmapFactory.decodeByteArray(buf, 0, read)
                                            if (bmp != null) {
                                                rawLatestBitmap = bmp
                                                currentBitmap = bmp.asImageBitmap()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        conn.disconnect()
                    } catch (_: Exception) {
                    }
                    if (activeSession == null) break
                    delay(reconnectDelay)
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(maxReconnectDelay)
                }
            }
        }
    }


    val launchGame: (ExecutableItem) -> Unit = { item ->
        scope.launch {
            isLoading = true
            val cfg = gameConfigs[item.path] ?: prefs.getGameConfig(item.path) ?: GameConfig(
                path = item.path,
                name = item.name,
                japaneseLocale = true,
                asAdmin = false,
                scaleFactor = uiScaleFactor
            )
            val res = client.launchApp(ipPort, cfg.path, cfg.japaneseLocale, cfg.asAdmin, true, cfg.scaleFactor)
            isLoading = false
            res.onSuccess {
                activeSession = it
                prefs.saveGameConfig(cfg.copy(lastPlayedTimestamp = System.currentTimeMillis()))
                statusMessage = "Playing ${it.name} off-screen on PC!"
            }.onFailure {
                statusMessage = "Launch failed: ${it.message}"
            }
        }
    }

    val stopGame = {
        scope.launch {
            audioPlayer.stop()
            isLoading = true
            client.stopApp(ipPort)
            activeSession = null
            isLoading = false
            showExitDialog = false
            statusMessage = "Game session ended."
        }
    }

    val sendKeyAction: (String) -> Unit = { keyName ->
        scope.launch {
            client.sendInput(ipPort = ipPort, eventType = "key", key = keyName)
        }
    }

    val takeNativeScreenshot: () -> Unit = {
        val bmp = rawLatestBitmap
        if (bmp != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val cleanName = (activeSession?.name ?: "Game").replace(" ", "_")
                    val filename = "Marucast_${cleanName}_${System.currentTimeMillis()}.png"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Marucast")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "📸 Saved native screenshot (${bmp.width}x${bmp.height}) to Pictures/Marucast!", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Failed to save screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(context, "No active frame to capture", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = Color(0xFF070A13)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (activeSession != null) {
                // ==========================================
                // 1. ACTIVE GAME STREAM MODE (FULLSCREEN LANDSCAPE)
                // ==========================================
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {
                    val activeContentScale = when (scaleModeIndex) {
                        0 -> ContentScale.FillBounds // Stretches edge-to-edge (no black bars!)
                        1 -> ContentScale.Crop       // Aspect zoom fill (no black bars!)
                        else -> ContentScale.Fit     // Letterbox 16:9
                    }

                    // Direct Touch Input & Gesture Tracking Layer
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(isTrackpadMode) {
                                if (!isTrackpadMode) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull() ?: continue
                                            val pos = change.position
                                            val xRatio = (pos.x / size.width).coerceIn(0f, 1f)
                                            val yRatio = (pos.y / size.height).coerceIn(0f, 1f)

                                            when (event.type) {
                                                PointerEventType.Press -> {
                                                    change.consume()
                                                    tapFeedbackPos = pos
                                                    showTapFeedback = true
                                                    scope.launch {
                                                        delay(350)
                                                        showTapFeedback = false
                                                    }
                                                    scope.launch {
                                                        client.sendInput(ipPort, "down", xRatio, yRatio, "left")
                                                    }
                                                }
                                                PointerEventType.Move -> {
                                                    change.consume()
                                                    tapFeedbackPos = pos
                                                    scope.launch {
                                                        client.sendInput(ipPort, "move", xRatio, yRatio, "left")
                                                    }
                                                }
                                                PointerEventType.Release -> {
                                                    change.consume()
                                                    scope.launch {
                                                        client.sendInput(ipPort, "up", xRatio, yRatio, "left")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        if (currentBitmap != null) {
                            Image(
                                bitmap = currentBitmap!!,
                                contentDescription = "PC Live Game Stream",
                                contentScale = activeContentScale,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(
                                        color = Color(0xFFFF5277),
                                        modifier = Modifier.size(44.dp)
                                    )
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        "Connecting to live video feed...",
                                        color = Color(0xFFC084FC),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        // Direct Touch Ripple / Pointer Click Feedback
                        if (showTapFeedback && tapFeedbackPos != null && !isTrackpadMode) {
                            Box(
                                modifier = Modifier
                                    .offset {
                                        IntOffset(
                                            (tapFeedbackPos!!.x - 18.dp.toPx()).roundToInt(),
                                            (tapFeedbackPos!!.y - 18.dp.toPx()).roundToInt()
                                        )
                                    }
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x66FF5277))
                                    .border(2.dp, Color(0xFFFF71A2), CircleShape)
                            )
                        }
                    }

                    // Trackpad Mode Overlay with Glowing Cursor Pointer
                    if (isTrackpadMode) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val curX = (virtualCursorPos.x * size.width + dragAmount.x).coerceIn(0f, size.width.toFloat())
                                            val curY = (virtualCursorPos.y * size.height + dragAmount.y).coerceIn(0f, size.height.toFloat())
                                            val newXRatio = curX / size.width
                                            val newYRatio = curY / size.height
                                            virtualCursorPos = Offset(newXRatio, newYRatio)
                                            scope.launch {
                                                client.sendInput(ipPort, "move", newXRatio, newYRatio, "left")
                                            }
                                        }
                                    )
                                }
                        ) {
                            // Glowing Cyberpunk Pointer Indicator
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset {
                                        IntOffset(
                                            (virtualCursorPos.x * 1920 - 10.dp.toPx()).roundToInt().coerceAtLeast(0),
                                            (virtualCursorPos.y * 1080 - 10.dp.toPx()).roundToInt().coerceAtLeast(0)
                                        )
                                    }
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF5277))
                                    .border(2.dp, Color.White, CircleShape)
                            )
                        }
                    }

                    // Floating In-Game HUD
                    AnimatedVisibility(
                        visible = showInGameHud && !isUiHidden,
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut() + slideOutVertically(),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 14.dp, start = 20.dp, end = 20.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xEE120E1E),
                            border = BorderStroke(1.dp, Color(0x66C084FC)),
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Live Indicator & Game Title
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4ADE80))
                                )
                                Text(
                                    text = activeSession?.name ?: "Game",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                }

                                Spacer(Modifier.width(4.dp))

                                // 📸 Snapshot Button
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xCC2A2438),
                                    border = BorderStroke(1.dp, Color(0x33C084FC)),
                                    modifier = Modifier.clickable { takeNativeScreenshot() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = Color(0xFFFF9ECD), modifier = Modifier.size(14.dp))
                                        Text("Photo", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }

                                // Quick VN Actions Toggle Button
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (showVnActionSheet) Color(0xCCFF5277) else Color(0xCC2A2438),
                                    border = BorderStroke(1.dp, Color(0x33C084FC)),
                                    modifier = Modifier.clickable { showVnActionSheet = !showVnActionSheet }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.Tune, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        Text("VN Bar", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }

                                // Aspect Fit / Stretch / Zoom Toggle Button
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xCC2A2438),
                                    border = BorderStroke(1.dp, Color(0x33C084FC)),
                                    modifier = Modifier.clickable {
                                        scaleModeIndex = (scaleModeIndex + 1) % 3
                                        prefs.scaleModeIndex = scaleModeIndex
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.AspectRatio, contentDescription = null, tint = Color(0xFFC084FC), modifier = Modifier.size(14.dp))
                                        Text(
                                            text = when (scaleModeIndex) {
                                                0 -> "Stretch"
                                                1 -> "Zoom Fill"
                                                else -> "16:9 Fit"
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                // Touchpad Mode Toggle
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isTrackpadMode) Color(0xCC9333EA) else Color(0xCC2A2438),
                                    border = BorderStroke(1.dp, Color(0x33C084FC)),
                                    modifier = Modifier.clickable {
                                        isTrackpadMode = !isTrackpadMode
                                        prefs.trackpadMode = isTrackpadMode
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.TouchApp, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                        Text(if (isTrackpadMode) "Trackpad" else "Direct", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }

                                // 👁️ Hide UI Button
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xCC2A2438),
                                    border = BorderStroke(1.dp, Color(0x33C084FC)),
                                    modifier = Modifier.clickable { isUiHidden = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.VisibilityOff, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                                        Text("Hide UI", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }

                                // Exit Button
                                Button(
                                    onClick = { showExitDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Exit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    // Floating Show UI Restore Button (Visible only when UI is hidden)
                    if (isUiHidden) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0x88181424),
                            border = BorderStroke(1.dp, Color(0x44C084FC)),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .clickable { isUiHidden = false }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, tint = Color(0xFFFF9ECD), modifier = Modifier.size(16.dp))
                                Text("Show UI", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }

                    // Floating Bottom Quick Action Toolbar for Visual Novels
                    AnimatedVisibility(
                        visible = showVnActionSheet && !isUiHidden,
                        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = Color(0xEE181424),
                            border = BorderStroke(1.dp, Color(0x66C084FC)),
                            shadowElevation = 12.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                VnQuickButton("⏭️ Skip (Ctrl)", Color(0xFFF59E0B)) { sendKeyAction("ctrl") }
                                VnQuickButton("▶️ Auto (Space)", Color(0xFF3B82F6)) { sendKeyAction("space") }
                                VnQuickButton("💾 Save (S)", Color(0xFF10B981)) { sendKeyAction("s") }
                                VnQuickButton("📂 Load (L)", Color(0xFF8B5CF6)) { sendKeyAction("l") }
                                VnQuickButton("📜 Log (PageUp)", Color(0xFFEC4899)) { sendKeyAction("pageup") }
                                VnQuickButton("⚙️ Menu (Esc)", Color(0xFF64748B)) { sendKeyAction("escape") }
                            }
                        }
                    }
                }
            } else {
                // ==========================================
                // 2. DASHBOARD / EXPLORER / LIBRARY MODE
                // ==========================================
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Top App Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Marucast",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFFF5277)
                                ) {
                                    Text(
                                        text = "GAMING",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Play PC Visual Novels Off-Screen",
                                fontSize = 11.5.sp,
                                color = Color(0xFF9E95B8)
                            )
                        }

                        // Host Status Pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isConnected) Color(0x224ADE80) else Color(0x22EF4444),
                            border = BorderStroke(
                                1.dp,
                                if (isConnected) Color(0xFF4ADE80) else Color(0xFFEF4444)
                            ),
                            modifier = Modifier.clickable { checkStatus(null) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (isConnected) Color(0xFF4ADE80) else Color(0xFFEF4444))
                                )
                                Text(
                                    text = if (isConnected) pcHostname else "Offline",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isConnected) Color(0xFF4ADE80) else Color(0xFFF87171)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Navigation Tabs Row (Library, Windows Explorer, Running PC Apps, Settings)
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color(0xFF120E1E),
                        contentColor = Color(0xFFC084FC),
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = Color(0xFFFF5277),
                                height = 3.dp
                            )
                        },
                        modifier = Modifier.clip(RoundedCornerShape(12.dp))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("🎮 Library (${gameList.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                                if (explorerItems.isEmpty()) {
                                    fetchDrivesAndExplorer()
                                }
                            },
                            text = { Text("📂 Explorer", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = {
                                selectedTab = 2
                                fetchRunningWindows()
                            },
                            text = { Text("⚡ Running (${runningWindows.size})", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        )
                        Tab(
                            selected = selectedTab == 3,
                            onClick = { selectedTab = 3 },
                            text = { Text("⚙️ Settings", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // TAB 0: LIBRARY TAB
                    if (selectedTab == 0) {
                        // Search Bar
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search games...", color = Color(0xFF6B6280)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF9E95B8)) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF181424),
                                unfocusedContainerColor = Color(0xFF181424),
                                focusedBorderColor = Color(0xFFC084FC),
                                unfocusedBorderColor = Color(0xFF2E2744),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))

                        val filteredGames = gameList.filter {
                            searchQuery.isEmpty() || it.name.contains(searchQuery, ignoreCase = true) || it.path.contains(searchQuery, ignoreCase = true)
                        }

                        if (filteredGames.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Default.SportsEsports,
                                        contentDescription = null,
                                        tint = Color(0xFF3B3454),
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        text = if (searchQuery.isNotEmpty()) "No games matching '$searchQuery'" else "No games added yet.",
                                        color = Color(0xFF9E95B8),
                                        fontSize = 14.sp
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Button(
                                        onClick = { selectedTab = 1; fetchDrivesAndExplorer() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Open Windows File Explorer")
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(filteredGames, key = { it.path }) { item ->
                                    val isPinned = pinnedGamePaths.contains(item.path)
                                    val config = gameConfigs[item.path] ?: prefs.getGameConfig(item.path) ?: GameConfig(
                                        path = item.path,
                                        name = item.name,
                                        japaneseLocale = true,
                                        asAdmin = false,
                                        scaleFactor = uiScaleFactor
                                    )

                                    GameCardItem(
                                        item = item,
                                        config = config,
                                        isPinned = isPinned,
                                        onTogglePin = {
                                            prefs.togglePinGame(item.path)
                                            pinnedGamePaths = prefs.getPinnedGamePaths()
                                        },
                                        onUpdateConfig = { updated ->
                                            gameConfigs[item.path] = updated
                                            prefs.saveGameConfig(updated)
                                        },
                                        onLaunch = { launchGame(item) }
                                    )
                                }
                            }
                        }
                    }

                    // TAB 1: WINDOWS FILE EXPLORER TAB
                    else if (selectedTab == 1) {
                        // 1. Drive Selector Chips (C:\, D:\, etc.)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("DRIVES:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF70A5FF))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(availableDrives) { drive ->
                                    val isCurrentDrive = currentExplorerPath.startsWith(drive, ignoreCase = true)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isCurrentDrive) Color(0xFF3B82F6) else Color(0xFF181424),
                                        border = BorderStroke(1.dp, if (isCurrentDrive) Color(0xFF70A5FF) else Color(0xFF2E2744)),
                                        modifier = Modifier.clickable { loadExplorerDir(drive) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Default.Storage, contentDescription = null, tint = if (isCurrentDrive) Color.White else Color(0xFF9E95B8), modifier = Modifier.size(13.dp))
                                            Text(drive, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 2. Interactive Breadcrumb Path Bar & Up Button
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF181424),
                            border = BorderStroke(1.dp, Color(0xFF2E2744)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Up button
                                IconButton(
                                    onClick = {
                                        val parent = File(currentExplorerPath).parent
                                        if (parent != null) {
                                            loadExplorerDir(parent)
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.ArrowUpward, contentDescription = "Up", tint = Color(0xFFC084FC), modifier = Modifier.size(16.dp))
                                }

                                Spacer(Modifier.width(4.dp))

                                // Breadcrumb segments
                                val parts = currentExplorerPath.replace("/", "\\").split("\\").filter { it.isNotEmpty() }
                                LazyRow(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    items(parts.indices.toList()) { index ->
                                        val subPath = if (index == 0) {
                                            parts[0] + "\\"
                                        } else {
                                            parts.subList(0, index + 1).joinToString("\\")
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = parts[index],
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (index == parts.lastIndex) Color(0xFFFF9ECD) else Color(0xFF9E95B8),
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .clickable { loadExplorerDir(subPath) }
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                            if (index < parts.lastIndex) {
                                                Text(" › ", color = Color(0xFF4B4265), fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }

                                // Bookmark current folder button
                                IconButton(
                                    onClick = {
                                        newBookmarkPath = currentExplorerPath
                                        newBookmarkName = File(currentExplorerPath).name.ifEmpty { currentExplorerPath }
                                        showAddBookmarkDialog = true
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.BookmarkAdd, contentDescription = "Bookmark", tint = Color(0xFFFF5277), modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Search in current directory
                        OutlinedTextField(
                            value = explorerSearchQuery,
                            onValueChange = { explorerSearchQuery = it },
                            placeholder = { Text("Filter items in folder...", color = Color(0xFF6B6280), fontSize = 12.sp) },
                            leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, tint = Color(0xFF9E95B8), modifier = Modifier.size(16.dp)) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF141020),
                                unfocusedContainerColor = Color(0xFF141020),
                                focusedBorderColor = Color(0xFFC084FC),
                                unfocusedBorderColor = Color(0xFF2E2744),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                        )

                        Spacer(Modifier.height(10.dp))

                        val filteredExplorerItems = explorerItems.filter {
                            explorerSearchQuery.isEmpty() || it.name.contains(explorerSearchQuery, ignoreCase = true)
                        }

                        if (isLoading) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color(0xFFFF5277))
                            }
                        } else if (filteredExplorerItems.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Text("Folder is empty or access denied.", color = Color(0xFF6B6280), fontSize = 13.sp)
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(filteredExplorerItems, key = { it.path }) { item ->
                                    if (item.isDir) {
                                        // Folder Item
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color(0xFF181424),
                                            border = BorderStroke(1.dp, Color(0xFF2E2744)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { loadExplorerDir(item.path) }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Default.Folder, contentDescription = null, tint = Color(0xFF70A5FF), modifier = Modifier.size(22.dp))
                                                    Text(
                                                        text = item.name,
                                                        fontSize = 13.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Color(0xFF6B6280), modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    } else {
                                        // Executable Game Item
                                        val isPinned = pinnedGamePaths.contains(item.path)
                                        val config = gameConfigs[item.path] ?: prefs.getGameConfig(item.path) ?: GameConfig(
                                            path = item.path,
                                            name = item.name,
                                            japaneseLocale = true,
                                            asAdmin = false,
                                            scaleFactor = uiScaleFactor
                                        )

                                        GameCardItem(
                                            item = item,
                                            config = config,
                                            isPinned = isPinned,
                                            onTogglePin = {
                                                prefs.togglePinGame(item.path)
                                                pinnedGamePaths = prefs.getPinnedGamePaths()
                                                // Also add to gameList if not present
                                                if (!gameList.any { it.path == item.path }) {
                                                    gameList = gameList + item
                                                }
                                            },
                                            onUpdateConfig = { updated ->
                                                gameConfigs[item.path] = updated
                                                prefs.saveGameConfig(updated)
                                            },
                                            onLaunch = { launchGame(item) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // TAB 2: RUNNING APPS TAB (Transfer PC Windows to Phone)
                    else if (selectedTab == 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "RUNNING PC APPS",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF70A5FF)
                                )
                                Text(
                                    "1-tap to cast any open PC window to phone.",
                                    fontSize = 11.5.sp,
                                    color = Color(0xFF9E95B8)
                                )
                            }
                            IconButton(onClick = { fetchRunningWindows() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF70A5FF))
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        if (isFetchingWindows) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color(0xFF70A5FF))
                            }
                        } else if (runningWindows.isEmpty()) {
                            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.DesktopWindows, contentDescription = null, tint = Color(0xFF3B3454), modifier = Modifier.size(52.dp))
                                    Spacer(Modifier.height(10.dp))
                                    Text("No open game windows detected on PC.", color = Color(0xFF9E95B8), fontSize = 13.sp)
                                    Spacer(Modifier.height(10.dp))
                                    Button(
                                        onClick = { fetchRunningWindows() },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF181424)),
                                        border = BorderStroke(1.dp, Color(0xFF2E2744)),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Text("Scan Windows", fontSize = 12.sp, color = Color.White)
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(runningWindows, key = { it.hwnd }) { win ->
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xFF120E1E),
                                        border = BorderStroke(1.dp, Color(0xFF2E2744)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = win.title,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(Modifier.height(5.dp))
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = Color(0x3370A5FF)
                                                    ) {
                                                        Text(
                                                            "${win.width}x${win.height}",
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                            fontSize = 10.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color(0xFF70A5FF)
                                                        )
                                                    }
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = Color(0x22FFFFFF)
                                                    ) {
                                                        Text(
                                                            "PID ${win.pid}",
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                            fontSize = 10.5.sp,
                                                            color = Color(0xFF9E95B8)
                                                        )
                                                    }
                                                }
                                            }

                                            Spacer(Modifier.width(12.dp))

                                            Button(
                                                onClick = { hookWindowToPhone(win) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5277)),
                                                shape = RoundedCornerShape(12.dp),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                            ) {
                                                Icon(Icons.Default.ScreenShare, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Cast", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // TAB 3: SETTINGS & STREAM CONFIG TAB
                    else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xFF120E1E),
                                    border = BorderStroke(1.dp, Color(0xFF2E2744)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text("PC HOST & PAIRING", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC084FC))
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = ipPort,
                                                onValueChange = { ipPort = it },
                                                label = { Text("PC IP:Port") },
                                                singleLine = true,
                                                shape = RoundedCornerShape(12.dp),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color(0xFFC084FC),
                                                    unfocusedBorderColor = Color(0xFF2E2744),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )
                                            Button(
                                                onClick = { checkStatus(ipPort) },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9333EA)),
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Connect")
                                            }
                                        }

                                        val recents = prefs.getRecentHosts()
                                        if (recents.isNotEmpty()) {
                                            Spacer(Modifier.height(12.dp))
                                            Text("RECENT HOSTS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6B6280))
                                            Spacer(Modifier.height(6.dp))
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                items(recents) { host ->
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = Color(0xFF231E34),
                                                        border = BorderStroke(1.dp, Color(0xFF3B3454)),
                                                        modifier = Modifier.clickable {
                                                            ipPort = host
                                                            checkStatus(host)
                                                        }
                                                    ) {
                                                        Text(
                                                            text = host,
                                                            fontSize = 11.sp,
                                                            color = Color.White,
                                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Readability DPI Scale
                            item {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xFF181424),
                                    border = BorderStroke(1.dp, Color(0xFF2E2744)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("DEFAULT DPI SCALE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF5277))
                                            Text("${uiScaleFactor}x Scale", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFC084FC))
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            listOf(1.25f to "1.25x", 1.5f to "1.5x", 1.75f to "1.75x", 2.0f to "2.0x").forEach { (scale, label) ->
                                                val isSel = uiScaleFactor == scale
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (isSel) Color(0xFF9333EA) else Color(0xFF231E34),
                                                    border = BorderStroke(1.dp, if (isSel) Color(0xFFC084FC) else Color(0xFF3B3454)),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clickable {
                                                            uiScaleFactor = scale
                                                            prefs.uiScaleFactor = scale
                                                        }
                                                ) {
                                                    Box(
                                                        modifier = Modifier.padding(vertical = 10.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Stream Target FPS
                            item {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = Color(0xFF181424),
                                    border = BorderStroke(1.dp, Color(0xFF2E2744)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("STREAM TARGET FRAMERATE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4ADE80))
                                            Text("$streamFpsTarget FPS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4ADE80))
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            listOf(15 to "15 FPS", 24 to "24 FPS", 30 to "30 FPS", 60 to "60 FPS").forEach { (fps, label) ->
                                                val isSel = streamFpsTarget == fps
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (isSel) Color(0xFF16A34A) else Color(0xFF231E34),
                                                    border = BorderStroke(1.dp, if (isSel) Color(0xFF4ADE80) else Color(0xFF3B3454)),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clickable {
                                                            streamFpsTarget = fps
                                                            prefs.streamFpsTarget = fps
                                                        }
                                                ) {
                                                    Box(
                                                        modifier = Modifier.padding(vertical = 10.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }


                        }
                    }
                }
            }

            // Bookmark Dialog
            if (showAddBookmarkDialog) {
                AlertDialog(
                    onDismissRequest = { showAddBookmarkDialog = false },
                    title = { Text("Bookmark Folder", fontWeight = FontWeight.Bold, color = Color.White) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = newBookmarkName,
                                onValueChange = { newBookmarkName = it },
                                label = { Text("Bookmark Name") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = newBookmarkPath,
                                onValueChange = { newBookmarkPath = it },
                                label = { Text("PC Folder Path") },
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (newBookmarkName.isNotBlank() && newBookmarkPath.isNotBlank()) {
                                    prefs.addBookmark(newBookmarkName, newBookmarkPath)
                                    bookmarks = prefs.getFolderBookmarks()
                                    showAddBookmarkDialog = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9333EA))
                        ) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddBookmarkDialog = false }) {
                            Text("Cancel", color = Color(0xFF9E95B8))
                        }
                    },
                    containerColor = Color(0xFF181424)
                )
            }

            // Exit Confirmation Dialog
            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { Text("Exit Game Session?", fontWeight = FontWeight.Bold, color = Color.White) },
                    text = {
                        Text("This will close '${activeSession?.name}' on your PC. Any unsaved Visual Novel progress might be lost!", color = Color(0xFF9E95B8))
                    },
                    confirmButton = {
                        Button(
                            onClick = { stopGame() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                        ) {
                            Text("Exit Game")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showExitDialog = false }) {
                            Text("Resume Playing", color = Color(0xFF9E95B8))
                        }
                    },
                    containerColor = Color(0xFF181424)
                )
            }
        }
    }
}

@Composable
fun GameCardItem(
    item: ExecutableItem,
    config: GameConfig,
    isPinned: Boolean,
    onTogglePin: () -> Unit,
    onUpdateConfig: (GameConfig) -> Unit,
    onLaunch: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF181424),
        border = BorderStroke(1.dp, if (isPinned) Color(0x88FBBF24) else Color(0xFF2E2744)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = if (isPinned) Icons.Default.Star else Icons.Default.StarOutline,
                            contentDescription = "Pin",
                            tint = if (isPinned) Color(0xFFFBBF24) else Color(0xFF6B6280)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = item.path,
                            fontSize = 10.sp,
                            color = Color(0xFF6B6280),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = onLaunch,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5277)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Launch", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Game Options Row (JP Locale & Admin)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        onUpdateConfig(config.copy(japaneseLocale = !config.japaneseLocale))
                    }
                ) {
                    Checkbox(
                        checked = config.japaneseLocale,
                        onCheckedChange = { onUpdateConfig(config.copy(japaneseLocale = it)) },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFF5277))
                    )
                    Text("🇯🇵 JP Locale", fontSize = 11.5.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        onUpdateConfig(config.copy(asAdmin = !config.asAdmin))
                    }
                ) {
                    Checkbox(
                        checked = config.asAdmin,
                        onCheckedChange = { onUpdateConfig(config.copy(asAdmin = it)) },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFEF4444))
                    )
                    Text("🛡️ Admin", fontSize = 11.5.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun VnActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF231E34),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun VnQuickButton(
    label: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xCC2A2438),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}
