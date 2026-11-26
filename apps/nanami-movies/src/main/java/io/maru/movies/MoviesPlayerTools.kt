package io.maru.movies

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.images.WebImage
import io.maru.manime.extensions.StremioStreamResult
import io.maru.manime.player.SnippetExportState
import io.maru.manime.player.SnippetExporter
import io.maru.manime.player.SnippetRange
import io.maru.manime.player.applyDurationPreset
import io.maru.manime.player.defaultSnippetRange
import io.maru.manime.player.normalizeSnippetRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class MoviesCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

internal data class MovieRemoteTransport(val seek: (Long) -> Unit, val toggle: () -> Unit)

@OptIn(UnstableApi::class)
@Composable
internal fun MoviesPlayerTools(
    title: MovieTitle,
    episode: MovieEpisode?,
    activeSource: StremioStreamResult?,
    offlineFile: File?,
    mediaUri: Uri?,
    dataSource: DataSource.Factory?,
    position: Long,
    duration: Long,
    onRemotePlayback: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onRemoteEnded: (Long) -> Unit = {},
    onRemoteProgress: ((Long, Long, Boolean) -> Unit)? = null,
    onRemoteTransport: ((MovieRemoteTransport?) -> Unit)? = null,
    onClipVisibilityChanged: ((Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    var showCast by remember { mutableStateOf(false) }
    var showClip by remember { mutableStateOf(false) }
    val device = remember(context) { MoviesCastDevice(context.applicationContext) }
    val opened by rememberUpdatedState(onRemotePlayback)
    val ended by rememberUpdatedState(onRemoteEnded)
    val progress by rememberUpdatedState(onRemoteProgress)
    val transport by rememberUpdatedState(onRemoteTransport)
    val clipVisibility by rememberUpdatedState(onClipVisibilityChanged)
    val startPosition by rememberUpdatedState(position)
    var requested by remember { mutableStateOf(false) }
    var castAttempt by remember { mutableIntStateOf(0) }
    var sent by remember { mutableStateOf(false) }
    var remotePosition by remember { mutableLongStateOf(0L) }
    var castMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(device) {
        device.attach()
        onDispose { device.detach(endSession = requested) }
    }
    DisposableEffect(device, showCast) {
        if (showCast) device.scan()
        onDispose { device.stopScan() }
    }
    val session = device.session
    LaunchedEffect(session, mediaUri, dataSource, requested, castAttempt) {
        val client = session?.remoteMediaClient
        if (client == null || !requested) {
            if (sent) {
                sent = false
                ended(remotePosition)
            }
            return@LaunchedEffect
        }
        if (mediaUri == null || dataSource == null) {
            castMessage = "Waiting for the selected source…"
            return@LaunchedEffect
        }
        var relay: MoviesMediaRelay? = null
        try {
            castMessage = "Loading on ${session.castDevice?.friendlyName ?: "your TV"}…"
            val direct = mediaUri.scheme == "https" && activeSource?.headers.orEmpty().isEmpty() && offlineFile == null
            val url = if (direct) mediaUri.toString() else MoviesMediaRelay.start(context, dataSource, mediaUri, lan = true)
                .also { relay = it }.url
            val metadata = MediaMetadata(if (episode == null) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_TV_SHOW)
            metadata.putString(MediaMetadata.KEY_TITLE, episode?.title ?: title.title)
            if (episode != null) {
                metadata.putString(MediaMetadata.KEY_SERIES_TITLE, title.title)
                metadata.putInt(MediaMetadata.KEY_SEASON_NUMBER, episode.season)
                metadata.putInt(MediaMetadata.KEY_EPISODE_NUMBER, episode.episode)
            }
            title.poster?.let { metadata.addImage(WebImage(Uri.parse(it))) }
            val mime = relay?.mime ?: MoviesMediaRelay.mediaMime(mediaUri.lastPathSegment.orEmpty().substringAfterLast('.'))
            val info = MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setContentType(mime).setMetadata(metadata).build()
            val result = CompletableDeferred<Boolean>()
            client.load(
                MediaLoadRequestData.Builder()
                    .setMediaInfo(info)
                    .setAutoplay(true)
                    .setCurrentTime(startPosition.coerceAtLeast(0))
                    .build()
            ).setResultCallback { result.complete(it.status.isSuccess) }
            check(withTimeoutOrNull(25_000) { result.await() } == true) { "The receiver couldn't play this source. Try another source." }
            sent = true
            castMessage = null
            transport?.invoke(
                MovieRemoteTransport(
                    seek = { client.seek(it.coerceAtLeast(0)) },
                    toggle = { if (client.isPlaying) client.pause() else client.play() },
                )
            )
            opened()
            while (true) {
                remotePosition = client.approximateStreamPosition.coerceAtLeast(0)
                val total = client.streamDuration
                if (total > 0) progress?.invoke(remotePosition, total, client.isPlaying)
                delay(1_000)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            castMessage = error.message ?: "Casting could not start."
            if (sent) {
                sent = false
                ended(remotePosition)
            }
        } finally {
            transport?.invoke(null)
            relay?.close()
        }
    }

    if (visible) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                enabled = mediaUri != null && dataSource != null && duration > 0 && !sent,
                onClick = { showClip = true },
            ) {
                Icon(
                    Icons.Default.ContentCut,
                    contentDescription = "Clip Studio",
                    tint = Color.White.copy(alpha = if (duration > 0) 1f else 0.4f),
                )
            }
            IconButton(onClick = { showCast = true }) {
                Icon(
                    imageVector = if (sent) Icons.Default.CastConnected else Icons.Default.Cast,
                    contentDescription = "Cast",
                    tint = if (sent) NetflixRed else Color.White,
                )
            }
        }
    }

    if (showCast) {
        AlertDialog(
            onDismissRequest = { showCast = false },
            containerColor = NetflixSurface,
            title = { Text("Cast to a TV", color = Color.White, fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val connected = device.session?.remoteMediaClient
                    if (connected != null) {
                        Text(device.session?.castDevice?.friendlyName ?: "Connected TV", color = NetflixMatchGreen, fontWeight = FontWeight.Bold)
                        castMessage?.let { Text(it, color = NetflixTextMuted, fontSize = 12.5.sp) }
                        if (sent) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NetflixChip("-10s", false) { connected.seek((remotePosition - 10_000).coerceAtLeast(0)) }
                                NetflixChip("Play / Pause", true) { if (connected.isPlaying) connected.pause() else connected.play() }
                                NetflixChip("+10s", false) { connected.seek(remotePosition + 10_000) }
                            }
                        } else {
                            Button(
                                onClick = { requested = true; castAttempt++ },
                                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed, contentColor = Color.White),
                            ) { Text("Cast This Title", fontWeight = FontWeight.Bold) }
                        }
                        TextButton(onClick = { requested = false; device.disconnect() }) {
                            Text("Disconnect", color = NetflixRed)
                        }
                    } else if (device.routes.isEmpty()) {
                        Text("Searching for Chromecast & Google Cast receivers on your Wi-Fi…", color = NetflixTextMuted, fontSize = 13.sp)
                    } else {
                        LazyColumn(Modifier.heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(device.routes, key = { it.id }) { route ->
                                Surface(
                                    onClick = { requested = true; castMessage = null; route.select() },
                                    shape = RoundedCornerShape(6.dp),
                                    color = NetflixCardBg,
                                    border = BorderStroke(1.dp, NetflixBorder),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = route.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCast = false }) { Text("Done", color = Color.White, fontWeight = FontWeight.Bold) }
            },
        )
    }

    if (showClip && mediaUri != null && dataSource != null && duration > 0) {
        DisposableEffect(Unit) {
            clipVisibility?.invoke(true)
            onDispose { clipVisibility?.invoke(false) }
        }
        MoviesClipStudioOverlay(
            title = title,
            episode = episode,
            source = activeSource,
            uri = mediaUri,
            factory = dataSource,
            position = position,
            duration = duration,
            onClose = { showClip = false },
        )
    }
}

