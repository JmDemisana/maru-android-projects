package io.maru.manime.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stars
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
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit
) {
    val categories = listOf(
        "All",
        "Action",
        "Romance",
        "Fantasy",
        "Slice of Life",
        "CGDCT",
        "Idol",
        "Music",
        "Sci-Fi",
        "Comedy"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

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
                CircularProgressIndicator(color = Color(0xFF60E2FF))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(recommendations, key = { "rec_${it.mediaId}" }) { media ->
                    AnimeListItemCard(
                        media = media,
                        onClick = { onAnimeClick(media) }
                    )
                }
            }
        }
    }
}
