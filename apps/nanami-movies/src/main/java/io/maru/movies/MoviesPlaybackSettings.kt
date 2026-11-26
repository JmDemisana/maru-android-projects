package io.maru.movies

import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import io.maru.manime.extensions.StremioStreamResult
import io.maru.manime.extensions.SubtitleTrack

// Video Sources FIRST so users can switch streams immediately while loading (matching Nanami Anime!)
internal enum class MoviePlayerPanel(val label: String) {
    SOURCES("Video Sources"),
    SUBTITLES("Audio & Subtitles"),
    GENERAL("Playback"),
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
            add(MovieTrack(group, index, if (channels == null) label else "$label • ${channels}ch"))
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
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .fillMaxHeight(0.90f),
                shape = RoundedCornerShape(10.dp),
                color = NetflixSurface,
                contentColor = Color.White,
                border = BorderStroke(1.dp, NetflixBorder),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, top = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Playback Settings",
                            modifier = Modifier.weight(1f),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MoviePlayerPanel.entries.forEach { panel ->
                            NetflixChip(
                                label = panel.label,
                                selected = initialPanel == panel,
                                onClick = { onPanel(panel) },
                            )
                        }
                    }

                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        when (initialPanel) {
                            MoviePlayerPanel.SOURCES -> {
                                val video = movieTrackChoices(tracks, C.TRACK_TYPE_VIDEO)
                                if (video.size > 1) {
                                    item { PanelSectionLabel("Adaptive Video Track") }
                                    item { PanelOptionRow("Auto", null, null, false) { onSelectTrack(C.TRACK_TYPE_VIDEO, null) } }
                                    items(video) { track ->
                                        PanelOptionRow(track.label, null, null, track.selected) { onSelectTrack(C.TRACK_TYPE_VIDEO, track) }
                                    }
                                }
                                if (offlineAvailable) {
                                    item { PanelSectionLabel("Private Offline Vault") }
                                    item {
                                        PanelOptionRow(
                                            label = "Saved Offline Download",
                                            detail = "Instant local playback from device storage",
                                            badge = "OFFLINE",
                                            selected = isOffline,
                                            onClick = onUseOffline,
                                        )
                                    }
                                }
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        PanelSectionLabel("Available Video Sources")
                                        TextButton(onClick = onRetrySources) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Refresh", color = NetflixRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }
                                    }
                                }
                                if (sources == null) {
                                    item {
                                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            if (!isOffline) CircularProgressIndicator(color = NetflixRed, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                                            Text(
                                                text = if (isOffline) "Tap Refresh to query live online sources." else "Searching fast streaming sources…",
                                                color = NetflixTextMuted,
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                } else if (sources.isEmpty()) {
                                    item { Text("No streaming sources returned for this title.", color = NetflixTextMuted, modifier = Modifier.padding(10.dp)) }
                                }
                                items(sources.orEmpty()) { source ->
                                    val qual = source.movieQuality()
                                    val seeds = source.reportedSeeds()
                                    val badge = listOfNotNull(qual, seeds?.let { "$it seeds" }).joinToString(" • ").ifBlank { null }
                                    val details = source.title?.replace('\n', ' ')?.trim()?.takeIf(String::isNotBlank)
                                    PanelOptionRow(
                                        label = source.movieSourceName(),
                                        detail = details,
                                        badge = badge,
                                        selected = !isOffline && source == selectedSource,
                                    ) { onSelectSource(source) }
                                }
                            }

                            MoviePlayerPanel.SUBTITLES -> {
                                val audio = movieTrackChoices(tracks, C.TRACK_TYPE_AUDIO)
                                val subtitles = movieTrackChoices(tracks, C.TRACK_TYPE_TEXT)

                                item { PanelSectionLabel("Audio Track") }
                                if (audio.isEmpty()) {
                                    item { Text("Audio tracks populate once video frames arrive.", color = NetflixTextMuted, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
                                }
                                items(audio) { track ->
                                    PanelOptionRow(track.label, null, null, track.selected) { onSelectTrack(C.TRACK_TYPE_AUDIO, track) }
                                }

                                item { PanelSectionLabel("Embedded Subtitles") }
                                item {
                                    PanelOptionRow("Off", null, null, subtitles.none(MovieTrack::selected)) {
                                        onSelectTrack(C.TRACK_TYPE_TEXT, null)
                                    }
                                }
                                items(subtitles) { track ->
                                    PanelOptionRow(track.label, null, null, track.selected) { onSelectTrack(C.TRACK_TYPE_TEXT, track) }
                                }

                                item { PanelSectionLabel("OpenSubtitles Cloud Captions") }
                                if (subtitlesLoading) {
                                    item {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            CircularProgressIndicator(color = NetflixRed, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                            Text("Loading external subtitles…", color = NetflixTextMuted, fontSize = 12.5.sp)
                                        }
                                    }
                                } else if (subtitleOptions.isEmpty()) {
                                    item { Text("No external subtitles listed.", color = NetflixTextMuted, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
                                }
                                items(subtitleOptions) { track ->
                                    PanelOptionRow(
                                        label = languageName(track.language),
                                        detail = null,
                                        badge = track.language.uppercase(),
                                        selected = subtitles.any(MovieTrack::selected) && selectedSubtitle == track,
                                    ) { onSelectExternal(track) }
                                }
                            }

                            MoviePlayerPanel.GENERAL -> {
                                item { PanelSectionLabel("Playback Speed") }
                                item {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                            NetflixChip(
                                                label = "${speed.toString().removeSuffix(".0")}x",
                                                selected = options.speed == speed,
                                                onClick = { onOptions(options.copy(speed = speed)) },
                                            )
                                        }
                                    }
                                }

                                item { PanelSectionLabel("Aspect & Picture Fit") }
                                item {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        MovieVideoFit.entries.forEach { fit ->
                                            NetflixChip(
                                                label = fit.label,
                                                selected = options.fit == fit,
                                                onClick = { onOptions(options.copy(fit = fit)) },
                                            )
                                        }
                                    }
                                }

                                item { PanelSectionLabel("Subtitle Appearance") }
                                item {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        listOf("Small" to 0.8f, "Normal" to 1f, "Large" to 1.25f, "XL" to 1.45f).forEach { (label, sz) ->
                                            NetflixChip(
                                                label = label,
                                                selected = kotlin.math.abs(options.subtitleSize - sz) < 0.05f,
                                                onClick = { onOptions(options.copy(subtitleSize = sz)) },
                                            )
                                        }
                                    }
                                }
                                item {
                                    NetflixFeatureRow(
                                        label = "Subtitle Black Fill",
                                        checked = options.subtitleBackground,
                                        onCheckedChange = { onOptions(options.copy(subtitleBackground = it)) },
                                        modifier = Modifier,
                                    )
                                }

                                item { PanelSectionLabel("Behavior") }
                                item {
                                    NetflixFeatureRow(
                                        label = "Autoplay Next Episode",
                                        checked = options.autoplay,
                                        onCheckedChange = { onOptions(options.copy(autoplay = it)) },
                                        modifier = Modifier,
                                    )
                                }
                                item {
                                    NetflixFeatureRow(
                                        label = "Auto-Skip Verified Intros (IntroDB)",
                                        checked = options.autoSkipIntro,
                                        onCheckedChange = { onOptions(options.copy(autoSkipIntro = it)) },
                                        modifier = Modifier,
                                    )
                                }
                                item {
                                    NetflixFeatureRow(
                                        label = "Always Show Slim Progress Bar",
                                        checked = options.persistentProgress,
                                        onCheckedChange = { onOptions(options.copy(persistentProgress = it)) },
                                        modifier = Modifier,
                                    )
                                }
                                item {
                                    NetflixFeatureRow(
                                        label = "Swipe for Brightness & Volume",
                                        checked = options.swipeGestures,
                                        onCheckedChange = { onOptions(options.copy(swipeGestures = it)) },
                                        modifier = Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelSectionLabel(label: String) {
    Text(
        text = label.uppercase(),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        color = NetflixRed,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.7.sp,
    )
}

@Composable
private fun PanelOptionRow(
    label: String,
    detail: String?,
    badge: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (selected) NetflixRed.copy(alpha = 0.16f) else NetflixCardBg,
        border = BorderStroke(1.dp, if (selected) NetflixRed else NetflixBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = label,
                        fontSize = 13.5.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (badge != null) {
                        Surface(
                            color = NetflixMatchGreen.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(3.dp),
                        ) {
                            Text(
                                text = badge,
                                color = NetflixMatchGreen,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp),
                            )
                        }
                    }
                }
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        fontSize = 11.5.sp,
                        color = NetflixTextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = NetflixRed, modifier = Modifier.size(18.dp))
            }
        }
    }
}
