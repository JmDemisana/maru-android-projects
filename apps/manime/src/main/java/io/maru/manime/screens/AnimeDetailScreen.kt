package io.maru.manime.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
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
import io.maru.manime.*
import org.jsoup.Jsoup

@Composable
fun AnimeDetailScreen(
    media: AnimeMedia,
    onBack: () -> Unit,
    onWatchEpisode: (Int) -> Unit
) {
    var isSynopsisExpanded by remember { mutableStateOf(false) }
    val cleanDescription = remember(media.description) {
        if (media.description == null) "No synopsis available."
        else try {
            Jsoup.parse(media.description).text()
        } catch (_: Exception) {
            media.description
        }
    }

    val totalEpisodes = media.episodes ?: 12
    val hasEnglishDub = media.externalLinks.any { it.isEnglishDub }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // Hero Banner Header
        item(key = "header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                // Banner background
                AsyncImage(
                    model = media.bannerUrl ?: media.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Dark gradient overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0x80050507),
                                    Color(0xDD0E0A1A),
                                    Color(0xFF050507)
                                )
                            )
                        )
                )

                // Back Button
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp)
                        .background(Color(0x66000000), CircleShape)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaruTextStrong
                    )
                }

                // Poster + Title Row
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    AsyncImage(
                        model = media.coverUrl,
                        contentDescription = media.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(100.dp)
                            .height(145.dp)
                            .clip(MaruInputShape)
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (hasEnglishDub) {
                            Surface(
                                color = Color(0x334ADE80),
                                shape = MaruPillShape,
                                border = BorderStroke(1.dp, MaruAccentGreen.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "🎙️ ENGLISH DUBBED",
                                    color = MaruAccentGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = media.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaruTextStrong,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (!media.titleEnglish.isNullOrEmpty() && media.titleEnglish != media.title) {
                            Text(
                                text = media.titleEnglish,
                                fontSize = 12.sp,
                                color = MaruTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
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
                            Text(text = "${media.episodes ?: "?"} eps", fontSize = 12.sp, color = MaruTextMuted)
                            if (media.seasonYear != null) {
                                Text(text = "${media.season ?: ""} ${media.seasonYear}", fontSize = 12.sp, color = MaruTextMuted)
                            }
                        }
                    }
                }
            }
        }

        // Synopsis Card
        item(key = "synopsis") {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable { isSynopsisExpanded = !isSynopsisExpanded }
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "SYNOPSIS",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        ),
                        color = MaruAccentPink
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = cleanDescription,
                        fontSize = 13.sp,
                        color = MaruTextStrong.copy(alpha = 0.9f),
                        lineHeight = 18.sp,
                        maxLines = if (isSynopsisExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (isSynopsisExpanded) "Show less" else "Read more...",
                        fontSize = 11.sp,
                        color = MaruAccentPink,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // IsThisDubbed Applet Card
        item(key = "is_this_dubbed_card") {
            var dubDetails by remember { mutableStateOf<DubDetails?>(null) }
            var isDubLoading by remember { mutableStateOf(true) }

            LaunchedEffect(media.mediaId) {
                isDubLoading = true
                try {
                    dubDetails = AniListClient.getDubDetails(media.mediaId)
                } catch (_: Exception) {} finally {
                    isDubLoading = false
                }
            }

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassSectionHeader(
                            title = "IS THIS DUBBED? APPLET",
                            icon = Icons.Default.AutoAwesome,
                            color = MaruAccentGreen
                        )

                        val isConfirmed = dubDetails?.isDubbed == true
                        Surface(
                            color = if (isConfirmed) Color(0x334ADE80) else Color(0x33FF5252),
                            shape = MaruPillShape,
                            border = BorderStroke(1.dp, if (isConfirmed) MaruAccentGreen else MaruDanger)
                        ) {
                            Text(
                                text = if (isConfirmed) "ENGLISH DUBBED" else "SUB ONLY",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.5.sp
                                ),
                                color = if (isConfirmed) MaruAccentGreen else MaruDanger,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isDubLoading) {
                        Text(text = "Scanning voice cast & dub registers...", fontSize = 12.sp, color = MaruTextMuted)
                    } else if (dubDetails?.isDubbed == true) {
                        if (dubDetails?.streamingPlatforms?.isNotEmpty() == true) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                dubDetails!!.streamingPlatforms.forEach { platform ->
                                    Surface(
                                        shape = MaruPillShape,
                                        color = Color(0x2260E2FF),
                                        border = BorderStroke(1.dp, MaruAccentBlue.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = platform,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaruAccentBlue,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (dubDetails?.englishCast?.isNotEmpty() == true) {
                            Text(
                                text = "ENGLISH VOICE CAST",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaruTextMuted,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                dubDetails!!.englishCast.take(4).forEach { role ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = role.characterName,
                                            fontSize = 12.sp,
                                            color = MaruTextStrong,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = role.voiceActor?.name ?: "English VA",
                                            fontSize = 11.5.sp,
                                            color = MaruAccentPink
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "No official English dub found for this anime. Japanese audio with soft subs will be streamed.",
                            fontSize = 12.sp,
                            color = MaruTextMuted
                        )
                    }
                }
            }
        }

        // Episodes Header
        item(key = "episodes_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassSectionHeader(
                    title = "EPISODES",
                    icon = Icons.Default.PlayCircle,
                    color = MaruAccentBlue
                )
                Text(
                    text = "$totalEpisodes AVAILABLE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = MaruTextMuted
                )
            }
        }

        // Episode List
        items(totalEpisodes, key = { "ep_$it" }) { index ->
            val episodeNum = index + 1
            EpisodeItemRow(
                episodeNum = episodeNum,
                title = "Episode $episodeNum",
                onWatch = { onWatchEpisode(episodeNum) }
            )
        }
    }
}

@Composable
fun EpisodeItemRow(
    episodeNum: Int,
    title: String,
    onWatch: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onWatch)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = Color(0x3360E2FF),
                    shape = MaruPillShape,
                    border = BorderStroke(1.dp, MaruAccentBlue.copy(alpha = 0.3f)),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "$episodeNum",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaruAccentBlue
                        )
                    }
                }

                Text(
                    text = title,
                    fontSize = 14.sp,
                    color = MaruTextStrong,
                    fontWeight = FontWeight.Medium
                )
            }

            Surface(
                onClick = onWatch,
                shape = CircleShape,
                color = MaruAccentPink,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Watch",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
