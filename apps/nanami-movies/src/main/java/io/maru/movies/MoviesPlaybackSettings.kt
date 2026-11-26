package io.maru.movies

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import io.maru.manime.extensions.StremioStreamResult
import io.maru.manime.extensions.SubtitleTrack
import kotlin.math.abs

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

/**
 * Sleek translucent right-side half-screen Playback Settings sheet,
 * keeping live video visible on the left half — exact parity with Nanami Anime!
 */
@Composable
internal fun MoviePlaybackSettingsSheet(
    visible: Boolean,
    showVideoSourcesFirst: Boolean,
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
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 4 },
        exit = fadeOut(tween(160)) + slideOutHorizontally(tween(180)) { it / 4 },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(0.88f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
                color = Color(0xF50E0E12),
                border = BorderStroke(1.dp, NetflixBorder),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NetflixSurface)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to video", tint = Color.White)
                        }
                        Text(
                            text = "Playback Settings",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

                    val audioTracks = movieTrackChoices(tracks, C.TRACK_TYPE_AUDIO)
                    val subtitleTracks = movieTrackChoices(tracks, C.TRACK_TYPE_TEXT)
                    val subsCurrentlyOff = subtitleTracks.none(MovieTrack::selected)

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        contentPadding = PaddingValues(top = 16.dp, bottom = 36.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        if (showVideoSourcesFirst) {
                            movieSourceItems(
                                sources = sources,
                                selectedSource = selectedSource,
                                isOffline = isOffline,
                                offlineAvailable = offlineAvailable,
                                onSelectSource = onSelectSource,
                                onUseOffline = onUseOffline,
                                onRetrySources = onRetrySources,
                            )
                        }

                        // 1. Playback & Gestures
                        item(key = "section_playback") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "Playback & Gestures",
                                    color = NetflixRed,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                        val sel = abs(options.speed - spd) < 0.03f
                                        Surface(
                                            onClick = { onOptions(options.copy(speed = spd)) },
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (sel) NetflixRed else NetflixCardBg,
                                            border = BorderStroke(1.dp, if (sel) NetflixRed else NetflixBorder),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "${spd.toString().removeSuffix(".0")}×",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White,
                                                )
                                            }
                                        }
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        MovieVideoFit.FIT to "Fit Screen",
                                        MovieVideoFit.CROP to "Crop / Zoom",
                                        MovieVideoFit.STRETCH to "Stretch",
                                    ).forEach { (fit, label) ->
                                        val sel = options.fit == fit
                                        Surface(
                                            onClick = { onOptions(options.copy(fit = fit)) },
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (sel) NetflixRed.copy(alpha = 0.20f) else NetflixCardBg,
                                            border = BorderStroke(1.dp, if (sel) NetflixRed else NetflixBorder),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = label,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (sel) NetflixRed else Color.White,
                                                )
                                            }
                                        }
                                    }
                                }

                                SheetToggleRow(
                                    label = "Swipe up/down for brightness & volume",
                                    subtitle = "Left side adjusts brightness, right side adjusts volume",
                                    checked = options.swipeGestures,
                                    onCheckedChange = { onOptions(options.copy(swipeGestures = it)) },
                                )

                                SheetToggleRow(
                                    label = "Persistent bottom progress bar",
                                    subtitle = "Shows a slim progress line at the bottom when controls are hidden",
                                    checked = options.persistentProgress,
                                    onCheckedChange = { onOptions(options.copy(persistentProgress = it)) },
                                )

                                SheetToggleRow(
                                    label = "Autoplay next episode",
                                    subtitle = "Starts the next episode automatically when an episode finishes",
                                    checked = options.autoplay,
                                    onCheckedChange = { onOptions(options.copy(autoplay = it)) },
                                )

                                SheetToggleRow(
                                    label = "Auto-skip verified intros",
                                    subtitle = "Skips opening sequences automatically when timestamps are available",
                                    checked = options.autoSkipIntro,
                                    onCheckedChange = { onOptions(options.copy(autoSkipIntro = it)) },
                                )
                            }
                        }

                        // 2. Audio Track
                        item(key = "section_audio") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Audio Track",
                                    color = NetflixRed,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                )

                                if (audioTracks.isEmpty()) {
                                    Text(
                                        text = "Audio tracks appear once playback starts.",
                                        color = NetflixTextMuted,
                                        fontSize = 12.5.sp,
                                    )
                                } else {
                                    audioTracks.forEach { track ->
                                        SheetTrackRow(
                                            label = track.label,
                                            selected = track.selected,
                                            onClick = {
                                                onSelectTrack(C.TRACK_TYPE_AUDIO, track)
                                                onDismiss()
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        // 3. Subtitles
                        item(key = "section_subs") {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "Subtitles",
                                    color = NetflixRed,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                )

                                SheetToggleRow(
                                    label = "Black fill behind subtitles",
                                    subtitle = "Adds a clean dark background box behind subtitle lines",
                                    checked = options.subtitleBackground,
                                    onCheckedChange = { onOptions(options.copy(subtitleBackground = it)) },
                                )

                                Text("Subtitle size", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        0.8f to "Small",
                                        1.0f to "Normal",
                                        1.25f to "Large",
                                        1.45f to "XL",
                                    ).forEach { (sz, sizeLabel) ->
                                        val sel = abs(options.subtitleSize - sz) < 0.05f
                                        Surface(
                                            onClick = { onOptions(options.copy(subtitleSize = sz)) },
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (sel) NetflixRed else NetflixCardBg,
                                            border = BorderStroke(1.dp, if (sel) NetflixRed else NetflixBorder),
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = sizeLabel,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                        }
                                    }
                                }

                                SheetTrackRow(
                                    label = "Subtitles Off",
                                    selected = subsCurrentlyOff,
                                    onClick = {
                                        onSelectTrack(C.TRACK_TYPE_TEXT, null)
                                        onDismiss()
                                    },
                                )

                                subtitleTracks.forEach { track ->
                                    SheetTrackRow(
                                        label = track.label,
                                        selected = !subsCurrentlyOff && track.selected,
                                        onClick = {
                                            onSelectTrack(C.TRACK_TYPE_TEXT, track)
                                            onDismiss()
                                        },
                                    )
                                }

                                if (subtitlesLoading) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        CircularProgressIndicator(color = NetflixRed, modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                                        Text("Checking cloud subtitles…", color = NetflixTextMuted, fontSize = 12.sp)
                                    }
                                } else {
                                    subtitleOptions.take(12).forEach { extSub ->
                                        val isSelected = !subsCurrentlyOff && selectedSubtitle?.url == extSub.url
                                        SheetTrackRow(
                                            label = "${languageName(extSub.language)} (Cloud)",
                                            selected = isSelected,
                                            onClick = {
                                                onSelectExternal(extSub)
                                                onDismiss()
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        if (!showVideoSourcesFirst) {
                            movieSourceItems(
                                sources = sources,
                                selectedSource = selectedSource,
                                isOffline = isOffline,
                                offlineAvailable = offlineAvailable,
                                onSelectSource = onSelectSource,
                                onUseOffline = onUseOffline,
                                onRetrySources = onRetrySources,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.movieSourceItems(
    sources: List<StremioStreamResult>?,
    selectedSource: StremioStreamResult?,
    isOffline: Boolean,
    offlineAvailable: Boolean,
    onSelectSource: (StremioStreamResult) -> Unit,
    onUseOffline: () -> Unit,
    onRetrySources: () -> Unit,
) {
    item(key = "section_video_sources_header") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Video Sources",
                    color = NetflixRed,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(
                    onClick = onRetrySources,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh", color = NetflixRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            if (sources == null && !isOffline) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(color = NetflixRed, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Looking for video sources…", color = NetflixTextMuted, style = MaterialTheme.typography.bodySmall)
                }
            } else if (sources != null && sources.isEmpty() && !offlineAvailable) {
                Text("No video sources available.", color = NetflixTextMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (offlineAvailable) {
        item(key = "source_offline_saved") {
            SheetSourceRow(
                title = "Saved on Device",
                detail = "Instant offline playback",
                selected = isOffline,
                onClick = onUseOffline,
            )
        }
    }

    itemsIndexed(sources.orEmpty()) { index, source ->
        val qual = source.movieQuality()
        val label = if (qual != null) "Source ${index + 1} • $qual" else "Source ${index + 1}"
        SheetSourceRow(
            title = label,
            detail = source.friendlyCinemaDetail(index),
            selected = !isOffline && source == selectedSource,
            onClick = { onSelectSource(source) },
        )
    }
}

@Composable
private fun SheetTrackRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = if (selected) NetflixRed.copy(alpha = 0.20f) else NetflixCardBg,
        border = BorderStroke(1.dp, if (selected) NetflixRed else NetflixBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SheetSourceRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = if (selected) NetflixRed.copy(alpha = 0.20f) else NetflixCardBg,
        border = BorderStroke(1.dp, if (selected) NetflixRed else NetflixBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        color = NetflixTextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Current source", tint = NetflixRed, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SheetToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(4.dp),
        color = NetflixCardBg,
        border = BorderStroke(1.dp, NetflixBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, color = Color.White, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = NetflixTextMuted, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = NetflixRed,
                    uncheckedThumbColor = Color(0xFFB5B8C4),
                    uncheckedTrackColor = Color(0xFF282830),
                    uncheckedBorderColor = NetflixBorder,
                ),
            )
        }
    }
}
