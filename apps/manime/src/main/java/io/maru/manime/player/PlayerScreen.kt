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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import io.maru.manime.*
import io.maru.manime.extensions.AniSkipInterval
import io.maru.manime.extensions.CrunchyrollStreamEngine
import io.maru.manime.extensions.ExternalSubtitleItem
import io.maru.manime.extensions.StreamLink
import io.maru.manime.torrent.TorrentStreamService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var detectedDualOrDubInStreams by remember { mutableStateOf(hasEnglishDub) }
    val effectiveHasDub = hasEnglishDub || detectedDualOrDubInStreams

    var activeAudioMode by remember(initialAudioMode, effectiveHasDub) {
        mutableStateOf(if (!effectiveHasDub) "SUB" else initialAudioMode)
    }
    var activeQuality by remember { mutableStateOf("1080p") }
    var rankedStreams by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var currentStreamIndex by remember { mutableIntStateOf(0) }
    var isResolvingStreams by remember { mutableStateOf(true) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    var isOfflinePlayback by remember { mutableStateOf(false) }

    var openSubtitlesList by remember { mutableStateOf<List<ExternalSubtitleItem>>(emptyList()) }
    var selectedOpenSub by remember { mutableStateOf<ExternalSubtitleItem?>(null) }

    val serviceStatus by TorrentStreamService.streamStatus.collectAsState()

    var isPlaying by remember { mutableStateOf(true) }
    var isPlayerBuffering by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(false) }
    var isInPipMode by remember { mutableStateOf(false) }
    var isScreenLocked by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var videoResizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var autoPlayCountdown by remember { mutableStateOf<Int?>(null) }
    var hasScrobbled85Percent by remember(media.mediaId, episodeNum) { mutableStateOf(false) }
    var hasRestoredPosition by remember(media.mediaId, episodeNum) { mutableStateOf(false) }

    var skipIntervals by remember { mutableStateOf<List<AniSkipInterval>>(emptyList()) }
    var activeSkipInterval by remember { mutableStateOf<AniSkipInterval?>(null) }

    // Full-page Settings screen (Audio, Subtitles & Quality) instead of draggable BottomSheet!
    var showFullSettingsPage by remember { mutableStateOf(false) }

    val token by prefs.anilistToken.collectAsState(initial = "")
    val reportProgress by prefs.reportProgress.collectAsState(initial = true)
    val rememberPosition by prefs.rememberPosition.collectAsState(initial = true)
    val autoSkipIntro by prefs.autoSkipIntro.collectAsState(initial = false)

    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            playWhenReady = true
        }
    }

    fun buildMediaItemWithSubtitles(videoUri: Uri, mimeType: String?, externalSubs: List<ExternalSubtitleItem>): MediaItem {
        val subConfigs = externalSubs.mapIndexed { idx, sub ->
            val subMime = when (sub.format) {
                "ASS" -> MimeTypes.TEXT_SSA
                "VTT" -> MimeTypes.TEXT_VTT
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
                .setMimeType(subMime)
                .setLanguage(sub.langCode)
                .setLabel(sub.langName)
                .setId("ext_sub_${sub.id}_$idx")
                .build()
        }
        val builder = MediaItem.Builder().setUri(videoUri)
        if (mimeType != null) builder.setMimeType(mimeType)
        if (subConfigs.isNotEmpty()) builder.setSubtitleConfigurations(subConfigs)
        return builder.build()
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
            compActivity?.removeOnPictureInPictureModeChangedListener(pipConsumer)
        }
    }

    LaunchedEffect(media.mediaId, episodeNum) {
        launch {
            try {
                val mappings = CrunchyrollStreamEngine.getAnimeMappings(media.mediaId, media.title)
                skipIntervals = CrunchyrollStreamEngine.getAniSkipIntervals(mappings.malId, episodeNum)
            } catch (_: Exception) {}
        }
        launch {
            try {
                openSubtitlesList = CrunchyrollStreamEngine.fetchOpenSubtitles(
                    anilistId = media.mediaId,
                    animeTitle = media.title,
                    episodeNum = episodeNum
                )
            } catch (_: Exception) {}
        }
    }

    fun playStreamAtIndex(index: Int, streams: List<StreamLink>) {
        if (index !in streams.indices) {
            resolveError = "No working stream available for Episode $episodeNum."
            return
        }
        currentStreamIndex = index
        val chosen = streams[index]
        activeQuality = chosen.quality
        resolveError = null
        isPlayerBuffering = true

        if (chosen.isTorrent) {
            TorrentStreamService.startStream(
                context = context,
                magnetUri = chosen.url,
                fileIdx = chosen.fileIdx,
                animeTitle = media.title,
                episodeNum = episodeNum
            )
        } else {
            val item = buildMediaItemWithSubtitles(Uri.parse(chosen.url), null, openSubtitlesList)
            exoPlayer.setMediaItem(item)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    LaunchedEffect(media.mediaId, episodeNum, activeAudioMode) {
        isResolvingStreams = true
        isPlayerBuffering = true
        resolveError = null

        val offlineEntry = TorrentStreamService.getCompletedOfflineEpisode(context, media.mediaId, episodeNum)
        if (offlineEntry != null) {
            isOfflinePlayback = true
            isResolvingStreams = false
            val file = File(offlineEntry.vaultFilePath)
            val item = buildMediaItemWithSubtitles(Uri.fromFile(file), MimeTypes.APPLICATION_MATROSKA, openSubtitlesList)
            exoPlayer.setMediaItem(item)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            return@LaunchedEffect
        }

        isOfflinePlayback = false
        val prefQual = prefs.preferredQuality.first()
        val streams = CrunchyrollStreamEngine.resolveRankedStreams(
            anilistId = media.mediaId,
            animeTitle = media.title,
            episodeNum = episodeNum,
            preferredAudio = activeAudioMode,
            preferredQuality = prefQual
        )
        rankedStreams = streams
        if (streams.any { it.audioType == "DUAL_AUDIO" || it.audioType == "DUB" }) {
            detectedDualOrDubInStreams = true
        }
        isResolvingStreams = false

        if (streams.isEmpty()) {
            isPlayerBuffering = false
            resolveError = "Episode $episodeNum is not available yet."
        } else {
            playStreamAtIndex(0, streams)
        }
    }

    LaunchedEffect(serviceStatus.isReady, serviceStatus.streamUrl, serviceStatus.error) {
        if (!isOfflinePlayback) {
            val err = serviceStatus.error
            if (err != null) {
                if (currentStreamIndex + 1 < rankedStreams.size) {
                    playStreamAtIndex(currentStreamIndex + 1, rankedStreams)
                } else {
                    isPlayerBuffering = false
                    resolveError = err
                }
            } else if (serviceStatus.isReady && !serviceStatus.streamUrl.isNullOrBlank()) {
                val url = serviceStatus.streamUrl!!
                val mime = if (url.endsWith(".mp4")) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_MATROSKA
                val item = buildMediaItemWithSubtitles(Uri.parse(url), mime, openSubtitlesList)
                exoPlayer.setMediaItem(item)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
            }
        }
    }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val originalOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        if (activity != null) {
            val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        PlayerForegroundService.start(context, media.title, episodeNum)

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                isPlayerBuffering = (state == Player.STATE_BUFFERING || state == Player.STATE_IDLE)
                if (state == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0L)
                    // Check if embedded MKV has > 1 audio tracks (Dual Audio!)
                    val audioTrackCount = exoPlayer.currentTracks.groups
                        .filter { it.type == C.TRACK_TYPE_AUDIO }
                        .sumOf { it.length }
                    if (audioTrackCount > 1) {
                        detectedDualOrDubInStreams = true
                    }

                    applyPreferredAudioTrack(exoPlayer, activeAudioMode)

                    if (activeAudioMode == "SUB") {
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setPreferredTextLanguage("en")
                            .build()
                    }

                    if (!hasRestoredPosition && rememberPosition) {
                        hasRestoredPosition = true
                        scope.launch {
                            val savedSec = prefs.getPositionFlow(media.mediaId, episodeNum).first()
                            if (savedSec in 10..((totalDuration / 1000) - 30).coerceAtLeast(10)) {
                                exoPlayer.seekTo(savedSec * 1000L)
                            }
                        }
                    }
                } else if (state == Player.STATE_ENDED) {
                    onEpisodeWatched(episodeNum)
                    val maxEps = media.latestAiredEpisode ?: media.episodes ?: 9999
                    if (episodeNum < maxEps) {
                        autoPlayCountdown = 5
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isOfflinePlayback && currentStreamIndex + 1 < rankedStreams.size) {
                    playStreamAtIndex(currentStreamIndex + 1, rankedStreams)
                } else {
                    isPlayerBuffering = false
                    resolveError = "Playback error (${error.errorCodeName}). Tap Try Next Server."
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            val posSec = (exoPlayer.currentPosition / 1000L).coerceAtLeast(0L)
            scope.launch {
                if (rememberPosition && posSec > 5) {
                    prefs.savePosition(media.mediaId, episodeNum, posSec)
                }
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            PlayerForegroundService.stop(context)
            TorrentStreamService.stopStream(context)

            activity?.requestedOrientation = originalOrientation
            if (activity != null) {
                val insetsController = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(exoPlayer, skipIntervals, autoSkipIntro) {
        while (true) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            totalDuration = exoPlayer.duration.coerceAtLeast(0L)

            val posSec = currentPosition / 1000.0
            val matchedSkip = skipIntervals.firstOrNull { posSec >= it.startTimeSeconds && posSec < it.endTimeSeconds - 1.0 }
            activeSkipInterval = matchedSkip

            if (matchedSkip != null && autoSkipIntro && matchedSkip.skipType.equals("op", ignoreCase = true)) {
                exoPlayer.seekTo((matchedSkip.endTimeSeconds * 1000).toLong())
            }

            if (totalDuration > 60_000L && !hasScrobbled85Percent) {
                val ratio = currentPosition.toDouble() / totalDuration.toDouble()
                if (ratio >= 0.85) {
                    hasScrobbled85Percent = true
                    onEpisodeWatched(episodeNum)
                    if (reportProgress && token.isNotEmpty() && episodeNum > media.progress) {
                        scope.launch {
                            try {
                                AniListClient.saveEntry(
                                    mediaId = media.mediaId,
                                    status = "CURRENT",
                                    progress = episodeNum,
                                    score = null,
                                    notes = null,
                                    isPrivate = false,
                                    token = token
                                )
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            delay(750)
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

    LaunchedEffect(showControls, isPlaying, showFullSettingsPage) {
        if (showControls && isPlaying && !showFullSettingsPage) {
            delay(4000)
            showControls = false
        }
    }

    BackHandler {
        if (showFullSettingsPage) {
            showFullSettingsPage = false
        } else {
            onBack()
        }
    }

    val isInitialPrebuffering = isResolvingStreams || (!isOfflinePlayback && !serviceStatus.isReady && resolveError == null)
    val showVisibleBuffering = (isInitialPrebuffering || isPlayerBuffering) && resolveError == null && !isInPipMode && !showFullSettingsPage

    // Strict rule: When media controls are visible, hide floating UI behind them so nothing overlaps!
    val controlsShowing = showControls && !isInPipMode && !isScreenLocked && !showFullSettingsPage && !isInitialPrebuffering

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isInPipMode && !showFullSettingsPage) {
                    showControls = !showControls
                }
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
                view.resizeMode = videoResizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // 1. Live Buffering Overlay (Always visible while connecting or mid-stream buffering, hidden when controls overlay is open unless initial prebuffering)
        if (showVisibleBuffering && (isInitialPrebuffering || !controlsShowing)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isInitialPrebuffering) Color(0xCC000000) else Color(0x55000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    CircularProgressIndicator(
                        color = CrunchyOrange,
                        strokeWidth = 3.5.dp,
                        modifier = Modifier.size(48.dp)
                    )
                    val speedText = if (serviceStatus.downloadSpeedKbps >= 1024) {
                        String.format("%.1f MB/s", serviceStatus.downloadSpeedKbps / 1024.0)
                    } else if (serviceStatus.downloadSpeedKbps > 0) {
                        "${serviceStatus.downloadSpeedKbps} KB/s"
                    } else ""

                    Text(
                        text = when {
                            isResolvingStreams -> "Finding best stream for Episode $episodeNum..."
                            isInitialPrebuffering -> serviceStatus.statusMessage
                            speedText.isNotBlank() -> "Buffering... ($speedText)"
                            else -> "Buffering..."
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.5.sp
                    )

                    if (isInitialPrebuffering) {
                        LinearProgressIndicator(
                            progress = { (serviceStatus.progressPercent / 100f).coerceIn(0.05f, 1f) },
                            color = CrunchyOrange,
                            trackColor = Color(0x33FFFFFF),
                            modifier = Modifier
                                .width(220.dp)
                                .height(4.dp)
                        )
                        Text(
                            text = "${media.title} • ${if (activeAudioMode == "DUB") "Dub" else "Subtitled"}",
                            color = MaruTextMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 2. Clean Error Overlay
        if (resolveError != null && !isInPipMode && !showFullSettingsPage) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xEE000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(44.dp))
                    Text(
                        text = resolveError ?: "Playback unavailable",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                if (currentStreamIndex + 1 < rankedStreams.size) {
                                    playStreamAtIndex(currentStreamIndex + 1, rankedStreams)
                                } else {
                                    playStreamAtIndex(0, rankedStreams)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("TRY NEXT SERVER", color = Color.Black, fontWeight = FontWeight.ExtraBold)
                        }
                        OutlinedButton(
                            onClick = onBack,
                            border = BorderStroke(1.dp, CrunchyBorder),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("BACK", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 3. Floating AniSkip "SKIP INTRO" / "SKIP ENDING" Button (HIDDEN when media controls are visible!)
        AnimatedVisibility(
            visible = activeSkipInterval != null && !controlsShowing && !isInPipMode && !showFullSettingsPage && !isInitialPrebuffering,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 2 }),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 32.dp)
        ) {
            val interval = activeSkipInterval
            if (interval != null) {
                Surface(
                    onClick = {
                        exoPlayer.seekTo((interval.endTimeSeconds * 1000).toLong())
                    },
                    shape = RoundedCornerShape(4.dp),
                    color = Color(0xEE141519),
                    border = BorderStroke(1.5.dp, CrunchyOrange)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.FastForward, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                        Text(
                            text = if (interval.skipType.equals("ed", ignoreCase = true)) "SKIP ENDING" else "SKIP INTRO",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                            letterSpacing = 0.8.sp
                        )
                    }
                }
            }
        }

        // 4. Up Next Auto-Play Card (HIDDEN when media controls are visible!)
        AnimatedVisibility(
            visible = autoPlayCountdown != null && !controlsShowing && !isInPipMode && !showFullSettingsPage,
            enter = fadeIn() + scaleIn(initialScale = 0.95f),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 32.dp)
        ) {
            val secs = autoPlayCountdown ?: 0
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xF0141519),
                border = BorderStroke(1.5.dp, CrunchyOrange)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column {
                        Text(
                            text = "UP NEXT IN ${secs}S",
                            color = CrunchyOrange,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = "Episode ${episodeNum + 1}",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Button(
                        onClick = {
                            autoPlayCountdown = null
                            onSwitchEpisode(episodeNum + 1)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("PLAY NOW", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp)
                    }
                    IconButton(
                        onClick = { autoPlayCountdown = null },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = MaruTextMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // 5. Screen Lock Unlock Pill
        if (showControls && isScreenLocked && !isInPipMode && !showFullSettingsPage) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Surface(
                    onClick = { isScreenLocked = false },
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xDD141519),
                    border = BorderStroke(1.5.dp, CrunchyOrange)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = "Unlock", tint = CrunchyOrange, modifier = Modifier.size(16.dp))
                        Text("TAP TO UNLOCK", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
                    }
                }
            }
        }

        // 6. Custom Crunchyroll Media Controls (Clean single layer, nothing overlaps behind it!)
        AnimatedVisibility(
            visible = controlsShowing,
            enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
            exit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x88000000))
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xDD000000), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = media.title,
                            color = CrunchyOrange,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "E$episodeNum - $episodeTitle",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Playback Speed Pill
                    Surface(
                        onClick = {
                            val nextSpeed = when (playbackSpeed) {
                                1.0f -> 1.25f
                                1.25f -> 1.5f
                                1.5f -> 2.0f
                                2.0f -> 0.75f
                                else -> 1.0f
                            }
                            playbackSpeed = nextSpeed
                            exoPlayer.playbackParameters = PlaybackParameters(nextSpeed)
                        },
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xCC141519),
                        border = BorderStroke(1.dp, CrunchyBorder),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = "${playbackSpeed}x",
                            color = if (playbackSpeed != 1.0f) CrunchyOrange else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        )
                    }

                    // Aspect Ratio Toggle
                    IconButton(
                        onClick = {
                            videoResizeMode = when (videoResizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        }
                    ) {
                        Icon(
                            Icons.Default.AspectRatio,
                            contentDescription = "Aspect Ratio",
                            tint = if (videoResizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT) CrunchyOrange else Color.White
                        )
                    }

                    // Touch Lock
                    IconButton(onClick = { isScreenLocked = true }) {
                        Icon(Icons.Default.LockOpen, contentDescription = "Lock Controls", tint = Color.White)
                    }

                    // Picture-in-Picture
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        IconButton(onClick = { enterPip() }) {
                            Icon(Icons.Default.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White)
                        }
                    }

                    // Open Full-Page Audio, Subtitles & Quality Screen
                    IconButton(onClick = { showFullSettingsPage = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Audio & Subtitles", tint = Color.White)
                    }
                }

                // Center Play / Pause / Skip / Next Ep
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (episodeNum > 1) {
                        IconButton(
                            onClick = { onSwitchEpisode(episodeNum - 1) },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0x55000000), CircleShape)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Episode", tint = Color.White)
                        }
                    }

                    IconButton(
                        onClick = {
                            exoPlayer.seekTo((exoPlayer.currentPosition - 10000).coerceAtLeast(0))
                        },
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color(0x55000000), CircleShape)
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(26.dp))
                    }

                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        modifier = Modifier
                            .size(68.dp)
                            .background(CrunchyOrange, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            exoPlayer.seekTo((exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration.coerceAtLeast(0L)))
                        },
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color(0x55000000), CircleShape)
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(26.dp))
                    }

                    val maxEps = media.latestAiredEpisode ?: media.episodes ?: 9999
                    if (episodeNum < maxEps) {
                        IconButton(
                            onClick = { onSwitchEpisode(episodeNum + 1) },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0x55000000), CircleShape)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next Episode", tint = Color.White)
                        }
                    }
                }

                // Bottom Bar with Permanent +85s OP Skip Button + Buffered Progress + Scrubber
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xEE000000))
                            )
                        )
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    // Action Row above scrubber: Live Buffer status on left, +85s Skip OP button on right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val bufferLabel = when {
                            isOfflinePlayback -> "Offline Vault • Ready"
                            isPlayerBuffering -> "Buffering stream..."
                            serviceStatus.downloadSpeedKbps > 0 -> {
                                val spd = if (serviceStatus.downloadSpeedKbps >= 1024) String.format("%.1f MB/s", serviceStatus.downloadSpeedKbps / 1024.0) else "${serviceStatus.downloadSpeedKbps} KB/s"
                                "Cached ${serviceStatus.progressPercent}% • $spd"
                            }
                            else -> "Cached ${serviceStatus.progressPercent}%"
                        }
                        Text(
                            text = bufferLabel,
                            color = if (isPlayerBuffering) CrunchyOrange else MaruTextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Permanent +85s OP Skip Button (Always available anytime!)
                        Surface(
                            onClick = {
                                val jumpTarget = (exoPlayer.currentPosition + 85_000L).coerceAtMost(exoPlayer.duration.coerceAtLeast(0L))
                                exoPlayer.seekTo(jumpTarget)
                            },
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xDD141519),
                            border = BorderStroke(1.dp, CrunchyOrange)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(Icons.Default.FastForward, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(15.dp))
                                Text(
                                    text = "+85s SKIP OP",
                                    color = Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }

                    // Buffered + Playback Scrubber
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        val bufFrac = if (totalDuration > 0) (bufferedPosition.toFloat() / totalDuration).coerceIn(0f, 1f) else 0f
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                                .height(4.dp)
                                .background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(bufFrac)
                                    .fillMaxHeight()
                                    .background(Color(0x88FFFFFF), RoundedCornerShape(2.dp))
                            )
                        }

                        Slider(
                            value = if (totalDuration > 0) (currentPosition.toFloat() / totalDuration).coerceIn(0f, 1f) else 0f,
                            onValueChange = { frac ->
                                val seekTo = (frac * totalDuration).toLong()
                                exoPlayer.seekTo(seekTo)
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = CrunchyOrange,
                                activeTrackColor = CrunchyOrange,
                                inactiveTrackColor = Color.Transparent
                            )
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(currentPosition),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$activeQuality • ${if (activeAudioMode == "DUB") "Dub" else "Sub"}",
                                color = CrunchyOrange,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.5.sp
                            )
                            Text(
                                text = formatTime(totalDuration),
                                color = Color(0xCCFFFFFF),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // 7. FULL-PAGE Audio, Subtitles & Quality Settings Screen (NOT a modal sheet! Smooth native scrolling!)
        AnimatedVisibility(
            visible = showFullSettingsPage,
            enter = slideInVertically(initialOffsetY = { it / 6 }, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 6 }) + fadeOut()
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFA0B0C0F)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Fixed Top Header Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CrunchySurface)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            IconButton(onClick = { showFullSettingsPage = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                            }
                            Text(
                                text = "Audio, Subtitles & Quality",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 17.sp
                            )
                        }
                        TextButton(onClick = { showFullSettingsPage = false }) {
                            Text("DONE", color = CrunchyOrange, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                        }
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
                        // Audio Tracks Section
                        item(key = "section_audio") {
                            val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("AUDIO LANGUAGE", fontSize = 11.5.sp, color = CrunchyOrange, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)

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
                                    val modes = if (effectiveHasDub) {
                                        listOf("SUB" to "Japanese (Original Audio)", "DUB" to "Dub / Dual-Audio Stream")
                                    } else {
                                        listOf("SUB" to "Japanese (Original Audio)")
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        modes.forEach { (mode, label) ->
                                            val selected = activeAudioMode == mode
                                            Surface(
                                                onClick = {
                                                    activeAudioMode = mode
                                                    scope.launch { prefs.setPreferredAudio(mode) }
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
                            val subGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("SUBTITLES (EMBEDDED ASS/SSA & OPENSUBTITLES)", fontSize = 11.5.sp, color = CrunchyOrange, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)

                                Surface(
                                    onClick = {
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
                                        val isSelected = group.isTrackSelected(i)
                                        val isAss = format.sampleMimeType == MimeTypes.TEXT_SSA || (format.codecs?.contains("ssa", true) == true) || (format.codecs?.contains("ass", true) == true)
                                        val baseName = formatSubtitleTrackName(format.label, format.language, i)
                                        val trackName = if (isAss) "$baseName • Styled ASS (Positioned)" else baseName

                                        Surface(
                                            onClick = {
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

                                if (openSubtitlesList.isNotEmpty() && subGroups.isEmpty()) {
                                    openSubtitlesList.take(10).forEach { extSub ->
                                        val isSelected = selectedOpenSub?.url == extSub.url
                                        Surface(
                                            onClick = {
                                                selectedOpenSub = extSub
                                                val currentUri = exoPlayer.currentMediaItem?.localConfiguration?.uri
                                                val currentMime = exoPlayer.currentMediaItem?.localConfiguration?.mimeType
                                                if (currentUri != null) {
                                                    val pos = exoPlayer.currentPosition
                                                    val newItem = buildMediaItemWithSubtitles(currentUri, currentMime, listOf(extSub))
                                                    exoPlayer.setMediaItem(newItem, pos)
                                                    exoPlayer.prepare()
                                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                        .buildUpon()
                                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                        .setPreferredTextLanguage(extSub.langCode)
                                                        .build()
                                                    exoPlayer.playWhenReady = true
                                                }
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
                                Text("VIDEO QUALITY & STREAM SERVER", fontSize = 11.5.sp, color = CrunchyOrange, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                            }
                            items(rankedStreams.take(6).withIndex().toList(), key = { "srv_${it.index}" }) { (idx, stream) ->
                                val isCurrent = idx == currentStreamIndex
                                Surface(
                                    onClick = {
                                        showFullSettingsPage = false
                                        playStreamAtIndex(idx, rankedStreams)
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
    val targetLangs = if (preferredAudioMode == "DUB") listOf("en", "eng", "english") else listOf("ja", "jpn", "japanese")
    val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    for (group in audioGroups) {
        for (i in 0 until group.length) {
            val fmt = group.getTrackFormat(i)
            val lang = (fmt.language ?: "").lowercase()
            val label = (fmt.label ?: "").lowercase()
            if (targetLangs.any { lang.contains(it) || label.contains(it) }) {
                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
                return true
            }
        }
    }
    return false
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

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
