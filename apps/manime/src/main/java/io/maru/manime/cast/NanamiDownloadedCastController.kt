package io.maru.manime.cast

import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import coil.compose.AsyncImage
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import io.maru.manime.torrent.parseByteRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NanamiCastUiTheme(
    val appTitle: String,
    val accentColor: Color,
    val accentOnColor: Color,
    val surfaceColor: Color,
    val cardColor: Color,
    val borderColor: Color,
    val textMutedColor: Color,
    val activeStatusColor: Color,
)

internal data class DownloadedCastMedia(
    val key: String,
    val title: String,
    val seriesTitle: String? = null,
    val subtitle: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val artworkUrl: String? = null,
    val file: File,
    val startPositionMs: Long = 0L,
    val isMovie: Boolean = false,
)

internal data class CastRouteItem(
    val id: String,
    val name: String,
    val description: String?,
    val isSelected: Boolean,
    val isConnecting: Boolean,
)

internal class NanamiDownloadedCastController(
    context: Context,
    private val appTitle: String,
    private val onProgress: (DownloadedCastMedia, Long, Long) -> Unit = { _, _, _ -> },
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val castManager = runCatching { CastContext.getSharedInstance(appContext).sessionManager }.getOrNull()
    private val mediaRouter = runCatching { MediaRouter.getInstance(appContext) }.getOrNull()
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(
            CastMediaControlIntent.categoryForCast(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
            ),
        )
        .build()

    private val stagingDir: File get() = File(appContext.cacheDir, "cast_staging")

    var routes by mutableStateOf<List<CastRouteItem>>(emptyList())
        private set
    var connectedSession by mutableStateOf<CastSession?>(null)
        private set
    var connectedDeviceName by mutableStateOf<String?>(null)
        private set
    var isConnecting by mutableStateOf(false)
        private set

    var activeMedia by mutableStateOf<DownloadedCastMedia?>(null)
        private set
    var pendingMedia by mutableStateOf<DownloadedCastMedia?>(null)
        private set
    var isRemoteSheetOpen by mutableStateOf(false)

    var isPreparing by mutableStateOf(false)
        private set
    var prepareStatus by mutableStateOf<String?>(null)
        private set
    var prepareProgressPercent by mutableStateOf<Int?>(null)
        private set
    var castError by mutableStateOf<String?>(null)
        private set

    var isCastingActive by mutableStateOf(false)
        private set
    var isRemotePlaying by mutableStateOf(false)
        private set
    var positionMs by mutableLongStateOf(0L)
        private set
    var durationMs by mutableLongStateOf(0L)
        private set
    var tvVolume by mutableDoubleStateOf(1.0)
        private set
    var isTvMuted by mutableStateOf(false)
        private set
    var subtitleTracks by mutableStateOf<List<CastSubtitleSidecar>>(emptyList())
        private set
    var activeSubtitleTrackId by mutableStateOf<Long?>(null)
        private set

    private var httpServer: DownloadedCastHttpServer? = null
    private var loadJob: Job? = null
    private var pollerJob: Job? = null
    private var activeTransformer: Transformer? = null
    private var attachedCastListenerSession: CastSession? = null

    private val castVolumeListener = object : Cast.Listener() {
        override fun onVolumeChanged() {
            syncVolumeFromSession()
        }
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
        override fun onRouteUnselected(router: MediaRouter, route: MediaRouter.RouteInfo, reason: Int) = refreshRoutes()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            isConnecting = true
            connectedDeviceName = session.castDevice?.friendlyName
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            attachSession(session)
            refreshRoutes()
            pendingMedia?.let { startCasting(it) }
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            isConnecting = false
            connectedSession = null
            connectedDeviceName = null
            castError = "Couldn't connect to Chromecast (code $error)."
            cleanupCastResources()
            refreshRoutes()
        }

        override fun onSessionEnding(session: CastSession) = Unit

        override fun onSessionEnded(session: CastSession, error: Int) {
            detachVolumeListener()
            isConnecting = false
            connectedSession = null
            connectedDeviceName = null
            cleanupCastResources()
            refreshRoutes()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            isConnecting = true
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            attachSession(session)
            refreshRoutes()
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            isConnecting = false
            connectedSession = null
            connectedDeviceName = null
            cleanupCastResources()
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            isConnecting = true
        }
    }

    init {
        cleanStagingDirectory()
    }

    fun attach() {
        NanamiCastKeepaliveService.onStopRequested = {
            scope.launch { disconnect() }
        }
        runCatching {
            mediaRouter?.addCallback(
                selector,
                routerCallback,
                MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN,
            )
            castManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
            val existing = castManager?.currentCastSession?.takeIf { it.isConnected }
            if (existing != null) {
                attachSession(existing)
            }
            refreshRoutes()
        }
    }

    fun detach() {
        if (NanamiCastKeepaliveService.onStopRequested != null) {
            NanamiCastKeepaliveService.onStopRequested = null
        }
        runCatching {
            mediaRouter?.removeCallback(routerCallback)
            castManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        }
    }

    fun requestCast(media: DownloadedCastMedia) {
        castError = null
        pendingMedia = media
        isRemoteSheetOpen = true
        val existing = connectedSession?.takeIf { it.isConnected }
        if (existing != null) {
            startCasting(media)
        } else {
            refreshRoutes()
        }
    }

    fun selectRoute(routeId: String) {
        val router = mediaRouter ?: return
        val target = router.routes.firstOrNull { it.id == routeId } ?: return
        castError = null
        isConnecting = true
        connectedDeviceName = target.name
        runCatching { router.selectRoute(target) }
    }

    fun refreshRoutes() {
        val router = mediaRouter ?: return
        routes = router.routes
            .filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .map { route ->
                CastRouteItem(
                    id = route.id,
                    name = route.name,
                    description = route.description?.takeIf { it.isNotBlank() },
                    isSelected = route.isSelected,
                    isConnecting = route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTING,
                )
            }
            .distinctBy { it.id }
    }

    fun togglePlayPause() {
        val client = connectedSession?.remoteMediaClient ?: return
        runCatching {
            if (client.isPlaying) client.pause() else client.play()
        }
    }

    fun seekTo(targetMs: Long) {
        val client = connectedSession?.remoteMediaClient ?: return
        val clamped = targetMs.coerceIn(0L, durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        positionMs = clamped
        runCatching { client.seek(clamped) }
    }

    fun seekBy(deltaMs: Long) {
        seekTo(positionMs + deltaMs)
    }

    fun setVolume(volume: Double) {
        val session = connectedSession ?: return
        val clamped = volume.coerceIn(0.0, 1.0)
        tvVolume = clamped
        runCatching {
            session.volume = clamped
            if (clamped > 0.0 && session.isMute) {
                session.isMute = false
                isTvMuted = false
            }
        }
    }

    fun adjustVolume(delta: Double) {
        setVolume(tvVolume + delta)
    }

    fun toggleMute() {
        val session = connectedSession ?: return
        val next = !isTvMuted
        isTvMuted = next
        runCatching { session.isMute = next }
    }

    fun selectSubtitleTrack(trackId: Long?) {
        val client = connectedSession?.remoteMediaClient ?: return
        activeSubtitleTrackId = trackId
        val activeIds = if (trackId == null) longArrayOf() else longArrayOf(trackId)
        runCatching { client.setActiveMediaTracks(activeIds) }
    }

    fun disconnect() {
        loadJob?.cancel()
        loadJob = null
        runCatching { activeTransformer?.cancel() }
        activeTransformer = null
        runCatching {
            castManager?.endCurrentSession(true)
            mediaRouter?.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
        }
        detachVolumeListener()
        connectedSession = null
        connectedDeviceName = null
        isConnecting = false
        pendingMedia = null
        isRemoteSheetOpen = false
        cleanupCastResources()
        refreshRoutes()
    }

    private fun attachSession(session: CastSession) {
        detachVolumeListener()
        isConnecting = false
        connectedSession = session
        connectedDeviceName = session.castDevice?.friendlyName ?: "Chromecast"
        runCatching {
            session.addCastListener(castVolumeListener)
            attachedCastListenerSession = session
        }
        syncVolumeFromSession()
    }

    private fun detachVolumeListener() {
        attachedCastListenerSession?.let { old ->
            runCatching { old.removeCastListener(castVolumeListener) }
        }
        attachedCastListenerSession = null
    }

    private fun syncVolumeFromSession() {
        val session = connectedSession ?: return
        runCatching {
            tvVolume = session.volume.coerceIn(0.0, 1.0)
            isTvMuted = session.isMute
        }
    }

    @OptIn(UnstableApi::class)
    private fun startCasting(media: DownloadedCastMedia) {
        val session = connectedSession?.takeIf { it.isConnected } ?: return
        val client = session.remoteMediaClient ?: return

        loadJob?.cancel()
        pollerJob?.cancel()
        httpServer?.close()
        httpServer = null
        runCatching { activeTransformer?.cancel() }
        activeTransformer = null

        loadJob = scope.launch {
            isPreparing = true
            isCastingActive = false
            castError = null
            prepareProgressPercent = null
            prepareStatus = "Scanning subtitles…"
            cleanStagingDirectory()

            try {
                if (!media.file.isFile) error("Downloaded file is missing from vault.")

                val extractedSubs = withContext(Dispatchers.IO) {
                    NanamiCastSubtitleHelper.collectAllSubtitlesForCast(media.file)
                }
                subtitleTracks = extractedSubs
                val defaultSub = extractedSubs.firstOrNull {
                    it.language == "en" || it.language == "eng" || it.label.contains("English", ignoreCase = true)
                } ?: extractedSubs.firstOrNull()
                activeSubtitleTrackId = defaultSub?.id

                val containerInfo = withContext(Dispatchers.IO) { inspectContainer(media.file) }
                var fileToServe = media.file
                var mimeToServe = containerInfo.mime

                if (containerInfo.needsRemux) {
                    prepareStatus = "Preparing Cast-friendly MP4…"
                    val remuxed = File(stagingDir.apply { mkdirs() }, "cast_ready.mp4")
                    val remuxOk = try {
                        remuxToCastMp4(
                            sourceFile = media.file,
                            sourceMime = containerInfo.mime,
                            outputFile = remuxed,
                            onProgress = { pct ->
                                prepareProgressPercent = pct
                                prepareStatus = if (pct != null) {
                                    "Preparing Cast-friendly MP4 ($pct%)…"
                                } else {
                                    "Preparing Cast-friendly MP4…"
                                }
                            },
                        )
                        remuxed.isFile && remuxed.length() > 0L
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("NanamiDownloadedCast", "MP4 fast remux failed, falling back to direct MKV stream", e)
                        runCatching { remuxed.delete() }
                        false
                    }
                    if (remuxOk) {
                        fileToServe = remuxed
                        mimeToServe = "video/mp4"
                    }
                }

                prepareStatus = "Starting local Wi-Fi server…"
                prepareProgressPercent = null
                val server = DownloadedCastHttpServer.start(
                    context = appContext,
                    videoFile = fileToServe,
                    mimeType = mimeToServe,
                    subtitles = extractedSubs,
                )
                httpServer = server

                val metadata = MediaMetadata(
                    if (media.isMovie) MediaMetadata.MEDIA_TYPE_MOVIE else MediaMetadata.MEDIA_TYPE_TV_SHOW,
                ).apply {
                    putString(MediaMetadata.KEY_TITLE, media.title)
                    media.seriesTitle?.let { putString(MediaMetadata.KEY_SERIES_TITLE, it) }
                    media.seasonNumber?.let { putInt(MediaMetadata.KEY_SEASON_NUMBER, it) }
                    media.episodeNumber?.let { putInt(MediaMetadata.KEY_EPISODE_NUMBER, it) }
                    media.artworkUrl?.takeIf { it.isNotBlank() }?.let { url ->
                        runCatching { addImage(WebImage(Uri.parse(url))) }
                    }
                }

                val castTracks = extractedSubs.map { sidecar ->
                    MediaTrack.Builder(sidecar.id, MediaTrack.TYPE_TEXT)
                        .setName(sidecar.label)
                        .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
                        .setContentId(server.subtitleUrl(sidecar.id))
                        .setContentType("text/vtt")
                        .setLanguage(sidecar.language)
                        .build()
                }

                val infoBuilder = MediaInfo.Builder(server.videoUrl)
                    .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                    .setContentType(mimeToServe)
                    .setMetadata(metadata)
                if (castTracks.isNotEmpty()) {
                    infoBuilder.setMediaTracks(castTracks)
                    infoBuilder.setTextTrackStyle(
                        TextTrackStyle().apply {
                            foregroundColor = AndroidColor.WHITE
                            backgroundColor = AndroidColor.argb(145, 0, 0, 0)
                            edgeType = TextTrackStyle.EDGE_TYPE_DROP_SHADOW
                            edgeColor = AndroidColor.BLACK
                            fontScale = 1.05f
                        },
                    )
                }

                val reqBuilder = MediaLoadRequestData.Builder()
                    .setMediaInfo(infoBuilder.build())
                    .setAutoplay(true)
                    .setCurrentTime(media.startPositionMs.coerceAtLeast(0L))
                if (defaultSub != null) {
                    reqBuilder.setActiveTrackIds(longArrayOf(defaultSub.id))
                }

                val loaded = CompletableDeferred<Boolean>()
                client.load(reqBuilder.build()).setResultCallback { result ->
                    loaded.complete(result.status.isSuccess)
                }
                check(withTimeoutOrNull(25_000L) { loaded.await() } == true) {
                    "Your TV couldn't play this video format. Try opening the app on Android TV instead."
                }

                activeMedia = media
                pendingMedia = null
                isPreparing = false
                prepareStatus = null
                prepareProgressPercent = null
                isCastingActive = true
                isRemotePlaying = true
                positionMs = media.startPositionMs
                durationMs = runCatching { client.streamDuration.coerceAtLeast(0L) }.getOrDefault(0L)
                syncVolumeFromSession()

                NanamiCastKeepaliveService.start(
                    context = appContext,
                    appTitle = appTitle,
                    mediaTitle = media.title,
                    deviceName = connectedDeviceName ?: "Chromecast",
                )

                startRemotePoller(media, client)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isPreparing = false
                prepareProgressPercent = null
                prepareStatus = null
                castError = e.message ?: "Casting failed."
                cleanupCastResources(keepError = true)
            }
        }
    }

    private fun startRemotePoller(media: DownloadedCastMedia, client: RemoteMediaClient) {
        pollerJob?.cancel()
        pollerJob = scope.launch {
            var lastReportedAt = 0L
            while (true) {
                val active = connectedSession
                if (active == null || !active.isConnected) {
                    cleanupCastResources()
                    break
                }
                val pos = runCatching { client.approximateStreamPosition.coerceAtLeast(0L) }.getOrDefault(positionMs)
                val dur = runCatching { client.streamDuration.coerceAtLeast(0L) }.getOrDefault(durationMs)
                val playing = runCatching { client.isPlaying }.getOrDefault(false)
                positionMs = pos
                if (dur > 0L) durationMs = dur
                isRemotePlaying = playing
                syncVolumeFromSession()

                val now = System.currentTimeMillis()
                if (dur > 0L && now - lastReportedAt >= 2_500L) {
                    lastReportedAt = now
                    onProgress(media, pos, dur)
                }
                delay(1_000L)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun remuxToCastMp4(
        sourceFile: File,
        sourceMime: String,
        outputFile: File,
        onProgress: (Int?) -> Unit,
    ) {
        withContext(Dispatchers.Main.immediate) {
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(sourceFile))
                .setMimeType(sourceMime)
                .build()
            val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(false)
                .setRemoveVideo(false)
                .build()

            var progressJob: Job? = null
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val transformer = Transformer.Builder(appContext)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                progressJob?.cancel()
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException,
                            ) {
                                progressJob?.cancel()
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        .build()

                    activeTransformer = transformer
                    cont.invokeOnCancellation {
                        progressJob?.cancel()
                        runCatching { transformer.cancel() }
                        activeTransformer = null
                    }

                    transformer.start(editedMediaItem, outputFile.absolutePath)
                    val holder = ProgressHolder()
                    progressJob = scope.launch {
                        while (true) {
                            when (transformer.getProgress(holder)) {
                                Transformer.PROGRESS_STATE_AVAILABLE -> onProgress(holder.progress.coerceIn(0, 99))
                                Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY -> onProgress(null)
                                else -> Unit
                            }
                            delay(350L)
                        }
                    }
                }
            } finally {
                progressJob?.cancel()
                activeTransformer = null
            }
        }
    }

    private fun cleanupCastResources(keepError: Boolean = false) {
        pollerJob?.cancel()
        pollerJob = null
        httpServer?.close()
        httpServer = null
        NanamiCastKeepaliveService.stop(appContext)
        cleanStagingDirectory()
        activeMedia = null
        isCastingActive = false
        isRemotePlaying = false
        isPreparing = false
        prepareStatus = null
        prepareProgressPercent = null
        subtitleTracks = emptyList()
        activeSubtitleTrackId = null
        positionMs = 0L
        durationMs = 0L
        if (!keepError) castError = null
    }

    private fun cleanStagingDirectory() {
        runCatching { stagingDir.deleteRecursively() }
    }

    private data class ContainerProbe(val mime: String, val needsRemux: Boolean)

    private fun inspectContainer(file: File): ContainerProbe {
        val header = ByteArray(12)
        runCatching {
            RandomAccessFile(file, "r").use { raf -> raf.read(header) }
        }
        val isMatroska = header[0] == 0x1A.toByte() &&
            header[1] == 0x45.toByte() &&
            header[2] == 0xDF.toByte() &&
            header[3] == 0xA3.toByte()
        if (isMatroska) {
            return if (file.extension.equals("webm", ignoreCase = true)) {
                ContainerProbe("video/webm", needsRemux = false)
            } else {
                ContainerProbe("video/x-matroska", needsRemux = true)
            }
        }
        val isMp4 = header[4] == 'f'.code.toByte() &&
            header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() &&
            header[7] == 'p'.code.toByte()
        if (isMp4) return ContainerProbe("video/mp4", needsRemux = false)
        return when (file.extension.lowercase()) {
            "webm" -> ContainerProbe("video/webm", needsRemux = false)
            "mkv" -> ContainerProbe("video/x-matroska", needsRemux = true)
            else -> ContainerProbe("video/mp4", needsRemux = false)
        }
    }
}

