package io.maru.manime.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.*

@Composable
fun RecommendationsScreen(
    recommendations: List<AnimeMedia>,
    isLoading: Boolean,
    isLoggedIn: Boolean,
    username: String,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    onLoginClick: () -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit
) {
    var dubbedOnlyFilter by remember { mutableStateOf(false) }

    val categories = listOf(
        "For You",
        "CGDCT",
        "Idol",
        "Music",
        "Slice of Life",
        "Romance",
        "Sci-Fi",
        "Action",
        "Fantasy",
        "Comedy"
    )

    val displayedRecs = remember(recommendations, dubbedOnlyFilter) {
        if (!dubbedOnlyFilter) recommendations
        else {
            val filtered = recommendations.filter { media ->
                media.externalLinks.any { it.isEnglishDub }
            }
            if (filtered.isNotEmpty()) filtered else recommendations
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Not Logged In Banner
        if (!isLoggedIn) {
            item(span = { GridItemSpan(2) }, key = "login_banner") {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "GET PERSONALIZED SUGGESTIONS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.5.sp,
                                    letterSpacing = 0.6.sp
                                ),
                                color = MaruAccentPink
                            )
                            Text(
                                text = "Log in with AniList to get anime suggestions tailored to your watching history.",
                                color = MaruTextMuted,
                                fontSize = 11.5.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            onClick = onLoginClick,
                            shape = MaruPillShape,
                            color = MaruAccentPink
                        ) {
                            Text(
                                text = "LOGIN",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp
                                ),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // Section Header & Dub Filter
        item(span = { GridItemSpan(2) }, key = "header_row") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassSectionHeader(
                    title = if (isLoggedIn && selectedCategory == "For You") "TASTE PROFILE ($username)" else "TASTE & GENRE ENGINE",
                    icon = Icons.Default.AutoAwesome,
                    color = MaruAccentBlue
                )

                Surface(
                    onClick = { dubbedOnlyFilter = !dubbedOnlyFilter },
                    shape = MaruPillShape,
                    color = if (dubbedOnlyFilter) Color(0x334ADE80) else MaruGlassSubtleBg,
                    border = BorderStroke(1.dp, if (dubbedOnlyFilter) MaruAccentGreen else MaruGlassBorderSoft)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.PlayCircle,
                            contentDescription = null,
                            tint = if (dubbedOnlyFilter) MaruAccentGreen else MaruTextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = if (dubbedOnlyFilter) "DUBBED ONLY" else "IS THIS DUBBED?",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp
                            ),
                            color = if (dubbedOnlyFilter) MaruAccentGreen else MaruTextMuted
                        )
                    }
                }
            }
        }

        // Category Filter Pills Bar
        item(span = { GridItemSpan(2) }, key = "categories_bar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                categories.forEach { cat ->
                    val isSelected = cat == selectedCategory
                    Surface(
                        onClick = { onSelectCategory(cat) },
                        shape = MaruPillShape,
                        color = if (isSelected) MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                        border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft)
                    ) {
                        Text(
                            text = cat.uppercase(),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaruAccentPink else MaruTextMuted,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        if (isLoading) {
            item(span = { GridItemSpan(2) }, key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaruAccentPink)
                }
            }
        } else if (displayedRecs.isEmpty()) {
            item(span = { GridItemSpan(2) }, key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (dubbedOnlyFilter) "No dubbed anime found in this category." else "No recommendations found.",
                        color = MaruTextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            items(displayedRecs, key = { "rec_${it.mediaId}" }) { media ->
                PosterCard(
                    media = media,
                    onClick = { onAnimeClick(media) }
                )
            }
        }
    }
}
