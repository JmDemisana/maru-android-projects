package io.maru.manime.screens

import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.maru.manime.*
import io.maru.manime.player.PlayerScreen
import io.maru.manime.torrent.OfflineEpisodeEntry
import io.maru.manime.torrent.TorrentStreamService

@Composable
fun ProfileScreen(
    user: AniListUser?,
    allLists: Map<String, List<AnimeMedia>>,
    watchingCount: Int,
    completedCount: Int,
    planningCount: Int,
    anilistToken: String,
    prefs: MAnimePrefs? = null,
    isLoading: Boolean = false,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit,
    onMediaUpdated: (AnimeMedia) -> Unit = {}
) {
    val context = LocalContext.current
    val offlineEpisodes by TorrentStreamService.offlineEpisodes.collectAsState()
    var activeOfflinePlaying by remember { mutableStateOf<OfflineEpisodeEntry?>(null) }
    var selectedOfflineSeriesId by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        TorrentStreamService.loadOfflineVault(context)
    }

    if (activeOfflinePlaying != null && prefs != null) {
        val entry = activeOfflinePlaying!!
        val dummyMedia = AnimeMedia(
            mediaId = entry.mediaId,
            title = entry.animeTitle,
            titleEnglish = entry.animeTitle,
            titleRomaji = entry.animeTitle,
            coverUrl = entry.coverUrl,
            bannerUrl = entry.thumbnailUrl,
            accentColor = null,
            episodes = null,
            format = "TV",
            status = "RELEASING",
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
            listStatus = "CURRENT",
            progress = entry.episodeNumber - 1,
            score = null,
            notes = null,
            isPrivate = false,
            updatedAt = null
        )
        PlayerScreen(
            media = dummyMedia,
            episodeNum = entry.episodeNumber,
            episodeTitle = entry.episodeTitle,
            initialAudioMode = if (entry.audioType == "DUB") "DUB" else "SUB",
            prefs = prefs,
            onBack = { activeOfflinePlaying = null }
        )
        return
    }

    val availableCategories = listOf("Watchlist", "History", "Completed", "Downloads")
    var selectedCategory by remember { mutableStateOf("Watchlist") }

    val currentList = remember(allLists, selectedCategory) {
        when (selectedCategory) {
            "Watchlist" -> {
                allLists.filter { (k, _) ->
                    k.contains("Watch", ignoreCase = true) ||
                    k.contains("Current", ignoreCase = true) ||
                    k.contains("Plan", ignoreCase = true)
                }.values.flatten().distinctBy { it.mediaId }
            }
            "History" -> {
                allLists.values.flatten().filter { it.progress > 0 }.distinctBy { it.mediaId }
            }
            "Completed" -> {
                allLists.filter { (k, _) ->
                    k.contains("Complet", ignoreCase = true) || k.contains("Finish", ignoreCase = true)
                }.values.flatten().distinctBy { it.mediaId }
            }
            else -> emptyList()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CrunchyBgBlack)
    ) {
        // Authentic Crunchyroll Top Underline Tab Bar (Watchlist | History | Completed | Downloads)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CrunchyBgBlack)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            availableCategories.forEach { category ->
                val isSelected = selectedCategory == category
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            selectedCategory = category
                            selectedOfflineSeriesId = null
                        }
                        .padding(top = 12.dp)
                ) {
                    Text(
                        text = category,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (isSelected) Color.White else MaruTextMuted,
                        modifier = Modifier.padding(bottom = 10.dp)
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
            if (offlineEpisodes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(28.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(44.dp))
                        Text("No Offline Downloads Yet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Tap the download icon on any episode to save it into your app-only Offline Vault.",
                            color = MaruTextMuted,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else if (selectedOfflineSeriesId != null) {
                // Drill-down: Episodes for the selected offline series
                val seriesEps = offlineEpisodes.filter { it.mediaId == selectedOfflineSeriesId }.sortedBy { it.episodeNumber }
                val seriesTitle = seriesEps.firstOrNull()?.animeTitle ?: "Downloaded Episodes"

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
                        items(seriesEps, key = { "${it.mediaId}_${it.episodeNumber}" }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (item.isCompleted) {
                                            activeOfflinePlaying = item
                                        } else {
                                            Toast.makeText(context, "Syncing (${item.progressPercent}%)", Toast.LENGTH_SHORT).show()
                                        }
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
                                        text = if (item.isCompleted) "${item.quality} • ${sizeMb} MB" else "Syncing ${item.progressPercent}%",
                                        fontSize = 11.5.sp,
                                        color = if (item.isCompleted) CrunchyTeal else CrunchyOrange
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        TorrentStreamService.deleteOfflineEpisode(context, item.mediaId, item.episodeNumber)
                                        if (seriesEps.size <= 1) selectedOfflineSeriesId = null
                                    }
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaruTextMuted)
                                }
                            }
                        }
                    }
                }
            } else {
                // Crunchyroll 2-Column Poster Grid for Downloads (Matches Reference Screenshot 2: "4 Episodes", "9 Episodes")
                val groupedSeries = remember(offlineEpisodes) {
                    offlineEpisodes.groupBy { it.mediaId }.map { (mId, eps) ->
                        val first = eps.first()
                        Pair(
                            AnimeMedia(
                                mediaId = mId,
                                title = first.animeTitle,
                                titleEnglish = first.animeTitle,
                                titleRomaji = first.animeTitle,
                                coverUrl = first.coverUrl ?: first.thumbnailUrl,
                                bannerUrl = first.thumbnailUrl,
                                accentColor = null,
                                episodes = eps.size,
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
                            ),
                            eps
                        )
                    }
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Text(
                            text = "Manage",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        items(groupedSeries, key = { "dl_series_${it.first.mediaId}" }) { (seriesMedia, eps) ->
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
        } else if (isLoading && currentList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CrunchyOrange)
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
                columns = GridCells.Fixed(2),
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
