package io.maru.movies

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal val CinemaRed = NetflixRed

private data class GestureHudState(val isBrightness: Boolean, val fraction: Float)

@Composable
internal fun MoviePlayerChrome(
    title: MovieTitle,
    episode: MovieEpisode?,
    ui: MoviePlaybackUi,
    sourceBadgeLabel: String,
    advisory: MovieContentAdvisory,
    showOpeningAdvisory: Boolean,
    segments: List<MovieSegment>,
    loading: String?,
    problem: String?,
    controls: Boolean,
    locked: Boolean,
    hideChrome: Boolean,
    persistentProgress: Boolean,
    swipeGestures: Boolean,
    skipSegment: MovieSegment?,
    nextCountdown: Int?,
    onScrubChange: (Boolean) -> Unit,
    onCancelNext: () -> Unit,
    onSkipSegment: (MovieSegment) -> Unit,
    onToggleControls: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onTracks: () -> Unit,
    onSources: () -> Unit,
    onLock: () -> Unit,
    onPip: (() -> Unit)?,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
    onPreviousEpisode: (() -> Unit)?,
    onNextEpisode: (() -> Unit)?,
    readBrightness: () -> Float,
    setBrightness: (Float) -> Unit,
    readVolume: () -> Float,
    setVolume: (Float) -> Unit,
    onRetry: () -> Unit,
    tools: @Composable (Boolean) -> Unit,
) {
    val state by rememberUpdatedState(ui)
    val toggleControls by rememberUpdatedState(onToggleControls)
    val seek by rememberUpdatedState(onSeek)
    val brightness by rememberUpdatedState(readBrightness)
    val volume by rememberUpdatedState(readVolume)
    val applyBrightness by rememberUpdatedState(setBrightness)
    val applyVolume by rememberUpdatedState(setVolume)

    var gestureHud by remember { mutableStateOf<GestureHudState?>(null) }
    var doubleTapDeltaSec by remember { mutableIntStateOf(0) }
    var hudPulse by remember { mutableIntStateOf(0) }
    var doubleTapPulse by remember { mutableIntStateOf(0) }
    var sawVideo by remember(title.imdbId, episode?.id) { mutableStateOf(false) }

    LaunchedEffect(ui.ready, ui.position) {
        if (ui.ready && ui.position > 0) sawVideo = true
    }
    LaunchedEffect(hudPulse) {
        if (gestureHud == null) return@LaunchedEffect
        delay(900)
        gestureHud = null
    }
    LaunchedEffect(doubleTapPulse) {
        if (doubleTapDeltaSec == 0) return@LaunchedEffect
        delay(800)
        doubleTapDeltaSec = 0
    }

    val showButtons = !hideChrome && !locked && (controls || loading != null || problem != null || nextCountdown != null)

    Box(Modifier.fillMaxSize()) {
        // Blurred / darkened backdrop before first video frame
        if (!hideChrome && !sawVideo && (loading != null || problem != null)) {
            val backdrop = episode?.thumbnail ?: title.backdrop ?: title.poster
            if (backdrop != null) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alpha = 0.34f,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xB309090D), Color(0xEB000000)),
                        ),
                    ),
            )
        }

        // Touch & Vertical Gesture Surface
        if (!hideChrome && !locked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { toggleControls() },
                            onDoubleTap = { touch ->
                                if (state.seekable) {
                                    val forward = touch.x >= size.width / 2f
                                    seek(state.position + if (forward) 10_000L else -10_000L)
                                    doubleTapDeltaSec = if (forward) 10 else -10
                                    doubleTapPulse++
                                }
                            },
                        )
                    }
                    .pointerInput(swipeGestures) {
                        if (!swipeGestures) return@pointerInput
                        var left = false
                        var level = 0f
                        detectVerticalDragGestures(
                            onDragStart = { start ->
                                left = start.x < size.width / 2f
                                level = if (left) brightness() else volume()
                            },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                level = (level - dy / (size.height.coerceAtLeast(1) * 0.72f)).coerceIn(0f, 1f)
                                if (left) applyBrightness(level) else applyVolume(level)
                                gestureHud = GestureHudState(isBrightness = left, fraction = level)
                                hudPulse++
                            },
                        )
                    },
            )
        }

        if (showButtons) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.76f),
                            0.30f to Color.Transparent,
                            0.68f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.84f),
                        ),
                    ),
            )
        }

        // Top Player Header
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showButtons) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = title.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (episode != null) {
                        Text(
                            text = "S${episode.season}:E${episode.episode} • ${episode.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD2D5DF),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            tools(showButtons && ui.ready)

            if (showButtons) {
                if (onPip != null) {
                    IconButton(onClick = onPip) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Picture in picture", tint = Color.White)
                    }
                }
                IconButton(onClick = onLock) {
                    Icon(Icons.Default.LockOpen, contentDescription = "Lock controls", tint = Color.White)
                }
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Playback settings", tint = Color.White)
                }
            }
        }

        // Netflix 4-Second Opening Content Advisory Banner (Top-Left)
        MovieOpeningAdvisory(
            advisory = advisory,
            visible = !hideChrome && showOpeningAdvisory && loading == null && problem == null,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start))
                .padding(top = if (showButtons) 60.dp else 18.dp, start = 16.dp),
        )

        // Screen Lock Shield Button
        if (!hideChrome && locked) {
            Surface(
                onClick = onLock,
                shape = CircleShape,
                color = Color(0xBF0A0A0D),
                border = BorderStroke(1.dp, NetflixBorder),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                    .padding(14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Lock, contentDescription = "Unlock", tint = NetflixRed, modifier = Modifier.size(18.dp))
                    Text("Locked", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Center Status (Loading / Problem with Instant Source Switcher!) OR Transport Controls
        if (!hideChrome && (loading != null || problem != null)) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (loading != null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(38.dp),
                        color = NetflixRed,
                        strokeWidth = 3.dp,
                    )
                } else {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(38.dp))
                }
                Text(
                    text = problem ?: loading.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                )
                if (!locked) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (problem != null) {
                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed, contentColor = Color.White),
                                shape = RoundedCornerShape(6.dp),
                            ) {
                                Text("Try Again", fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        OutlinedButton(
                            onClick = onSources,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Color(0x77FFFFFF)),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0x8814141A), contentColor = Color.White),
                        ) {
                            Icon(Icons.Default.VideoLibrary, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Switch Source", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        }
                    }
                }
            }
        } else if (showButtons && nextCountdown == null) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onPreviousEpisode != null) {
                    MovieTransportButton(Icons.Default.SkipPrevious, "Previous episode", onPreviousEpisode)
                }
                MovieTransportButton(Icons.Default.Replay10, "Back 10 seconds", { onSeek(ui.position - 10_000L) }, enabled = ui.seekable)
                MovieTransportButton(
                    icon = if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    label = if (ui.playing) "Pause" else "Play",
                    onClick = onTogglePlayback,
                    primary = true,
                )
                MovieTransportButton(Icons.Default.Forward10, "Forward 10 seconds", { onSeek(ui.position + 10_000L) }, enabled = ui.seekable)
                if (onNextEpisode != null) {
                    MovieTransportButton(Icons.Default.SkipNext, "Next episode", onNextEpisode)
                }
            }
        }

        // Custom 60fps Canvas Seekbar & Bottom Bar (identical engine to Nanami Anime!)
        if (showButtons && ui.duration > 0L && ui.ready) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(bottom = 4.dp),
            ) {
                MoviePlayerTimeline(
                    positionMs = ui.position,
                    bufferedMs = ui.buffered,
                    durationMs = ui.duration,
                    isSeekable = ui.seekable,
                    sourceLabel = sourceBadgeLabel,
                    segments = segments,
                    onScrub = onScrubChange,
                    onSeek = onSeek,
                    onOpenSources = onSources,
                    onOpenTracks = onTracks,
                )
            }
        } else if (!hideChrome && persistentProgress && ui.duration > 0L) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .background(Color(0x28FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((ui.position.toFloat() / ui.duration).coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(NetflixRed),
                )
            }
        }

        // Netflix Skip Intro / Recap / Credits Button
        if (!hideChrome && !locked && skipSegment != null && loading == null && problem == null) {
            Surface(
                onClick = { onSkipSegment(skipSegment) },
                shape = RoundedCornerShape(4.dp),
                color = Color(0xF2FFFFFF),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                    .padding(end = 18.dp, bottom = if (showButtons) 88.dp else 22.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    Text(
                        text = skipSegment.type.action.uppercase(Locale.ROOT),
                        color = Color.Black,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.5.sp,
                        letterSpacing = 0.5.sp,
                    )
                }
            }
        }

        // Up Next Episode Countdown Card
        if (!hideChrome && nextCountdown != null && onNextEpisode != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                    .padding(end = 18.dp, bottom = if (showButtons) 88.dp else 24.dp),
                color = Color(0xF2121217),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, NetflixRed),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column {
                        Text("Next Episode", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                        Text("Starting in ${nextCountdown}s", color = NetflixTextMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = onNextEpisode,
                        colors = ButtonDefaults.buttonColors(containerColor = NetflixWhite, contentColor = Color.Black),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Play Now", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                    IconButton(onClick = onCancelNext) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel autoplay", tint = Color.White)
                    }
                }
            }
        }

        // Brightness / Volume Top Pill HUD
        MovieGestureHud(
            isBrightness = gestureHud?.isBrightness ?: true,
            fraction = gestureHud?.fraction ?: 0.5f,
            visible = !hideChrome && !locked && gestureHud != null,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(top = 56.dp),
        )

        // Double-Tap +/-10s Badge
        MovieDoubleTapBadge(
            deltaSeconds = doubleTapDeltaSec,
            visible = !hideChrome && !locked && doubleTapDeltaSec != 0,
            modifier = Modifier.align(if (doubleTapDeltaSec >= 0) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 44.dp),
        )
    }
}

