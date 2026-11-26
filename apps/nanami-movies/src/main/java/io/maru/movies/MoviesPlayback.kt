package io.maru.movies

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import io.maru.manime.extensions.StremioStreamResult
import io.maru.manime.extensions.SubtitleTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private const val AutoSourceLimit = 4

internal data class MoviePlaybackUi(
    val position: Long = 0,
    val duration: Long = 0,
    val buffered: Long = 0,
    val playing: Boolean = false,
    val ready: Boolean = false,
    val seekable: Boolean = false,
    val tracks: Tracks = Tracks.EMPTY,
)

internal data class MoviePlayerOptions(
    val speed: Float,
    val autoplay: Boolean,
    val persistentProgress: Boolean,
    val subtitleSize: Float,
    val subtitleBackground: Boolean,
    val fit: MovieVideoFit,
    val swipeGestures: Boolean,
    val autoSkipIntro: Boolean,
)

@OptIn(UnstableApi::class)
internal enum class MovieVideoFit(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    CROP("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

private data class PlayerMedia(val uri: Uri, val factory: DataSource.Factory, val torrent: Boolean = false)

@Composable
internal fun MoviesPlayback(
    title: MovieTitle,
    episode: MovieEpisode?,
    startPosition: Long,
    onProgress: (position: Long, duration: Long) -> Unit,
    onBack: () -> Unit,
    offlineFile: File? = null,
    preferredQuality: String = "1080p",
    onNextEpisode: (() -> Unit)? = null,
    onPreviousEpisode: (() -> Unit)? = null,
) {
    val activity = LocalContext.current.findMoviesActivity()
    MovieLandscapeWindow(activity)
    MoviePlaybackSession(
        title = title,
        episode = episode,
        startPosition = startPosition,
        onProgress = onProgress,
        onBack = onBack,
        offlineFile = offlineFile,
        preferredQuality = preferredQuality,
        onNextEpisode = onNextEpisode,
        onPreviousEpisode = onPreviousEpisode,
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun MoviePlaybackSession(
    title: MovieTitle,
    episode: MovieEpisode?,
    startPosition: Long,
    onProgress: (Long, Long) -> Unit,
    onBack: () -> Unit,
    offlineFile: File?,
    preferredQuality: String,
    onNextEpisode: (() -> Unit)?,
    onPreviousEpisode: (() -> Unit)?,
) {
    val context = LocalContext.current
    val activity = context.findMoviesActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val streamId = movieStreamId(title, episode)
    val advisory = remember(title) { deriveMovieAdvisory(title) }

    val progressState = remember(streamId) { mutableStateOf(onProgress) }
    val progressCallback by progressState
    SideEffect { progressState.value = onProgress }
    val nextCallback by rememberUpdatedState(onNextEpisode)
    val preferences = remember(context) { context.getSharedPreferences("movies_player", Context.MODE_PRIVATE) }
    val sourceMemory = remember(context) { MovieSourceMemory(context) }
    val deviceAudio = remember(context) { context.getSystemService(AudioManager::class.java) }

    var options by remember {
        mutableStateOf(
            MoviePlayerOptions(
                speed = preferences.getFloat("speed", 1f).coerceIn(0.5f, 2f),
                autoplay = preferences.getBoolean("autoplay", true),
                persistentProgress = preferences.getBoolean("persistent_progress", false),
                subtitleSize = preferences.getFloat("subtitle_size", 1f).coerceIn(0.75f, 1.5f),
                subtitleBackground = preferences.getBoolean("subtitle_background", true),
                fit = MovieVideoFit.entries.firstOrNull { it.name == preferences.getString("video_fit", null) }
                    ?: if (preferences.getBoolean("fill_screen", false)) MovieVideoFit.CROP else MovieVideoFit.FIT,
                swipeGestures = preferences.getBoolean("swipe_gestures", true),
                autoSkipIntro = preferences.getBoolean("auto_skip_intro", false),
            )
        )
    }

    val player = remember(streamId) {
        ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
            setPlaybackSpeed(options.speed)
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
    }

    var sources by remember(streamId) { mutableStateOf<List<StremioStreamResult>?>(null) }
    var selected by remember(streamId) { mutableStateOf<StremioStreamResult?>(null) }
    val attempted = remember(streamId) { mutableSetOf<StremioStreamResult>() }
    var lookupError by remember(streamId) { mutableStateOf(false) }
    var lookupAttempt by remember(streamId) { mutableIntStateOf(0) }
    var completedLookup by remember(streamId) { mutableIntStateOf(-1) }
    var sourceAttempt by remember(streamId) { mutableIntStateOf(0) }
    var useOffline by remember(streamId, offlineFile) { mutableStateOf(offlineFile?.isFile == true) }
    var activeMedia by remember(streamId) { mutableStateOf<PlayerMedia?>(null) }
    var resumePosition by remember(streamId) { mutableStateOf(startPosition.coerceAtLeast(0)) }
    var status by remember(streamId) { mutableStateOf("Finding fastest video source…") }
    var prebufferPercent by remember(streamId) { mutableIntStateOf(0) }
    var failure by remember(streamId) { mutableStateOf<String?>(null) }
    var ui by remember(streamId) { mutableStateOf(MoviePlaybackUi(position = startPosition.coerceAtLeast(0))) }
    var ended by remember(streamId) { mutableStateOf(false) }
    var nextCountdown by remember(streamId) { mutableStateOf<Int?>(null) }
    var playIntent by remember(streamId) { mutableStateOf(true) }
    var remotePlayback by remember { mutableStateOf(false) }
    var remoteTransport by remember { mutableStateOf<MovieRemoteTransport?>(null) }
    var wasPlayingBeforeClip by remember(streamId) { mutableStateOf(false) }
    var showSettingsSheet by remember(streamId) { mutableStateOf(false) }
    var showVideoSourcesFirst by remember(streamId) { mutableStateOf(false) }
    var controls by remember(streamId) { mutableStateOf(true) }
    var isScrubbing by remember(streamId) { mutableStateOf(false) }
    var controlPulse by remember(streamId) { mutableIntStateOf(0) }
    var locked by remember(streamId) { mutableStateOf(false) }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    val supportsPip = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    var subtitleOptions by remember(streamId) { mutableStateOf<List<SubtitleTrack>?>(null) }
    var segmentOptions by remember(streamId) { mutableStateOf(emptyList<MovieSegment>()) }
    var autoSkipped by remember(streamId) { mutableStateOf(false) }
    var externalSubtitle by remember(streamId) { mutableStateOf<SubtitleTrack?>(null) }
    var ensureTextOverride by remember(streamId) { mutableStateOf<String?>(null) }
    var showOpeningAdvisory by remember(streamId) { mutableStateOf(false) }
    var advisoryShownForStream by remember(streamId) { mutableStateOf(false) }
    var hasRenderedFrame by remember(streamId) { mutableStateOf(false) }
    var isBufferingMidPlay by remember(streamId) { mutableStateOf(false) }
    var showRebuffering by remember(streamId) { mutableStateOf(false) }

    fun resetControls() {
        controls = true
        controlPulse++
    }

    fun setOptions(value: MoviePlayerOptions) {
        options = value
        preferences.edit()
            .putFloat("speed", value.speed)
            .putBoolean("autoplay", value.autoplay)
            .putBoolean("persistent_progress", value.persistentProgress)
            .putFloat("subtitle_size", value.subtitleSize)
            .putBoolean("subtitle_background", value.subtitleBackground)
            .putString("video_fit", value.fit.name)
            .putBoolean("swipe_gestures", value.swipeGestures)
            .putBoolean("auto_skip_intro", value.autoSkipIntro)
            .apply()
        player.setPlaybackSpeed(value.speed)
    }

    fun captureProgress() {
        if (remotePlayback) return
        val stateReady = hasRenderedFrame || player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED
        val duration = player.duration
        if (stateReady && duration > 0 && (player.currentPosition > 0 || resumePosition == 0L)) {
            resumePosition = player.currentPosition.coerceIn(0, duration)
            progressCallback(resumePosition, duration)
        }
    }

    fun chooseSource(source: StremioStreamResult) {
        captureProgress()
        sourceMemory.save(title, episode, source)
        attempted.clear()
        attempted.add(source)
        useOffline = false
        failure = null
        sourceAttempt++
        selected = source
        showSettingsSheet = false
        resetControls()
    }

    fun chooseInitial(available: List<StremioStreamResult>) {
        val source = sourceMemory.preferred(title, episode, available) ?: available.firstOrNull()
        if (source != null) attempted.add(source)
        selected = source
    }

    fun seekTo(position: Long) {
        if (!ui.seekable) return
        val value = position.coerceIn(0, ui.duration)
        if (remotePlayback) {
            remoteTransport?.seek?.invoke(value) ?: return
        } else {
            player.seekTo(value)
        }
        resumePosition = value
        ui = ui.copy(position = value)
        ended = false
        nextCountdown = null
        resetControls()
    }

    fun selectTrack(type: Int, track: MovieTrack?) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        builder.setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && track == null)
        if (track != null) builder.setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.index))
        player.trackSelectionParameters = builder.build()
    }

    fun selectExternal(track: SubtitleTrack) {
        captureProgress()
        ensureTextOverride = track.url
        externalSubtitle = track
        if (player.currentTracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { group.getTrackFormat(it).id == track.url }
            }) {
            val candidate = movieTrackChoices(player.currentTracks, C.TRACK_TYPE_TEXT)
                .firstOrNull { it.group.getTrackFormat(it.index).id == track.url }
            selectTrack(C.TRACK_TYPE_TEXT, candidate)
            ensureTextOverride = null
        }
    }

    // 4-Second Opening Content Advisory when first frame renders (identical to Nanami Anime!)
    LaunchedEffect(hasRenderedFrame, remotePlayback) {
        if ((hasRenderedFrame || remotePlayback) && !advisoryShownForStream) {
            advisoryShownForStream = true
            showOpeningAdvisory = true
            delay(4_200L)
            showOpeningAdvisory = false
        }
    }

    LaunchedEffect(isBufferingMidPlay, hasRenderedFrame, remotePlayback) {
        showRebuffering = false
        if (isBufferingMidPlay && hasRenderedFrame && !remotePlayback) {
            delay(250L)
            showRebuffering = true
        }
    }

    BackHandler(!inPip) {
        when {
            showSettingsSheet -> showSettingsSheet = false
            locked -> {
                locked = false
                resetControls()
            }
            else -> {
                captureProgress()
                player.pause()
                onBack()
            }
        }
    }

    val revealControls by rememberUpdatedState<() -> Unit>(::resetControls)
    val dismissSettings by rememberUpdatedState<() -> Unit>({ showSettingsSheet = false })
    DisposableEffect(activity) {
        val component = activity as? ComponentActivity
        val pipEvents = Consumer<PictureInPictureModeChangedInfo> { info ->
            inPip = info.isInPictureInPictureMode
            if (inPip) dismissSettings() else revealControls()
        }
        component?.addOnPictureInPictureModeChangedListener(pipEvents)
        onDispose { component?.removeOnPictureInPictureModeChangedListener(pipEvents) }
    }

    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                hasRenderedFrame = true
                isBufferingMidPlay = false
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBufferingMidPlay = playbackState == Player.STATE_BUFFERING && hasRenderedFrame
                if (playbackState == Player.STATE_ENDED) {
                    captureProgress()
                    ended = true
                    resetControls()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("MoviesPlayback", "Media3 playback failed: ${error.errorCodeName}")
                failure = "This video source stopped playing."
            }
        }
        val session = runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            MediaSession.Builder(context, player).setId(streamId).apply {
                if (intent != null) setSessionActivity(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE))
            }.build()
        }.getOrNull()
        val backgroundAvailable = session != null && MoviesPlaybackService.attach(context, session)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isInPictureInPictureMode != true) {
                captureProgress()
                if (!backgroundAvailable || !player.isPlaying) player.pause()
            }
        }
        player.addListener(listener)
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            captureProgress()
            lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            if (session != null) {
                MoviesPlaybackService.detach(context, session)
                session.release()
            }
            player.release()
        }
    }

    LaunchedEffect(streamId, lookupAttempt, useOffline, preferredQuality) {
        if ((useOffline && lookupAttempt == 0) || completedLookup == lookupAttempt) return@LaunchedEffect
        lookupError = false
        sources = null
        try {
            val results = findMovieSources(title, episode, context, preferredQuality)
            sources = results
            completedLookup = lookupAttempt
            if (selected == null && !useOffline) chooseInitial(results)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            lookupError = true
        }
    }

    LaunchedEffect(showSettingsSheet, streamId, useOffline) {
        if (showSettingsSheet && subtitleOptions == null) {
            subtitleOptions = if (useOffline) emptyList() else findMovieSubtitles(title, episode)
        }
    }

    LaunchedEffect(streamId, useOffline) {
        if (!useOffline && segmentOptions.isEmpty()) segmentOptions = findMovieSegments(title, episode)
    }

    val activeSegment = if (ui.seekable && !remotePlayback) segmentOptions.firstOrNull { it.at(ui.position, ui.duration) } else null
    LaunchedEffect(activeSegment, options.autoSkipIntro, ui.playing) {
        if (activeSegment?.type == MovieSegmentType.INTRO && options.autoSkipIntro && ui.playing && !autoSkipped) {
            autoSkipped = true
            seekTo(activeSegment.end)
        }
    }

    LaunchedEffect(streamId, selected, sourceAttempt, useOffline, offlineFile) {
        activeMedia = null
        failure = null
        ended = false
        hasRenderedFrame = false
        isBufferingMidPlay = false
        prebufferPercent = 0
        ui = MoviePlaybackUi(position = resumePosition)
        val source = selected
        val unitLabel = if (episode != null) "Episode ${episode.episode}" else "Movie"
        if (useOffline && offlineFile != null) {
            status = "Opening saved download…"
            activeMedia = PlayerMedia(Uri.fromFile(offlineFile), DefaultDataSource.Factory(context))
            return@LaunchedEffect
        }
        if (source == null) return@LaunchedEffect
        val direct = source.httpUri()
        if (direct != null) {
            status = "Connecting to $unitLabel stream…"
            val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(source.headers)
            activeMedia = PlayerMedia(direct, factory)
            return@LaunchedEffect
        }
        status = "Connecting to $unitLabel stream…"
        prebufferPercent = 5
        try {
            withMoviesTorrent(context.cacheDir, source, episode, onBufferProgress = { buf ->
                val speedLabel = if (buf.speedKbps >= 1024) {
                    String.format(Locale.US, "%.1f MB/s", buf.speedKbps / 1024.0)
                } else {
                    "${buf.speedKbps} KB/s"
                }
                withContext(Dispatchers.Main) {
                    prebufferPercent = buf.percent
                    status = if (buf.peers > 0 || buf.speedKbps > 0) {
                        "Buffering $unitLabel (${buf.percent}% • $speedLabel)"
                    } else {
                        "Buffering $unitLabel (${buf.percent}% • 0 KB/s)"
                    }
                }
            }) { uri, factory ->
                withContext(Dispatchers.Main) {
                    prebufferPercent = 100
                    activeMedia = PlayerMedia(uri, factory, torrent = true)
                    awaitCancellation()
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure = "This source took too long to respond."
        } catch (error: CancellationException) {
            throw error
        } catch (_: LinkageError) {
            failure = "Playback engine could not start on this device."
        } catch (_: Exception) {
            failure = "This source could not be opened."
        }
    }

    LaunchedEffect(activeMedia, externalSubtitle) {
        player.stop()
        player.clearMediaItems()
        val media = activeMedia ?: return@LaunchedEffect
        val item = MediaItem.Builder()
            .setUri(media.uri)
            .setMediaId(streamId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(episode?.title ?: title.title).build())
            .build()
        val video = if (media.torrent) ProgressiveMediaSource.Factory(media.factory).createMediaSource(item)
        else DefaultMediaSourceFactory(context).setDataSourceFactory(media.factory).createMediaSource(item)
        val subtitle = externalSubtitle?.let { track ->
            val mime = when (track.url.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase(Locale.ROOT)) {
                "vtt" -> MimeTypes.TEXT_VTT
                "ssa", "ass" -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            val spec = MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setId(track.url)
                .setLabel(languageName(track.language))
                .setLanguage(track.language)
                .setMimeType(mime)
                .build()
            SingleSampleMediaSource.Factory(DefaultDataSource.Factory(context)).createMediaSource(spec, C.TIME_UNSET)
        }
        player.setMediaSource(if (subtitle == null) video else MergingMediaSource(video, subtitle), resumePosition)
        if (subtitle != null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }
        player.playWhenReady = playIntent && !remotePlayback && (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || inPip)
        player.prepare()
    }

    LaunchedEffect(failure, sourceAttempt, useOffline) {
        val error = failure ?: return@LaunchedEffect
        if (useOffline || attempted.size >= AutoSourceLimit) return@LaunchedEffect
        val next = sources?.firstOrNull { it !in attempted } ?: return@LaunchedEffect
        captureProgress()
        status = "$error Trying next source…"
        delay(650)
        attempted.add(next)
        selected = next
        sourceAttempt++
    }

    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            val duration = player.duration.coerceAtLeast(0)
            val tracks = player.currentTracks
            val pending = ensureTextOverride
            if (pending != null && player.playbackState == Player.STATE_READY) {
                val match = movieTrackChoices(tracks, C.TRACK_TYPE_TEXT)
                    .firstOrNull { it.group.getTrackFormat(it.index).id == pending }
                if (match != null) {
                    selectTrack(C.TRACK_TYPE_TEXT, match)
                    ensureTextOverride = null
                }
            }
            if (!remotePlayback) {
                ui = MoviePlaybackUi(
                    position = if (duration > 0) player.currentPosition.coerceIn(0, duration) else resumePosition,
                    duration = duration,
                    buffered = player.bufferedPosition.coerceAtLeast(0),
                    playing = player.isPlaying,
                    ready = hasRenderedFrame || player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED,
                    seekable = player.isCurrentMediaItemSeekable && duration > 0,
                    tracks = tracks,
                )
                if (++ticks % 8 == 0 && player.isPlaying) captureProgress()
            }
            delay(200)
        }
    }

    LaunchedEffect(ui.playing, controlPulse, controls, showSettingsSheet, locked, isScrubbing) {
        if ((ui.playing || locked) && controls && !showSettingsSheet && !isScrubbing) {
            delay(4_000)
            controls = false
        }
    }

    LaunchedEffect(ended, options.autoplay, onNextEpisode != null) {
        if (!ended || !options.autoplay || nextCallback == null) return@LaunchedEffect
        for (seconds in 8 downTo 1) {
            nextCountdown = seconds
            delay(1_000)
            if (nextCountdown == null) return@LaunchedEffect
        }
        nextCallback?.invoke()
    }

    val canAutoFallback = !useOffline && attempted.size < AutoSourceLimit && sources.orEmpty().any { it !in attempted }
    val problem = when {
        lookupError && selected == null && !useOffline -> "Video sources could not be reached."
        !useOffline && selected == null && sources?.isEmpty() == true -> "No playable sources found for this title."
        !canAutoFallback -> failure
        else -> null
    }
    val loading = when {
        problem != null -> null
        failure != null && canAutoFallback -> "Trying another source…"
        !useOffline && sources == null && selected == null -> "Finding fastest video source…"
        !hasRenderedFrame && !remotePlayback && !ui.ready -> status
        !hasRenderedFrame && !remotePlayback -> status
        else -> null
    }

    val sourceBadgeLabel = remember(useOffline, selected, sources, options.speed) {
        val base = if (useOffline) {
            "Downloaded"
        } else if (selected == null) {
            "Sources"
        } else {
            val idx = sources?.indexOf(selected)?.takeIf { it >= 0 }?.plus(1)
            val qual = selected?.movieQuality()
            when {
                idx != null && qual != null -> "Source $idx • $qual"
                idx != null -> "Source $idx"
                qual != null -> qual
                else -> "Sources"
            }
        }
        if (kotlin.math.abs(options.speed - 1f) >= 0.03f) {
            "$base • ${options.speed.toString().removeSuffix(".0")}×"
        } else {
            base
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Bottom-most pointer shield so NO touch event inside MoviesPlayback can ever leak to screens behind it!
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        }
                    }
                },
        )

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { PlayerView(it).apply { useController = false; this.player = player } },
            update = { view ->
                if (view.player !== player) view.player = player
                view.resizeMode = options.fit.resizeMode
                view.subtitleView?.apply {
                    setApplyEmbeddedFontSizes(false)
                    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * options.subtitleSize)
                    setStyle(
                        CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            if (options.subtitleBackground) 0xBE000000.toInt() else android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null,
                        )
                    )
                }
            },
        )

        MoviePlayerChrome(
            title = title,
            episode = episode,
            ui = ui,
            sourceBadgeLabel = sourceBadgeLabel,
            advisory = advisory,
            showOpeningAdvisory = showOpeningAdvisory && !showSettingsSheet,
            segments = segmentOptions,
            loading = loading,
            loadingProgressPercent = if (loading != null && !hasRenderedFrame) prebufferPercent else 0,
            rebuffering = showRebuffering,
            problem = problem,
            controls = controls && !inPip && !showSettingsSheet,
            locked = locked && !inPip,
            hideChrome = inPip,
            persistentProgress = options.persistentProgress && !showSettingsSheet,
            swipeGestures = options.swipeGestures && !showSettingsSheet,
            skipSegment = activeSegment.takeUnless { showSettingsSheet },
            nextCountdown = nextCountdown.takeUnless { showSettingsSheet },
            onScrubChange = { active ->
                isScrubbing = active
                if (active) resetControls()
            },
            onCancelNext = { nextCountdown = null },
            onSkipSegment = { segment -> seekTo(segment.end) },
            onToggleControls = { if (controls) controls = false else resetControls() },
            onBack = {
                captureProgress()
                player.pause()
                onBack()
            },
            // Open half-screen Playback Settings (Video Sources FIRST when still loading or on error, matching Nanami Anime!)
            onSettings = {
                showVideoSourcesFirst = !hasRenderedFrame || loading != null || problem != null
                showSettingsSheet = true
            },
            onSources = {
                showVideoSourcesFirst = true
                showSettingsSheet = true
            },
            onLock = { locked = !locked; resetControls() },
            onPip = if (supportsPip && ui.ready && activity != null && !remotePlayback) ({
                runCatching {
                    activity.enterPictureInPictureMode(
                        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                    )
                }
            }) else null,
            onTogglePlayback = {
                if (remotePlayback) {
                    remoteTransport?.toggle?.invoke()
                } else {
                    playIntent = !player.playWhenReady || ended
                    if (ended) seekTo(0)
                    player.playWhenReady = playIntent
                }
                resetControls()
            },
            onSeek = ::seekTo,
            onPreviousEpisode = onPreviousEpisode?.let { { captureProgress(); it() } },
            onNextEpisode = onNextEpisode?.let { { captureProgress(); it() } },
            readBrightness = { currentBrightness(activity) },
            setBrightness = { value ->
                activity?.window?.let { window ->
                    window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.03f, 1f) }
                }
            },
            readVolume = {
                deviceAudio?.let {
                    it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                        it.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                } ?: 0.5f
            },
            setVolume = { value ->
                deviceAudio?.let {
                    runCatching {
                        it.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            (value * it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt(),
                            0,
                        )
                    }
                }
            },
            onRetry = {
                failure = null
                if (useOffline) {
                    useOffline = false
                    if (selected == null) sources?.let(::chooseInitial)
                } else if (selected != null && !lookupError) {
                    val source = selected
                    attempted.clear()
                    if (source != null) attempted.add(source)
                    sourceAttempt++
                } else lookupAttempt++
            },
            tools = { visible ->
                MoviesPlayerTools(
                    title = title,
                    episode = episode,
                    activeSource = selected.takeUnless { useOffline },
                    offlineFile = offlineFile.takeIf { useOffline },
                    mediaUri = activeMedia?.uri,
                    dataSource = activeMedia?.factory,
                    position = ui.position,
                    duration = ui.duration,
                    onRemotePlayback = { remotePlayback = true; player.pause() },
                    visible = visible,
                    onRemoteEnded = { position ->
                        remotePlayback = false
                        remoteTransport = null
                        if (position > 0 && player.isCurrentMediaItemSeekable) player.seekTo(position)
                        if (playIntent) player.play()
                    },
                    onRemoteProgress = { position, duration, playing ->
                        if (duration > 0) {
                            resumePosition = position.coerceIn(0, duration)
                            ui = ui.copy(position = resumePosition, duration = duration, playing = playing, ready = true, seekable = true)
                            progressCallback(resumePosition, duration)
                        }
                    },
                    onRemoteTransport = { remoteTransport = it },
                    onClipVisibilityChanged = { clipOpen ->
                        if (clipOpen) {
                            wasPlayingBeforeClip = player.playWhenReady
                            player.pause()
                        } else {
                            if (wasPlayingBeforeClip && playIntent && !remotePlayback && !ended &&
                                lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                            ) player.play()
                            wasPlayingBeforeClip = false
                        }
                    },
                )
            },
        )

        val addOnSubtitles = selected?.subtitles.orEmpty().filter { Uri.parse(it.url).scheme == "https" }
        MoviePlaybackSettingsSheet(
            visible = showSettingsSheet && !inPip,
            showVideoSourcesFirst = showVideoSourcesFirst,
            options = options,
            onOptions = ::setOptions,
            sources = sources,
            isOffline = useOffline,
            offlineAvailable = offlineFile?.isFile == true,
            selectedSource = selected,
            onSelectSource = ::chooseSource,
            onRetrySources = { lookupAttempt++ },
            onUseOffline = {
                captureProgress()
                useOffline = true
                showSettingsSheet = false
            },
            tracks = ui.tracks,
            subtitleOptions = (addOnSubtitles + subtitleOptions.orEmpty()).distinctBy(SubtitleTrack::url),
            subtitlesLoading = subtitleOptions == null && !useOffline,
            selectedSubtitle = externalSubtitle,
            onSelectExternal = ::selectExternal,
            onSelectTrack = { type, track ->
                if (type == C.TRACK_TYPE_TEXT) ensureTextOverride = null
                selectTrack(type, track)
            },
            onDismiss = {
                showSettingsSheet = false
                resetControls()
            },
        )
    }
}