private class DownloadedCastHttpServer private constructor(
    private val socket: ServerSocket,
    private val token: String,
    private val videoFile: File,
    private val mimeType: String,
    private val subtitlesByPath: Map<String, CastSubtitleSidecar>,
) : AutoCloseable {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val ext = if (mimeType == "video/webm") "webm" else if (mimeType == "video/x-matroska") "mkv" else "mp4"
    private val videoPath = "/$token/video.$ext"
    val baseUrl = "http://${socket.inetAddress.hostAddress}:${socket.localPort}/$token"
    val videoUrl = "$baseUrl/video.$ext"

    fun subtitleUrl(trackId: Long): String = "$baseUrl/sub_$trackId.vtt"

    init {
        scope.launch {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                clients.add(client)
                scope.launch {
                    try {
                        client.use { conn ->
                            conn.soTimeout = 10_000
                            conn.tcpNoDelay = true
                            serve(conn.getInputStream().buffered(), conn.getOutputStream().buffered())
                        }
                    } catch (_: Exception) {
                        // Receivers routinely close connections early on seek
                    } finally {
                        clients.remove(client)
                    }
                }
            }
        }
    }

    private fun serve(input: InputStream, output: OutputStream) {
        val reqLine = readLineLimited(input)?.trim()?.split(' ') ?: return
        if (reqLine.size != 3) return
        val method = reqLine[0].uppercase()
        val target = reqLine[1].substringBefore('?')
        var rangeHeader: String? = null
        for (i in 0..48) {
            val line = readLineLimited(input) ?: return
            if (line.isEmpty()) break
            if (line.substringBefore(':').equals("Range", ignoreCase = true)) {
                rangeHeader = line.substringAfter(':').trim()
            }
        }

        if (method == "OPTIONS") {
            writeHeaders(
                output,
                "204 No Content",
                "Content-Length: 0",
                "Access-Control-Allow-Methods: GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers: Range, Content-Type",
            )
            return
        }
        if (method != "GET" && method != "HEAD") {
            writeHeaders(output, "405 Method Not Allowed", "Content-Length: 0")
            return
        }

        val subTrack = subtitlesByPath[target]
        if (subTrack != null) {
            val bytes = subTrack.vttContent.toByteArray(Charsets.UTF_8)
            writeHeaders(
                output,
                "200 OK",
                "Content-Type: text/vtt; charset=utf-8",
                "Content-Length: ${bytes.size}",
            )
            if (method == "GET") {
                output.write(bytes)
                output.flush()
            }
            return
        }

        if (target != videoPath || !videoFile.isFile) {
            writeHeaders(output, "404 Not Found", "Content-Length: 0")
            return
        }

        val totalLen = videoFile.length()
        val range = parseByteRange(rangeHeader, totalLen)
        if (range == null) {
            writeHeaders(
                output,
                "416 Range Not Satisfiable",
                "Content-Range: bytes */$totalLen",
                "Content-Length: 0",
            )
            return
        }

        val fields = mutableListOf(
            "Content-Type: $mimeType",
            "Content-Length: ${range.length}",
            "Accept-Ranges: bytes",
        )
        val status = if (rangeHeader == null) {
            "200 OK"
        } else {
            fields += "Content-Range: bytes ${range.start}-${range.end}/$totalLen"
            "206 Partial Content"
        }
        writeHeaders(output, status, *fields.toTypedArray())
        if (method == "HEAD") return

        RandomAccessFile(videoFile, "r").use { raf ->
            raf.seek(range.start)
            val buf = ByteArray(64 * 1024)
            var remaining = range.length
            while (remaining > 0L && !socket.isClosed) {
                val readCount = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (readCount <= 0) break
                output.write(buf, 0, readCount)
                remaining -= readCount
            }
            output.flush()
        }
    }

    private fun writeHeaders(output: OutputStream, status: String, vararg fields: String) {
        val payload = (
            listOf(
                "HTTP/1.1 $status",
                "Connection: close",
                "Access-Control-Allow-Origin: *",
                "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges",
            ) + fields
        ).joinToString("\r\n", postfix = "\r\n\r\n")
        output.write(payload.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun readLineLimited(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 4_096) {
            val b = input.read()
            if (b == -1) return null
            if (b == 10) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    override fun close() {
        runCatching { socket.close() }
        clients.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    companion object {
        @Suppress("DEPRECATION")
        private fun lanIpv4(context: Context): Inet4Address? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return cm.allNetworks.asSequence()
                .filter { network ->
                    val caps = cm.getNetworkCapabilities(network)
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                }
                .flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
        }

        suspend fun start(
            context: Context,
            videoFile: File,
            mimeType: String,
            subtitles: List<CastSubtitleSidecar>,
        ): DownloadedCastHttpServer = withContext(Dispatchers.IO) {
            val address = requireNotNull(lanIpv4(context)) {
                "Connect your phone and TV to the same Wi-Fi network first."
            }
            val token = UUID.randomUUID().toString()
            val pathMap = subtitles.associateBy { sidecar -> "/$token/sub_${sidecar.id}.vtt" }
            val server = ServerSocket()
            try {
                server.bind(InetSocketAddress(address, 0))
                DownloadedCastHttpServer(server, token, videoFile, mimeType, pathMap)
            } catch (e: Exception) {
                runCatching { server.close() }
                throw e
            }
        }
    }
}

@Composable
internal fun rememberNanamiDownloadedCastController(
    context: Context,
    appTitle: String,
    onProgress: (DownloadedCastMedia, Long, Long) -> Unit = { _, _, _ -> },
): NanamiDownloadedCastController {
    val controller = remember(context.applicationContext, appTitle) {
        NanamiDownloadedCastController(context.applicationContext, appTitle, onProgress)
    }
    DisposableEffect(controller) {
        controller.attach()
        onDispose { controller.detach() }
    }
    return controller
}

@Composable
internal fun DownloadedNowCastingBar(
    controller: NanamiDownloadedCastController,
    theme: NanamiCastUiTheme,
    modifier: Modifier = Modifier,
) {
    val media = controller.activeMedia ?: controller.pendingMedia
    if (!controller.isCastingActive && !controller.isPreparing) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { controller.isRemoteSheetOpen = true },
        color = theme.surfaceColor,
        border = BorderStroke(1.dp, theme.accentColor.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CastConnected,
                contentDescription = null,
                tint = theme.accentColor,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media?.title ?: "Casting",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = controller.prepareStatus
                        ?: "Casting to ${controller.connectedDeviceName ?: "TV"}",
                    color = theme.activeStatusColor,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (controller.isCastingActive) {
                IconButton(onClick = { controller.togglePlayPause() }) {
                    Icon(
                        imageVector = if (controller.isRemotePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (controller.isRemotePlaying) "Pause" else "Play",
                        tint = Color.White,
                    )
                }
            }
            IconButton(onClick = { controller.isRemoteSheetOpen = true }) {
                Icon(
                    imageVector = Icons.Default.Tv,
                    contentDescription = "Open TV Remote",
                    tint = theme.accentColor,
                )
            }
        }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadedCastRemoteDialog(
    controller: NanamiDownloadedCastController,
    theme: NanamiCastUiTheme,
    onDismiss: () -> Unit,
) {
    if (!controller.isRemoteSheetOpen) return
    val connected = controller.connectedSession?.takeIf { it.isConnected } != null
    val active = controller.activeMedia ?: controller.pendingMedia

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .onPreviewKeyEvent { keyEvent ->
                    val native = keyEvent.nativeKeyEvent
                    if (connected && native.action == KeyEvent.ACTION_DOWN) {
                        when (native.keyCode) {
                            KeyEvent.KEYCODE_VOLUME_UP -> {
                                controller.adjustVolume(0.05)
                                return@onPreviewKeyEvent true
                            }
                            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                controller.adjustVolume(-0.05)
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                    false
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = theme.surfaceColor,
                border = BorderStroke(1.dp, theme.borderColor),
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = if (connected) Icons.Default.CastConnected else Icons.Default.Cast,
                                contentDescription = null,
                                tint = theme.accentColor,
                            )
                            Text(
                                text = controller.connectedDeviceName?.let { "TV Remote • $it" } ?: "Cast Downloaded Video",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = theme.textMutedColor)
                        }
                    }

                    HorizontalDivider(color = theme.borderColor, thickness = 1.dp)

                    if (active != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (!active.artworkUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = active.artworkUrl,
                                    contentDescription = active.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(84.dp)
                                        .height(52.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(theme.cardColor),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = active.title,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = active.subtitle,
                                    color = theme.textMutedColor,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    controller.castError?.let { err ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x33FF5252),
                            border = BorderStroke(1.dp, Color(0x88FF5252)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = err,
                                color = Color(0xFFFFB4AB),
                                fontSize = 12.5.sp,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    when {
                        controller.isPreparing -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    text = controller.prepareStatus ?: "Preparing downloaded video for TV…",
                                    color = theme.activeStatusColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                )
                                val pct = controller.prepareProgressPercent
                                if (pct != null) {
                                    LinearProgressIndicator(
                                        progress = { pct / 100f },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = theme.accentColor,
                                        trackColor = theme.borderColor,
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = theme.accentColor,
                                        trackColor = theme.borderColor,
                                    )
                                }
                            }
                        }
                        connected && controller.isCastingActive -> {
                            // Seekbar + transport remote
                            val dur = controller.durationMs.coerceAtLeast(1L)
                            val pos = controller.positionMs.coerceIn(0L, dur)
                            var isSliding by remember { mutableStateOf(false) }
                            var sliderMs by remember { mutableLongStateOf(pos) }
                            LaunchedEffect(pos, isSliding) {
                                if (!isSliding) sliderMs = pos
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Slider(
                                    value = sliderMs.toFloat(),
                                    onValueChange = {
                                        isSliding = true
                                        sliderMs = it.toLong()
                                    },
                                    onValueChangeFinished = {
                                        isSliding = false
                                        controller.seekTo(sliderMs)
                                    },
                                    valueRange = 0f..dur.toFloat(),
                                    colors = SliderDefaults.colors(
                                        thumbColor = theme.accentColor,
                                        activeTrackColor = theme.accentColor,
                                        inactiveTrackColor = theme.borderColor,
                                    ),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(formatCastTimestamp(sliderMs), color = theme.textMutedColor, fontSize = 11.5.sp)
                                    Text(formatCastTimestamp(controller.durationMs), color = theme.textMutedColor, fontSize = 11.5.sp)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(
                                    onClick = { controller.seekBy(-10_000L) },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(30.dp))
                                }
                                Surface(
                                    onClick = { controller.togglePlayPause() },
                                    shape = CircleShape,
                                    color = theme.accentColor,
                                    modifier = Modifier.size(58.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (controller.isRemotePlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = if (controller.isRemotePlaying) "Pause" else "Play",
                                            tint = theme.accentOnColor,
                                            modifier = Modifier.size(32.dp),
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { controller.seekBy(10_000L) },
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(30.dp))
                                }
                            }

                            HorizontalDivider(color = theme.borderColor, thickness = 1.dp)

                            // TV Volume Remote
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("TV Volume", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        text = if (controller.isTvMuted) "Muted" else "${(controller.tvVolume * 100).toInt()}%",
                                        color = theme.activeStatusColor,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    IconButton(onClick = { controller.toggleMute() }) {
                                        Icon(
                                            imageVector = if (controller.isTvMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = "Mute TV",
                                            tint = if (controller.isTvMuted) Color(0xFFFF8A80) else Color.White,
                                        )
                                    }
                                    IconButton(onClick = { controller.adjustVolume(-0.08) }) {
                                        Icon(Icons.Default.Remove, contentDescription = "Volume Down", tint = Color.White)
                                    }
                                    Slider(
                                        value = controller.tvVolume.toFloat(),
                                        onValueChange = { controller.setVolume(it.toDouble()) },
                                        valueRange = 0f..1f,
                                        modifier = Modifier.weight(1f),
                                        colors = SliderDefaults.colors(
                                            thumbColor = theme.accentColor,
                                            activeTrackColor = theme.accentColor,
                                            inactiveTrackColor = theme.borderColor,
                                        ),
                                    )
                                    IconButton(onClick = { controller.adjustVolume(0.08) }) {
                                        Icon(Icons.Default.Add, contentDescription = "Volume Up", tint = Color.White)
                                    }
                                }
                            }

                            // Softsubs Track Picker (WebVTT over LAN)
                            if (controller.subtitleTracks.isNotEmpty()) {
                                HorizontalDivider(color = theme.borderColor, thickness = 1.dp)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(Icons.Default.Subtitles, contentDescription = null, tint = theme.accentColor, modifier = Modifier.size(18.dp))
                                    Text("TV Subtitles", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val offSelected = controller.activeSubtitleTrackId == null
                                    FilterChip(
                                        selected = offSelected,
                                        onClick = { controller.selectSubtitleTrack(null) },
                                        label = { Text("Off", fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = theme.accentColor,
                                            selectedLabelColor = theme.accentOnColor,
                                        ),
                                    )
                                    controller.subtitleTracks.forEach { track ->
                                        val selected = controller.activeSubtitleTrackId == track.id
                                        FilterChip(
                                            selected = selected,
                                            onClick = { controller.selectSubtitleTrack(track.id) },
                                            label = { Text(track.label, fontSize = 12.sp) },
                                            colors = FilterChipDefaults.filterChipColors(
                                                selectedContainerColor = theme.accentColor,
                                                selectedLabelColor = theme.accentOnColor,
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                        controller.isConnecting -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(color = theme.accentColor, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Text("Connecting to ${controller.connectedDeviceName ?: "TV"}…", color = Color.White)
                            }
                        }
                        controller.routes.isEmpty() -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                CircularProgressIndicator(color = theme.accentColor, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
                                Text("Searching for Chromecast devices on Wi-Fi…", color = Color.White, fontSize = 13.sp)
                                Text(
                                    text = "Ensure both your phone and TV are connected to the same Wi-Fi network.",
                                    color = theme.textMutedColor,
                                    fontSize = 11.5.sp,
                                )
                            }
                        }
                        else -> {
                            Text("Choose a TV", color = theme.textMutedColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 220.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(controller.routes, key = { it.id }) { route ->
                                    Surface(
                                        onClick = { controller.selectRoute(route.id) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = theme.cardColor,
                                        border = BorderStroke(1.dp, theme.borderColor),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Icon(Icons.Default.Tv, contentDescription = null, tint = theme.accentColor)
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(route.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                                Text(
                                                    route.description ?: "Chromecast / Google TV",
                                                    color = theme.textMutedColor,
                                                    fontSize = 11.5.sp,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (connected || controller.isPreparing || controller.isConnecting) {
                            OutlinedButton(
                                onClick = { controller.disconnect() },
                                border = BorderStroke(1.dp, Color(0xFFFF6B6B)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("Stop Casting", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                            }
                        }
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = theme.accentColor,
                                contentColor = theme.accentOnColor,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Minimize", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
        }
    }
}

private fun formatCastTimestamp(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0L) / 1_000L)
    val h = totalSec / 3_600L
    val m = (totalSec % 3_600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
