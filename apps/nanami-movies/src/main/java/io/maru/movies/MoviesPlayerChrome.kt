package io.maru.movies

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

private data class GestureIndicator(val label: String, val icon: ImageVector, val level: Float? = null)
internal val CinemaRed = Color(0xFFFF5B69)

@Composable
internal fun MoviePlayerChrome(
    title: MovieTitle,
    episode: MovieEpisode?,
    ui: MoviePlaybackUi,
    loading: String?,
    problem: String?,
    controls: Boolean,
    locked: Boolean,
    hideChrome: Boolean,
    persistentProgress: Boolean,
    skipSegment: MovieSegment?,
    nextCountdown: Int?,
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
    var indicator by remember { mutableStateOf<GestureIndicator?>(null) }
    var signal by remember { mutableIntStateOf(0) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var sawVideo by remember(title.imdbId, episode?.id) { mutableStateOf(false) }
    LaunchedEffect(ui.ready, ui.position) { if (ui.ready && ui.position > 0) sawVideo = true }
    LaunchedEffect(signal) {
        if (indicator == null) return@LaunchedEffect
        delay(850)
        indicator = null
    }
    val showButtons = !hideChrome && !locked && (controls || loading != null || problem != null || nextCountdown != null)

    Box(Modifier.fillMaxSize()) {
        if (!hideChrome && !sawVideo && (loading != null || problem != null)) {
            val backdrop = episode?.thumbnail ?: title.backdrop ?: title.poster
            if (backdrop != null) {
                AsyncImage(backdrop, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.38f)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x9923161E), Color.Black.copy(alpha = 0.77f)))))
        }
        if (!hideChrome && !locked) {
            Box(Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { toggleControls() }, onDoubleTap = { touch ->
                        if (state.seekable) {
                            val forward = touch.x >= size.width / 2f
                            seek(state.position + if (forward) 10_000L else -10_000L)
                            indicator = GestureIndicator(if (forward) "+10 seconds" else "−10 seconds",
                                if (forward) Icons.Default.FastForward else Icons.Default.FastRewind)
                            signal++
                        }
                    })
                }
                .pointerInput(Unit) {
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
                            indicator = if (left) GestureIndicator("Brightness", Icons.Default.WbSunny, level)
                            else GestureIndicator("Volume", Icons.AutoMirrored.Filled.VolumeUp, level)
                            signal++
                        },
                    )
                })
        }

        if (showButtons) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.72f),
            0.32f to Color.Transparent,
            0.67f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.8f),
        )))

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 10.dp, top = 4.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showButtons) {
                MoviePlayerIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Column(Modifier.weight(1f).padding(start = 6.dp, end = 6.dp)) {
                    Text(title.title, style = MaterialTheme.typography.titleMedium, color = Color.White,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (episode != null) Text("S${episode.season} · E${episode.episode}  ${episode.title}",
                        style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.76f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else Spacer(Modifier.weight(1f))
            tools(showButtons && ui.ready)
            if (showButtons) {
                if (onPip != null) MoviePlayerIcon(Icons.Default.PictureInPictureAlt, "Picture-in-picture", onPip)
                MoviePlayerIcon(Icons.Default.LockOpen, "Lock controls", onLock)
                MoviePlayerIcon(Icons.Default.Settings, "Playback settings", onSettings)
            }
        }

        if (!hideChrome && locked) {
            Box(Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.End))
                .padding(12.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)) {
                MoviePlayerIcon(Icons.Default.Lock, "Unlock controls", onLock)
            }
        }

        if (!hideChrome && (loading != null || problem != null)) {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (loading != null) CircularProgressIndicator(Modifier.size(35.dp), color = CinemaRed, strokeWidth = 3.dp)
                Text(problem ?: loading.orEmpty(), color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center)
                if (!locked) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (problem != null) Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = CinemaRed, contentColor = Color.Black)) {
                        Text("Try again")
                    }
                    TextButton(onClick = onSources) { Text("Video sources", color = Color.White) }
                }
            }
        } else if (showButtons && nextCountdown == null) {
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(30.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MoviePlayerIcon(Icons.Default.FastRewind, "Back 10 seconds", { onSeek(ui.position - 10_000) }, enabled = ui.seekable)
                Box(Modifier.size(66.dp).background(Color.Black.copy(alpha = 0.57f), CircleShape), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onTogglePlayback, modifier = Modifier.size(62.dp)) {
                        Icon(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (ui.playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(37.dp))
                    }
                }
                MoviePlayerIcon(Icons.Default.FastForward, "Forward 10 seconds", { onSeek(ui.position + 10_000) }, enabled = ui.seekable)
            }
        }

        if (showButtons && ui.duration > 0 && ui.ready) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 18.dp, vertical = 4.dp)) {
                val position = (scrub?.times(ui.duration))?.toLong() ?: ui.position
                val fraction = position.toFloat() / ui.duration
                Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) {
                    val buffered = (ui.buffered.toFloat() / ui.duration).coerceIn(0f, 1f)
                    Canvas(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = 8.dp)) {
                        drawRect(Color.White.copy(alpha = 0.25f), size = androidx.compose.ui.geometry.Size(size.width * buffered, size.height))
                    }
                    Slider(value = fraction.coerceIn(0f, 1f), onValueChange = { scrub = it },
                        onValueChangeFinished = { scrub?.let { onSeek((it * ui.duration).toLong()) }; scrub = null },
                        modifier = Modifier.fillMaxWidth(), enabled = ui.seekable,
                        colors = SliderDefaults.colors(thumbColor = CinemaRed, activeTrackColor = CinemaRed,
                            inactiveTrackColor = Color.White.copy(alpha = 0.29f)))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${movieTime(position)} / ${movieTime(ui.duration)}", color = Color.White,
                        style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    MoviePlayerIcon(Icons.Default.VideoLibrary, "Video sources", onSources)
                    MoviePlayerIcon(Icons.Default.Subtitles, "Audio and subtitles", onTracks)
                    if (onPreviousEpisode != null) MoviePlayerIcon(Icons.Default.SkipPrevious, "Previous episode", onPreviousEpisode)
                    if (onNextEpisode != null) MoviePlayerIcon(Icons.Default.SkipNext, "Next episode", onNextEpisode)
                }
            }
        } else if (!hideChrome && persistentProgress && ui.duration > 0) {
            LinearProgressIndicator(progress = { (ui.position.toFloat() / ui.duration).coerceIn(0f, 1f) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                color = CinemaRed, trackColor = Color.White.copy(alpha = 0.16f))
        }

        if (!hideChrome && !locked && skipSegment != null && loading == null && problem == null) {
            Button(onClick = { onSkipSegment(skipSegment) },
                modifier = Modifier.align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                    .padding(end = 18.dp, bottom = if (showButtons) 93.dp else 22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF151015))) {
                Text(skipSegment.type.action)
            }
        }

        if (showButtons && nextCountdown != null && onNextEpisode != null) {
            Row(Modifier.align(Alignment.Center).background(Color(0xEA171218)).padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancelNext) { Text("Cancel", color = Color.White) }
                Button(onClick = onNextEpisode, colors = ButtonDefaults.buttonColors(containerColor = CinemaRed, contentColor = Color.Black)) {
                    Text("Next episode in $nextCountdown")
                }
            }
        }

        if (!hideChrome && !locked) indicator?.let { info ->
            Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.85f))
                .padding(horizontal = 20.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(info.icon, null, tint = Color.White)
                Text(info.label, color = Color.White, style = MaterialTheme.typography.labelMedium)
                info.level?.let { level -> LinearProgressIndicator(progress = { level }, modifier = Modifier.width(98.dp), color = CinemaRed) }
            }
        }
    }
}

@Composable
private fun MoviePlayerIcon(icon: ImageVector, label: String, click: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = click, enabled = enabled, modifier = Modifier.heightIn(min = 40.dp)) {
        Icon(icon, label, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.38f))
    }
}

internal fun movieTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1_000
    val minutes = seconds / 60
    return if (minutes >= 60) String.format(Locale.ROOT, "%d:%02d:%02d", minutes / 60, minutes % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", minutes, seconds % 60)
}
