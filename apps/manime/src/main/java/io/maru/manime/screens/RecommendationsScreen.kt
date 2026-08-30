package io.maru.manime.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
        else recommendations.filter { media ->
            media.externalLinks.any { it.isEnglishDub || it.site.contains("Dub", ignoreCase = true) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Not Logged In Banner
        if (!isLoggedIn) {
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
                            text = "Log in with AniList to get anime suggestions tailored to your watching history & score affinity.",
                            style = MaterialTheme.typography.bodySmall,
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
            Spacer(modifier = Modifier.height(8.dp))
        }

        // IsThisDubbed Applet Filter Bar
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

            // IsThisDubbed Toggle Pill
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

        // Category horizontal scroll pills in MAudio style
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            categories.forEach { cat ->
                val isSelected = cat == selectedCategory
                Surface(
                    onClick = { onSelectCategory(cat) },
                    shape = MaruPillShape,
                    color = if (isSelected) Color(0x33E85D9F) else MaruGlassSubtleBg,
                    border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft)
                ) {
                    Text(
                        text = cat.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.6.sp
                        ),
                        color = if (isSelected) MaruAccentPink else MaruTextMuted,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaruAccentPink)
            }
        } else if (displayedRecs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (dubbedOnlyFilter) "No dubbed anime found in this category." else "No recommendations found.",
                    color = MaruTextMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(displayedRecs, key = { "rec_${it.mediaId}" }) { media ->
                    AnimeListItemCard(
                        media = media,
                        onClick = { onAnimeClick(media) }
                    )
                }
            }
        }
    }
}
