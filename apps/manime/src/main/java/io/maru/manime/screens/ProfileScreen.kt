package io.maru.manime.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.*
import io.maru.manime.extensions.CrunchyrollStreamEngine
import io.maru.manime.player.PlayerScreen
import io.maru.manime.torrent.OfflineEpisodeEntry
import io.maru.manime.torrent.TorrentStreamService
import io.maru.manime.torrent.selectDownloadCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
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
    updatedAt = null,
    hasEnglishDubCast = audioType == "DUB" || audioType == "DUAL_AUDIO" || KnownDubRegistry.isKnownDub(mediaId)
)

private val defaultAniListTabs = listOf("Watching", "Planning", "Completed", "Paused", "Dropped")

@Composable
fun ProfileScreen(
    allLists: Map<String, List<AnimeMedia>>,
    anilistToken: String,
    prefs: MAnimePrefs? = null,
    pendingSyncCount: Int = 0,
    isSyncingQueue: Boolean = false,
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
    var selectedCategory by rememberSaveable { mutableStateOf("Downloads") }
    var isVaultLoading by remember { mutableStateOf(true) }
    val retryingDownloads = remember { mutableStateMapOf<Pair<Int, Int>, Boolean>() }

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
        val seriesCompletedEps = remember(offlineEpisodes, entry.mediaId) {
            offlineEpisodes
                .filter { it.mediaId == entry.mediaId && it.isCompleted }
                .sortedBy { it.episodeNumber }
        }
        val currentIdx = seriesCompletedEps.indexOfFirst { it.episodeNumber == entry.episodeNumber }
        val prevOffline = if (currentIdx > 0) seriesCompletedEps[currentIdx - 1] else null
        val nextOffline = if (currentIdx >= 0 && currentIdx < seriesCompletedEps.lastIndex) seriesCompletedEps[currentIdx + 1] else null

        PlayerScreen(
            media = playerMedia,
            episodeNum = entry.episodeNumber,
            episodeTitle = entry.episodeTitle,
            initialAudioMode = if (entry.audioType == "DUAL_AUDIO") preferredAudio else entry.audioType,
            hasEnglishDub = entry.audioType == "DUB" || entry.audioType == "DUAL_AUDIO" || playerMedia.hasEnglishDub,
            prefs = prefs,
            onEpisodeWatched = { episode ->
                onMediaUpdated(playerMedia.copy(progress = maxOf(playerMedia.progress, episode)))
            },
            onSwitchEpisode = { targetEp ->
                val match = seriesCompletedEps.firstOrNull { it.episodeNumber == targetEp }
                    ?: if (targetEp < entry.episodeNumber) prevOffline else nextOffline
                if (match != null) activeOfflinePlaying = match
            },
            hasPrevEpisode = prevOffline != null,
            hasNextEpisode = nextOffline != null,
            offlineOnly = true,
            onBack = { activeOfflinePlaying = null }
        )
        return
    }

    // Order strictly: Downloads FIRST, followed by the AniList Lists!
    val aniListTabNames = remember(allLists) {
        if (allLists.isEmpty()) {
            defaultAniListTabs
        } else {
            val ordered = LinkedHashSet<String>()
            val standardOrder = listOf("Watching", "Rewatching", "Planning", "Completed", "Paused", "Dropped")
            for (std in standardOrder) {
                if (std == "Rewatching" && allLists[std].isNullOrEmpty()) continue
                ordered += std
            }
            for (custom in allLists.keys) {
                ordered += custom
            }
            ordered.toList()
        }
    }
    val availableCategories = remember(aniListTabNames) {
        listOf("Downloads") + aniListTabNames
    }

    LaunchedEffect(availableCategories) {
        if (selectedCategory !in availableCategories) {
            selectedCategory = "Downloads"
        }
    }

    val selectedSeries = remember(offlineEpisodes, selectedOfflineSeriesId) {
        offlineEpisodes.filter { it.mediaId == selectedOfflineSeriesId }.sortedBy { it.episodeNumber }
    }
    BackHandler(enabled = selectedCategory == "Downloads" && selectedSeries.isNotEmpty()) {
        selectedOfflineSeriesId = null
    }

    val currentList = remember(allLists, selectedCategory) {
        if (selectedCategory == "Downloads") emptyList()
        else allLists[selectedCategory].orEmpty().distinctBy { it.mediaId }
    }

    LaunchedEffect(selectedCategory, currentList) {
        if (selectedCategory != "Downloads" && currentList.isNotEmpty()) {
            KnownDubRegistry.verifyCandidatesInBackground(context, currentList, maxProbes = 10)
        }
    }

    fun retryFailedDownload(item: OfflineEpisodeEntry) {
        val key = item.mediaId to item.episodeNumber
        if (retryingDownloads[key] == true) return
        if (TorrentStreamService.retryOfflineDownload(context, item)) {
            Toast.makeText(context, "Retrying Episode ${item.episodeNumber} download…", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            retryingDownloads[key] = true
            try {
                val prefQual = prefs?.preferredQuality?.first() ?: item.quality
                val streams = CrunchyrollStreamEngine.resolveRankedStreams(
                    anilistId = item.mediaId,
                    animeTitle = item.animeTitle,
                    episodeNum = item.episodeNumber,
                    preferredAudio = item.audioType.takeIf { it == "DUB" || it == "SUB" } ?: "SUB",
                    preferredQuality = prefQual
                )
                val candidates = selectDownloadCandidates(streams, item.audioType)
                if (candidates.isEmpty()) {
                    Toast.makeText(context, "No active download sources found right now.", Toast.LENGTH_SHORT).show()
                } else {
                    val primary = candidates.first()
                    TorrentStreamService.startOfflineDownload(
                        context = context,
                        magnetUri = primary.url,
                        fileIdx = primary.fileIdx,
                        mediaId = item.mediaId,
                        animeTitle = item.animeTitle,
                        episodeNum = item.episodeNumber,
                        episodeTitle = item.episodeTitle,
                        coverUrl = item.coverUrl,
                        thumbnailUrl = item.thumbnailUrl,
                        audioType = primary.audioType,
                        quality = primary.quality,
                        candidates = candidates
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, "Could not restart download.", Toast.LENGTH_SHORT).show()
            } finally {
                retryingDownloads.remove(key)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CrunchyBgBlack)
    ) {
        // Top Tab Strip: Downloads followed by AniList Lists
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
                val count = if (category == "Downloads") {
                    offlineEpisodes.size
                } else {
                    allLists[category]?.size ?: 0
                }
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else MaruTextMuted,
                    animationSpec = tween(160),
                    label = "tab_text_color"
                )
                val indicatorColor by animateColorAsState(
                    targetValue = if (isSelected) CrunchyOrange else Color.Transparent,
                    animationSpec = tween(180),
                    label = "tab_indicator_color"
                )

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
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = if (count > 0) "$category ($count)" else category,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = textColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(indicatorColor, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    )
                }
            }
        }

        HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

        // Optional Offline Sync Queue or Guest Mode Banner (never blocks list management!)
        if (pendingSyncCount > 0 && anilistToken.isNotBlank()) {
            Surface(
                color = Color(0xFF141820),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isSyncingQueue) Icons.Default.Sync else Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = CrunchyTeal,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isSyncingQueue) {
                            "Syncing $pendingSyncCount offline update${if (pendingSyncCount == 1) "" else "s"} to AniList…"
                        } else {
                            "$pendingSyncCount offline update${if (pendingSyncCount == 1) "" else "s"} queued • Will sync automatically when online"
                        },
                        fontSize = 11.5.sp,
                        color = CrunchyTeal,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)
        } else if (anilistToken.isBlank() && selectedCategory != "Downloads") {
            Surface(
                color = CrunchySurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Managing lists on this device. Connect AniList anytime for cloud sync.",
                        fontSize = 11.5.sp,
                        color = MaruTextMuted,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onLoginClick) {
                        Text("Connect", color = CrunchyOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)
        }

        AnimatedContent(
            targetState = selectedCategory to selectedOfflineSeriesId,
            transitionSpec = {
                fadeIn(tween(170)) togetherWith fadeOut(tween(120))
            },
            label = "my_lists_content",
            modifier = Modifier.fillMaxSize()
        ) { (activeCat, activeSeriesId) ->
            if (activeCat == "Downloads") {
                val seriesEps = offlineEpisodes.filter { it.mediaId == activeSeriesId }.sortedBy { it.episodeNumber }
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
                            Text("No Downloads Yet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Tap the download icon next to any episode to save it privately inside Nanami Anime for offline viewing.",
                                color = MaruTextMuted,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else if (activeSeriesId != null && seriesEps.isNotEmpty()) {
                    val seriesTitle = seriesEps.first().animeTitle
                    val seriesSummary = seriesEps.first().toAnimeSummary()

                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
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
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onAnimeClick(seriesSummary) }
                            )
                            TextButton(onClick = { onAnimeClick(seriesSummary) }) {
                                Text("Series Page", color = CrunchyOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(seriesEps, key = { "${it.mediaId}_${it.episodeNumber}" }) { item ->
                                val animPct by animateFloatAsState(
                                    targetValue = (item.progressPercent / 100f).coerceIn(0f, 1f),
                                    animationSpec = tween(250),
                                    label = "dl_progress"
                                )
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = item.isCompleted && prefs != null) {
                                                    activeOfflinePlaying = item
                                                },
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .width(128.dp)
                                                    .aspectRatio(16f / 9f)
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(CrunchyCardBg),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CrossfadeImage(
                                                    model = item.thumbnailUrl ?: item.coverUrl,
                                                    contentDescription = item.episodeTitle,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                if (item.isCompleted) {
                                                    Surface(
                                                        color = Color(0xCC000000),
                                                        shape = CircleShape,
                                                        modifier = Modifier.size(34.dp)
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = CrunchyOrange, modifier = Modifier.size(22.dp))
                                                        }
                                                    }
                                                }
                                            }

                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "E${item.episodeNumber} • ${item.episodeTitle}",
                                                    fontSize = 13.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    lineHeight = 18.sp
                                                )
                                                val sizeMb = (item.sizeBytes / (1024 * 1024)).coerceAtLeast(1)
                                                val audioBadge = when (item.audioType) {
                                                    "DUAL_AUDIO" -> "Dual Audio"
                                                    "DUB" -> "Dub"
                                                    else -> "Sub"
                                                }
                                                Text(
                                                    text = when {
                                                        item.isCompleted -> "${item.quality} • $audioBadge • ${sizeMb} MB"
                                                        item.failure != null -> item.failure
                                                        !item.statusText.isNullOrBlank() -> item.statusText
                                                        item.progressPercent == 0 -> "Connecting to source…"
                                                        else -> "Downloading ${item.progressPercent}%"
                                                    },
                                                    fontSize = 11.5.sp,
                                                    color = when {
                                                        item.isCompleted -> CrunchyTeal
                                                        item.failure != null -> Color(0xFFFF8A80)
                                                        else -> CrunchyOrange
                                                    },
                                                    lineHeight = 15.sp
                                                )
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (item.failure != null) {
                                                    IconButton(onClick = { retryFailedDownload(item) }) {
                                                        Icon(Icons.Default.Refresh, contentDescription = "Retry download", tint = CrunchyOrange)
                                                    }
                                                }
                                                IconButton(
                                                    onClick = {
                                                        scope.launch {
                                                            val removed = TorrentStreamService.deleteOfflineEpisode(context, item.mediaId, item.episodeNumber)
                                                            if (!removed) {
                                                                Toast.makeText(context, "Could not remove this download.", Toast.LENGTH_SHORT).show()
                                                            } else if (seriesEps.size == 1 && selectedOfflineSeriesId == item.mediaId) {
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

                                        if (!item.isCompleted && item.failure == null) {
                                            Spacer(Modifier.height(8.dp))
                                            LinearProgressIndicator(
                                                progress = { animPct },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(2.dp)),
                                                color = CrunchyOrange,
                                                trackColor = CrunchyBorder
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    val groupedSeries = remember(offlineEpisodes) {
                        offlineEpisodes.groupBy { it.mediaId }.values.toList()
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(140.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(groupedSeries, key = { "dl_series_${it.first().mediaId}" }) { eps ->
                            val seriesMedia = eps.first().toAnimeSummary()
                            val completedCount = eps.count { it.isCompleted }
                            val activeCount = eps.count { !it.isCompleted && it.failure == null }
                            val epCountLabel = when {
                                activeCount > 0 -> "$completedCount/${eps.size} Ready • Downloading…"
                                eps.size == 1 -> "1 Episode Saved"
                                else -> "${eps.size} Episodes Saved"
                            }
                            PosterCard(
                                media = seriesMedia,
                                subtitleOverride = epCountLabel,
                                onClick = { selectedOfflineSeriesId = seriesMedia.mediaId }
                            )
                        }
                    }
                }
            } else {
                val listForTab = allLists[activeCat].orEmpty().distinctBy { it.mediaId }
                if (isLoading && listForTab.isEmpty() && anilistToken.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CrunchyOrange)
                    }
                } else if (loadFailed && listForTab.isEmpty() && anilistToken.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Couldn't refresh your AniList lists right now.", color = MaruTextMuted)
                            TextButton(onClick = onRetry) { Text("Try again", color = CrunchyOrange) }
                        }
                    }
                } else if (listForTab.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = "No anime in $activeCat yet",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Open any anime and tap + My List to add it to $activeCat.",
                                color = MaruTextMuted,
                                fontSize = 12.5.sp,
                                textAlign = TextAlign.Center
                            )
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
                        items(listForTab, key = { "lib_${activeCat}_${it.mediaId}" }) { media ->
                            PosterCard(
                                media = media,
                                onClick = { onAnimeClick(media) }
                            )
                        }
                    }
                }
            }
        }
    }
}
