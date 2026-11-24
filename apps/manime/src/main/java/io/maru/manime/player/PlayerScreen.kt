package io.maru.manime.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import io.maru.manime.*
import io.maru.manime.extensions.AniSkipInterval
import io.maru.manime.extensions.CrunchyrollStreamEngine
import io.maru.manime.extensions.ExternalSubtitleItem
import io.maru.manime.extensions.StreamLink
import io.maru.manime.extensions.SubtitleTrack
import io.maru.manime.extensions.playbackIdentity
import io.maru.manime.torrent.STREAM_READ_TIMEOUT_MS
import io.maru.manime.torrent.TorrentStreamService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

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
    hasPrevEpisode: Boolean = episodeNum > 1,
    hasNextEpisode: Boolean = episodeNum < (media.latestAiredEpisode ?: episodeNum),
    offlineOnly: Boolean = false,
    lastPlayableEpisode: Int? = media.latestAiredEpisode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val castController = remember(context) { NanamiCastController(context) }

    // Keep landscape orientation, immersive system bars, screen brightness, and Chromecast session
    // mounted across episode transitions so Next/Previous never flashes portrait or corrupts orientation!
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val originalBrightness = activity?.window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (activity != null) {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        castController.startDiscovery()

        onDispose {
            castController.stopDiscovery()
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (activity != null) {
                val lp = activity.window.attributes
                lp.screenBrightness = originalBrightness
                activity.window.attributes = lp
                activity.requestedOrientation = originalOrientation
                WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    key(media.mediaId, episodeNum) {
        EpisodePlayerScreen(
            media = media,
            episodeNum = episodeNum,
            episodeTitle = episodeTitle,
            initialAudioMode = initialAudioMode,
            hasEnglishDub = hasEnglishDub,
            prefs = prefs,
            castController = castController,
            onEpisodeWatched = onEpisodeWatched,
            onSwitchEpisode = onSwitchEpisode,
            hasPrevEpisode = hasPrevEpisode,
            hasNextEpisode = hasNextEpisode,
            offlineOnly = offlineOnly,
            lastPlayableEpisode = lastPlayableEpisode,
            onBack = onBack
        )
    }
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
    castController: NanamiCastController,
    onEpisodeWatched: (Int) -> Unit,
    onSwitchEpisode: (Int) -> Unit,
    hasPrevEpisode: Boolean,
    hasNextEpisode: Boolean,
    offlineOnly: Boolean,
    lastPlayableEpisode: Int?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var detectedDualOrDubInStreams by remember { mutableStateOf(hasEnglishDub) }
    val effectiveHasDub = hasEnglishDub || detectedDualOrDubInStreams
    LaunchedEffect(detectedDualOrDubInStreams) {
        if (detectedDualOrDubInStreams) {
            KnownDubRegistry.recordKnownDub(media.mediaId, context)
        }
    }

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
    var autoAppliedAudioAttempt by remember { mutableIntStateOf(-1) }
    var sourceHeaders by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sourceSubtitles by remember { mutableStateOf<List<SubtitleTrack>>(emptyList()) }
    var currentLoadedUri by remember { mutableStateOf<Uri?>(null) }
    var currentLoadedMime by remember { mutableStateOf<String?>(null) }
    var resumeAfterSwitch by remember { mutableLongStateOf(C.TIME_UNSET) }
    var playAfterSwitch by remember { mutableStateOf(true) }
    var subtitlesEnabled by remember { mutableStateOf<Boolean?>(null) }
    var selectExternalTrackOnLoad by remember { mutableStateOf(false) }

    val serviceStatus = TorrentStreamService.streamStatus.collectAsState()
    val offlineCastUrl by TorrentStreamService.offlineCastUrl.collectAsState()
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
    var hasScrobbled80Percent by remember(media.mediaId, episodeNum) { mutableStateOf(false) }
    var savedPositionMs by remember { mutableStateOf<Long?>(null) }

    var skipIntervals by remember { mutableStateOf<List<AniSkipInterval>>(emptyList()) }
    var activeSkipInterval by remember { mutableStateOf<AniSkipInterval?>(null) }

    var showFullSettingsPage by remember { mutableStateOf(false) }
    var showCastDialog by remember { mutableStateOf(false) }

    // Swipe Brightness & Volume + Double-Tap Seek HUD states
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var activeGestureIsBrightness by remember { mutableStateOf(true) }
    var gestureHudFraction by remember { mutableFloatStateOf(0.5f) }
    var gestureHudVisible by remember { mutableStateOf(false) }
    var gestureHideTick by remember { mutableIntStateOf(0) }

    var doubleTapDeltaSec by remember { mutableIntStateOf(0) }
    var doubleTapLeftSide by remember { mutableStateOf(true) }
    var doubleTapVisible by remember { mutableStateOf(false) }
    var doubleTapTick by remember { mutableIntStateOf(0) }

    val session by prefs.anilistSession.collectAsState(initial = AniListSession())
    val token = session.token
    val scoreFormat by prefs.anilistScoreFormat.collectAsState(initial = "POINT_100")
    val reportProgress by prefs.reportProgress.collectAsState(initial = true)
    val rememberPosition by prefs.rememberPosition.collectAsState(initial = true)
    val autoSkipIntro by prefs.autoSkipIntro.collectAsState(initial = false)
    val swipeGesturesEnabled by prefs.swipeGesturesEnabled.collectAsState(initial = true)
    val subtitleBlackFill by prefs.subtitleBlackFill.collectAsState(initial = true)
    val subtitleSize by prefs.subtitleSize.collectAsState(initial = "MEDIUM")

    var advisoryMedia by remember(media.mediaId) { mutableStateOf(media) }
    var showOpeningAdvisory by remember { mutableStateOf(false) }
    var hasShownOpeningAdvisory by remember { mutableStateOf(false) }
    val openingAdvisory = remember(advisoryMedia.isAdult, advisoryMedia.genres, advisoryMedia.tags) {
        advisoryMedia.contentAdvisory
    }

    val latestRememberPosition by rememberUpdatedState(rememberPosition)
    val latestWatchedCallback by rememberUpdatedState(onEpisodeWatched)
    val latestReportProgress by rememberUpdatedState(reportProgress)
    val latestToken by rememberUpdatedState(token)
    val latestUsername by rememberUpdatedState(session.username)
    val latestScoreFormat by rememberUpdatedState(scoreFormat)
    val latestMedia by rememberUpdatedState(media)
    val latestSwipeEnabled by rememberUpdatedState(swipeGesturesEnabled)

    val persistenceScope = remember(context) {
        (context as? ComponentActivity)?.lifecycleScope
            ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    var isAttached by remember { mutableStateOf(true) }

    fun markWatched() {
        if (hasScrobbled80Percent) return
        hasScrobbled80Percent = true
        val auth = latestToken
        val activeUser = latestUsername
        val snapshot = latestMedia
        val shouldSyncRemote = latestReportProgress && auth.isNotBlank()
        val fmt = latestScoreFormat
        persistenceScope.launch {
            LocalLibraryStore.recordEpisodeWatchedAt80Percent(
                context = context.applicationContext,
                media = snapshot,
                episodeNum = episodeNum,
                isLoggedIn = auth.isNotBlank(),
                reportProgress = shouldSyncRemote,
                anilistToken = auth,
                anilistUsername = activeUser,
                scoreFormat = fmt
            )
            if (isAttached) {
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    latestWatchedCallback(episodeNum)
                }
            }
        }
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

    fun refreshSeekabilityAndDuration() {
        if (castController.isConnected) {
            val castDur = castController.durationMs() ?: 0L
            if (castDur > 0L) totalDuration.longValue = castDur
            isSeekable = castDur > 0L
            return
        }
        val rawDur = exoPlayer.duration
        val safeDur = if (rawDur == C.TIME_UNSET || rawDur < 0L) 0L else rawDur
        if (safeDur > 0L) totalDuration.longValue = safeDur
        isSeekable = safeDur > 0L && (exoPlayer.isCurrentMediaItemSeekable || isOfflinePlayback || selectedStream != null)
    }

    fun prepareVideo(videoUri: Uri, mimeType: String?) {
        currentLoadedUri = videoUri
        currentLoadedMime = mimeType
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
        val dataSource = DefaultDataSource.Factory(
            context,
            DefaultHttpDataSource.Factory().setReadTimeoutMs(STREAM_READ_TIMEOUT_MS)
        )
        val headers = sourceHeaders
        val sourceFactory = if (headers.isEmpty()) dataSource else ResolvingDataSource.Factory(dataSource) { request ->
            if (request.uri.toString() == externalSub?.url) request else request.withAdditionalHeaders(headers)
        }
        val source = DefaultMediaSourceFactory(sourceFactory).createMediaSource(builder.build())
        val startMs = if (resumeAfterSwitch != C.TIME_UNSET && resumeAfterSwitch > 0L) {
            resumeAfterSwitch
        } else {
            val saved = savedPositionMs ?: 0L
            if (saved >= 10_000L) saved else C.TIME_UNSET
        }
        exoPlayer.setMediaSource(source, startMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = if (castController.isConnected) false else playAfterSwitch
    }

    // Automatically push media to Chromecast whenever Cast connects or stream URL changes
    val isCasting = castController.isConnected
    LaunchedEffect(isCasting, currentLoadedUri, offlineCastUrl, selectedOpenSub) {
        val uri = currentLoadedUri ?: return@LaunchedEffect
        if (!isCasting) return@LaunchedEffect
        exoPlayer.pause()
        val castUrl = castController.resolveCastableUrl(uri, offlineCastUrl) ?: return@LaunchedEffect
        val startPos = currentPosition.longValue.takeIf { it > 0L }
            ?: exoPlayer.currentPosition.coerceAtLeast(0L)
        val subUrl = selectedOpenSub?.url
            ?: sourceSubtitles.firstOrNull { it.language.startsWith("en", true) }?.url
        val loaded = castController.loadMediaIfNeeded(
            castUrl = castUrl,
            mimeType = currentLoadedMime,
            animeTitle = media.title,
            episodeNum = episodeNum,
            episodeTitle = episodeTitle,
            coverUrl = media.coverUrl,
            subtitleUrl = subUrl,
            startPositionMs = startPos
        )
        if (loaded) {
            hasRenderedFrame = true
            isPlayerBuffering = false
            showControls = true
        }
    }

    // When disconnecting from Chromecast, resume local playback seamlessly at the TV's position
    var wasCasting by remember { mutableStateOf(false) }
    LaunchedEffect(isCasting) {
        if (isCasting) {
            wasCasting = true
        } else if (wasCasting) {
            wasCasting = false
            val resumeMs = currentPosition.longValue
            if (exoPlayer.currentMediaItem != null) {
                if (resumeMs > 0L) exoPlayer.seekTo(resumeMs)
                exoPlayer.play()
            }
        }
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
                showCastDialog = false
            }
        }
        compActivity?.addOnPictureInPictureModeChangedListener(pipConsumer)
        onDispose {
            isAttached = false
            compActivity?.removeOnPictureInPictureModeChangedListener(pipConsumer)
        }
    }

    LaunchedEffect(media.mediaId, episodeNum) {
        if (media.genres.isEmpty() && media.tags.isEmpty()) {
            launch {
                runCatching {
                    AniListClient.getMediaDetail(media.mediaId, token.ifBlank { null })
                }.onSuccess { detailed ->
                    advisoryMedia = detailed
                }
            }
        }
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
        if (!castController.isConnected) hasRenderedFrame = false
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
            .clearOverrides()
            .setPreferredAudioLanguage(if (sourceAudio == "DUB") "en" else "ja")
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !(subtitlesEnabled ?: (sourceAudio != "DUB")))
            .setPreferredTextLanguage(selectedOpenSub?.langCode ?: "en")
            .build()

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
                altTitles = listOfNotNull(media.titleEnglish, media.titleRomaji, media.title),
                isStandalone = media.isStandaloneFeature,
                isAdult = media.isAdult,
                genres = media.genres,
                isMusicOrConcert = media.isMusicOrConcert,
                trailerYoutubeId = media.trailerYoutubeId,
                externalLinks = media.externalLinks,
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
            exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString() != connection.url
        ) {
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

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!castController.isConnected) {
                wantsPlayback = exoPlayer.playWhenReady
            }
            refreshSeekabilityAndDuration()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            refreshSeekabilityAndDuration()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (!castController.isConnected) {
                isPlayerBuffering = state == Player.STATE_BUFFERING || (state == Player.STATE_IDLE && resolveError == null)
            }
            if (state == Player.STATE_READY) {
                refreshSeekabilityAndDuration()
                val duration = totalDuration.longValue
                savedPositionMs?.let { position ->
                    savedPositionMs = 0L
                    if (isSeekable && duration > 45_000L && position in 10_000..(duration - 25_000)) {
                        exoPlayer.seekTo(position)
                        currentPosition.longValue = position
                    }
                }
            } else if (state == Player.STATE_ENDED) {
                markWatched()
                if (hasNextEpisode) {
                    autoPlayCountdown = 5
                }
            }
        }

        override fun onRenderedFirstFrame() {
            refreshSeekabilityAndDuration()
            if (!hasRenderedFrame && !isInPipMode && !isScreenLocked) showControls = true
            hasRenderedFrame = true
        }

        override fun onTracksChanged(tracks: Tracks) {
            playbackTracks = tracks
            if (findAudioTrack(tracks, "DUB") != null) detectedDualOrDubInStreams = true

            // Ensure Dual-Audio MKVs honor the chosen SUB/DUB mode even if Japanese is flagged default in the container
            if (autoAppliedAudioAttempt != playbackAttempt && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                val desiredAudio = selectedStream?.audioType?.takeIf { it == "SUB" || it == "DUB" } ?: activeAudioMode
                if (applyPreferredAudioTrack(exoPlayer, desiredAudio)) {
                    autoAppliedAudioAttempt = playbackAttempt
                }
            }

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
        PlayerForegroundService.start(context, media.title, episodeNum)
        val listener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                latestListener.onPlayWhenReadyChanged(playWhenReady, reason)
            override fun onIsPlayingChanged(isPlaying: Boolean) =
                latestListener.onIsPlayingChanged(isPlaying)
            override fun onTimelineChanged(timeline: Timeline, reason: Int) =
                latestListener.onTimelineChanged(timeline, reason)
            override fun onPlaybackStateChanged(state: Int) =
                latestListener.onPlaybackStateChanged(state)
            override fun onRenderedFirstFrame() =
                latestListener.onRenderedFirstFrame()
            override fun onTracksChanged(tracks: Tracks) =
                latestListener.onTracksChanged(tracks)
            override fun onPlayerError(error: PlaybackException) =
                latestListener.onPlayerError(error)
        }
        exoPlayer.addListener(listener)

        onDispose {
            val ended = exoPlayer.playbackState == Player.STATE_ENDED
            val position = when {
                castController.isConnected -> castController.currentPositionMs() ?: currentPosition.longValue
                exoPlayer.currentMediaItem != null -> exoPlayer.currentPosition
                else -> resumeAfterSwitch
            }
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
        }
    }

    // Fast, smooth 150ms position & 80% watch scrobble loop
    LaunchedEffect(exoPlayer, skipIntervals, autoSkipIntro, isCasting) {
        var lastSavedPosition = 0L
        while (true) {
            if (castController.isConnected) {
                val cPos = castController.currentPositionMs() ?: currentPosition.longValue
                val cDur = castController.durationMs() ?: totalDuration.longValue
                currentPosition.longValue = cPos.coerceAtLeast(0L)
                bufferedPosition.longValue = cPos.coerceAtLeast(0L)
                if (cDur > 0L) {
                    totalDuration.longValue = cDur
                    isSeekable = true
                }
                wantsPlayback = castController.isRemotePlaying()
            } else {
                val rawPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                val rawBuf = exoPlayer.bufferedPosition.coerceAtLeast(0L)
                if (!isScrubbing) {
                    currentPosition.longValue = rawPos
                }
                bufferedPosition.longValue = rawBuf
                refreshSeekabilityAndDuration()
            }

            val position = currentPosition.longValue
            val duration = totalDuration.longValue

            val posSec = position / 1000.0
            val matchedSkip = skipIntervals.firstOrNull { posSec >= it.startTimeSeconds && posSec < it.endTimeSeconds - 1.0 }
            activeSkipInterval = matchedSkip

            if (!castController.isConnected && matchedSkip != null && autoSkipIntro && exoPlayer.isPlaying &&
                matchedSkip.skipType.equals("op", ignoreCase = true)
            ) {
                val target = (matchedSkip.endTimeSeconds * 1000).toLong()
                exoPlayer.seekTo(target)
                currentPosition.longValue = target
            }

            // Mark watched at 80% (even offline or in guest mode!)
            if (duration > 45_000L && !hasScrobbled80Percent) {
                val ratio = position.toDouble() / duration.toDouble()
                if (ratio >= 0.80) {
                    markWatched()
                }
            }

            if (latestRememberPosition && position > 5_000L && abs(position - lastSavedPosition) >= 10_000L) {
                prefs.savePosition(media.mediaId, episodeNum, position / 1000L)
                lastSavedPosition = position
            }

            delay(if ((showControls || isCasting) && hasRenderedFrame) 150 else 500)
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

    LaunchedEffect(showControls, wantsPlayback, showFullSettingsPage, showCastDialog, hasRenderedFrame, controlsInteraction, isScrubbing, isCasting) {
        if (showControls && wantsPlayback && hasRenderedFrame && !showFullSettingsPage && !showCastDialog && !isScrubbing && !isCasting) {
            delay(4000)
            showControls = false
        }
    }

    LaunchedEffect(isPlayerBuffering, hasRenderedFrame, isCasting) {
        showRebuffering = false
        if (isPlayerBuffering && hasRenderedFrame && !isCasting) {
            delay(250)
            showRebuffering = true
        }
    }

    LaunchedEffect(hasRenderedFrame, isCasting) {
        if ((hasRenderedFrame || isCasting) && !hasShownOpeningAdvisory) {
            hasShownOpeningAdvisory = true
            showOpeningAdvisory = true
            delay(3800)
            showOpeningAdvisory = false
        }
    }

    LaunchedEffect(gestureHideTick) {
        if (gestureHideTick > 0) {
            delay(900)
            gestureHudVisible = false
        }
    }

    LaunchedEffect(doubleTapTick) {
        if (doubleTapTick > 0) {
            delay(750)
            doubleTapVisible = false
            delay(200)
            if (!doubleTapVisible) doubleTapDeltaSec = 0
        }
    }

    BackHandler {
        when {
            showCastDialog -> showCastDialog = false
            showFullSettingsPage -> showFullSettingsPage = false
            else -> onBack()
        }
    }

    val canToggleChrome = (hasRenderedFrame || isCasting) && resolveError == null && !isInPipMode && !showFullSettingsPage
    val controlsShowing = canToggleChrome && showControls && !isScreenLocked
    val episodeLabel = if (episodeTitle.equals("Episode $episodeNum", true) || episodeTitle.isBlank()) {
        "Episode $episodeNum"
    } else {
        "Episode $episodeNum · $episodeTitle"
    }
    val sourceAudio = selectedStream?.audioType?.takeIf { it == "SUB" || it == "DUB" } ?: activeAudioMode
    val englishSubtitle = if (sourceAudio == "SUB" && subtitlesEnabled != false && selectedOpenSub == null &&
        playbackTracks.groups.none { it.type == C.TRACK_TYPE_TEXT }
    ) {
        openSubtitlesList.firstOrNull { it.langCode.equals("en", true) || it.langCode.equals("eng", true) }
    } else null

    fun performSeekTo(targetMs: Long) {
        val clamped = if (totalDuration.longValue > 0L) targetMs.coerceIn(0L, totalDuration.longValue) else targetMs.coerceAtLeast(0L)
        if (castController.isConnected) {
            castController.seekTo(clamped)
        } else {
            exoPlayer.seekTo(clamped)
        }
        currentPosition.longValue = clamped
        controlsInteraction++
    }

    fun jumpBy(offsetMs: Long) {
        if (!isSeekable) return
        val base = if (castController.isConnected) {
            castController.currentPositionMs() ?: currentPosition.longValue
        } else {
            exoPlayer.currentPosition
        }
        performSeekTo(base + offsetMs)
    }

    fun togglePlayPause() {
        if (castController.isConnected) {
            castController.togglePlayPause()
        } else if (exoPlayer.playbackState == Player.STATE_ENDED) {
            autoPlayCountdown = null
            exoPlayer.seekToDefaultPosition()
            exoPlayer.play()
        } else if (exoPlayer.playWhenReady) {
            exoPlayer.pause()
        } else {
            exoPlayer.play()
        }
        controlsInteraction++
    }

    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    fun readInitialBrightness(): Float {
        val activity = context as? Activity ?: return 0.5f
        val currentAttr = activity.window.attributes.screenBrightness
        if (currentAttr in 0.0f..1.0f) return currentAttr
        return runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
        }.getOrDefault(0.5f).coerceIn(0.05f, 1.0f)
    }

    fun applyBrightness(fraction: Float) {
        val activity = context as? Activity ?: return
        val clamped = fraction.coerceIn(0.02f, 1.0f)
        val lp = activity.window.attributes
        lp.screenBrightness = clamped
        activity.window.attributes = lp
        activeGestureIsBrightness = true
        gestureHudFraction = clamped
        gestureHudVisible = true
        gestureHideTick++
    }

    fun readInitialVolumeFraction(): Float {
        val am = audioManager ?: return 0.5f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        return cur.toFloat() / max.toFloat()
    }

    fun applyVolumeFraction(fraction: Float) {
        val am = audioManager ?: return
        val clamped = fraction.coerceIn(0f, 1f)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetStep = (clamped * max).roundToInt().coerceIn(0, max)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, targetStep, 0) }
        activeGestureIsBrightness = false
        gestureHudFraction = clamped
        gestureHudVisible = true
        gestureHideTick++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged { viewportSize = it }
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
                    subtitleView?.let { subView ->
                        subView.setBottomPaddingFraction(0.06f)
                        applySubtitleAppearance(subView, subtitleBlackFill, subtitleSize)
                    }
                }
            },
            update = { view ->
                if (view.resizeMode != videoResizeMode) view.resizeMode = videoResizeMode
                view.subtitleView?.let { subView ->
                    applySubtitleAppearance(subView, subtitleBlackFill, subtitleSize)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Casting backdrop when streaming to Chromecast
        if (isCasting && !isInPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE808090C)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    if (!media.coverUrl.isNullOrBlank()) {
                        CrossfadeImage(
                            model = media.coverUrl,
                            contentDescription = media.title,
                            modifier = Modifier
                                .width(96.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    } else {
                        Icon(Icons.Default.CastConnected, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(48.dp))
                    }
                    Text(
                        text = "Casting to ${castController.connectedDeviceName ?: "Chromecast"}",
                        color = CrunchyOrange,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "${media.title} • $episodeLabel",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Dedicated Touch Gesture Surface: Single-tap Chrome toggle, Double-tap -10s/+10s/Play,
        // and Left/Right Vertical Swipes for Brightness & Volume!
        if (canToggleChrome) {
            val edgeMarginPx = with(density) { 26.dp.toPx() }
            val topBottomMarginPx = with(density) { 42.dp.toPx() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isScreenLocked, isSeekable, isCasting) {
                        detectTapGestures(
                            onTap = {
                                showControls = !showControls
                                if (showControls) controlsInteraction++
                            },
                            onDoubleTap = { offset ->
                                if (isScreenLocked) {
                                    showControls = true
                                    return@detectTapGestures
                                }
                                val width = size.width.coerceAtLeast(1)
                                when {
                                    offset.x < width * 0.35f && isSeekable -> {
                                        jumpBy(-10_000L)
                                        doubleTapLeftSide = true
                                        doubleTapDeltaSec = if (doubleTapVisible && doubleTapDeltaSec < 0) doubleTapDeltaSec - 10 else -10
                                        doubleTapVisible = true
                                        doubleTapTick++
                                    }
                                    offset.x > width * 0.65f && isSeekable -> {
                                        jumpBy(10_000L)
                                        doubleTapLeftSide = false
                                        doubleTapDeltaSec = if (doubleTapVisible && doubleTapDeltaSec > 0) doubleTapDeltaSec + 10 else 10
                                        doubleTapVisible = true
                                        doubleTapTick++
                                    }
                                    else -> togglePlayPause()
                                }
                            }
                        )
                    }
                    .pointerInput(isScreenLocked, swipeGesturesEnabled, isCasting) {
                        if (isScreenLocked || !latestSwipeEnabled || isCasting) return@pointerInput
                        var dragIsBrightness = true
                        var dragFraction = 0.5f
                        var validGestureZone = false

                        detectVerticalDragGestures(
                            onDragStart = { start ->
                                val w = size.width.toFloat().coerceAtLeast(1f)
                                val h = size.height.toFloat().coerceAtLeast(1f)
                                validGestureZone = start.x in edgeMarginPx..(w - edgeMarginPx) &&
                                    start.y in topBottomMarginPx..(h - topBottomMarginPx)
                                if (!validGestureZone) return@detectVerticalDragGestures
                                dragIsBrightness = start.x < (w * 0.5f)
                                dragFraction = if (dragIsBrightness) readInitialBrightness() else readInitialVolumeFraction()
                            },
                            onVerticalDrag = { change, dragAmount ->
                                if (!validGestureZone) return@detectVerticalDragGestures
                                change.consume()
                                val gestureRangePx = (size.height * 0.68f).coerceAtLeast(180f)
                                val delta = -dragAmount / gestureRangePx
                                dragFraction = (dragFraction + delta).coerceIn(if (dragIsBrightness) 0.02f else 0f, 1f)
                                if (dragIsBrightness) applyBrightness(dragFraction) else applyVolumeFraction(dragFraction)
                            },
                            onDragEnd = {
                                if (validGestureZone) gestureHideTick++
                                validGestureZone = false
                            },
                            onDragCancel = {
                                if (validGestureZone) gestureHideTick++
                                validGestureZone = false
                            }
                        )
                    }
            )
        }

        val next = autoPlayCountdown
        val jumpToNext: () -> Unit = {
            autoPlayCountdown = null
            onSwitchEpisode(episodeNum + 1)
        }

        // Loading & Error States
        AnimatedVisibility(
            visible = !isInPipMode && !showFullSettingsPage && resolveError != null,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(150))
        ) {
            PlayerProblem(
                title = media.title,
                episode = episodeLabel,
                message = resolveError.orEmpty(),
                retryLabel = when {
                    isOfflinePlayback && !offlineOnly -> "Try Streaming Instead"
                    rankedStreams.size > 1 -> "Try Another Source"
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
        }

        AnimatedVisibility(
            visible = !isInPipMode && !showFullSettingsPage && resolveError == null && !hasRenderedFrame && !isCasting,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220))
        ) {
            PlayerLoading(
                title = media.title,
                episode = episodeLabel,
                lookingForSource = waitingForAlternative != null || (selectedStream == null && isResolvingStreams),
                torrentRequest = expectedTorrentRequest,
                serviceStatus = serviceStatus,
                onBack = onBack
            )
        }

        // Smooth Animated Main Transport & Seekbar Chrome (never unmounts abruptly mid-scrub!)
        AnimatedVisibility(
            visible = controlsShowing || (isCasting && !isInPipMode && !showFullSettingsPage && !isScreenLocked),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180))
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xD8000000), Color(0x28000000), Color(0xEB000000))
                        )
                    )
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                PlayerHeader(
                    title = media.title,
                    episode = episodeLabel,
                    onBack = onBack,
                    isCasting = isCasting,
                    hasCastRoutes = castController.routes.isNotEmpty(),
                    onCastClick = {
                        castController.refreshRoutes()
                        showCastDialog = true
                        controlsInteraction++
                    },
                    onLockClick = if (!isCasting) {
                        {
                            isScreenLocked = true
                            showControls = false
                        }
                    } else null,
                    onPipClick = if (!isCasting && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        { enterPip() }
                    } else null,
                    onSettings = { showFullSettingsPage = true }
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (next != null) {
                        PlayerUpNext(episodeNum + 1, next, jumpToNext, { autoPlayCountdown = null })
                    } else {
                        val spacing = if (maxWidth < 380.dp) 14.dp else 24.dp
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(spacing),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hasPrevEpisode) {
                                PlayerTransportButton(
                                    icon = Icons.Default.SkipPrevious,
                                    label = "Previous episode",
                                    onClick = { onSwitchEpisode(episodeNum - 1) }
                                )
                            }
                            PlayerTransportButton(
                                icon = Icons.Default.Replay10,
                                label = "Rewind 10 seconds",
                                onClick = { jumpBy(-10_000L) },
                                enabled = isSeekable
                            )
                            val canPause = wantsPlayback && exoPlayer.playbackState != Player.STATE_ENDED
                            PlayerTransportButton(
                                icon = if (canPause) Icons.Default.Pause else Icons.Default.PlayArrow,
                                label = if (canPause) "Pause" else "Play",
                                onClick = ::togglePlayPause,
                                primary = true
                            )
                            PlayerTransportButton(
                                icon = Icons.Default.Forward10,
                                label = "Forward 10 seconds",
                                onClick = { jumpBy(10_000L) },
                                enabled = isSeekable
                            )
                            if (hasNextEpisode) {
                                PlayerTransportButton(
                                    icon = Icons.Default.SkipNext,
                                    label = "Next episode",
                                    onClick = { onSwitchEpisode(episodeNum + 1) }
                                )
                            }
                        }
                    }
                }

                if (next == null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (englishSubtitle != null && !showRebuffering) {
                                Surface(
                                    onClick = { useOpenSubtitle(englishSubtitle) },
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xCC16181D),
                                    border = BorderStroke(1.dp, CrunchyOrange)
                                ) {
                                    Text(
                                        text = "Load English Subtitles",
                                        color = CrunchyOrange,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.5.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            } else if (showRebuffering || isOfflinePlayback) {
                                Text(
                                    text = if (showRebuffering) "Buffering…" else "Offline Vault",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (showRebuffering) CrunchyOrange else CrunchyTeal
                                )
                            }
                        }

                        val interval = activeSkipInterval
                        if (interval != null && isSeekable) {
                            CrunchySkipButton(
                                label = interval.skipLabel,
                                onClick = { performSeekTo((interval.endTimeSeconds * 1000).toLong()) }
                            )
                        } else if (isSeekable) {
                            Surface(
                                onClick = { jumpBy(85_000L) },
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0x9916181D),
                                border = BorderStroke(1.dp, CrunchyBorder)
                            ) {
                                Text(
                                    text = "+85s OP",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                PlayerTimeline(
                    position = currentPosition,
                    buffered = bufferedPosition,
                    duration = totalDuration,
                    isSeekable = isSeekable,
                    label = buildString {
                        append(activeQuality)
                        append(" · ")
                        append(if (sourceAudio == "DUB") "Dub" else "Sub")
                        if (playbackSpeed != 1.0f) append(" · ${playbackSpeed}×")
                    },
                    skipIntervals = skipIntervals,
                    onScrub = { scrubbing ->
                        isScrubbing = scrubbing
                        if (!scrubbing) controlsInteraction++
                    },
                    onSeek = ::performSeekTo,
                    onOpenSettings = { showFullSettingsPage = true }
                )
            }
        }

        // Ambient Overlays when Main Controls are Hidden (Rebuffering, Screen Unlock, Autoplay, Skip Intro)
        if (!isInPipMode && !showFullSettingsPage && hasRenderedFrame && resolveError == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                if (showRebuffering && !controlsShowing) {
                    CircularProgressIndicator(
                        color = CrunchyOrange,
                        strokeWidth = 3.dp,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(34.dp)
                    )
                }

                AnimatedVisibility(
                    visible = isScreenLocked && showControls,
                    enter = fadeIn(tween(150)) + scaleIn(tween(150)),
                    exit = fadeOut(tween(150)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    Surface(
                        onClick = {
                            isScreenLocked = false
                            showControls = true
                            controlsInteraction++
                        },
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0xE014151A),
                        border = BorderStroke(1.dp, CrunchyOrange)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                            Text("Tap to Unlock", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = !controlsShowing && !isScreenLocked && next != null,
                    enter = slideInHorizontally(tween(220)) { it / 2 } + fadeIn(tween(180)),
                    exit = slideOutHorizontally(tween(180)) { it / 2 } + fadeOut(tween(150)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(18.dp)
                ) {
                    if (next != null) {
                        PlayerUpNext(episodeNum + 1, next, jumpToNext, { autoPlayCountdown = null })
                    }
                }

                val floatingSkip = activeSkipInterval
                AnimatedVisibility(
                    visible = !controlsShowing && !isScreenLocked && next == null && isSeekable && !isPlayerBuffering && floatingSkip != null,
                    enter = slideInHorizontally(tween(220)) { it / 2 } + fadeIn(tween(180)),
                    exit = slideOutHorizontally(tween(180)) { it / 2 } + fadeOut(tween(150)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 22.dp, bottom = 24.dp)
                ) {
                    if (floatingSkip != null) {
                        CrunchySkipButton(
                            label = floatingSkip.skipLabel,
                            onClick = { performSeekTo((floatingSkip.endTimeSeconds * 1000).toLong()) }
                        )
                    }
                }
            }
        }

        // Netflix / Crunchyroll Opening Content Warning Toast (first ~4 seconds of video playback!)
        val advisoryTopPad by animateDpAsState(
            targetValue = if (controlsShowing) 64.dp else 20.dp,
            animationSpec = tween(200),
            label = "advisory_top_pad"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopStart
        ) {
            PlayerOpeningAdvisory(
                advisory = openingAdvisory,
                visible = showOpeningAdvisory && !isInPipMode && !showFullSettingsPage && resolveError == null,
                modifier = Modifier.padding(start = 20.dp, top = advisoryTopPad)
            )
        }

        // Brightness / Volume Swipe HUD Pill at Top Center
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter
        ) {
            PlayerGestureHud(
                isBrightness = activeGestureIsBrightness,
                fraction = gestureHudFraction,
                visible = gestureHudVisible && !isInPipMode,
                modifier = Modifier.padding(top = 18.dp)
            )
        }

        // Double-Tap -10s / +10s Seek Feedback Pill on Left / Right
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp),
            contentAlignment = if (doubleTapLeftSide) Alignment.CenterStart else Alignment.CenterEnd
        ) {
            DoubleTapSeekBadge(
                deltaSeconds = doubleTapDeltaSec,
                visible = doubleTapVisible && !isInPipMode
            )
        }

        // Sleek Translucent Playback Settings Sheet (keeps live video visible behind!)
        AnimatedVisibility(
            visible = showFullSettingsPage && !isInPipMode,
            enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 4 },
            exit = fadeOut(tween(160)) + slideOutHorizontally(tween(180)) { it / 4 }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showFullSettingsPage = false },
                contentAlignment = Alignment.CenterEnd
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = 460.dp)
                        .fillMaxWidth(0.88f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {},
                    color = Color(0xF50E0F13),
                    border = BorderStroke(1.dp, CrunchyBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CrunchySurface)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(onClick = { showFullSettingsPage = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to video", tint = Color.White)
                            }
                            Text(
                                text = "Playback Settings",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 20.dp),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 36.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            item(key = "section_playback") {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Playback & Gestures", color = CrunchyOrange, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                            val sel = playbackSpeed == spd
                                            Surface(
                                                onClick = {
                                                    playbackSpeed = spd
                                                    exoPlayer.playbackParameters = PlaybackParameters(spd)
                                                },
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (sel) CrunchyOrange else CrunchyCardBg,
                                                border = BorderStroke(1.dp, if (sel) CrunchyOrange else CrunchyBorder),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = "${spd}×",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = if (sel) Color.Black else Color.White
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit Screen",
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Crop / Zoom",
                                            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch"
                                        ).forEach { (mode, label) ->
                                            val sel = videoResizeMode == mode
                                            Surface(
                                                onClick = { videoResizeMode = mode },
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (sel) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                                border = BorderStroke(1.dp, if (sel) CrunchyOrange else CrunchyBorder),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = label,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (sel) CrunchyOrange else Color.White
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    GlassFeatureRow(
                                        label = "Swipe up/down for brightness & volume",
                                        subtitle = "Left side adjusts brightness, right side adjusts volume",
                                        checked = swipeGesturesEnabled,
                                        onCheckedChange = { enabled ->
                                            scope.launch { prefs.setSwipeGesturesEnabled(enabled) }
                                        }
                                    )
                                }
                            }

                            item(key = "section_audio") {
                                val audioGroups = playbackTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Audio Track", color = CrunchyOrange, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

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
                                                        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                                        if (isSelected) {
                                                            Icon(Icons.Default.Check, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        val modes = if (isOfflinePlayback) {
                                            listOf(activeAudioMode to if (activeAudioMode == "DUB") "Dubbed Audio" else "Japanese (Original Audio)")
                                        } else if (effectiveHasDub) {
                                            listOf("SUB" to "Japanese (Sub)", "DUB" to "English Dub")
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
                                                    Box(modifier = Modifier.padding(vertical = 13.dp, horizontal = 10.dp), contentAlignment = Alignment.Center) {
                                                        Text(label, color = if (selected) CrunchyOrange else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            item(key = "section_subs") {
                                val subGroups = playbackTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                                val subsCurrentlyOff = subtitlesEnabled == false ||
                                    exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Subtitles", color = CrunchyOrange, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)

                                    GlassFeatureRow(
                                        label = "Black fill behind subtitles",
                                        subtitle = "Adds Crunchyroll-style dark background box behind subtitle lines",
                                        checked = subtitleBlackFill,
                                        onCheckedChange = { enabled ->
                                            scope.launch { prefs.setSubtitleBlackFill(enabled) }
                                        }
                                    )

                                    Text("Subtitle size", color = MaruTextStrong, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(
                                            "SMALL" to "Small",
                                            "MEDIUM" to "Normal",
                                            "LARGE" to "Large",
                                            "EXTRA_LARGE" to "XL"
                                        ).forEach { (sizeKey, sizeLabel) ->
                                            val sel = subtitleSize == sizeKey
                                            Surface(
                                                onClick = {
                                                    scope.launch { prefs.setSubtitleSize(sizeKey) }
                                                },
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (sel) CrunchyOrange else CrunchyCardBg,
                                                border = BorderStroke(1.dp, if (sel) CrunchyOrange else CrunchyBorder),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = sizeLabel,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = if (sel) Color.Black else Color.White,
                                                        maxLines = 1,
                                                        softWrap = false
                                                    )
                                                }
                                            }
                                        }
                                    }

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
                                        color = if (subsCurrentlyOff) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                        border = BorderStroke(1.dp, if (subsCurrentlyOff) CrunchyOrange else CrunchyBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Subtitles Off", color = Color.White, fontSize = 14.sp, fontWeight = if (subsCurrentlyOff) FontWeight.Bold else FontWeight.Normal)
                                            if (subsCurrentlyOff) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }

                                    subGroups.forEach { group ->
                                        for (i in 0 until group.length) {
                                            val format = group.getTrackFormat(i)
                                            val isSelected = !subsCurrentlyOff && group.isTrackSelected(i)
                                            val isAss = format.sampleMimeType == MimeTypes.TEXT_SSA ||
                                                (format.codecs?.contains("ssa", true) == true) ||
                                                (format.codecs?.contains("ass", true) == true)
                                            val baseName = formatSubtitleTrackName(format.label, format.language, i)
                                            val trackName = if (isAss) "$baseName • Styled ASS" else baseName

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
                                                    Text(trackName, color = Color.White, fontSize = 13.5.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
                                                    if (isSelected) {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (openSubtitlesList.isNotEmpty() && exoPlayer.currentMediaItem != null) {
                                        openSubtitlesList.take(10).forEach { extSub ->
                                            val isSelected = selectedOpenSub?.url == extSub.url && !subsCurrentlyOff
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
                                                    Text("${extSub.langName} (${extSub.format})", color = Color.White, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                                                    if (isSelected) {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (!isOfflinePlayback && rankedStreams.size > 1) {
                                item(key = "section_quality_header") {
                                    Text("Video Sources", color = CrunchyOrange, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
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
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Source ${idx + 1} • ${stream.quality}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
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

        if (showCastDialog) {
            CastDevicePickerDialog(
                routes = castController.routes,
                connectedDeviceName = castController.connectedDeviceName,
                isConnecting = castController.isConnecting,
                onSelectRoute = { castController.selectRoute(it) },
                onDisconnect = { castController.disconnect() },
                onDismiss = { showCastDialog = false }
            )
        }
    }
}

@OptIn(UnstableApi::class)
private fun applySubtitleAppearance(
    subtitleView: SubtitleView,
    blackFill: Boolean,
    sizeKey: String
) {
    val scale = when (sizeKey) {
        "SMALL" -> 0.80f
        "LARGE" -> 1.25f
        "EXTRA_LARGE" -> 1.50f
        else -> 1.00f
    }
    subtitleView.setApplyEmbeddedFontSizes(false)
    subtitleView.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
    if (blackFill) {
        subtitleView.setApplyEmbeddedStyles(false)
        subtitleView.setStyle(
            CaptionStyleCompat(
                android.graphics.Color.WHITE,
                android.graphics.Color.argb(204, 0, 0, 0),
                android.graphics.Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_NONE,
                android.graphics.Color.BLACK,
                Typeface.DEFAULT_BOLD
            )
        )
    } else {
        subtitleView.setApplyEmbeddedStyles(true)
        subtitleView.setStyle(
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
    val cleanLang = when (lang?.lowercase()?.substringBefore('-')?.substringBefore('_')) {
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
    val cleanLang = when (lang?.lowercase()?.substringBefore('-')?.substringBefore('_')) {
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