@Composable
private fun MovieLandscapeWindow(activity: Activity?) {
    if (activity == null) return
    DisposableEffect(activity) {
        val window = activity.window
        val decor = window.decorView
        val controller = WindowCompat.getInsetsController(window, decor)
        val insets = ViewCompat.getRootWindowInsets(decor)
        val statusWasVisible = insets?.isVisible(WindowInsetsCompat.Type.statusBars()) != false
        val navigationWasVisible = insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) != false
        val originalOrientation = activity.requestedOrientation
        val originalBrightness = window.attributes.screenBrightness
        val originalBehavior = controller.systemBarsBehavior
        val keepAwake = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        if (!activity.isInMultiWindowMode) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity.requestedOrientation = originalOrientation
            window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
            if (!keepAwake) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.systemBarsBehavior = originalBehavior
            val mask = (if (statusWasVisible) WindowInsetsCompat.Type.statusBars() else 0) or
                if (navigationWasVisible) WindowInsetsCompat.Type.navigationBars() else 0
            if (mask != 0) controller.show(mask)
        }
    }
}

private tailrec fun Context.findMoviesActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.findMoviesActivity()
    else -> null
}

private fun currentBrightness(activity: Activity?): Float {
    val windowValue = activity?.window?.attributes?.screenBrightness ?: -1f
    if (windowValue >= 0f) return windowValue
    val system = activity?.contentResolver?.let { Settings.System.getInt(it, Settings.System.SCREEN_BRIGHTNESS, 128) } ?: 128
    return system / 255f
}
