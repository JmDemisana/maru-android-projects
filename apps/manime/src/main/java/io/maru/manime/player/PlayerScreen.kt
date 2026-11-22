package io.maru.manime.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
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

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
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

    var activeAudioMode by remember(initialAudioMode, hasEnglishDub) {
        mutableStateOf(if (!hasEnglishDub) "SUB" else initialAudioMode)
    }
    var activeQuality by remember { mutableStateOf("1080p") }
    var rankedStreams by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var currentStreamIndex by remember { mutableIntStateOf(0) }
    var isResolvingStreams by remember { mutableStateOf(true) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    var isOfflinePlayback by remember { mutableStateOf(false) }

    // External OpenSubtitles tracks (ASS / VTT / SRT)
    var openSubtitlesList by remember { mutableStateOf<List<ExternalSubtitleItem>>(emptyList()) }
    var selectedOpenSub by remember { mutableStateOf<ExternalSubtitleItem?>(null) }

    val serviceStatus by TorrentStreamService.streamStatus.collectAsState()

    var isPlaying by remember { mutableStateOf(true) }
    var isPlayerBuffering by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var totalDuration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var isInPipMode by remember { mutableStateOf(false) }
    var hasScrobbled85Percent by remember(media.mediaId, episodeNum) { mutableStateOf(false) }
    var hasRestoredPosition by remember(media.mediaId, episodeNum) { mutableStateOf(false) }

    var skipIntervals by remember { mutableStateOf<List<AniSkipInterval>>(emptyList()) }
    var activeSkipInterval by remember { mutableStateOf<AniSkipInterval?>(null) }

    var showSettingsSheet by remember { mutableStateOf(false) }

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

    // Helper to build a MediaItem with all discovered OpenSubtitles side-loaded
    // so Media3's SubtitleView renders positioned ASS/SSA/VTT/SRT subtitles seamlessly.
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

    // Helper to trigger Picture-in-Picture mode
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

    // Listen to Picture-in-Picture mode changes so controls auto-hide in PiP window
    DisposableEffect(context) {
        val compActivity = context as? ComponentActivity
        val pipConsumer = Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            isInPipMode = info.isInPictureInPictureMode
            if (info.isInPictureInPictureMode) {
                showControls = false
                showSettingsSheet = false
            }
        }
        compActivity?.addOnPictureInPictureModeChangedListener(pipConsumer)
        onDispose {
            compActivity?.removeOnPictureInPictureModeChangedListener(pipConsumer)
        }
    }

    // Load AniSkip OP/ED timestamps & OpenSubtitles in background
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

    // Resolve either from private Offline Vault first, or silently via CrunchyrollStreamEngine
    LaunchedEffect(media.mediaId, episodeNum, activeAudioMode) {
        isResolvingStreams = true
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
        isResolvingStreams = false

        if (streams.isEmpty()) {
            resolveError = "Episode $episodeNum is not available yet."
        } else {
            playStreamAtIndex(0, streams)
        }
    }

    // Feed localhost HTTP Byte-Range stream into ExoPlayer as soon as pre-buffer finishes
    LaunchedEffect(serviceStatus.isReady, serviceStatus.streamUrl, serviceStatus.error) {
        if (!isOfflinePlayback) {
            val err = serviceStatus.error
            if (err != null) {
                if (currentStreamIndex + 1 < rankedStreams.size) {
                    playStreamAtIndex(currentStreamIndex + 1, rankedStreams)
                } else {
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

    // Force landscape & immersive fullscreen + Auto-failover on ExoPlayer decode/container error
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
                isPlayerBuffering = (state == Player.STATE_BUFFERING)
                if (state == Player.STATE_READY) {
                    totalDuration = exoPlayer.duration.coerceAtLeast(0L)
                    applyPreferredAudioTrack(exoPlayer, activeAudioMode)

                    // Ensure subtitles are enabled by default in SUB mode (or if embedded ASS exists)
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
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Automatic silent failover to next ranked stream if a stream has an unsupported codec (e.g. Dolby Vision)
                if (!isOfflinePlayback && currentStreamIndex + 1 < rankedStreams.size) {
                    playStreamAtIndex(currentStreamIndex + 1, rankedStreams)
                } else {
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

    // Progress & AniSkip polling loop + 85% AniList auto-scrobble
    LaunchedEffect(exoPlayer, skipIntervals, autoSkipIntro) {
        while (true) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
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

            delay(1000)
        }
    }

    // Auto-hide controls after 4 seconds
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying && !showSettingsSheet) {
            delay(4000)
            showControls = false
        }
    }

    BackHandler {
        onBack()
    }

    val isLoadingOverlay = isResolvingStreams || (!isOfflinePlayback && serviceStatus.isBuffering && !serviceStatus.isReady)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isInPipMode) {
                    showControls = !showControls
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // Configure SubtitleView to preserve ASS/SSA positioned cues (\pos, \an1-9, signs, and styling)
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
            modifier = Modifier.fillMaxSize()
        )

        // Crunchyroll Buffering / Connecting Overlay
        if ((isLoadingOverlay || isPlayerBuffering) && resolveError == null && !isInPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isLoadingOverlay) Color(0xBB0B0C0F) else Color(0x44000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = CrunchyOrange,
                        strokeWidth = 3.5.dp,
                        modifier = Modifier.size(48.dp)
                    )
                    if (isLoadingOverlay) {
                        Text(
                            text = if (isResolvingStreams) "Starting Episode $episodeNum..." else serviceStatus.statusMessage,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "${media.title} • ${if (activeAudioMode == "DUB") "English Dub" else "Japanese (Subtitled)"}",
                            color = MaruTextMuted,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Clean Error Overlay with 1-Click Retry
        if (resolveError != null && !isInPipMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xDD0B0C0F)),
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

        // Crunchyroll Floating "SKIP INTRO" / "SKIP ENDING" Button
        AnimatedVisibility(
            visible = activeSkipInterval != null && !isInPipMode,
            enter = fadeIn() + slideInHorizontally(initialOffsetX = { it / 2 }),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 24.dp, bottom = 92.dp)
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

        // Custom Crunchyroll Overlay Controls (hidden automatically when inside Picture-in-Picture window)
        AnimatedVisibility(
            visible = showControls && !isInPipMode,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x77000000))
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
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

                    // Instant SUB / DUB Toggle Pill (Only allows toggling to DUB if the anime actually has an English Dub!)
                    if (hasEnglishDub) {
                        Surface(
                            onClick = {
                                val newMode = if (activeAudioMode == "SUB") "DUB" else "SUB"
                                activeAudioMode = newMode
                                scope.launch { prefs.setPreferredAudio(newMode) }
                                val switchedInternally = applyPreferredAudioTrack(exoPlayer, newMode)
                                if (!switchedInternally && !isOfflinePlayback) {
                                    isResolvingStreams = true
                                }
                            },
                            shape = RoundedCornerShape(4.dp),
                            color = CrunchySurface,
                            border = BorderStroke(1.dp, CrunchyOrange),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.GraphicEq, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(15.dp))
                                Text(
                                    text = if (activeAudioMode == "DUB") "ENG DUB" else "JP SUB",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }

                    // Picture-in-Picture (PiP) Button
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        IconButton(onClick = { enterPip() }) {
                            Icon(
                                Icons.Default.PictureInPictureAlt,
                                contentDescription = "Picture in Picture",
                                tint = Color.White
                            )
                        }
                    }

                    // Audio, Subtitles (Embedded ASS + OpenSubtitles) & Quality Gear Button
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(
                            Icons.Default.Subtitles,
                            contentDescription = "Subtitles & Audio",
                            tint = Color.White
                        )
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
                                .background(Color(0x44000000), CircleShape)
                        ) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Episode", tint = Color.White)
                        }
                    }

                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition - 10000).coerceAtLeast(0)
                            exoPlayer.seekTo(newPos)
                        },
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color(0x44000000), CircleShape)
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
                            val newPos = (exoPlayer.currentPosition + 10000).coerceAtMost(exoPlayer.duration.coerceAtLeast(0L))
                            exoPlayer.seekTo(newPos)
                        },
                        modifier = Modifier
                            .size(50.dp)
                            .background(Color(0x44000000), CircleShape)
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(26.dp))
                    }

                    val maxEps = media.latestAiredEpisode ?: media.episodes ?: 9999
                    if (episodeNum < maxEps) {
                        IconButton(
                            onClick = { onSwitchEpisode(episodeNum + 1) },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0x44000000), CircleShape)
                        ) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next Episode", tint = Color.White)
                        }
                    }
                }

                // Bottom Crunchyroll Orange Scrubber Bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xDD000000))
                            )
                        )
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    Slider(
                        value = if (totalDuration > 0) (currentPosition.toFloat() / totalDuration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { frac ->
                            val seekTo = (frac * totalDuration).toLong()
                            exoPlayer.seekTo(seekTo)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = CrunchyOrange,
                            activeTrackColor = CrunchyOrange,
                            inactiveTrackColor = Color(0x44FFFFFF)
                        )
                    )
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = activeQuality,
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
    }

    // Crunchyroll Player Settings Sheet (Audio Track, Embedded Styled ASS Subtitles + OpenSubtitles, Quality)
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = CrunchySurface,
            contentColor = Color.White
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                item {
                    Text(
                        text = "AUDIO, SUBTITLES & QUALITY",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CrunchyOrange,
                        letterSpacing = 1.sp
                    )
                }

                // Audio Tracks
                item {
                    val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Audio Track", fontSize = 12.sp, color = MaruTextMuted, fontWeight = FontWeight.Bold)

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
                                            showSettingsSheet = false
                                        },
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isSelected) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                        border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(label, color = Color.White, fontSize = 13.5.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                            if (isSelected) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            val availableModes = if (hasEnglishDub) {
                                listOf("SUB" to "Japanese (Original)", "DUB" to "English Dub")
                            } else {
                                listOf("SUB" to "Japanese (Original • No English Dub Released)")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                availableModes.forEach { (mode, label) ->
                                    val selected = activeAudioMode == mode
                                    Surface(
                                        onClick = {
                                            activeAudioMode = mode
                                            scope.launch { prefs.setPreferredAudio(mode) }
                                            showSettingsSheet = false
                                        },
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (selected) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                        border = BorderStroke(1.dp, if (selected) CrunchyOrange else CrunchyBorder),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = label,
                                                color = if (selected) CrunchyOrange else Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.5.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Embedded ASS/SSA Styled Subtitles + OpenSubtitles
                item {
                    val subGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Subtitles (Embedded Styled ASS/SSA & OpenSubtitles)",
                            fontSize = 12.sp,
                            color = MaruTextMuted,
                            fontWeight = FontWeight.Bold
                        )

                        Surface(
                            onClick = {
                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                                showSettingsSheet = false
                            },
                            shape = RoundedCornerShape(4.dp),
                            color = CrunchyCardBg,
                            border = BorderStroke(1.dp, CrunchyBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Subtitles Off", color = Color.White, modifier = Modifier.padding(12.dp), fontSize = 13.5.sp)
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
                                        showSettingsSheet = false
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSelected) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                    border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(trackName, color = Color.White, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }

                        // If OpenSubtitles fetched tracks that aren't yet active in current item, allow 1-tap load
                        if (openSubtitlesList.isNotEmpty() && subGroups.isEmpty()) {
                            openSubtitlesList.take(8).forEach { extSub ->
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
                                        showSettingsSheet = false
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSelected) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                    border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${extSub.langName} (${extSub.format})", color = Color.White, fontSize = 13.sp)
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Clean Quality / Stream Server Switcher
                if (!isOfflinePlayback && rankedStreams.size > 1) {
                    item {
                        Text("Video Quality & Stream Server", fontSize = 12.sp, color = MaruTextMuted, fontWeight = FontWeight.Bold)
                    }
                    items(rankedStreams.take(5).withIndex().toList()) { (idx, stream) ->
                        val isCurrent = idx == currentStreamIndex
                        Surface(
                            onClick = {
                                showSettingsSheet = false
                                playStreamAtIndex(idx, rankedStreams)
                            },
                            shape = RoundedCornerShape(4.dp),
                            color = if (isCurrent) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                            border = BorderStroke(1.dp, if (isCurrent) CrunchyOrange else CrunchyBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Server ${idx + 1} • ${stream.quality}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.5.sp
                                    )
                                    Text(
                                        text = stream.sourceName,
                                        color = MaruTextMuted,
                                        fontSize = 11.5.sp
                                    )
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
        else -> lang?.uppercase() ?: "Track ${index + 1}"
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
