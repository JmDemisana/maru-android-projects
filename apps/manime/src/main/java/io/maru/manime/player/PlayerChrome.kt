package io.maru.manime.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.maru.manime.CrunchyBorder
import io.maru.manime.CrunchyOrange
import io.maru.manime.MaruTextMuted
import io.maru.manime.torrent.ActiveStreamStatus

@Composable
internal fun PlayerHeader(
    title: String,
    episode: String,
    onBack: () -> Unit,
    onSettings: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            Text(episode, color = MaruTextMuted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
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
    onBack: () -> Unit
) {
    PlayerStatusLayout(title, episode, onBack) {
        val status = if (torrentRequest == null) null
            else serviceStatus.value.takeIf { it.requestId == torrentRequest && !it.isReady }
        val stateLabel = when {
            status?.phase == "prebuffer" -> "Buffering the first part"
            status != null -> "Connecting to the source"
            lookingForSource -> "Finding a video source"
            else -> "Preparing the video"
        }
        CircularProgressIndicator(color = CrunchyOrange, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        Text(stateLabel, color = Color.White, style = MaterialTheme.typography.titleSmall)
        if (status?.phase == "prebuffer" && status.progressPercent > 0) {
            LinearProgressIndicator(
                progress = { (status.progressPercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.widthIn(max = 208.dp).fillMaxWidth(),
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
    onRetry: () -> Unit
) {
    PlayerStatusLayout(title, episode, onBack) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CrunchyOrange)
        Text(message, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(
            containerColor = CrunchyOrange, contentColor = Color.Black
        )) {
            Text(retryLabel)
        }
    }
}

@Composable
private fun PlayerStatusLayout(
    title: String,
    episode: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxSize().background(Color(0xF20B0C0F))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        PlayerHeader(title, episode, onBack)
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(if (primary) 52.dp else 48.dp).background(
            if (primary) CrunchyOrange else Color(0x80000000), CircleShape
        )
    ) {
        Icon(icon, contentDescription = label, tint = when {
            primary -> Color.Black
            enabled -> Color.White
            else -> Color(0x66FFFFFF)
        }, modifier = Modifier.size(if (primary) 30.dp else 23.dp))
    }
}

@Composable
internal fun PlayerTimeline(
    position: LongState,
    buffered: LongState,
    duration: LongState,
    isSeekable: Boolean,
    label: String,
    onScrub: (Boolean) -> Unit,
    onSeek: (Long) -> Unit
) {
    val end = duration.longValue
    val current = position.longValue
    var preview by remember { mutableFloatStateOf(-1f) }
    val latestOnScrub by rememberUpdatedState(onScrub)
    DisposableEffect(Unit) {
        onDispose { if (preview >= 0f) latestOnScrub(false) }
    }
    val fraction = if (end > 0L) (current.toFloat() / end).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (end > 0L) (buffered.longValue.toFloat() / end).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(4.dp)
                .background(Color(0x3DFFFFFF), RoundedCornerShape(2.dp))) {
                Box(Modifier.fillMaxWidth(bufferedFraction).fillMaxHeight()
                    .background(Color(0x66FFFFFF), RoundedCornerShape(2.dp)))
            }
            Slider(
                value = if (preview >= 0f) preview else fraction,
                enabled = isSeekable && end > 0L,
                onValueChange = { value ->
                    if (preview < 0f) onScrub(true)
                    preview = value
                },
                onValueChangeFinished = {
                    if (preview >= 0f) onSeek((preview * end).toLong().coerceIn(0L, end))
                    preview = -1f
                    onScrub(false)
                },
                colors = SliderDefaults.colors(
                    thumbColor = CrunchyOrange,
                    activeTrackColor = CrunchyOrange,
                    inactiveTrackColor = Color.Transparent
                )
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val displayPosition = if (preview >= 0f) (preview * end).toLong() else current
            Text("${formatPlayerTime(displayPosition)} / ${formatPlayerTime(end)}",
                color = Color.White, style = MaterialTheme.typography.labelSmall)
            Text(label, color = MaruTextMuted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun PlayerUpNext(episodeNum: Int, seconds: Int, onPlay: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xF2141519),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, CrunchyBorder)
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Episode $episodeNum", color = Color.White, style = MaterialTheme.typography.labelLarge)
                Text("Up next in ${seconds}s", color = MaruTextMuted, style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onPlay) { Text("Play", color = CrunchyOrange) }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Cancel autoplay", tint = Color.White)
            }
        }
    }
}

private fun formatPlayerTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    val hours = totalSeconds / 3600
    val minutes = totalSeconds / 60 % 60
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}
