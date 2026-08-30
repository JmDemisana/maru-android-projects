package io.maru.manime.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.maru.manime.*

@Composable
fun ProfileScreen(
    user: AniListUser?,
    allLists: Map<String, List<AnimeMedia>>,
    watchingCount: Int,
    completedCount: Int,
    planningCount: Int,
    anilistToken: String,
    isLoading: Boolean = false,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit,
    onMediaUpdated: (AnimeMedia) -> Unit = {}
) {
    val context = LocalContext.current

    val availableCategories = remember(allLists) {
        if (allLists.isEmpty()) {
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
    }

    var selectedCategory by remember(availableCategories) {
        mutableStateOf(availableCategories.firstOrNull() ?: "Watching")
    }

    val currentList = remember(allLists, selectedCategory) {
        if (selectedCategory.equals("Completed", ignoreCase = true)) {
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

    val isLoggedIn = anilistToken.isNotBlank()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Library Categories Scrollable Filter Bar (Full Span)
        item(span = { GridItemSpan(2) }, key = "categories_bar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableCategories.forEach { cat ->
                    val isSelected = selectedCategory.equals(cat, ignoreCase = true)
                    val count = if (cat.equals("Completed", ignoreCase = true) && !allLists.containsKey("Completed")) {
                        completedCount
                    } else if (cat.equals("Watching", ignoreCase = true) && !allLists.containsKey("Watching")) {
                        watchingCount
                    } else if (cat.equals("Planning", ignoreCase = true) && !allLists.containsKey("Planning")) {
                        planningCount
                    } else {
                        allLists[cat]?.size ?: allLists.entries.find { it.key.equals(cat, ignoreCase = true) }?.value?.size ?: 0
                    }

                    Surface(
                        onClick = { selectedCategory = cat },
                        shape = MaruPillShape,
                        color = if (isSelected) MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                        border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = cat.uppercase(),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaruAccentPink else MaruTextMuted
                            )
                            if (count > 0) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) MaruAccentPink.copy(alpha = 0.4f) else Color(0x33FFFFFF)
                                ) {
                                    Text(
                                        text = "$count",
                                        fontSize = 9.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaruTextStrong,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Category Title Header with Item Count (Full Span)
        item(span = { GridItemSpan(2) }, key = "section_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = selectedCategory,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaruTextStrong
                )
                Text(
                    text = "(${currentList.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaruTextMuted
                )
            }
        }

        if (currentList.isEmpty()) {
            item(span = { GridItemSpan(2) }, key = "empty_list") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = MaruAccentPink)
                    } else {
                        Text("No anime in $selectedCategory.", color = MaruTextMuted, fontSize = 13.sp)
                    }
                }
            }
        } else {
            // 2-Column Poster Cards exactly matching the web reference
            items(currentList, key = { "media_${it.mediaId}" }) { itemMedia ->
                PosterCard(
                    media = itemMedia,
                    onClick = { onAnimeClick(itemMedia) }
                )
            }
        }
    }
}

@Composable
fun PosterCard(
    media: AnimeMedia,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF140F22),
        border = BorderStroke(1.dp, MaruGlassBorderSoft),
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Full Cover Background Image
            AsyncImage(
                model = media.coverUrl,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Bottom Gradient Scrim Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Transparent,
                                Color(0x99050507),
                                Color(0xF5050507)
                            )
                        )
                    )
            )

            // Top Badges (Dub / Score)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (media.score != null && media.score > 0) {
                    Surface(
                        color = Color(0xCC0E0A1A),
                        shape = MaruPillShape,
                        border = BorderStroke(1.dp, MaruAccentYellow.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = MaruAccentYellow, modifier = Modifier.size(10.dp))
                            Text(text = "${media.score}", fontSize = 9.5.sp, color = MaruAccentYellow, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.size(1.dp))
                }

                if (media.externalLinks.any { it.isEnglishDub }) {
                    Surface(
                        color = Color(0xCC0E0A1A),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaruAccentGreen.copy(alpha = 0.5f)),
                        modifier = Modifier.size(22.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = "D", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaruAccentGreen)
                        }
                    }
                }
            }

            // Bottom Text Info (Title + Ep Progress + Season)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = media.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    lineHeight = 15.sp
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Ep ${media.progress}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaruAccentPink
                    )
                    Text(
                        text = "/ ${media.episodes ?: "?"}",
                        fontSize = 11.sp,
                        color = MaruTextMuted
                    )
                    if (media.seasonYear != null) {
                        Text(
                            text = "${media.season ?: ""} ${media.seasonYear}".trim(),
                            fontSize = 10.sp,
                            color = MaruTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
