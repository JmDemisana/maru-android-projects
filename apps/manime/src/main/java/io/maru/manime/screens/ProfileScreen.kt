package io.maru.manime.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
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

    Column(modifier = Modifier.fillMaxSize()) {
        // Sticky Categories Bar at the Top!
        Surface(
            color = Color(0xEE0E0A1A),
            border = BorderStroke(0.dp, Color.Transparent),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                availableCategories.forEach { category ->
                    val isSelected = selectedCategory.equals(category, ignoreCase = true)
                    val count = allLists[category]?.size
                    val label = if (count != null && count > 0) "${category.uppercase()} ($count)" else category.uppercase()

                    Surface(
                        onClick = { selectedCategory = category },
                        shape = MaruPillShape,
                        color = if (isSelected) MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                        border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = if (isSelected) MaruAccentPink else MaruTextMuted,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)

        if (isLoading && currentList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaruAccentPink)
            }
        } else if (currentList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "No anime in $selectedCategory.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaruTextMuted
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(currentList, key = { it.mediaId }) { mediaItem ->
                    PosterCard(
                        media = mediaItem,
                        onClick = { onAnimeClick(mediaItem) }
                    )
                }
            }
        }
    }
}