private class MoviesCastDevice(context: Context) {
    private val manager = runCatching { CastContext.getSharedInstance(context).sessionManager }.getOrNull()
    private val router = MediaRouter.getInstance(context)
    private val selector = MediaRouteSelector.Builder().addControlCategory(
        CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID),
    ).build()
    var session by mutableStateOf<CastSession?>(null)
        private set
    var routes by mutableStateOf<List<MediaRouter.RouteInfo>>(emptyList())
        private set

    private val routesListener = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }
    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(value: CastSession) = Unit
        override fun onSessionStarted(value: CastSession, id: String) { session = value }
        override fun onSessionStartFailed(value: CastSession, error: Int) { session = null }
        override fun onSessionEnding(value: CastSession) = Unit
        override fun onSessionEnded(value: CastSession, error: Int) { session = null }
        override fun onSessionResuming(value: CastSession, id: String) = Unit
        override fun onSessionResumed(value: CastSession, suspended: Boolean) { session = value }
        override fun onSessionResumeFailed(value: CastSession, error: Int) { session = null }
        override fun onSessionSuspended(value: CastSession, reason: Int) { session = null }
    }

    private fun refresh() {
        routes = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
    }

    fun attach() {
        manager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        session = manager?.currentCastSession?.takeIf { it.isConnected }
    }

    fun scan() {
        router.addCallback(selector, routesListener, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
    }

    fun stopScan() = router.removeCallback(routesListener)

    fun disconnect() { manager?.endCurrentSession(true) }

    fun detach(endSession: Boolean) {
        stopScan()
        manager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        if (endSession) disconnect()
    }
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MoviesClipStudioOverlay(
    title: MovieTitle,
    episode: MovieEpisode?,
    source: StremioStreamResult?,
    uri: Uri,
    factory: DataSource.Factory,
    position: Long,
    duration: Long,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exporter = remember(context) { SnippetExporter(context.applicationContext) }
    var range by remember(uri, duration) { mutableStateOf(defaultSnippetRange(position, duration)) }
    var preview by remember(uri) { mutableStateOf<SnippetRange?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var result by remember { mutableStateOf<SnippetExportState>(SnippetExportState.Idle) }
    val subtitle = source?.subtitles?.firstOrNull { Uri.parse(it.url).scheme == "https" }
    var burn by remember(subtitle) { mutableStateOf(subtitle != null) }

    fun save() {
        if (job?.isActive == true) return
        preview = null
        val selectedRange = range
        val includeSubtitles = burn
        job = scope.launch {
            var relay: MoviesMediaRelay? = null
            try {
                result = SnippetExportState.Exporting(null, "Preparing MP4 clip…")
                val exportUri = if (uri.scheme == "nanami-torrent") {
                    MoviesMediaRelay.start(context, factory, uri, lan = false).also { relay = it }.let { Uri.parse(it.url) }
                } else uri
                val name = if (episode == null) title.title else "${title.title} S${episode.season}"
                result = exporter.exportToGallery(
                    videoUri = exportUri,
                    subtitleUri = null,
                    headers = source?.headers.orEmpty(),
                    animeTitle = name,
                    episodeNum = episode?.episode ?: 0,
                    range = selectedRange,
                    burnSubtitles = includeSubtitles,
                    externalSubtitleUrl = subtitle?.url,
                    onProgress = { result = it },
                )
            } catch (error: CancellationException) {
                result = SnippetExportState.Idle
                throw error
            } catch (error: Exception) {
                result = SnippetExportState.Error(error.message ?: "Couldn't export the clip.")
            } finally {
                relay?.close()
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) save() else result = SnippetExportState.Error("Allow storage access to save the clip to Gallery.")
    }
    val busy = result is SnippetExportState.Exporting

    Dialog(
        onDismissRequest = { if (!busy) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = NetflixSurface,
                contentColor = Color.White,
                border = BorderStroke(1.dp, NetflixBorder),
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Clip Studio", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text(
                                text = "${clipTime(range.startMs)} to ${clipTime(range.endMs)} • ${(range.durationMs / 1000.0)}s",
                                fontSize = 12.sp,
                                color = NetflixMatchGreen,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        IconButton(enabled = !busy, onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    if (duration > 1_000L) {
                        RangeSlider(
                            value = range.startMs.toFloat()..range.endMs.toFloat(),
                            onValueChange = {
                                range = normalizeSnippetRange(it.start.toLong(), it.endInclusive.toLong(), duration, maxDurationMs = 60_000L)
                            },
                            valueRange = 0f..duration.toFloat(),
                            enabled = !busy,
                            colors = SliderDefaults.colors(
                                thumbColor = NetflixRed,
                                activeTrackColor = NetflixRed,
                                inactiveTrackColor = NetflixBorder,
                            ),
                        )
                    }

                    // Duration Presets + Fine Nudge Controls (matching Nanami Anime's Clip Studio!)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(5L, 10L, 15L, 30L, 60L).forEach { seconds ->
                            val selected = kotlin.math.abs(range.durationMs - seconds * 1_000L) < 400L
                            NetflixChip(
                                label = "${seconds}s",
                                selected = selected,
                                onClick = { if (!busy) range = applyDurationPreset(range, seconds * 1_000L, duration) },
                            )
                        }
                        NetflixChip("-0.5s Start", false) {
                            if (!busy) range = normalizeSnippetRange(range.startMs - 500L, range.endMs, duration, maxDurationMs = 60_000L)
                        }
                        NetflixChip("+0.5s End", false) {
                            if (!busy) range = normalizeSnippetRange(range.startMs, range.endMs + 500L, duration, maxDurationMs = 60_000L)
                        }
                    }

                    if (subtitle != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = burn,
                                onCheckedChange = { burn = it },
                                enabled = !busy,
                                colors = CheckboxDefaults.colors(checkedColor = NetflixRed),
                            )
                            Text("Burn source subtitles into MP4", fontSize = 13.sp, color = Color.White)
                        }
                    }

                    preview?.let { MoviesClipPreview(uri, factory, it) }

                    when (val current = result) {
                        is SnippetExportState.Exporting -> {
                            Text(current.statusLabel, color = NetflixRed, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            if (current.progressPercent == null) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NetflixRed, trackColor = NetflixBorder)
                            } else {
                                LinearProgressIndicator(
                                    progress = { current.progressPercent / 100f },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = NetflixRed,
                                    trackColor = NetflixBorder,
                                )
                            }
                        }
                        is SnippetExportState.Saved -> Text("Saved ${current.displayName} to Gallery!", color = NetflixMatchGreen, fontWeight = FontWeight.Bold)
                        is SnippetExportState.Error -> Text(current.message, color = MaterialTheme.colorScheme.error, fontSize = 12.5.sp)
                        SnippetExportState.Idle -> Unit
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { preview = if (preview == null) range else null },
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, NetflixBorder),
                        ) {
                            Text(if (preview == null) "Preview Clip" else "Hide Preview", color = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (busy) {
                            OutlinedButton(
                                onClick = { job?.cancel() },
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, NetflixRed),
                            ) {
                                Text("Cancel Export", color = NetflixRed, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        != PackageManager.PERMISSION_GRANTED
                                    ) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    else save()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed, contentColor = Color.White),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text("Export MP4", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MoviesClipPreview(uri: Uri, factory: DataSource.Factory, range: SnippetRange) {
    val context = LocalContext.current
    val player = remember(uri, factory, range) {
        val media = MediaItem.Builder().setUri(uri).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(range.startMs).setEndPositionMs(range.endMs).build(),
        ).build()
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
            setMediaItem(media)
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(6.dp)),
    )
}

private fun clipTime(ms: Long): String {
    val seconds = ms / 1_000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
