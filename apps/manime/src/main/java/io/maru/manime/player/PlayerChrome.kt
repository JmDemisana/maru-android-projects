package io.maru.manime.player

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.ContentAdvisory
import io.maru.manime.CrunchyBorder
import io.maru.manime.CrunchyGold
import io.maru.manime.CrunchyOrange
import io.maru.manime.CrunchyTeal
import io.maru.manime.MaruTextMuted
import io.maru.manime.extensions.AniSkipInterval
import io.maru.manime.pressableClick
import io.maru.manime.torrent.ActiveStreamStatus
import io.maru.manime.tv.nanamiFocusHighlight
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun PlayerHeader(
    title: String,
    episode: String,
    onBack: () -> Unit,
    onClipClick: (() -> Unit)? = null,
    onLockClick: (() -> Unit)? = null,
    onPipClick: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.nanamiFocusHighlight(CrunchyOrange, cornerRadius = 20.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = episode,
                color = Color(0xFFD0D3DC),
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (onClipClick != null) {
            IconButton(
                onClick = onClipClick,
                modifier = Modifier.nanamiFocusHighlight(CrunchyOrange, cornerRadius = 20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCut,
                    contentDescription = "Save snippet to gallery",
                    tint = Color.White
                )
            }
        }
        if (onPipClick != null) {
            IconButton(
                onClick = onPipClick,
                modifier = Modifier.nanamiFocusHighlight(CrunchyOrange, cornerRadius = 20.dp)
            ) {
                Icon(
                    Icons.Default.PictureInPictureAlt,
                    contentDescription = "Picture in picture",
                    tint = Color.White
                )
            }
        }
        if (onLockClick != null) {
            IconButton(
                onClick = onLockClick,
                modifier = Modifier.nanamiFocusHighlight(CrunchyOrange, cornerRadius = 20.dp)
            ) {
                Icon(
                    Icons.Default.LockOpen,
                    contentDescription = "Lock controls",
                    tint = Color.White
                )
            }
        }
        if (onSettings != null) {
            IconButton(
                onClick = onSettings,
                modifier = Modifier.nanamiFocusHighlight(CrunchyOrange, cornerRadius = 20.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = "Playback settings", tint = Color.White)
            }
        }
    }
}

@Composable
internal fun PlayerLoading(
    title: String,
    episode: String,
    lookingForSource: Boolean,
    torrentRequest: String?,
    serviceStatus: State<ActiveStreamStatus>,
    onBack: () -> Unit,
    onSettings: () -> Unit
) {
    PlayerStatusLayout(title, episode, onBack, onSettings) {
        val status = if (torrentRequest == null) null
        else serviceStatus.value.takeIf { it.requestId == torrentRequest && !it.isReady }
        val stateLabel = when {
            status?.phase == "prebuffer" && status.connectedPeers > 0 ->
                status.statusMessage
            status?.phase == "prebuffer" -> "Buffering opening scene…"
            status != null -> "Connecting to stream…"
            lookingForSource -> "Finding fastest video source…"
            else -> "Preparing video…"
        }
        CircularProgressIndicator(color = CrunchyOrange, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(4.dp))
        Text(stateLabel, color = Color.White, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        if (status?.phase == "prebuffer" && status.progressPercent > 0) {
            val animProgress by animateFloatAsState(
                targetValue = (status.progressPercent / 100f).coerceIn(0f, 1f),
                animationSpec = tween(220),
                label = "prebuffer_progress"
            )
            LinearProgressIndicator(
                progress = { animProgress },
                modifier = Modifier
                    .widthIn(max = 220.dp)
                    .fillMaxWidth()
                    .height(4.dp),
                color = CrunchyOrange,
                trackColor = CrunchyBorder
            )
        }
    }
}

@Composable
internal fun PlayerProblem(
    title: String,
    episode: String,
    message: String,
    retryLabel: String,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSettings: (() -> Unit)? = null
) {
    PlayerStatusLayout(title, episode, onBack, onSettings) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(36.dp))
        Text(
            text = message,
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = CrunchyOrange,
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text(retryLabel, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun PlayerStatusLayout(
    title: String,
    episode: String,
    onBack: () -> Unit,
    onSettings: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xEB08090C))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        PlayerHeader(title, episode, onBack, onSettings = onSettings)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
    }
}

@Composable
internal fun PlayerTransportButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false
) {
    val size = if (primary) 62.dp else 48.dp
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .background(
                color = if (primary) CrunchyOrange else Color(0x880D0E12),
                shape = CircleShape
            )
            .pressableClick(enabled = enabled, pressedScale = 0.90f, onClick = onClick)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = when {
                primary -> Color.Black
                enabled -> Color.White
                else -> Color(0x55FFFFFF)
            },
            modifier = Modifier.size(if (primary) 34.dp else 24.dp)
        )
    }
}

/**
 * Custom 60fps Crunchyroll Seekbar with immediate touch-down scrub lock, tap-to-seek,
 * settled seek target holding (no thumb snap-back!), floating time preview bubble, and AniSkip OP/ED markers.
 */
