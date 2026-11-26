package io.maru.movies

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import io.maru.manime.extensions.StremioStreamResult
import io.maru.manime.extensions.SubtitleTrack

internal enum class MoviePlayerPanel(val label: String) {
    GENERAL("Playback"), SOURCES("Sources"), SUBTITLES("Audio & subtitles"),
}

internal data class MovieTrack(val group: Tracks.Group, val index: Int, val label: String) {
    val selected: Boolean get() = group.isTrackSelected(index)
}

@OptIn(UnstableApi::class)
internal fun movieTrackChoices(tracks: Tracks, type: Int): List<MovieTrack> = buildList {
    for (group in tracks.groups) {
        if (group.type != type) continue
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            val label = when {
                type == C.TRACK_TYPE_VIDEO && format.height > 0 -> if (format.height >= 2_100) "4K" else "${format.height}p"
                else -> format.label?.takeIf(String::isNotBlank) ?: languageName(format.language)
            }
            val channels = format.channelCount.takeIf { it > 0 && type == C.TRACK_TYPE_AUDIO }
            add(MovieTrack(group, index, if (channels == null) label else "$label · ${channels} ch"))
        }
    }
}

@Composable
internal fun MoviePlaybackSettings(
    initialPanel: MoviePlayerPanel,
    options: MoviePlayerOptions,
    onOptions: (MoviePlayerOptions) -> Unit,
    sources: List<StremioStreamResult>?,
    isOffline: Boolean,
    offlineAvailable: Boolean,
    selectedSource: StremioStreamResult?,
    onSelectSource: (StremioStreamResult) -> Unit,
    onRetrySources: () -> Unit,
    onUseOffline: () -> Unit,
    tracks: Tracks,
    subtitleOptions: List<SubtitleTrack>,
    subtitlesLoading: Boolean,
    selectedSubtitle: SubtitleTrack?,
    onSelectExternal: (SubtitleTrack) -> Unit,
    onSelectTrack: (Int, MovieTrack?) -> Unit,
    onPanel: (MoviePlayerPanel) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
            contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 740.dp).fillMaxWidth().fillMaxHeight(0.9f),
                color = Color(0xFF19151C), contentColor = Color.White, tonalElevation = 2.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Playback settings", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close playback settings") }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        MoviePlayerPanel.entries.forEach { panel ->
                            TextButton(onClick = { onPanel(panel) },
                                modifier = Modifier.background(if (initialPanel == panel) Color(0xFF37202C) else Color.Transparent)) {
                                Text(panel.label, color = if (initialPanel == panel) CinemaRed else Color.White,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp), color = Color.White.copy(alpha = 0.14f))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        when (initialPanel) {
                            MoviePlayerPanel.GENERAL -> {
                                item { SettingsLabel("Playback speed") }
                                item {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                            TextButton(onClick = { onOptions(options.copy(speed = speed)) },
                                                modifier = Modifier.weight(1f)) {
                                                Text("${speed.toString().removeSuffix(".0")}×", maxLines = 1,
                                                    color = if (options.speed == speed) CinemaRed else Color.White)
                                            }
                                        }
                                    }
                                }
                                item { SettingsSwitch("Autoplay next episode", options.autoplay) { onOptions(options.copy(autoplay = it)) } }
                                item { SettingsSwitch("Automatically skip intros", options.autoSkipIntro) { onOptions(options.copy(autoSkipIntro = it)) } }
                                if (options.autoSkipIntro) item { SettingsLabel("Skip markers from IntroDB when available") }
                                item { SettingsSwitch("Always show progress bar", options.persistentProgress) { onOptions(options.copy(persistentProgress = it)) } }
                                item { SettingsSwitch("Swipe for brightness and volume", options.swipeGestures) { onOptions(options.copy(swipeGestures = it)) } }
                                item { SettingsLabel("Picture fit") }
                                item {
                                    Row {
                                        MovieVideoFit.entries.forEach { fit ->
                                            TextButton(onClick = { onOptions(options.copy(fit = fit)) }) {
                                                Text(fit.label, color = if (options.fit == fit) CinemaRed else Color.White)
                                            }
                                        }
                                    }
                                }
                                item { SettingsLabel("Subtitle size") }
                                item {
                                    Row {
                                        listOf("Small" to 0.8f, "Medium" to 1f, "Large" to 1.3f).forEach { (label, size) ->
                                            TextButton(onClick = { onOptions(options.copy(subtitleSize = size)) }) {
                                                Text(label, color = if (options.subtitleSize == size) CinemaRed else Color.White)
                                            }
                                        }
                                    }
                                }
                                item { SettingsSwitch("Subtitle background", options.subtitleBackground) { onOptions(options.copy(subtitleBackground = it)) } }
                            }
                            MoviePlayerPanel.SOURCES -> {
                                val video = movieTrackChoices(tracks, C.TRACK_TYPE_VIDEO)
                                if (video.size > 1) {
                                    item { SettingsLabel("Video quality") }
                                    item { SettingsOption("Auto", null, false) { onSelectTrack(C.TRACK_TYPE_VIDEO, null) } }
                                    items(video) { track ->
                                        SettingsOption(track.label, null, track.selected) { onSelectTrack(C.TRACK_TYPE_VIDEO, track) }
                                    }
                                }
                                if (offlineAvailable) item { SettingsOption("Offline download", "Saved on this device", isOffline, onUseOffline) }
                                if (sources == null) {
                                    item {
                                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                            if (!isOffline) CircularProgressIndicator(color = CinemaRed)
                                            Text(if (isOffline) "Streaming sources are available online." else "Looking for streaming sources…")
                                        }
                                    }
                                } else if (sources.isEmpty()) item { SettingsLabel("No sources found") }
                                items(sources.orEmpty()) { source ->
                                    val sourceLabel = source.movieSourceName()
                                    val details = source.title?.replace('\n', ' ')?.trim()?.takeIf(String::isNotBlank)
                                    SettingsOption(sourceLabel, details, !isOffline && source == selectedSource) { onSelectSource(source) }
                                }
                                item {
                                    TextButton(onClick = onRetrySources) { Text(if (isOffline) "Find streaming sources" else "Refresh sources", color = CinemaRed) }
                                }
                            }
                            MoviePlayerPanel.SUBTITLES -> {
                                val audio = movieTrackChoices(tracks, C.TRACK_TYPE_AUDIO)
                                val subtitles = movieTrackChoices(tracks, C.TRACK_TYPE_TEXT)
                                item { SettingsLabel("Audio") }
                                if (audio.isEmpty()) item { SettingsLabel("Audio tracks appear when playback starts") }
                                items(audio) { track -> SettingsOption(track.label, null, track.selected) { onSelectTrack(C.TRACK_TYPE_AUDIO, track) } }
                                item { SettingsLabel("Subtitles") }
                                item { SettingsOption("Off", null, subtitles.none(MovieTrack::selected)) { onSelectTrack(C.TRACK_TYPE_TEXT, null) } }
                                items(subtitles) { track -> SettingsOption(track.label, null, track.selected) { onSelectTrack(C.TRACK_TYPE_TEXT, track) } }
                                if (subtitlesLoading) item { SettingsLabel("Looking for external subtitles…") }
                                if (subtitleOptions.isNotEmpty()) item { SettingsLabel("External subtitles") }
                                items(subtitleOptions) { track ->
                                    SettingsOption(languageName(track.language), null, subtitles.any(MovieTrack::selected) && selectedSubtitle == track) {
                                        onSelectExternal(track)
                                    }
                                }
                            }
                        }
                        item { Box(Modifier.height(10.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsLabel(label: String) {
    Text(label, Modifier.padding(start = 10.dp, top = 12.dp, bottom = 6.dp), color = Color.White.copy(alpha = 0.71f),
        style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun SettingsOption(label: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) Color(0xFF37202C) else Color.Transparent)
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) CinemaRed else Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.68f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (selected) Icon(Icons.Default.Check, "Selected", tint = CinemaRed)
    }
}

@Composable
private fun SettingsSwitch(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!enabled) }.padding(horizontal = 12.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(enabled, onCheckedChange = onChange)
    }
}
