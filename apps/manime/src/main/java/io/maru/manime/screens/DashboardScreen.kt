package io.maru.manime.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.AutoAwesome
import io.maru.manime.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    watchingList: List<AnimeMedia>,
    trendingList: List<AnimeMedia>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit
) {
    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Continue Watching Rail (if user is logged in and has items)
            if (watchingList.isNotEmpty()) {
                item(key = "continue_watching") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        GlassSectionHeader(
                            title = "CONTINUE WATCHING",
                            icon = Icons.Default.AutoAwesome,
                            color = MaruAccentPink
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            watchingList.forEach { media ->
                                WatchingCard(media = media, onClick = { onAnimeClick(media) })
                            }
                        }
                    }
                }
            }

            // Trending This Season
            item(key = "trending_header") {
                GlassSectionHeader(
                    title = "TRENDING THIS SEASON",
                    icon = Icons.Default.AutoAwesome,
                    color = MaruAccentBlue
                )
            }

            items(trendingList, key = { "trending_${it.mediaId}" }) { media ->
                AnimeListItemCard(
                    media = media,
                    onClick = { onAnimeClick(media) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
fun WatchingCard(
    media: AnimeMedia,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                AsyncImage(
                    model = media.coverUrl,
                    contentDescription = media.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                val progressText = if (media.episodes != null) "Ep ${media.progress}/${media.episodes}" else "Ep ${media.progress}"
                Surface(
                    color = Color(0xDD0E0A1A),
                    shape = RoundedCornerShape(topStart = 8.dp),
                    border = BorderStroke(1.dp, MaruGlassBorderSoft),
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Text(
                        text = progressText,
                        color = MaruAccentPink,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = media.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaruTextStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}

@Composable
fun AnimeListItemCard(
    media: AnimeMedia,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasEnglishDub = media.externalLinks.any { it.isEnglishDub }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncImage(
                model = media.coverUrl,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(64.dp)
                    .height(90.dp)
                    .clip(MaruInputShape)
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (hasEnglishDub) {
                        Surface(
                            color = Color(0x334ADE80),
                            shape = MaruPillShape,
                            border = BorderStroke(1.dp, MaruAccentGreen.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = "DUB",
                                color = MaruAccentGreen,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (media.format != null) {
                        Surface(
                            color = MaruGlassSubtleBg,
                            shape = MaruPillShape,
                            border = BorderStroke(1.dp, MaruGlassBorderSoft)
                        ) {
                            Text(
                                text = media.format,
                                fontSize = 9.5.sp,
                                color = MaruTextMuted,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = media.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaruTextStrong,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (media.averageScore != null) {
                        Surface(
                            color = Color(0x22FBBF24),
                            shape = MaruPillShape,
                            border = BorderStroke(1.dp, MaruAccentYellow.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = MaruAccentYellow, modifier = Modifier.size(12.dp))
                                Text(text = "${media.averageScore}%", fontSize = 10.5.sp, color = MaruAccentYellow, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (media.episodes != null) {
                        Text(text = "${media.episodes} eps", fontSize = 12.sp, color = MaruTextMuted)
                    }
                }
            }
        }
    }
}
