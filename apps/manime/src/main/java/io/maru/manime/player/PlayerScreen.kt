package io.maru.manime.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import io.maru.manime.*
import io.maru.manime.extensions.AniSkipInterval
import io.maru.manime.extensions.CrunchyrollStreamEngine
import io.maru.manime.extensions.ExternalSubtitleItem
import io.maru.manime.extensions.StreamLink
import io.maru.manime.extensions.SubtitleTrack
import io.maru.manime.extensions.playbackIdentity
import io.maru.manime.torrent.TorrentStreamService
import io.maru.manime.torrent.STREAM_READ_TIMEOUT_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import androidx.lifecycle.lifecycleScope
import java.io.File

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    media: AnimeMedia,
    episodeNum: Int,
    episodeTitle: String,
    initialAudioMode: String, // "SUB" | "DUB"
    hasEnglishDub: Boolean = true,
    prefs: MAnimePrefs,
    onEpisodeWatched: (Int) -> Unit = {},
    onSwitchEpisode: (Int) -> Unit = {},
    offlineOnly: Boolean = false,
    lastPlayableEpisode: Int? = media.latestAiredEpisode,
    onBack: () -> Unit
) = key(media.mediaId, episodeNum) {
    EpisodePlayerScreen(media, episodeNum, episodeTitle, initialAudioMode, hasEnglishDub,
        prefs, onEpisodeWatched, onSwitchEpisode, offlineOnly, lastPlayableEpisode, onBack)
}