@Composable
private fun MovieTransportButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    val buttonSize = if (primary) 64.dp else 46.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(buttonSize)
            .background(
                color = if (primary) NetflixRed else Color(0x8A0D0E12),
                shape = CircleShape,
            )
            .pressableClick(enabled = enabled, pressedScale = 0.90f, onClick = onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (enabled) Color.White else Color(0x55FFFFFF),
            modifier = Modifier.size(if (primary) 34.dp else 24.dp),
        )
    }
}

@Composable
private fun MoviePlayerTimeline(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    isSeekable: Boolean,
    sourceLabel: String,
    segments: List<MovieSegment>,
    onScrub: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onOpenSources: () -> Unit,
    onOpenTracks: () -> Unit,
) {
    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    var settledTargetMs by remember { mutableLongStateOf(-1L) }
    var showRemaining by remember { mutableStateOf(false) }
    var barWidthPx by remember { mutableIntStateOf(0) }

    val latestEnd by rememberUpdatedState(durationMs)
    val latestOnScrub by rememberUpdatedState(onScrub)
    val latestOnSeek by rememberUpdatedState(onSeek)

    LaunchedEffect(settledTargetMs) {
        val target = settledTargetMs
        if (target < 0L) return@LaunchedEffect
        delay(1800)
        if (settledTargetMs == target) settledTargetMs = -1L
    }
    LaunchedEffect(positionMs) {
        if (settledTargetMs >= 0L && abs(positionMs - settledTargetMs) <= 1500L) {
            settledTargetMs = -1L
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (scrubFraction >= 0f) latestOnScrub(false)
        }
    }

    val effectiveCurrentMs = when {
        scrubFraction >= 0f && durationMs > 0L -> (scrubFraction * durationMs).toLong()
        settledTargetMs >= 0L -> settledTargetMs
        else -> positionMs
    }
    val activeFraction = when {
        scrubFraction >= 0f -> scrubFraction.coerceIn(0f, 1f)
        durationMs > 0L -> (effectiveCurrentMs.toFloat() / durationMs).coerceIn(0f, 1f)
        else -> 0f
    }
    val bufferedFraction = if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val isScrubbingNow = scrubFraction >= 0f

    val trackHeight by animateDpAsState(
        targetValue = if (isScrubbingNow) 7.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "movie_seek_track_h",
    )
    val thumbDiameter by animateDpAsState(
        targetValue = when {
            !isSeekable || durationMs <= 0L -> 0.dp
            isScrubbingNow -> 18.dp
            else -> 13.dp
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "movie_seek_thumb_d",
    )

    val density = LocalDensity.current
    val horizontalInsetPx = with(density) { 10.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            androidx.compose.animation.AnimatedVisibility(
                visible = isScrubbingNow && durationMs > 0L && barWidthPx > 0,
                enter = fadeIn(tween(110)) + scaleIn(tween(110), initialScale = 0.88f),
                exit = fadeOut(tween(110)) + scaleOut(tween(110), targetScale = 0.88f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val usable = (barWidthPx - horizontalInsetPx * 2f).coerceAtLeast(1f)
                        val thumbX = horizontalInsetPx + usable * activeFraction
                        val bubbleHalfPx = with(density) { 34.dp.toPx() }
                        val clampedX = thumbX.coerceIn(bubbleHalfPx, (barWidthPx - bubbleHalfPx).coerceAtLeast(bubbleHalfPx))
                        IntOffset((clampedX - bubbleHalfPx).roundToInt(), with(density) { (-26).dp.roundToPx() })
                    },
            ) {
                Surface(
                    color = Color(0xF0141418),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, NetflixRed),
                ) {
                    Text(
                        text = movieTime(effectiveCurrentMs),
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .onSizeChanged { barWidthPx = it.width }
                    .pointerInput(isSeekable) {
                        if (!isSeekable) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (latestEnd <= 0L) return@awaitEachGesture
                            down.consume()

                            fun xToFraction(x: Float): Float {
                                val usable = (size.width - horizontalInsetPx * 2f).coerceAtLeast(1f)
                                return ((x - horizontalInsetPx) / usable).coerceIn(0f, 1f)
                            }

                            latestOnScrub(true)
                            scrubFraction = xToFraction(down.position.x)

                            var pointerId = down.id
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                    ?: event.changes.firstOrNull()
                                    ?: break
                                pointerId = change.id
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                scrubFraction = xToFraction(change.position.x)
                                change.consume()
                            }

                            val finalFraction = scrubFraction
                            val dur = latestEnd
                            if (finalFraction >= 0f && dur > 0L) {
                                val targetMs = (finalFraction * dur).toLong().coerceIn(0L, dur)
                                settledTargetMs = targetMs
                                latestOnSeek(targetMs)
                            }
                            scrubFraction = -1f
                            latestOnScrub(false)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val trackH = trackHeight.toPx()
                    val left = horizontalInsetPx
                    val right = (size.width - horizontalInsetPx).coerceAtLeast(left + 1f)
                    val usableWidth = right - left
                    val top = (size.height - trackH) / 2f
                    val corner = CornerRadius(trackH / 2f, trackH / 2f)

                    drawRoundRect(
                        color = Color(0x42FFFFFF),
                        topLeft = Offset(left, top),
                        size = Size(usableWidth, trackH),
                        cornerRadius = corner,
                    )

                    if (bufferedFraction > 0f) {
                        drawRoundRect(
                            color = Color(0x85FFFFFF),
                            topLeft = Offset(left, top),
                            size = Size(usableWidth * bufferedFraction, trackH),
                            cornerRadius = corner,
                        )
                    }

                    // IntroDB Intro / Recap / Credits Gold Chapter Markers
                    if (durationMs > 0L && segments.isNotEmpty()) {
                        for (seg in segments) {
                            val sFrac = (seg.start.toFloat() / durationMs).coerceIn(0f, 1f)
                            val eFrac = (seg.end.toFloat() / durationMs).coerceIn(sFrac, 1f)
                            if (eFrac > sFrac) {
                                drawRoundRect(
                                    color = NetflixGold.copy(alpha = 0.90f),
                                    topLeft = Offset(left + usableWidth * sFrac, top),
                                    size = Size((usableWidth * (eFrac - sFrac)).coerceAtLeast(3f), trackH),
                                    cornerRadius = corner,
                                )
                            }
                        }
                    }

                    if (activeFraction > 0f) {
                        drawRoundRect(
                            color = NetflixRed,
                            topLeft = Offset(left, top),
                            size = Size(usableWidth * activeFraction, trackH),
                            cornerRadius = corner,
                        )
                    }

                    val radius = thumbDiameter.toPx() / 2f
                    if (radius > 0f) {
                        val cx = left + usableWidth * activeFraction
                        val cy = size.height / 2f
                        if (isScrubbingNow) {
                            drawCircle(
                                color = NetflixRed.copy(alpha = 0.32f),
                                radius = radius * 1.55f,
                                center = Offset(cx, cy),
                            )
                        }
                        drawCircle(
                            color = NetflixRed,
                            radius = radius,
                            center = Offset(cx, cy),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val remainingMs = (durationMs - effectiveCurrentMs).coerceAtLeast(0L)
            val timeText = if (showRemaining && durationMs > 0L) {
                "${movieTime(effectiveCurrentMs)} / -${movieTime(remainingMs)}"
            } else {
                "${movieTime(effectiveCurrentMs)} / ${movieTime(durationMs)}"
            }
            Text(
                text = timeText,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable { showRemaining = !showRemaining },
            )

            Spacer(Modifier.weight(1f))

            Surface(
                onClick = onOpenSources,
                shape = RoundedCornerShape(4.dp),
                color = Color(0x8816161C),
                border = BorderStroke(1.dp, NetflixBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = NetflixMatchGreen, modifier = Modifier.size(14.dp))
                    Text(
                        text = sourceLabel,
                        color = NetflixMatchGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.width(6.dp))

            Surface(
                onClick = onOpenTracks,
                shape = RoundedCornerShape(4.dp),
                color = Color(0x8816161C),
                border = BorderStroke(1.dp, NetflixBorder),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Default.Subtitles, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(
                        text = "Audio & Subtitles",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MovieOpeningAdvisory(
    advisory: MovieContentAdvisory,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(260)) { -it / 2 } + fadeIn(tween(220)),
        exit = fadeOut(tween(320)),
        modifier = modifier,
    ) {
        Surface(
            color = Color(0xCC09090C),
            shape = RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp, topStart = 2.dp, bottomStart = 2.dp),
        ) {
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(NetflixRed),
                )
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "RATED ${advisory.ratingBadge}",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.5.sp,
                        letterSpacing = 0.6.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                    if (advisory.warnings.isNotEmpty()) {
                        Text(
                            text = advisory.warningsText,
                            color = Color(0xFFD5D8E2),
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieGestureHud(
    isBrightness: Boolean,
    fraction: Float,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.92f),
        exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.92f),
        modifier = modifier,
    ) {
        val clamped = fraction.coerceIn(0f, 1f)
        val pct = (clamped * 100).roundToInt()
        val icon = if (isBrightness) {
            if (clamped < 0.35f) Icons.Default.BrightnessLow
            else if (clamped < 0.7f) Icons.Default.BrightnessMedium
            else Icons.Default.BrightnessHigh
        } else {
            when {
                clamped <= 0.01f -> Icons.AutoMirrored.Filled.VolumeMute
                clamped < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                else -> Icons.AutoMirrored.Filled.VolumeUp
            }
        }

        Surface(
            color = Color(0xE8121218),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, NetflixBorder),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(icon, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(20.dp))
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(5.dp)
                        .background(Color(0x3DFFFFFF), RoundedCornerShape(3.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(clamped)
                            .fillMaxHeight()
                            .background(NetflixRed, RoundedCornerShape(3.dp)),
                    )
                }
                Text(
                    text = "$pct%",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    modifier = Modifier.widthIn(min = 34.dp),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun MovieDoubleTapBadge(
    deltaSeconds: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && deltaSeconds != 0,
        enter = fadeIn(tween(100)) + scaleIn(tween(140), initialScale = 0.85f),
        exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.9f),
        modifier = modifier,
    ) {
        val isForward = deltaSeconds > 0
        Surface(
            color = Color(0xCC0D0E12),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, NetflixRed.copy(alpha = 0.75f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!isForward) {
                    Icon(Icons.Default.FastRewind, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(20.dp))
                }
                Text(
                    text = if (isForward) "+${deltaSeconds}s" else "${deltaSeconds}s",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp,
                )
                if (isForward) {
                    Icon(Icons.Default.FastForward, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

internal fun movieTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    val minutes = seconds / 60
    return if (minutes >= 60) String.format(Locale.ROOT, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60)
}
