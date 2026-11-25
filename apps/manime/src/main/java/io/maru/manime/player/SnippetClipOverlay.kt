package io.maru.manime.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.CrunchyBorder
import io.maru.manime.CrunchyGold
import io.maru.manime.CrunchyOrange
import io.maru.manime.CrunchyTeal
import io.maru.manime.extensions.AniSkipInterval
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class ClipDragHandle { NONE, START, END, WINDOW }

private val CLIP_DURATION_PRESETS_SEC = listOf(5, 10, 15, 30, 60)

@Composable
internal fun SnippetClipOverlay(
    durationMs: Long,
    currentPositionMs: Long,
    range: SnippetRange,
    isPreviewPlaying: Boolean,
    exportState: SnippetExportState,
    skipIntervals: List<AniSkipInterval>,
    onRangeChange: (SnippetRange, Long) -> Unit,
    onTogglePreview: () -> Unit,
    onExportClick: () -> Unit,
    onCancelExport: () -> Unit,
    onResetExport: () -> Unit,
    onDismiss: () -> Unit
) {
    val isExporting = exportState is SnippetExportState.Exporting
    val controlsEnabled = !isExporting && durationMs > 0L

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0x28000000),
                        0.45f to Color(0x40000000),
                        0.68f to Color(0xDD0B0C0F),
                        1.0f to Color(0xF50B0C0F)
                    )
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Subtle top-right close badge so top video remains unobstructed for frame inspection
        Surface(
            onClick = {
                if (isExporting) onCancelExport()
                onDismiss()
            },
            shape = RoundedCornerShape(20.dp),
            color = Color(0xCC16181D),
            border = BorderStroke(1.dp, CrunchyBorder),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close clip editor",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (isExporting) "Cancel & Close" else "Close",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Bottom docked Clip Studio panel
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xEB121419),
            border = BorderStroke(1.dp, CrunchyBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header row: Title + live start/end/duration readout
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCut,
                            contentDescription = null,
                            tint = CrunchyOrange,
                            modifier = Modifier.size(17.dp)
                        )
                        Text(
                            text = "Save Snippet to Gallery",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.5.sp
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${formatPlayerTime(range.startMs)} → ${formatPlayerTime(range.endMs)}",
                            color = Color(0xFFE0E3EC),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = CrunchyTeal.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, CrunchyTeal.copy(alpha = 0.65f))
                        ) {
                            Text(
                                text = formatSnippetDuration(range.durationMs),
                                color = CrunchyTeal,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Dual-handle range seekbar
                SnippetRangeSeekbar(
                    durationMs = durationMs,
                    currentPositionMs = currentPositionMs,
                    range = range,
                    skipIntervals = skipIntervals,
                    enabled = controlsEnabled,
                    onRangeChange = onRangeChange
                )

                // Fine-tune nudges + Quick duration presets
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NudgePair(
                        label = "Start",
                        enabled = controlsEnabled,
                        onMinus = {
                            val next = normalizeSnippetRange(range.startMs - 1_000L, range.endMs, durationMs)
                            onRangeChange(next, next.startMs)
                        },
                        onPlus = {
                            val next = normalizeSnippetRange(range.startMs + 1_000L, range.endMs, durationMs)
                            onRangeChange(next, next.startMs)
                        }
                    )

                    NudgePair(
                        label = "End",
                        enabled = controlsEnabled,
                        onMinus = {
                            val next = normalizeSnippetRange(range.startMs, range.endMs - 1_000L, durationMs)
                            onRangeChange(next, next.endMs)
                        },
                        onPlus = {
                            val next = normalizeSnippetRange(range.startMs, range.endMs + 1_000L, durationMs)
                            onRangeChange(next, next.endMs)
                        }
                    )

                    Spacer(Modifier.width(4.dp))

                    val activeSec = (range.durationMs / 1000L).toInt()
                    for (presetSec in CLIP_DURATION_PRESETS_SEC) {
                        val presetMs = presetSec * 1000L
                        if (presetMs > durationMs && presetSec > 5) continue
                        val isSelected = abs(activeSec - presetSec) == 0
                        Surface(
                            onClick = {
                                if (controlsEnabled) {
                                    val next = applyDurationPreset(range, presetMs, durationMs)
                                    onRangeChange(next, next.endMs)
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) CrunchyOrange.copy(alpha = 0.22f) else Color(0xFF1C1F26),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) CrunchyOrange else CrunchyBorder
                            )
                        ) {
                            Text(
                                text = "${presetSec}s",
                                color = if (isSelected) CrunchyOrange else Color(0xFFD0D4DE),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // Bottom action bar / export status
                when (exportState) {
                    is SnippetExportState.Idle -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onTogglePreview,
                                enabled = controlsEnabled,
                                border = BorderStroke(1.dp, CrunchyBorder),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    text = if (isPreviewPlaying) "Pause Loop" else "Preview Clip",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                            }

                            Button(
                                onClick = onExportClick,
                                enabled = controlsEnabled && range.durationMs >= SNIPPET_MIN_DURATION_MS,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CrunchyOrange,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SaveAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = "Save MP4 to Gallery",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }

                    is SnippetExportState.Exporting -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = exportState.statusLabel,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                                val pct = exportState.progressPercent
                                if (pct != null) {
                                    LinearProgressIndicator(
                                        progress = { (pct / 100f).coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = CrunchyOrange,
                                        trackColor = CrunchyBorder
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = CrunchyOrange,
                                        trackColor = CrunchyBorder
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = onCancelExport,
                                border = BorderStroke(1.dp, CrunchyBorder),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Cancel", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }

                    is SnippetExportState.Saved -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = CrunchyTeal,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Saved MP4 to Gallery!",
                                    color = CrunchyTeal,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.5.sp
                                )
                                Text(
                                    text = exportState.galleryPath,
                                    color = Color(0xFFBFC4D0),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = onResetExport,
                                border = BorderStroke(1.dp, CrunchyBorder),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Clip Another", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.5.sp)
                            }
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CrunchyOrange,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Done", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            }
                        }
                    }

                    is SnippetExportState.Error -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = Color(0xFFFF6B6B),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = exportState.message,
                                color = Color(0xFFFFB4B4),
                                fontSize = 11.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = onExportClick,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CrunchyOrange,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Retry", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NudgePair(
    label: String,
    enabled: Boolean,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF1C1F26),
        border = BorderStroke(1.dp, CrunchyBorder)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = label,
                color = Color(0xFF9EA4B3),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 5.dp, end = 4.dp)
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onMinus)
            ) {
                Text("-1s", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, onClick = onPlus)
            ) {
                Text("+1s", color = CrunchyOrange, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SnippetRangeSeekbar(
    durationMs: Long,
    currentPositionMs: Long,
    range: SnippetRange,
    skipIntervals: List<AniSkipInterval>,
    enabled: Boolean,
    onRangeChange: (SnippetRange, Long) -> Unit
) {
    val density = LocalDensity.current
    val horizontalInsetPx = with(density) { 14.dp.toPx() }
    val handleHitRadiusPx = with(density) { 26.dp.toPx() }

    var barWidthPx by remember { mutableIntStateOf(0) }
    var activeHandle by remember { mutableStateOf(ClipDragHandle.NONE) }
    var dragBubbleTimeMs by remember { mutableLongStateOf(0L) }
    var dragBubbleFraction by remember { mutableFloatStateOf(0f) }

    val latestDuration by rememberUpdatedState(durationMs)
    val latestRange by rememberUpdatedState(range)
    val latestOnRangeChange by rememberUpdatedState(onRangeChange)

    val startFraction = if (durationMs > 0L) (range.startMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val endFraction = if (durationMs > 0L) (range.endMs.toFloat() / durationMs).coerceIn(startFraction, 1f) else 0f
    val playheadFraction = if (durationMs > 0L) (currentPositionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    Box(modifier = Modifier.fillMaxWidth()) {
        // Floating timestamp pill while dragging a handle
        AnimatedVisibility(
            visible = activeHandle != ClipDragHandle.NONE && barWidthPx > 0 && durationMs > 0L,
            enter = fadeIn(tween(100)) + scaleIn(tween(100), initialScale = 0.88f),
            exit = fadeOut(tween(100)) + scaleOut(tween(100), targetScale = 0.88f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset {
                    val usable = (barWidthPx - horizontalInsetPx * 2f).coerceAtLeast(1f)
                    val x = horizontalInsetPx + usable * dragBubbleFraction
                    val halfBubble = with(density) { 36.dp.toPx() }
                    val clampedX = x.coerceIn(halfBubble, (barWidthPx - halfBubble).coerceAtLeast(halfBubble))
                    IntOffset((clampedX - halfBubble).roundToInt(), with(density) { (-28).dp.roundToPx() })
                }
        ) {
            Surface(
                color = Color(0xF216181D),
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, CrunchyOrange)
            ) {
                Text(
                    text = formatPlayerTime(dragBubbleTimeMs),
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .onSizeChanged { barWidthPx = it.width }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val dur = latestDuration
                        if (dur <= 0L) return@awaitEachGesture

                        val usable = (size.width - horizontalInsetPx * 2f).coerceAtLeast(1f)
                        fun xToMs(x: Float): Long {
                            val frac = ((x - horizontalInsetPx) / usable).coerceIn(0f, 1f)
                            return (frac * dur).toLong()
                        }

                        val cur = latestRange
                        val startX = horizontalInsetPx + usable * (cur.startMs.toFloat() / dur)
                        val endX = horizontalInsetPx + usable * (cur.endMs.toFloat() / dur)
                        val downX = down.position.x

                        val distStart = abs(downX - startX)
                        val distEnd = abs(downX - endX)

                        val chosen = when {
                            distStart <= handleHitRadiusPx && distStart <= distEnd -> ClipDragHandle.START
                            distEnd <= handleHitRadiusPx -> ClipDragHandle.END
                            downX in startX..endX -> ClipDragHandle.WINDOW
                            distStart < distEnd -> ClipDragHandle.START
                            else -> ClipDragHandle.END
                        }

                        down.consume()
                        activeHandle = chosen
                        val anchorRange = cur
                        val anchorX = downX

                        fun applyPosition(x: Float) {
                            val currentDur = latestDuration
                            if (currentDur <= 0L) return
                            when (chosen) {
                                ClipDragHandle.START -> {
                                    val targetStart = xToMs(x)
                                    val maxStart = (anchorRange.endMs - SNIPPET_MIN_DURATION_MS).coerceAtLeast(0L)
                                    val minStart = (anchorRange.endMs - SNIPPET_MAX_DURATION_MS).coerceAtLeast(0L)
                                    val clampedStart = targetStart.coerceIn(minStart, maxStart)
                                    val next = normalizeSnippetRange(clampedStart, anchorRange.endMs, currentDur)
                                    dragBubbleTimeMs = next.startMs
                                    dragBubbleFraction = (next.startMs.toFloat() / currentDur).coerceIn(0f, 1f)
                                    latestOnRangeChange(next, next.startMs)
                                }
                                ClipDragHandle.END -> {
                                    val targetEnd = xToMs(x)
                                    val minEnd = (anchorRange.startMs + SNIPPET_MIN_DURATION_MS).coerceAtMost(currentDur)
                                    val maxEnd = (anchorRange.startMs + SNIPPET_MAX_DURATION_MS).coerceAtMost(currentDur)
                                    val clampedEnd = targetEnd.coerceIn(minEnd, maxEnd)
                                    val next = normalizeSnippetRange(anchorRange.startMs, clampedEnd, currentDur)
                                    dragBubbleTimeMs = next.endMs
                                    dragBubbleFraction = (next.endMs.toFloat() / currentDur).coerceIn(0f, 1f)
                                    latestOnRangeChange(next, next.endMs)
                                }
                                ClipDragHandle.WINDOW -> {
                                    val deltaFrac = (x - anchorX) / usable
                                    val deltaMs = (deltaFrac * currentDur).toLong()
                                    val next = shiftSnippetWindow(anchorRange, deltaMs, currentDur)
                                    dragBubbleTimeMs = next.startMs
                                    dragBubbleFraction = (next.startMs.toFloat() / currentDur).coerceIn(0f, 1f)
                                    latestOnRangeChange(next, next.startMs)
                                }
                                ClipDragHandle.NONE -> Unit
                            }
                        }

                        if (chosen != ClipDragHandle.WINDOW) {
                            applyPosition(downX)
                        } else {
                            dragBubbleTimeMs = anchorRange.startMs
                            dragBubbleFraction = (anchorRange.startMs.toFloat() / dur).coerceIn(0f, 1f)
                        }

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
                            applyPosition(change.position.x)
                            change.consume()
                        }

                        activeHandle = ClipDragHandle.NONE
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val trackH = 12.dp.toPx()
                val left = horizontalInsetPx
                val right = (size.width - horizontalInsetPx).coerceAtLeast(left + 1f)
                val usableWidth = right - left
                val top = (size.height - trackH) / 2f
                val corner = CornerRadius(trackH / 2f, trackH / 2f)

                // 1. Full episode base track
                drawRoundRect(
                    color = Color(0x36FFFFFF),
                    topLeft = Offset(left, top),
                    size = Size(usableWidth, trackH),
                    cornerRadius = corner
                )

                // 2. AniSkip OP/ED markers for easy OP/ED clipping
                if (durationMs > 0L && skipIntervals.isNotEmpty()) {
                    val durSec = durationMs / 1000.0
                    for (interval in skipIntervals) {
                        val sFrac = (interval.startTimeSeconds / durSec).toFloat().coerceIn(0f, 1f)
                        val eFrac = (interval.endTimeSeconds / durSec).toFloat().coerceIn(sFrac, 1f)
                        if (eFrac > sFrac) {
                            drawRoundRect(
                                color = CrunchyGold.copy(alpha = 0.55f),
                                topLeft = Offset(left + usableWidth * sFrac, top),
                                size = Size((usableWidth * (eFrac - sFrac)).coerceAtLeast(3f), trackH),
                                cornerRadius = corner
                            )
                        }
                    }
                }

                // 3. Selected clip window
                val selLeft = left + usableWidth * startFraction
                val selRight = left + usableWidth * endFraction
                val selWidth = (selRight - selLeft).coerceAtLeast(4f)
                val selTop = top - 2.dp.toPx()
                val selHeight = trackH + 4.dp.toPx()

                drawRoundRect(
                    color = CrunchyOrange.copy(alpha = 0.32f),
                    topLeft = Offset(selLeft, selTop),
                    size = Size(selWidth, selHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )
                // Top and bottom crisp orange rails for the clip window
                val railH = 2.5.dp.toPx()
                drawRect(
                    color = CrunchyOrange,
                    topLeft = Offset(selLeft, selTop),
                    size = Size(selWidth, railH)
                )
                drawRect(
                    color = CrunchyOrange,
                    topLeft = Offset(selLeft, selTop + selHeight - railH),
                    size = Size(selWidth, railH)
                )

                // 4. Live playhead marker
                val playX = left + usableWidth * playheadFraction
                drawLine(
                    color = Color.White,
                    start = Offset(playX, selTop - 3.dp.toPx()),
                    end = Offset(playX, selTop + selHeight + 3.dp.toPx()),
                    strokeWidth = 2.dp.toPx()
                )

                // 5. Left (Start) & Right (End) pill grab handles
                val handleW = 10.dp.toPx()
                val handleH = 28.dp.toPx()
                val handleTop = (size.height - handleH) / 2f
                val handleCorner = CornerRadius(5.dp.toPx(), 5.dp.toPx())

                fun drawHandle(cx: Float, isFocused: Boolean) {
                    if (isFocused) {
                        drawRoundRect(
                            color = CrunchyOrange.copy(alpha = 0.35f),
                            topLeft = Offset(cx - handleW * 0.9f, handleTop - 3.dp.toPx()),
                            size = Size(handleW * 1.8f, handleH + 6.dp.toPx()),
                            cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                        )
                    }
                    drawRoundRect(
                        color = if (isFocused) Color.White else CrunchyOrange,
                        topLeft = Offset(cx - handleW / 2f, handleTop),
                        size = Size(handleW, handleH),
                        cornerRadius = handleCorner
                    )
                    // Inner grip line
                    drawLine(
                        color = Color(0xFF0B0C0F),
                        start = Offset(cx, handleTop + 7.dp.toPx()),
                        end = Offset(cx, handleTop + handleH - 7.dp.toPx()),
                        strokeWidth = 1.6.dp.toPx()
                    )
                }

                drawHandle(selLeft, activeHandle == ClipDragHandle.START)
                drawHandle(selRight, activeHandle == ClipDragHandle.END)
            }
        }
    }
}
