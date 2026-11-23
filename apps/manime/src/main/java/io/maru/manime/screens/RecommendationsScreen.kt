package io.maru.manime.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.*
import kotlinx.coroutines.delay

private val browseCategories = listOf(
    "For You", "Simulcast Calendar", "Trending Now", "Romance & CGDCT", "Music & Idols",
    "Slice of Life", "Action & Shounen", "Sci-Fi & Cyberpunk", "Fantasy", "Comedy"
)

@Composable
fun RecommendationsScreen(
    recommendations: List<AnimeMedia>,
    isLoading: Boolean,
    loadFailed: Boolean,
    onRetry: () -> Unit,
    isLoggedIn: Boolean,
    username: String,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    onLoginClick: () -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit
) {
    var dubbedOnlyFilter by rememberSaveable { mutableStateOf(false) }
    val displayedRecs = remember(recommendations, dubbedOnlyFilter) {
        if (dubbedOnlyFilter) recommendations.filter { it.hasEnglishDub } else recommendations
    }
    val isSimulcast = selectedCategory == "Simulcast Calendar"
    val nowSec by produceState(System.currentTimeMillis() / 1000L, isSimulcast) {
        if (!isSimulcast) return@produceState
        while (true) {
            value = System.currentTimeMillis() / 1000L
            delay(60_000)
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(128.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, top = 8.dp, end = 14.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "categories_bar") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(browseCategories, key = { it }) { category ->
                    FilterChip(
                        selected = category == selectedCategory,
                        onClick = { if (category != selectedCategory) onSelectCategory(category) },
                        label = { Text(category) }
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "header_row") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isLoggedIn && selectedCategory == "For You") "For you, $username" else selectedCategory,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = dubbedOnlyFilter,
                    onClick = { dubbedOnlyFilter = !dubbedOnlyFilter },
                    label = { Text("Dubbed only") }
                )
            }
        }

        if (!isLoggedIn && selectedCategory == "For You") {
            item(span = { GridItemSpan(maxLineSpan) }, key = "login_banner") {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Connect AniList for suggestions based on your watchlist.",
                            color = MaruTextMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onLoginClick) { Text("Connect") }
                    }
                }
            }
        }

        if (isLoading) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                if (displayedRecs.isNotEmpty()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CrunchyOrange)
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = CrunchyOrange)
                    }
                }
            }
        } else if (loadFailed) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                BrowseMessage("Couldn't load this category.", "Try again", onRetry)
            }
        } else if (displayedRecs.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                if (dubbedOnlyFilter && recommendations.isNotEmpty()) {
                    BrowseMessage("None of these titles are marked as dubbed.", "Show all titles") { dubbedOnlyFilter = false }
                } else {
                    BrowseMessage("No titles found in this category.")
                }
            }
        }

        items(displayedRecs, key = { it.mediaId }) { media ->
            val episodeAt = media.nextEpisodeAt
            val episode = media.nextEpisode
            val countdown = if (isSimulcast && episodeAt != null && episode != null && episodeAt > nowSec) {
                val minutes = (episodeAt - nowSec + 59) / 60
                val hours = minutes / 60
                val relativeTime = when {
                    hours >= 24 -> "${hours / 24}d ${hours % 24}h"
                    hours >= 1 -> "${hours}h"
                    else -> "${minutes}m"
                }
                "Ep $episode in $relativeTime"
            } else null
            Box {
                PosterCard(media = media, onClick = { onAnimeClick(media) })
                if (countdown != null) {
                    Surface(
                        color = CrunchyTeal,
                        shape = MaruPillShape,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    ) {
                        Text(
                            text = countdown,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseMessage(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(message, color = MaruTextMuted, textAlign = TextAlign.Center)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}