@Composable
internal fun PlayerTimeline(
    position: LongState,
    buffered: LongState,
    duration: LongState,
    isSeekable: Boolean,
    label: String,
    skipIntervals: List<AniSkipInterval> = emptyList(),
    onScrub: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onOpenSettings: (() -> Unit)? = null
) {
    val end = duration.longValue
    val current = position.longValue
    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    var settledTargetMs by remember { mutableLongStateOf(-1L) }
    var showRemaining by remember { mutableStateOf(false) }
    var barWidthPx by remember { mutableIntStateOf(0) }

    val latestEnd by rememberUpdatedState(end)
    val latestOnScrub by rememberUpdatedState(onScrub)
    val latestOnSeek by rememberUpdatedState(onSeek)

    // Release settledTargetMs once ExoPlayer catches up within 1.5s or after timeout
    LaunchedEffect(settledTargetMs) {
        val target = settledTargetMs
        if (target < 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(1800)
        if (settledTargetMs == target) settledTargetMs = -1L
    }
    LaunchedEffect(current) {
        if (settledTargetMs >= 0L && abs(current - settledTargetMs) <= 1500L) {
            settledTargetMs = -1L
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (scrubFraction >= 0f) latestOnScrub(false)
        }
    }

    val effectiveCurrentMs = when {
        scrubFraction >= 0f && end > 0L -> (scrubFraction * end).toLong()
        settledTargetMs >= 0L -> settledTargetMs
        else -> current
    }
    val activeFraction = when {
        scrubFraction >= 0f -> scrubFraction.coerceIn(0f, 1f)
        end > 0L -> (effectiveCurrentMs.toFloat() / end).coerceIn(0f, 1f)
        else -> 0f
    }
    val bufferedFraction = if (end > 0L) (buffered.longValue.toFloat() / end).coerceIn(0f, 1f) else 0f
    val isScrubbingNow = scrubFraction >= 0f

    val trackHeight by animateDpAsState(
        targetValue = if (isScrubbingNow) 7.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "seek_track_height"
    )
    val thumbDiameter by animateDpAsState(
        targetValue = when {
            !isSeekable || end <= 0L -> 0.dp
            isScrubbingNow -> 18.dp
            else -> 13.dp
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "seek_thumb_size"
    )

    val density = LocalDensity.current
    val horizontalInsetDp = 10.dp
    val horizontalInsetPx = with(density) { horizontalInsetDp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Floating timestamp bubble above thumb while scrubbing
            androidx.compose.animation.AnimatedVisibility(
                visible = isScrubbingNow && end > 0L && barWidthPx > 0,
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
                    }
            ) {
                Surface(
                    color = Color(0xF016181D),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, CrunchyOrange)
                ) {
                    Text(
                        text = formatPlayerTime(effectiveCurrentMs),
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Generous 40dp touch target for effortless seeking & scrubbing
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
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val trackH = trackHeight.toPx()
                    val left = horizontalInsetPx
                    val right = (size.width - horizontalInsetPx).coerceAtLeast(left + 1f)
                    val usableWidth = right - left
                    val top = (size.height - trackH) / 2f
                    val corner = CornerRadius(trackH / 2f, trackH / 2f)

                    // 1. Unplayed background track
                    drawRoundRect(
                        color = Color(0x42FFFFFF),
                        topLeft = Offset(left, top),
                        size = Size(usableWidth, trackH),
                        cornerRadius = corner
                    )

                    // 2. Buffered range
                    if (bufferedFraction > 0f) {
                        drawRoundRect(
                            color = Color(0x85FFFFFF),
                            topLeft = Offset(left, top),
                            size = Size(usableWidth * bufferedFraction, trackH),
                            cornerRadius = corner
                        )
                    }

                    // 3. AniSkip OP/ED chapter markers in Crunchyroll Gold
                    if (end > 0L && skipIntervals.isNotEmpty()) {
                        val endSec = end / 1000.0
                        for (interval in skipIntervals) {
                            val sFrac = (interval.startTimeSeconds / endSec).toFloat().coerceIn(0f, 1f)
                            val eFrac = (interval.endTimeSeconds / endSec).toFloat().coerceIn(sFrac, 1f)
                            if (eFrac > sFrac) {
                                drawRoundRect(
                                    color = CrunchyGold.copy(alpha = 0.88f),
                                    topLeft = Offset(left + usableWidth * sFrac, top),
                                    size = Size((usableWidth * (eFrac - sFrac)).coerceAtLeast(3f), trackH),
                                    cornerRadius = corner
                                )
                            }
                        }
                    }

                    // 4. Active played range
                    if (activeFraction > 0f) {
                        drawRoundRect(
                            color = CrunchyOrange,
                            topLeft = Offset(left, top),
                            size = Size(usableWidth * activeFraction, trackH),
                            cornerRadius = corner
                        )
                    }

                    // 5. Glowing circular thumb
                    val radius = thumbDiameter.toPx() / 2f
                    if (radius > 0f) {
                        val cx = left + usableWidth * activeFraction
                        val cy = size.height / 2f
                        if (isScrubbingNow) {
                            drawCircle(
                                color = CrunchyOrange.copy(alpha = 0.30f),
                                radius = radius * 1.55f,
                                center = Offset(cx, cy)
                            )
                        }
                        drawCircle(
                            color = CrunchyOrange,
                            radius = radius,
                            center = Offset(cx, cy)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val remainingMs = (end - effectiveCurrentMs).coerceAtLeast(0L)
            val timeText = if (showRemaining && end > 0L) {
                "${formatPlayerTime(effectiveCurrentMs)} / -${formatPlayerTime(remainingMs)}"
            } else {
                "${formatPlayerTime(effectiveCurrentMs)} / ${formatPlayerTime(end)}"
            }
            Text(
                text = timeText,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable { showRemaining = !showRemaining }
            )

            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0x8016181D),
                border = BorderStroke(1.dp, CrunchyBorder),
                modifier = if (onOpenSettings != null) Modifier.clickable(onClick = onOpenSettings) else Modifier
            ) {
                Text(
                    text = label,
                    color = CrunchyTeal,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
internal fun CrunchySkipButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = Color(0xF0FFFFFF),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label.uppercase(),
                color = Color.Black,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.5.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
internal fun PlayerGestureHud(
    isBrightness: Boolean,
    fraction: Float,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(120)) + scaleIn(tween(120), initialScale = 0.92f),
        exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.92f),
        modifier = modifier
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
            color = Color(0xE812141A),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, CrunchyBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(icon, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(20.dp))
                Box(
                    modifier = Modifier
                        .width(110.dp)
                        .height(5.dp)
                        .background(Color(0x3DFFFFFF), RoundedCornerShape(3.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(clamped)
                            .fillMaxHeight()
                            .background(CrunchyOrange, RoundedCornerShape(3.dp))
                    )
                }
                Text(
                    text = "$pct%",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    modifier = Modifier.widthIn(min = 34.dp),
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

@Composable
internal fun DoubleTapSeekBadge(
    deltaSeconds: Int,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && deltaSeconds != 0,
        enter = fadeIn(tween(100)) + scaleIn(tween(140), initialScale = 0.85f),
        exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.9f),
        modifier = modifier
    ) {
        val isForward = deltaSeconds > 0
        Surface(
            color = Color(0xCC0D0E12),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, CrunchyOrange.copy(alpha = 0.7f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!isForward) {
                    Icon(Icons.Default.FastRewind, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(20.dp))
                }
                Text(
                    text = if (isForward) "+${deltaSeconds}s" else "${deltaSeconds}s",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
                if (isForward) {
                    Icon(Icons.Default.FastForward, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
internal fun PlayerUpNext(
    episodeNum: Int,
    seconds: Int,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xF2141519),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, CrunchyOrange)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column {
                Text("Up Next • Episode $episodeNum", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                Text("Starting in ${seconds}s", color = MaruTextMuted, style = MaterialTheme.typography.labelSmall)
            }
            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange, contentColor = Color.Black),
                shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Play Now", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Cancel autoplay", tint = Color.White)
            }
        }
    }
}

@Composable
internal fun PlayerOpeningAdvisory(
    advisory: ContentAdvisory,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(260)) { -it / 2 } + fadeIn(tween(220)),
        exit = fadeOut(tween(320)),
        modifier = modifier
    ) {
        val barColor = when (advisory.ageRating) {
            "18+" -> Color(0xFFE53935)
            "16+", "14+" -> CrunchyOrange
            else -> CrunchyTeal
        }
        Surface(
            color = Color(0xCC090A0D),
            shape = RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp, topStart = 2.dp, bottomStart = 2.dp)
        ) {
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(barColor)
                )
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "RATED ${advisory.ageRating}",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.5.sp,
                        letterSpacing = 0.6.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (advisory.warnings.isNotEmpty()) {
                        Text(
                            text = advisory.warningsText,
                            color = Color(0xFFD5D8E2),
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.5.sp,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

internal fun formatPlayerTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    val hours = totalSeconds / 3600
    val minutes = totalSeconds / 60 % 60
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}

@Composable
internal fun PersistentMiniProgressBar(
    position: Long,
    bufferedPosition: Long,
    duration: Long,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(140)),
        modifier = modifier
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
        ) {
            val total = duration.coerceAtLeast(1L).toFloat()
            val playedFraction = (position.toFloat() / total).coerceIn(0f, 1f)
            val bufferedFraction = (bufferedPosition.toFloat() / total).coerceIn(0f, 1f)

            // Dark semi-transparent background track
            drawRect(
                color = Color.Black.copy(alpha = 0.50f),
                size = size
            )

            // Buffered line
            if (bufferedFraction > 0f) {
                drawRect(
                    color = Color.White.copy(alpha = 0.35f),
                    size = size.copy(width = size.width * bufferedFraction)
                )
            }

            // Played orange progress line
            if (playedFraction > 0f) {
                drawRect(
                    color = CrunchyOrange,
                    size = size.copy(width = size.width * playedFraction)
                )
            }
        }
    }
}
