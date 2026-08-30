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
import androidx.compose.runtime.*
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ThumbUp
import io.maru.manime.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    watchingList: List<AnimeMedia>,
    trendingList: List<AnimeMedia>,
    activitiesList: List<AniListActivity>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit
) {
    var selectedSubTab by remember { mutableIntStateOf(0) } // 0: Discovery, 1: AniList Feed

    Column(modifier = Modifier.fillMaxSize()) {
        // MAudio Pills Sub-Tab Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Discovery Feed", "AniList Community").forEachIndexed { index, title ->
                val isSelected = selectedSubTab == index
                Surface(
                    onClick = { selectedSubTab = index },
                    shape = MaruPillShape,
                    color = if (isSelected) Color(0x33E85D9F) else MaruGlassSubtleBg,
                    border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = title.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 0.6.sp
                            ),
                            color = if (isSelected) MaruAccentPink else MaruTextMuted
                        )
                    }
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            if (selectedSubTab == 0) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 32.dp),
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
            } else {
                // AniList Community Activity Feed
                if (activitiesList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isLoading) "Loading community posts..." else "No recent community activity found.",
                            color = MaruTextMuted,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(activitiesList, key = { "act_${it.id}" }) { act ->
                            ActivityCard(activity = act)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActivityCard(activity: AniListActivity) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AsyncImage(
                    model = activity.userAvatar,
                    contentDescription = activity.userName,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activity.userName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaruAccentPink
                    )
                    val actionText = if (activity.type == "ANIME_LIST") {
                        "${activity.status ?: "Updated"} ${activity.progress ?: ""}".trim()
                    } else "Posted"
                    Text(
                        text = actionText,
                        fontSize = 11.sp,
                        color = MaruTextMuted
                    )
                }
            }

            if (!activity.text.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = activity.text,
                    fontSize = 13.sp,
                    color = MaruTextStrong,
                    lineHeight = 18.sp
                )
            }

            if (!activity.mediaTitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = MaruInputShape,
                    color = Color(0x33000000),
                    border = BorderStroke(1.dp, MaruGlassBorderSoft),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AsyncImage(
                            model = activity.mediaCover,
                            contentDescription = activity.mediaTitle,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Text(
                            text = activity.mediaTitle,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                            color = MaruAccentBlue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.ThumbUp, contentDescription = null, tint = MaruAccentPink, modifier = Modifier.size(14.dp))
                    Text(text = "${activity.likeCount}", fontSize = 11.sp, color = MaruTextMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.ChatBubble, contentDescription = null, tint = MaruAccentBlue, modifier = Modifier.size(14.dp))
                    Text(text = "${activity.replyCount}", fontSize = 11.sp, color = MaruTextMuted)
                }
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