@OptIn(UnstableApi::class)
@Composable
private fun EpisodePlayerScreen(
    media: AnimeMedia,
    episodeNum: Int,
    episodeTitle: String,
    initialAudioMode: String,
    hasEnglishDub: Boolean,
    prefs: MAnimePrefs,
    onEpisodeWatched: (Int) -> Unit,
    onSwitchEpisode: (Int) -> Unit,
    offlineOnly: Boolean,
    lastPlayableEpisode: Int?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detectedDualOrDubInStreams by remember { mutableStateOf(hasEnglishDub) }
    val effectiveHasDub = hasEnglishDub || detectedDualOrDubInStreams

    var activeAudioMode by remember {
        mutableStateOf(if (!effectiveHasDub) "SUB" else initialAudioMode)
    }
    var activeQuality by remember { mutableStateOf("1080p") }
    var rankedStreams by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var selectedStream by remember { mutableStateOf<StreamLink?>(null) }
    val attemptedSources = remember { mutableSetOf<Pair<String, Int?>>() }
    var isResolvingStreams by remember { mutableStateOf(true) }
    var waitingForAlternative by remember { mutableStateOf<String?>(null) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    var isOfflinePlayback by remember { mutableStateOf(false) }
    var skipOffline by remember { mutableStateOf(false) }

    var openSubtitlesList by remember { mutableStateOf<List<ExternalSubtitleItem>>(emptyList()) }
    var selectedOpenSub by remember { mutableStateOf<ExternalSubtitleItem?>(null) }
    var expectedTorrentRequest by remember { mutableStateOf<String?>(null) }
    var playbackAttempt by remember { mutableIntStateOf(0) }
    var playbackTracks by remember { mutableStateOf(Tracks.EMPTY) }
    var sourceHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sourceSubtitles by remember { mutableStateOf<List<SubtitleTrack>>(emptyList()) }
    var resumeAfterSwitch by remember { mutableLongStateOf(C.TIME_UNSET) }
    var playAfterSwitch by remember { mutableStateOf(true) }
    var subtitlesEnabled by remember { mutableStateOf<Boolean?>(null) }
    var selectExternalTrackOnLoad by remember { mutableStateOf(false) }

    val serviceStatus = TorrentStreamService.streamStatus.collectAsState()
    val torrentConnection by remember {
        derivedStateOf {
            val status = serviceStatus.value
            if (expectedTorrentRequest != null && status.requestId == expectedTorrentRequest) {
                TorrentConnection(status.isReady, status.streamUrl, status.error, status.phase)
            } else null
        }
    }

    var wantsPlayback by remember { mutableStateOf(true) }
    var isPlayerBuffering by remember { mutableStateOf(true) }
    var hasRenderedFrame by remember { mutableStateOf(false) }
    var showRebuffering by remember { mutableStateOf(false) }
    val currentPosition = remember { mutableLongStateOf(0L) }
    val bufferedPosition = remember { mutableLongStateOf(0L) }
    val totalDuration = remember { mutableLongStateOf(0L) }
    var isSeekable by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var showControls by remember { mutableStateOf(false) }
    var isInPipMode by remember { mutableStateOf(false) }
    var isScreenLocked by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var videoResizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var autoPlayCountdown by remember { mutableStateOf<Int?>(null) }
    var hasScrobbled85Percent by remember(media.mediaId, episodeNum) { mutableStateOf(false) }
    var savedPositionMs by remember { mutableStateOf<Long?>(null) }

    var skipIntervals by remember { mutableStateOf<List<AniSkipInterval>>(emptyList()) }
    var activeSkipInterval by remember { mutableStateOf<AniSkipInterval?>(null) }

    // Full-page Settings screen (Audio, Subtitles & Quality) instead of draggable BottomSheet!
    var showFullSettingsPage by remember { mutableStateOf(false) }

    val token by prefs.anilistToken.collectAsState(initial = "")
    val reportProgress by prefs.reportProgress.collectAsState(initial = true)
    val rememberPosition by prefs.rememberPosition.collectAsState(initial = true)
    val autoSkipIntro by prefs.autoSkipIntro.collectAsState(initial = false)
    val latestRememberPosition by rememberUpdatedState(rememberPosition)
    val latestWatchedCallback by rememberUpdatedState(onEpisodeWatched)
    val latestReportProgress by rememberUpdatedState(reportProgress)
    val latestToken by rememberUpdatedState(token)
    val latestMedia by rememberUpdatedState(media)
    val persistenceScope = remember(context) { (context as? ComponentActivity)?.lifecycleScope
        ?: CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    var isAttached by remember { mutableStateOf(true) }

    fun markWatched() {
        if (hasScrobbled85Percent) return
        hasScrobbled85Percent = true
        if (latestReportProgress && latestToken.isNotBlank() && episodeNum > latestMedia.progress) {
            val auth = latestToken
            val snapshot = latestMedia
            persistenceScope.launch {
                try {
                    if (!prefs.reportProgress.first() || prefs.anilistToken.first() != auth) return@launch
                    val current = AniListClient.getMediaDetail(snapshot.mediaId, auth)
                    if (episodeNum > current.progress && current.listStatus !in setOf("COMPLETED", "REPEATING")) {
                        if (!prefs.reportProgress.first() || prefs.anilistToken.first() != auth) return@launch
                        AniListClient.saveEntry(current.mediaId,
                            if (current.episodes != null && episodeNum >= current.episodes) "COMPLETED" else "CURRENT",
                            episodeNum, null, null, null, auth)
                    }
                    if (isAttached) latestWatchedCallback(episodeNum)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    android.widget.Toast.makeText(context, "AniList progress sync failed. Your list was not updated.",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        } else latestWatchedCallback(episodeNum)
    }

    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            playWhenReady = true
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(if (activeAudioMode == "DUB") "en" else "ja")
                .setPreferredTextLanguage("en")
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, activeAudioMode == "DUB")
                .build()
        }
    }

    fun prepareVideo(videoUri: Uri, mimeType: String?) {
        val externalSub = selectedOpenSub
        selectExternalTrackOnLoad = externalSub != null && subtitlesEnabled != false
        val subConfigs = sourceSubtitles.map { sub ->
            val uri = Uri.parse(sub.url)
            val subMime = when (uri.lastPathSegment?.substringAfterLast('.')?.lowercase()) {
                "ass", "ssa" -> MimeTypes.TEXT_SSA
                "vtt" -> MimeTypes.TEXT_VTT
                "ttml", "xml" -> MimeTypes.APPLICATION_TTML
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(subMime)
                .setLanguage(sub.language)
                .setLabel(sub.language)
                .build()
        }.toMutableList()
        if (externalSub != null) {
            val subMime = when (externalSub.format) {
                "ASS" -> MimeTypes.TEXT_SSA
                "VTT" -> MimeTypes.TEXT_VTT
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            subConfigs.removeAll { it.uri.toString() == externalSub.url }
            subConfigs += MediaItem.SubtitleConfiguration.Builder(Uri.parse(externalSub.url))
                .setMimeType(subMime)
                .setLanguage(externalSub.langCode)
                .setLabel("${externalSub.langName} (OpenSubtitles)")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        }
        val builder = MediaItem.Builder().setMediaId(playbackAttempt.toString()).setUri(videoUri)
        if (mimeType != null) builder.setMimeType(mimeType)
        if (subConfigs.isNotEmpty()) builder.setSubtitleConfigurations(subConfigs)
        val dataSource = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
            .setReadTimeoutMs(STREAM_READ_TIMEOUT_MS))
        val headers = sourceHeaders
        // Video headers can authorize manifest and segment requests. Keep them off OpenSubtitles.
        val sourceFactory = if (headers.isEmpty()) dataSource else ResolvingDataSource.Factory(dataSource) { request ->
            if (request.uri.toString() == externalSub?.url) request else request.withAdditionalHeaders(headers)
        }
        val source = DefaultMediaSourceFactory(sourceFactory).createMediaSource(builder.build())
        exoPlayer.setMediaSource(source, resumeAfterSwitch)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playAfterSwitch
    }

    fun enterPip() {
        val activity = context as? Activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                activity.enterPictureInPictureMode(params)
            } catch (_: Exception) {}
        }
    }

    DisposableEffect(context) {
        val compActivity = context as? ComponentActivity
        val pipConsumer = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            isInPipMode = info.isInPictureInPictureMode
            if (info.isInPictureInPictureMode) {
                showControls = false
                showFullSettingsPage = false
            }
        }
        compActivity?.addOnPictureInPictureModeChangedListener(pipConsumer)
        onDispose {
            isAttached = false
            compActivity?.removeOnPictureInPictureModeChangedListener(pipConsumer)
        }
    }

    LaunchedEffect(media.mediaId, episodeNum) {
        if (offlineOnly) return@LaunchedEffect
        launch {
            try {
                val mappings = CrunchyrollStreamEngine.getAnimeMappings(media.mediaId, media.title, includeEpisodeMetadata = false)
                skipIntervals = CrunchyrollStreamEngine.getAniSkipIntervals(mappings.malId, episodeNum)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        launch {
            try {
                openSubtitlesList = CrunchyrollStreamEngine.fetchOpenSubtitles(
                    anilistId = media.mediaId,
                    animeTitle = media.title,
                    episodeNum = episodeNum,
                    isMovie = media.format == "MOVIE"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
    }

    fun capturePlaybackState() {
        if (exoPlayer.currentMediaItem == null) return
        val position = exoPlayer.currentPosition
        if (exoPlayer.duration > 0 || position > 0) resumeAfterSwitch = position.coerceAtLeast(0L)
        playAfterSwitch = exoPlayer.playWhenReady
    }

    fun stopTorrent() {
        val requestId = expectedTorrentRequest
        expectedTorrentRequest = null
        if (requestId != null) TorrentStreamService.stopStream(requestId)
    }

    fun resetPlayback(stopPreviousTorrent: Boolean = true) {
        capturePlaybackState()
        playbackAttempt++
        autoPlayCountdown = null
        activeSkipInterval = null
        playbackTracks = Tracks.EMPTY
        isPlayerBuffering = true
        hasRenderedFrame = false
        isSeekable = false
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        if (stopPreviousTorrent) stopTorrent()
    }

    fun useOpenSubtitle(sub: ExternalSubtitleItem?) {
        val current = exoPlayer.currentMediaItem?.localConfiguration ?: return
        selectedOpenSub = sub
        subtitlesEnabled = true
        resetPlayback(stopPreviousTorrent = false)
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(sub?.langCode ?: "en")
            .build()
        prepareVideo(current.uri, current.mimeType)
    }

    fun playStream(chosen: StreamLink) {
        selectedStream = chosen
        attemptedSources.add(chosen.playbackIdentity)
        waitingForAlternative = null
        activeQuality = chosen.quality
        resolveError = null
        resetPlayback(stopPreviousTorrent = !chosen.isTorrent)
        sourceHeaders = if (chosen.isTorrent) emptyMap() else chosen.headers
        sourceSubtitles = chosen.subtitles
        val sourceAudio = chosen.audioType.takeIf { it == "SUB" || it == "DUB" } ?: activeAudioMode
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .clearOverrides().setPreferredAudioLanguage(if (sourceAudio == "DUB") "en" else "ja")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !(subtitlesEnabled ?: (sourceAudio != "DUB")))
            .setPreferredTextLanguage(selectedOpenSub?.langCode ?: "en").build()

        if (chosen.isTorrent) {
            expectedTorrentRequest = TorrentStreamService.startStream(
                context = context,
                magnetUri = chosen.url,
                fileIdx = chosen.fileIdx,
                animeTitle = media.title,
                episodeNum = episodeNum
            )
        } else {
            prepareVideo(Uri.parse(chosen.url), null)
        }
    }

    var streamReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(streamReload) {
        try {
            isResolvingStreams = true
            resolveError = null
            waitingForAlternative = null
            selectedStream = null
            attemptedSources.clear()
            resetPlayback()
            rankedStreams = emptyList()
            val loadPosition = async {
                if (savedPositionMs == null && prefs.rememberPosition.first()) {
                    prefs.getPositionFlow(media.mediaId, episodeNum).first().coerceAtLeast(0) * 1000L
                } else null
            }
            val offlineEntry = if (skipOffline) null else {
                TorrentStreamService.getCompletedOfflineEpisode(context, media.mediaId, episodeNum)
            }
            if (savedPositionMs == null) savedPositionMs = loadPosition.await() ?: 0L
            if (offlineEntry != null) {
                isOfflinePlayback = true
                isResolvingStreams = false
                activeQuality = offlineEntry.quality
                sourceHeaders = emptyMap()
                sourceSubtitles = emptyList()
                if (offlineEntry.audioType != "DUAL_AUDIO") activeAudioMode = offlineEntry.audioType
                if (offlineEntry.audioType != "SUB") detectedDualOrDubInStreams = true
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguage(if (activeAudioMode == "DUB") "en" else "ja")
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !(subtitlesEnabled ?: (activeAudioMode != "DUB")))
                    .build()
                prepareVideo(Uri.fromFile(File(offlineEntry.vaultFilePath)), null)
                return@LaunchedEffect
            }
            if (offlineOnly) {
                isPlayerBuffering = false
                resolveError = "This episode is unavailable offline. Download it again to play it here."
                return@LaunchedEffect
            }

            isOfflinePlayback = false
            val prefQual = prefs.preferredQuality.first()
            var startFallback: Job? = null
            val streams = CrunchyrollStreamEngine.resolveRankedStreams(
                anilistId = media.mediaId,
                animeTitle = media.title,
                episodeNum = episodeNum,
                preferredAudio = activeAudioMode,
                preferredQuality = prefQual,
                isMovie = media.format == "MOVIE",
                onStreamsUpdated = { options ->
                    rankedStreams = options
                    if (options.any { it.audioType == "DUAL_AUDIO" || it.audioType == "DUB" }) {
                        detectedDualOrDubInStreams = true
                    }
                    val candidates = options.filter { it.playbackIdentity !in attemptedSources }
                    if (selectedStream == null || waitingForAlternative != null) {
                        val preferred = candidates.firstOrNull {
                            (it.audioType == activeAudioMode || it.audioType == "DUAL_AUDIO") &&
                                (!it.isTorrent || it.seeders != 0)
                        }
                        if (preferred != null) {
                            startFallback?.cancel()
                            playStream(preferred)
                        } else if (candidates.isNotEmpty() && startFallback?.isActive != true) {
                            startFallback = launch {
                                // Give matching audio a brief head start while providers finish in parallel.
                                delay(750)
                                if (selectedStream == null || waitingForAlternative != null) {
                                    rankedStreams.firstOrNull { it.playbackIdentity !in attemptedSources }?.let(::playStream)
                                }
                            }
                        }
                    }
                }
            )
            startFallback?.cancel()
            rankedStreams = streams
            isResolvingStreams = false
            if (selectedStream == null || waitingForAlternative != null) {
                val remaining = streams.firstOrNull { it.playbackIdentity !in attemptedSources }
                if (remaining != null) playStream(remaining)
                else {
                    isPlayerBuffering = false
                    resolveError = waitingForAlternative ?: "Episode $episodeNum is not available yet."
                    waitingForAlternative = null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (selectedStream == null || waitingForAlternative != null) {
                isPlayerBuffering = false
                resolveError = waitingForAlternative ?: "Could not find sources for this episode. Try again."
                waitingForAlternative = null
            }
        } finally {
            isResolvingStreams = false
        }
    }

    fun handlePlaybackFailure(message: String) {
        val next = rankedStreams.firstOrNull { it.playbackIdentity !in attemptedSources }
        if (!isOfflinePlayback && next != null) {
            playStream(next)
        } else {
            resetPlayback()
            isPlayerBuffering = false
            if (!isOfflinePlayback && isResolvingStreams) waitingForAlternative = message
            else resolveError = message
        }
    }

    val hasFallback = rankedStreams.any { it.playbackIdentity !in attemptedSources }
    LaunchedEffect(playbackAttempt, hasRenderedFrame, hasFallback) {
        val source = selectedStream ?: return@LaunchedEffect
        val attempt = playbackAttempt
        if (!hasRenderedFrame && hasFallback && resolveError == null && waitingForAlternative == null) {
            delay(if (source.isTorrent) 45_000 else 15_000)
            if (attempt == playbackAttempt && !hasRenderedFrame) {
                handlePlaybackFailure("This source is taking too long to start.")
            }
        }
    }

    LaunchedEffect(torrentConnection, expectedTorrentRequest) {
        val connection = torrentConnection ?: return@LaunchedEffect
        if (connection.error != null) {
            android.util.Log.w("NanamiPlayer", "Source failed: ${connection.phase}")
            handlePlaybackFailure("Couldn't start this source. Try another source.")
        } else if (connection.isReady && connection.url != null &&
            exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() != connection.url) {
            val url = connection.url
            val mime = when {
                url.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
                url.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
                else -> MimeTypes.APPLICATION_MATROSKA
            }
            prepareVideo(Uri.parse(url), mime)
        }
    }

    val latestListener by rememberUpdatedState(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                wantsPlayback = playWhenReady
            }

            override fun onPlaybackStateChanged(state: Int) {
                isPlayerBuffering = state == Player.STATE_BUFFERING || (state == Player.STATE_IDLE && resolveError == null)
                if (state == Player.STATE_READY) {
                    val duration = exoPlayer.duration.coerceAtLeast(0L)
                    totalDuration.longValue = duration
                    isSeekable = exoPlayer.isCurrentMediaItemSeekable && duration > 0
                    savedPositionMs?.let { position ->
                        savedPositionMs = 0L
                        if (isSeekable && position in 10_000..(duration - 30_000)) {
                            exoPlayer.seekTo(position)
                        }
                    }
                } else if (state == Player.STATE_ENDED) {
                    markWatched()
                    val maxEps = lastPlayableEpisode ?: episodeNum
                    if (!offlineOnly && episodeNum < maxEps) {
                        autoPlayCountdown = 5
                    }
                }
            }

            override fun onRenderedFirstFrame() {
                if (!hasRenderedFrame && !isInPipMode && !isScreenLocked) showControls = true
                hasRenderedFrame = true
            }

            override fun onTracksChanged(tracks: Tracks) {
                playbackTracks = tracks
                if (findAudioTrack(tracks, "DUB") != null) detectedDualOrDubInStreams = true
                val sub = selectedOpenSub
                if (selectExternalTrackOnLoad && sub != null) {
                    val label = "${sub.langName} (OpenSubtitles)"
                    for (group in tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }) {
                        val trackIndex = (0 until group.length).firstOrNull { group.getTrackFormat(it).label == label }
                        if (trackIndex != null) {
                            selectExternalTrackOnLoad = false
                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                                .build()
                            break
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val failedAttempt = exoPlayer.currentMediaItem?.mediaId ?: return
                scope.launch {
                    // The engine and Media3 can both report the same failure.
                    if (failedAttempt != playbackAttempt.toString()) return@launch
                    if (selectedOpenSub != null) {
                        useOpenSubtitle(null)
                        return@launch
                    }
                    android.util.Log.w("NanamiPlayer", "Playback failed: ${error.errorCodeName} (${torrentConnection?.phase ?: "media"})")
                    handlePlaybackFailure("This source stopped playing. Try another source.")
                }
            }
        })
    DisposableEffect(exoPlayer) {
        val activity = context as? Activity
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (activity != null) {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        PlayerForegroundService.start(context, media.title, episodeNum)
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                latestListener.onPlayWhenReadyChanged(playWhenReady, reason)
            override fun onPlaybackStateChanged(state: Int) = latestListener.onPlaybackStateChanged(state)
            override fun onRenderedFirstFrame() = latestListener.onRenderedFirstFrame()
            override fun onTracksChanged(tracks: Tracks) = latestListener.onTracksChanged(tracks)
            override fun onPlayerError(error: PlaybackException) = latestListener.onPlayerError(error)
        }
        exoPlayer.addListener(listener)

        onDispose {
            val ended = exoPlayer.playbackState == Player.STATE_ENDED
            val position = if (exoPlayer.currentMediaItem != null) exoPlayer.currentPosition else resumeAfterSwitch
            val posSec = (position / 1000L).coerceAtLeast(0L)
            persistenceScope.launch {
                if (latestRememberPosition && (ended || posSec > 5)) {
                    prefs.savePosition(media.mediaId, episodeNum, if (ended) 0L else posSec)
                }
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            PlayerForegroundService.stop(context)
            stopTorrent()

            activity?.requestedOrientation = originalOrientation
            if (activity != null) {
                val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(exoPlayer, skipIntervals, autoSkipIntro) {
        var lastSavedPosition = 0L
        while (true) {
            val position = exoPlayer.currentPosition.coerceAtLeast(0L)
            val duration = exoPlayer.duration.coerceAtLeast(0L)
            currentPosition.longValue = position
            bufferedPosition.longValue = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            totalDuration.longValue = duration

            val posSec = position / 1000.0
            val matchedSkip = skipIntervals.firstOrNull { posSec >= it.startTimeSeconds && posSec < it.endTimeSeconds - 1.0 }
            activeSkipInterval = matchedSkip

            if (matchedSkip != null && autoSkipIntro && exoPlayer.isPlaying && matchedSkip.skipType.equals("op", ignoreCase = true)) {
                exoPlayer.seekTo((matchedSkip.endTimeSeconds * 1000).toLong())
            }

            if (duration > 60_000L && !hasScrobbled85Percent) {
                val ratio = position.toDouble() / duration.toDouble()
                if (ratio >= 0.85) {
                    markWatched()
                }
            }

            if (latestRememberPosition && exoPlayer.playbackState == Player.STATE_READY && position > 5000 &&
                kotlin.math.abs(position - lastSavedPosition) >= 10_000) {
                prefs.savePosition(media.mediaId, episodeNum, position / 1000)
                lastSavedPosition = position
            }

            delay(if (showControls && hasRenderedFrame) 350 else 850)
        }
    }

    LaunchedEffect(autoPlayCountdown) {
        val count = autoPlayCountdown ?: return@LaunchedEffect
        if (count <= 0) {
            autoPlayCountdown = null
            onSwitchEpisode(episodeNum + 1)
        } else {
            delay(1000)
            if (autoPlayCountdown != null) {
                autoPlayCountdown = count - 1
            }
        }
    }

    LaunchedEffect(showControls, wantsPlayback, showFullSettingsPage, hasRenderedFrame, controlsInteraction, isScrubbing) {
        if (showControls && wantsPlayback && hasRenderedFrame && !showFullSettingsPage && !isScrubbing) {
            delay(4000)
            showControls = false
        }
    }

    LaunchedEffect(isPlayerBuffering, hasRenderedFrame) {
        showRebuffering = false
        if (isPlayerBuffering && hasRenderedFrame) {
            delay(250)
            showRebuffering = true
        }
    }

    BackHandler {
        if (showFullSettingsPage) {
            showFullSettingsPage = false
        } else {
            onBack()
        }
    }

    val canToggleChrome = hasRenderedFrame && resolveError == null && !isInPipMode && !showFullSettingsPage
    val controlsShowing = canToggleChrome && showControls && !isScreenLocked
    val episodeLabel = if (episodeTitle.equals("Episode $episodeNum", true) || episodeTitle.isBlank()) {
        "Episode $episodeNum"
    } else "Episode $episodeNum · $episodeTitle"
    val sourceAudio = selectedStream?.audioType?.takeIf { it == "SUB" || it == "DUB" } ?: activeAudioMode
    val englishSubtitle = if (sourceAudio == "SUB" && subtitlesEnabled != false && selectedOpenSub == null &&
        playbackTracks.groups.none { it.type == C.TRACK_TYPE_TEXT }) {
        openSubtitlesList.firstOrNull { it.langCode.equals("en", true) || it.langCode.equals("eng", true) }
    } else null

    fun jumpBy(offsetMs: Long) {
        if (!isSeekable) return
        exoPlayer.seekTo((exoPlayer.currentPosition + offsetMs).coerceIn(0L, totalDuration.longValue))
        controlsInteraction++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .clickable(
                enabled = canToggleChrome,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = videoResizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    subtitleView?.apply {
                        setApplyEmbeddedStyles(true)
                        setApplyEmbeddedFontSizes(true)
                        setBottomPaddingFraction(0.06f)
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                Typeface.DEFAULT_BOLD
                            )
                        )
                    }
                }
            },
            update = { view ->
                if (view.resizeMode != videoResizeMode) view.resizeMode = videoResizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        val next = autoPlayCountdown
        val jumpToNext: () -> Unit = {
            autoPlayCountdown = null
            onSwitchEpisode(episodeNum + 1)
        }
        when {
            isInPipMode || showFullSettingsPage -> Unit
            resolveError != null -> PlayerProblem(
                title = media.title,
                episode = episodeLabel,
                message = resolveError.orEmpty(),
                retryLabel = when {
                    isOfflinePlayback && !offlineOnly -> "Try streaming"
                    rankedStreams.size > 1 -> "Try another source"
                    else -> "Retry"
                },
                onBack = onBack,
                onRetry = {
                    val identity = selectedStream?.playbackIdentity
                    if (!isOfflinePlayback && rankedStreams.size > 1) {
                        val index = rankedStreams.indexOfFirst { it.playbackIdentity == identity }
                        attemptedSources.clear()
                        playStream(rankedStreams[(index + 1) % rankedStreams.size])
                    } else {
                        skipOffline = isOfflinePlayback && !offlineOnly
                        streamReload++
                    }
                }
            )
            !hasRenderedFrame -> PlayerLoading(
                title = media.title,
                episode = episodeLabel,
                lookingForSource = waitingForAlternative != null || (selectedStream == null && isResolvingStreams),
                torrentRequest = expectedTorrentRequest,
                serviceStatus = serviceStatus,
                onBack = onBack
            )
            controlsShowing -> {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(
                            listOf(Color(0xD6000000), Color(0x32000000), Color(0xE6000000))
                        ))
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    PlayerHeader(media.title, episodeLabel, onBack) { showFullSettingsPage = true }

                    BoxWithConstraints(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (next != null) {
                            PlayerUpNext(episodeNum + 1, next, jumpToNext, { autoPlayCountdown = null })
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(if (maxWidth < 360.dp) 1.dp else 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!offlineOnly && episodeNum > 1) {
                                    PlayerTransportButton(Icons.Default.SkipPrevious, "Previous episode",
                                        onClick = { onSwitchEpisode(episodeNum - 1) })
                                }
                                PlayerTransportButton(Icons.Default.Replay10, "Rewind 10 seconds",
                                    onClick = { jumpBy(-10_000L) }, enabled = isSeekable)
                                val canPause = wantsPlayback && exoPlayer.playbackState != Player.STATE_ENDED
                                PlayerTransportButton(
                                    icon = if (canPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    label = if (canPause) "Pause" else "Play",
                                    onClick = {
                                        if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                            autoPlayCountdown = null
                                            exoPlayer.seekToDefaultPosition()
                                            exoPlayer.play()
                                        } else if (exoPlayer.playWhenReady) {
                                            exoPlayer.pause()
                                        } else {
                                            exoPlayer.play()
                                        }
                                        controlsInteraction++
                                    },
                                    primary = true
                                )
                                PlayerTransportButton(Icons.Default.Forward10, "Forward 10 seconds",
                                    onClick = { jumpBy(10_000L) }, enabled = isSeekable)
                                if (!offlineOnly && episodeNum < (lastPlayableEpisode ?: episodeNum)) {
                                    PlayerTransportButton(Icons.Default.SkipNext, "Next episode",
                                        onClick = { onSwitchEpisode(episodeNum + 1) })
                                }
                            }
                        }
                    }

                    if (next == null) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.weight(1f)) {
                                if (englishSubtitle != null && !showRebuffering) {
                                    TextButton(onClick = { useOpenSubtitle(englishSubtitle) }) {
                                        Text("English subtitles")
                                    }
                                } else if (showRebuffering || isOfflinePlayback) {
                                    Text(
                                        if (showRebuffering) "Buffering…" else "Offline",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (showRebuffering) CrunchyOrange else Color.White
                                    )
                                }
                            }
                            val interval = activeSkipInterval
                            TextButton(
                                enabled = isSeekable,
                                onClick = {
                                    if (interval == null) jumpBy(85_000L) else {
                                        exoPlayer.seekTo((interval.endTimeSeconds * 1000).toLong())
                                        controlsInteraction++
                                    }
                                }
                            ) {
                                Text(if (interval == null) "Skip forward 85s" else interval.skipLabel,
                                    color = if (isSeekable) CrunchyOrange else MaruTextMuted)
                            }
                        }
                    }

                    PlayerTimeline(
                        position = currentPosition,
                        buffered = bufferedPosition,
                        duration = totalDuration,
                        isSeekable = isSeekable,
                        label = "$activeQuality · ${if (sourceAudio == "DUB") "Dub" else "Sub"}",
                        onScrub = { scrubbing ->
                            isScrubbing = scrubbing
                            if (!scrubbing) controlsInteraction++
                        },
                        onSeek = { position ->
                            exoPlayer.seekTo(position)
                            currentPosition.longValue = position
                        }
                    )
                }
            }
            else -> {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    if (showRebuffering) {
                        CircularProgressIndicator(
                            color = CrunchyOrange,
                            strokeWidth = 3.dp,
                            modifier = Modifier.align(Alignment.Center).size(30.dp)
                        )
                    }
                    if (isScreenLocked && showControls) {
                        FilledTonalIconButton(
                            onClick = { isScreenLocked = false; controlsInteraction++ },
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = "Unlock controls")
                        }
                    } else if (!isScreenLocked && next != null) {
                        PlayerUpNext(episodeNum + 1, next, jumpToNext, { autoPlayCountdown = null },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp))
                    } else if (!isScreenLocked && isSeekable && !isPlayerBuffering) {
                        val interval = activeSkipInterval
                        if (interval != null) {
                            FilledTonalButton(
                                onClick = { exoPlayer.seekTo((interval.endTimeSeconds * 1000).toLong()) },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp)
                            ) {
                                Text(interval.skipLabel)
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showFullSettingsPage && !isInPipMode,
            enter = fadeIn(tween(120)),
            exit = ExitTransition.None
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = CrunchyBgBlack
            ) {
                Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CrunchySurface)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(onClick = { showFullSettingsPage = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to video", tint = Color.White)
                        }
                        Text("Playback settings", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    // Native Scrollable Full-Page Content (Zero swipe-to-dismiss interference!)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        item(key = "section_playback") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Playback", color = CrunchyOrange, style = MaterialTheme.typography.titleSmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        playbackSpeed = when (playbackSpeed) {
                                            0.75f -> 1.0f
                                            1.0f -> 1.25f
                                            1.25f -> 1.5f
                                            1.5f -> 2.0f
                                            else -> 0.75f
                                        }
                                        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
                                    }, modifier = Modifier.weight(1f)) {
                                        Text("Speed ${playbackSpeed}×")
                                    }
                                    OutlinedButton(onClick = {
                                        videoResizeMode = when (videoResizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        }
                                    }, modifier = Modifier.weight(1f)) {
                                        Text(when (videoResizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
                                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"
                                            else -> "Fit screen"
                                        })
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        isScreenLocked = true
                                        showControls = false
                                        showFullSettingsPage = false
                                    }, modifier = Modifier.weight(1f)) { Text("Lock controls") }
                                    OutlinedButton(onClick = { enterPip() }, modifier = Modifier.weight(1f)) {
                                        Text("Picture in picture")
                                    }
                                }
                            }
                        }

                        item(key = "section_audio") {
                            val audioGroups = playbackTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Audio", color = CrunchyOrange, style = MaterialTheme.typography.titleSmall)

                                if (audioGroups.sumOf { it.length } > 1) {
                                    audioGroups.forEach { group ->
                                        for (i in 0 until group.length) {
                                            val format = group.getTrackFormat(i)
                                            val isSelected = group.isTrackSelected(i)
                                            val label = formatAudioTrackName(format.label, format.language, i)
                                            Surface(
                                                onClick = {
                                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                        .buildUpon()
                                                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                                        .build()
                                                    showFullSettingsPage = false
                                                },
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isSelected) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                                border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(14.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(label, color = Color.White, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                                    if (isSelected) {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    val modes = if (isOfflinePlayback) {
                                        listOf(activeAudioMode to if (activeAudioMode == "DUB") "Dubbed Audio" else "Original Audio")
                                    } else if (effectiveHasDub) {
                                        listOf("SUB" to "Japanese (Original Audio)", "DUB" to "Dub / Dual-Audio Stream")
                                    } else {
                                        listOf("SUB" to "Japanese (Original Audio)")
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        modes.forEach { (mode, label) ->
                                            val selected = sourceAudio == mode
                                            Surface(
                                                onClick = {
                                                    if (!selected) {
                                                        activeAudioMode = mode
                                                        if (!applyPreferredAudioTrack(exoPlayer, mode)) streamReload++
                                                        scope.launch { prefs.setPreferredAudio(mode) }
                                                    }
                                                    showFullSettingsPage = false
                                                },
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (selected) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                                border = BorderStroke(1.dp, if (selected) CrunchyOrange else CrunchyBorder),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(modifier = Modifier.padding(vertical = 14.dp, horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                                    Text(label, color = if (selected) CrunchyOrange else Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Subtitles Section (Embedded Positioned ASS/SSA + OpenSubtitles)
                        item(key = "section_subs") {
                            val subGroups = playbackTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Subtitles", color = CrunchyOrange, style = MaterialTheme.typography.titleSmall)

                                Surface(
                                    onClick = {
                                        subtitlesEnabled = false
                                        selectExternalTrackOnLoad = false
                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                            .build()
                                        showFullSettingsPage = false
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    color = CrunchyCardBg,
                                    border = BorderStroke(1.dp, CrunchyBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Subtitles Off", color = Color.White, modifier = Modifier.padding(14.dp), fontSize = 14.sp)
                                }

                                subGroups.forEach { group ->
                                    for (i in 0 until group.length) {
                                        val format = group.getTrackFormat(i)
                                        val isSelected = subtitlesEnabled != false && group.isTrackSelected(i)
                                        val isAss = format.sampleMimeType == MimeTypes.TEXT_SSA || (format.codecs?.contains("ssa", true) == true) || (format.codecs?.contains("ass", true) == true)
                                        val baseName = formatSubtitleTrackName(format.label, format.language, i)
                                        val trackName = if (isAss) "$baseName • Styled ASS (Positioned)" else baseName

                                        Surface(
                                            onClick = {
                                                subtitlesEnabled = true
                                                selectExternalTrackOnLoad = false
                                                if (format.label != "${selectedOpenSub?.langName} (OpenSubtitles)") selectedOpenSub = null
                                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                    .buildUpon()
                                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                                    .build()
                                                showFullSettingsPage = false
                                            },
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isSelected) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                            border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(trackName, color = Color.White, fontSize = 13.5.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                                if (isSelected) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                    }
                                }

                                if (openSubtitlesList.isNotEmpty() && exoPlayer.currentMediaItem != null) {
                                    openSubtitlesList.take(10).forEach { extSub ->
                                        val isSelected = selectedOpenSub?.url == extSub.url && subtitlesEnabled != false
                                        Surface(
                                            onClick = {
                                                if (!isSelected) useOpenSubtitle(extSub)
                                                showFullSettingsPage = false
                                            },
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isSelected) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                            border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(14.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("${extSub.langName} (${extSub.format})", color = Color.White, fontSize = 13.5.sp)
                                                if (isSelected) {
                                                    Icon(Icons.Default.Check, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Video Quality & Stream Server Section
                        if (!isOfflinePlayback && rankedStreams.size > 1) {
                            item(key = "section_quality_header") {
                                Text("Video sources", color = CrunchyOrange, style = MaterialTheme.typography.titleSmall)
                            }
                            items(rankedStreams.withIndex().toList(), key = { it.value.playbackIdentity.toString() }) { (idx, stream) ->
                                val isCurrent = stream.playbackIdentity == selectedStream?.playbackIdentity
                                Surface(
                                    onClick = {
                                        showFullSettingsPage = false
                                        if (!isCurrent) {
                                            attemptedSources.clear()
                                            playStream(stream)
                                        }
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isCurrent) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                    border = BorderStroke(1.dp, if (isCurrent) CrunchyOrange else CrunchyBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text("Server ${idx + 1} • ${stream.quality}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            Text(stream.sourceName, color = MaruTextMuted, fontSize = 12.sp)
                                        }
                                        if (isCurrent) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
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

@OptIn(UnstableApi::class)
private fun applyPreferredAudioTrack(exoPlayer: ExoPlayer, preferredAudioMode: String): Boolean {
    val override = findAudioTrack(exoPlayer.currentTracks, preferredAudioMode) ?: return false
    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
        .setOverrideForType(override)
        .build()
    return true
}

@OptIn(UnstableApi::class)
private fun findAudioTrack(tracks: Tracks, preferredAudioMode: String): TrackSelectionOverride? {
    val isDub = preferredAudioMode == "DUB"
    val targetLangs = if (isDub) setOf("en", "eng", "english") else setOf("ja", "jpn", "japanese")
    val targetLabel = if (isDub) "english" else "japanese"
    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    for (group in audioGroups) {
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val fmt = group.getTrackFormat(i)
            val lang = fmt.language.orEmpty().lowercase().substringBefore('-').substringBefore('_')
            val labelWords = fmt.label.orEmpty().lowercase().split(Regex("[^a-z]+"))
            if (lang in targetLangs || ((lang.isBlank() || lang == "und") && targetLabel in labelWords)) {
                return TrackSelectionOverride(group.mediaTrackGroup, i)
            }
        }
    }
    return null
}

private fun formatAudioTrackName(label: String?, lang: String?, index: Int): String {
    val cleanLang = when (lang?.lowercase()) {
        "ja", "jpn" -> "Japanese"
        "en", "eng" -> "English"
        "es", "spa" -> "Spanish"
        "pt", "por" -> "Portuguese"
        "fr", "fre", "fra" -> "French"
        "de", "ger", "deu" -> "German"
        "zh", "chi", "zho" -> "Chinese"
        "ko", "kor" -> "Korean"
        else -> lang?.uppercase() ?: "Audio Track ${index + 1}"
    }
    return if (!label.isNullOrBlank() && !label.equals(cleanLang, ignoreCase = true)) {
        "$cleanLang ($label)"
    } else {
        cleanLang
    }
}

private fun formatSubtitleTrackName(label: String?, lang: String?, index: Int): String {
    val cleanLang = when (lang?.lowercase()) {
        "en", "eng" -> "English"
        "es", "spa" -> "Español"
        "pt", "por" -> "Português"
        "fr", "fre", "fra" -> "Français"
        "de", "ger", "deu" -> "Deutsch"
        else -> lang?.uppercase() ?: "Subtitle ${index + 1}"
    }
    return if (!label.isNullOrBlank()) "$cleanLang • $label" else cleanLang
}

private data class TorrentConnection(val isReady: Boolean, val url: String?, val error: String?, val phase: String)

private val AniSkipInterval.skipLabel: String
    get() = if (skipType.equals("ed", true)) "Skip ending" else "Skip intro"
