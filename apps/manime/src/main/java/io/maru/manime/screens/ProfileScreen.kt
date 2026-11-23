package io.maru.manime.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.maru.manime.*
import io.maru.manime.player.PlayerScreen
import io.maru.manime.torrent.OfflineEpisodeEntry
import io.maru.manime.torrent.TorrentStreamService
import kotlinx.coroutines.launch

private fun OfflineEpisodeEntry.toAnimeSummary() = AnimeMedia(
    mediaId = mediaId,
    title = animeTitle,
    titleEnglish = animeTitle,
    titleRomaji = animeTitle,
    coverUrl = coverUrl ?: thumbnailUrl,
    bannerUrl = thumbnailUrl,
    accentColor = null,
    episodes = null,
    format = "TV",
    status = null,
    season = null,
    seasonYear = null,
    averageScore = null,
    genres = emptyList(),
    description = null,
    siteUrl = null,
    isAdult = false,
    nextEpisode = null,
    nextEpisodeAt = null,
    externalLinks = emptyList(),
    listEntryId = null,
    listStatus = null,
    progress = 0,
    score = null,
    notes = null,
    isPrivate = false,
    updatedAt = null
)

@Composable
fun ProfileScreen(
    allLists: Map<String, List<AnimeMedia>>,
    anilistToken: String,
    prefs: MAnimePrefs? = null,
    isLoading: Boolean = false,
    loadFailed: Boolean = false,
    onRetry: () -> Unit = {},
    onLoginClick: () -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit,
    onMediaUpdated: (AnimeMedia) -> Unit = {},
    onPlaybackVisibilityChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val offlineEpisodes by TorrentStreamService.offlineEpisodes.collectAsState()
    var activeOfflinePlaying by remember { mutableStateOf<OfflineEpisodeEntry?>(null) }
    var selectedOfflineSeriesId by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedCategory by rememberSaveable { mutableStateOf("Watchlist") }
    var isVaultLoading by remember { mutableStateOf(true) }
    val notifyPlaybackVisibility by rememberUpdatedState(onPlaybackVisibilityChange)
    val isOfflinePlayerVisible = activeOfflinePlaying != null && prefs != null

    LaunchedEffect(Unit) {
        TorrentStreamService.loadOfflineVault(context)
        isVaultLoading = false
    }

    DisposableEffect(isOfflinePlayerVisible) {
        if (isOfflinePlayerVisible) notifyPlaybackVisibility(true)
        onDispose {
            if (isOfflinePlayerVisible) notifyPlaybackVisibility(false)
        }
    }

    val playingEntry = activeOfflinePlaying
    if (playingEntry != null && prefs != null) {
        val entry = playingEntry
        val preferredAudio by prefs.preferredAudio.collectAsState(initial = "SUB")
        val cachedMedia = allLists.values.asSequence().flatten().firstOrNull { it.mediaId == entry.mediaId }
        val playerMedia = cachedMedia ?: entry.toAnimeSummary()
        PlayerScreen(
            media = playerMedia,
            episodeNum = entry.episodeNumber,
            episodeTitle = entry.episodeTitle,
            initialAudioMode = if (entry.audioType == "DUAL_AUDIO") preferredAudio else entry.audioType,
            hasEnglishDub = entry.audioType == "DUB" || entry.audioType == "DUAL_AUDIO",
            prefs = prefs,
            onEpisodeWatched = { episode -> onMediaUpdated(playerMedia.copy(progress = maxOf(playerMedia.progress, episode))) },
            offlineOnly = true,
            onBack = { activeOfflinePlaying = null }
        )
        return
    }

    val availableCategories = listOf("Watchlist", "History", "Completed", "Downloads")
    val selectedSeries = remember(offlineEpisodes, selectedOfflineSeriesId) {
        offlineEpisodes.filter { it.mediaId == selectedOfflineSeriesId }.sortedBy { it.episodeNumber }
    }
    BackHandler(enabled = selectedCategory == "Downloads" && selectedSeries.isNotEmpty()) {
        selectedOfflineSeriesId = null
    }

    val currentList = remember(allLists, selectedCategory) {
        val entries = allLists.values.flatten().distinctBy { it.mediaId }
        when (selectedCategory) {
            "Watchlist" -> entries.filter { it.listStatus in listOf("CURRENT", "PLANNING", "REPEATING", "PAUSED") }
            "History" -> entries.filter { it.progress > 0 }.sortedByDescending { it.updatedAt }
            "Completed" -> entries.filter { it.listStatus == "COMPLETED" }
            else -> emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CrunchyBgBlack)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CrunchyBgBlack)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            availableCategories.forEach { category ->
                val isSelected = selectedCategory == category
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .width(IntrinsicSize.Min)
                        .selectable(selected = isSelected, role = Role.Tab) {
                            selectedCategory = category
                            selectedOfflineSeriesId = null
                        }
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = category,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (isSelected) Color.White else MaruTextMuted,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 10.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(if (isSelected) CrunchyOrange else Color.Transparent)
                    )
                }
            }
        }

        HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

        if (selectedCategory == "Downloads") {
            if (isVaultLoading && offlineEpisodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CrunchyOrange)
                }
            } else if (offlineEpisodes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(28.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(44.dp))
                        Text("No Offline Downloads Yet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Tap the download icon on an episode to save an available copy for offline viewing.",
                            color = MaruTextMuted,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else if (selectedSeries.isNotEmpty()) {
                val seriesTitle = selectedSeries.first().animeTitle

                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedOfflineSeriesId = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Text(
                            text = seriesTitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(selectedSeries, key = { "${it.mediaId}_${it.episodeNumber}" }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = (item.isCompleted && prefs != null) || item.failure != null) {
                                        if (item.isCompleted) activeOfflinePlaying = item
                                        else onAnimeClick(item.toAnimeSummary())
                                    },
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(136.dp)
                                        .height(76.dp)
                                        .background(CrunchyCardBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = item.thumbnailUrl ?: item.coverUrl,
                                        contentDescription = item.episodeTitle,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (item.isCompleted) {
                                        Surface(
                                            color = Color(0xBB000000),
                                            shape = CircleShape,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "E${item.episodeNumber} - ${item.episodeTitle}",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 2
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val sizeMb = (item.sizeBytes / (1024 * 1024)).coerceAtLeast(1)
                                    Text(
                                        text = when {
                                            item.isCompleted -> "${item.quality} • ${sizeMb} MB"
                                            item.failure != null -> item.failure
                                            item.progressPercent == 0 -> "Preparing download…"
                                            else -> "Downloading ${item.progressPercent}%"
                                        },
                                        fontSize = 11.5.sp,
                                        color = if (item.isCompleted) CrunchyTeal else CrunchyOrange
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            val removed = TorrentStreamService.deleteOfflineEpisode(context, item.mediaId, item.episodeNumber)
                                            if (!removed) {
                                                Toast.makeText(context, "Could not remove this download.", Toast.LENGTH_SHORT).show()
                                            } else if (selectedSeries.size == 1 && selectedOfflineSeriesId == item.mediaId) {
                                                selectedOfflineSeriesId = null
                                            }
                                        }
                                    }
                                ) {
                                    val label = if (item.isCompleted || item.failure != null) "Delete download" else "Cancel download"
                                    Icon(Icons.Default.DeleteOutline, contentDescription = label, tint = MaruTextMuted)
                                }
                            }
                        }
                    }
                }
            } else {
                val groupedSeries = remember(offlineEpisodes) {
                    offlineEpisodes.groupBy { it.mediaId }.values.toList()
                }

                Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(groupedSeries, key = { "dl_series_${it.first().mediaId}" }) { eps ->
                            val seriesMedia = eps.first().toAnimeSummary()
                            val epCountLabel = if (eps.size == 1) "1 Episode" else "${eps.size} Episodes"
                            PosterCard(
                                media = seriesMedia,
                                subtitleOverride = epCountLabel,
                                onClick = { selectedOfflineSeriesId = seriesMedia.mediaId }
                            )
                        }
                    }
                }
            }
        } else if (anilistToken.isBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Connect AniList to see your lists.", color = MaruTextMuted)
                    TextButton(onClick = onLoginClick) { Text("Connect AniList") }
                }
            }
        } else if (isLoading && currentList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CrunchyOrange)
            }
        } else if (loadFailed && currentList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load your AniList lists.", color = MaruTextMuted)
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
        } else if (currentList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No anime in $selectedCategory.", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tap any show to add it to your watchlist!", color = MaruTextMuted, fontSize = 12.5.sp)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                items(currentList, key = { "lib_${selectedCategory}_${it.mediaId}" }) { media ->
                    PosterCard(
                        media = media,
                        onClick = { onAnimeClick(media) }
                    )
                }
            }
        }
    }
}
