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

    val availableCategories = remember(allLists) {
        val base = if (allLists.isEmpty()) {
            listOf("Watching", "Planning", "Completed", "Paused", "Dropped")
        } else {
            val keys = allLists.keys.toList()
            val resultList = mutableListOf<String>()
            keys.find { it.contains("Watch", ignoreCase = true) || it.equals("Current", ignoreCase = true) }?.let { resultList.add(it) }
            keys.find { it.contains("Plan", ignoreCase = true) }?.let { if (!resultList.contains(it)) resultList.add(it) }
            for (k in keys) {
                if (!resultList.contains(k)) resultList.add(k)
            }
            resultList
        }
        listOf("Offline") + base
    }

    var selectedCategory by remember {
        mutableStateOf(availableCategories.getOrElse(1) { "Watching" })
    }

    val currentList = remember(allLists, selectedCategory) {
        if (selectedCategory.equals("Offline", ignoreCase = true)) {
            emptyList()
        } else if (selectedCategory.equals("Completed", ignoreCase = true)) {
            val direct = allLists[selectedCategory] ?: allLists.entries.find { it.key.equals(selectedCategory, ignoreCase = true) }?.value
            if (direct != null && direct.isNotEmpty()) direct
            else {
                val aggregated = allLists.filter { (k, _) -> k.contains("Complet", ignoreCase = true) || k.contains("Finish", ignoreCase = true) }.values.flatten()
                if (aggregated.isNotEmpty()) aggregated.distinctBy { it.mediaId }
                else emptyList()
            }
        } else if (selectedCategory.equals("Watching", ignoreCase = true)) {
            val direct = allLists[selectedCategory] ?: allLists.entries.find { it.key.equals(selectedCategory, ignoreCase = true) }?.value
            if (direct != null && direct.isNotEmpty()) direct
            else {
                val aggregated = allLists.filter { (k, _) -> k.contains("Watch", ignoreCase = true) || k.contains("Current", ignoreCase = true) }.values.flatten()
                if (aggregated.isNotEmpty()) aggregated.distinctBy { it.mediaId }
                else emptyList()
            }
        } else {
            allLists[selectedCategory]
                ?: allLists[selectedCategory.lowercase()]
                ?: allLists.entries.find { it.key.equals(selectedCategory, ignoreCase = true) }?.value
                ?: emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Crunchyroll "MY LISTS" Category Bar
        Surface(
            color = CrunchySurface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableCategories.forEach { category ->
                    val isSelected = selectedCategory.equals(category, ignoreCase = true)
                    val isOfflineTab = category.equals("Offline", ignoreCase = true)
                    val count = if (isOfflineTab) offlineEpisodes.size else allLists[category]?.size
                    val label = if (count != null && count > 0) "${category.uppercase()} ($count)" else category.uppercase()

                    Surface(
                        onClick = { selectedCategory = category },
                        shape = RoundedCornerShape(4.dp),
                        color = if (isSelected) CrunchyOrange else CrunchyCardBg,
                        border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (isOfflineTab) {
                                Icon(
                                    Icons.Default.DownloadDone,
                                    contentDescription = null,
                                    tint = if (isSelected) Color.Black else CrunchyTeal,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 11.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = if (isSelected) Color.Black else MaruTextStrong
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

        if (selectedCategory.equals("Offline", ignoreCase = true)) {
            if (offlineEpisodes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(28.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(44.dp))
                        Text("Your Offline Vault is Empty", color = MaruTextStrong, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Tap the download icon on any episode to save it for offline viewing. Videos stay privately inside Nanami Anime and never clutter your phone's gallery or external video players.",
                            color = MaruTextMuted,
                            fontSize = 12.5.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PRIVATE OFFLINE VAULT (${offlineEpisodes.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CrunchyTeal,
                                letterSpacing = 0.8.sp
                            )
                            Text(
                                text = "App-Only Encrypted Storage",
                                fontSize = 10.5.sp,
                                color = MaruTextMuted
                            )
                        }
                    }

                    items(offlineEpisodes, key = { "${it.mediaId}_${it.episodeNumber}" }) { item ->
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (item.isCompleted) {
                                        activeOfflinePlaying = item
                                    } else {
                                        Toast.makeText(context, "Episode is still syncing (${item.progressPercent}%)", Toast.LENGTH_SHORT).show()
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(120.dp)
                                        .height(68.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.Black),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = item.thumbnailUrl ?: item.coverUrl,
                                        contentDescription = item.episodeTitle,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        color = Color(0xCC0B0C0F),
                                        shape = CircleShape,
                                        border = BorderStroke(1.dp, CrunchyOrange),
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = "Play Offline",
                                                tint = CrunchyOrange,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = item.animeTitle,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = CrunchyOrange,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "S1 E${item.episodeNumber} - ${item.episodeTitle}",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sizeMb = (item.sizeBytes / (1024 * 1024)).coerceAtLeast(1)
                                    Text(
                                        text = if (item.isCompleted) {
                                            "${item.quality} • ${if (item.audioType == "DUB") "Dub" else "Sub"} • ${sizeMb} MB"
                                        } else {
                                            "Downloading... ${item.progressPercent}%"
                                        },
                                        fontSize = 11.sp,
                                        color = if (item.isCompleted) CrunchyTeal else CrunchyOrange
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        TorrentStreamService.deleteOfflineEpisode(context, item.mediaId, item.episodeNumber)
                                        Toast.makeText(context, "Deleted from Offline Vault", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaruDanger)
                                }
                            }
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
                    Text("No anime in $selectedCategory.", color = MaruTextStrong, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tap any show to add it to your watchlist!", color = MaruTextMuted, fontSize = 12.sp)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
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
