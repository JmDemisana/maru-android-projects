package io.maru.movies

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
) {
    val context = LocalContext.current
    var showCast by remember { mutableStateOf(false) }
    var showClip by remember { mutableStateOf(false) }
    val device = remember(context) { MoviesCastDevice(context.applicationContext) }
    val opened by rememberUpdatedState(onRemotePlayback)
    val ended by rememberUpdatedState(onRemoteEnded)
    val progress by rememberUpdatedState(onRemoteProgress)
    val transport by rememberUpdatedState(onRemoteTransport)
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
            client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
                .setCurrentTime(startPosition.coerceAtLeast(0)).build()).setResultCallback { result.complete(it.status.isSuccess) }
            check(withTimeoutOrNull(25_000) { result.await() } == true) { "The receiver couldn't play this source. Try another source." }
            sent = true
            castMessage = null
            transport?.invoke(MovieRemoteTransport(
                seek = { client.seek(it.coerceAtLeast(0)) },
                toggle = { if (client.isPlaying) client.pause() else client.play() },
            ))
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

    if (visible) Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { showCast = true }) {
            Icon(if (sent) Icons.Default.CastConnected else Icons.Default.Cast, "Cast", tint = Color.White)
        }
        IconButton(enabled = mediaUri != null && dataSource != null && duration > 0 && !sent, onClick = { showClip = true }) {
            Icon(Icons.Default.ContentCut, "Clip Studio", tint = Color.White.copy(alpha = if (duration > 0) 1f else 0.4f))
        }
    }
    if (showCast) AlertDialog(
        onDismissRequest = { showCast = false }, title = { Text("Cast to a TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val connected = device.session?.remoteMediaClient
                if (connected != null) {
                    Text(device.session?.castDevice?.friendlyName ?: "Connected")
                    castMessage?.let { Text(it) }
                    if (sent) Row {
                        TextButton(onClick = { connected.seek((remotePosition - 10_000).coerceAtLeast(0)) }) { Text("−10s") }
                        TextButton(onClick = { if (connected.isPlaying) connected.pause() else connected.play() }) { Text("Play / Pause") }
                        TextButton(onClick = { connected.seek(remotePosition + 10_000) }) { Text("+10s") }
                    } else TextButton(onClick = { requested = true; castAttempt++ }) { Text("Cast this title") }
                    TextButton(onClick = { requested = false; device.disconnect() }) { Text("Disconnect") }
                } else if (device.routes.isEmpty()) {
                    Text("Connect your phone and TV to the same Wi-Fi.")
                } else LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(device.routes, key = { it.id }) { route ->
                        TextButton(onClick = { requested = true; castMessage = null; route.select() }) { Text(route.name) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showCast = false }) { Text("Done") } },
    )
    if (showClip && mediaUri != null && dataSource != null && duration > 0) MoviesClipEditor(
        title, episode, activeSource, mediaUri, dataSource, position, duration, onClose = { showClip = false },
    )
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
@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MoviesClipEditor(
    title: MovieTitle, episode: MovieEpisode?, source: StremioStreamResult?, uri: Uri, factory: DataSource.Factory,
    position: Long, duration: Long, onClose: () -> Unit,
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
                result = SnippetExportState.Exporting(null, "Preparing clip…")
                val exportUri = if (uri.scheme == "nanami-torrent") {
                    MoviesMediaRelay.start(context, factory, uri, lan = false).also { relay = it }.let { Uri.parse(it.url) }
                } else uri
                val name = if (episode == null) title.title else "${title.title} S${episode.season}"
                result = exporter.exportToGallery(exportUri, null, source?.headers.orEmpty(), name, episode?.episode ?: 0,
                    selectedRange, burnSubtitles = includeSubtitles, externalSubtitleUrl = subtitle?.url,
                    onProgress = { result = it })
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
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() }, title = { Text("Clip Studio") },
        text = {
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("${clipTime(range.startMs)} – ${clipTime(range.endMs)} · ${clipTime(range.durationMs)}")
                if (duration > 1_000L) RangeSlider(
                    value = range.startMs.toFloat()..range.endMs.toFloat(),
                    onValueChange = { range = normalizeSnippetRange(it.start.toLong(), it.endInclusive.toLong(), duration, maxDurationMs = 60_000L) },
                    valueRange = 0f..duration.toFloat(), enabled = !busy,
                )
                FlowRow {
                    listOf(5L, 10L, 15L, 30L, 60L).forEach { seconds ->
                        TextButton(enabled = !busy, onClick = { range = applyDurationPreset(range, seconds * 1_000, duration) }) {
                            Text("${seconds}s")
                        }
                    }
                }
                if (subtitle != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = burn, onCheckedChange = { burn = it }, enabled = !busy)
                    Text("Include source subtitles")
                }
                preview?.let { MoviesClipPreview(uri, factory, it) }
                when (val current = result) {
                    is SnippetExportState.Exporting -> {
                        Text(current.statusLabel)
                        if (current.progressPercent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { current.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    is SnippetExportState.Saved -> Text("Saved ${current.displayName} to Gallery.")
                    is SnippetExportState.Error -> Text(current.message)
                    SnippetExportState.Idle -> Unit
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(enabled = !busy, onClick = { preview = if (preview == null) range else null }) {
                    Text(if (preview == null) "Preview" else "Close preview")
                }
                TextButton(onClick = { if (busy) job?.cancel() else onClose() }) { Text(if (busy) "Cancel export" else "Close") }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                ) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else save()
            }) { Text("Save MP4") }
        },
    )
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
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth().height(140.dp))
}

private fun clipTime(ms: Long): String {
    val seconds = ms / 1_000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(seconds / 60, seconds % 60)
}
