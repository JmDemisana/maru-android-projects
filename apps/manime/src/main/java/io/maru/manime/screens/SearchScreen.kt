package io.maru.manime.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
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
fun SearchScreen(
    searchResults: List<AnimeMedia>,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var dubbedOnly by remember { mutableStateOf(false) }
    var selectedGenre by remember { mutableStateOf<String?>(null) }

    val genres = listOf("Action", "Romance", "Slice of Life", "Sci-Fi", "Comedy", "Music", "Fantasy", "Mystery", "Supernatural", "Mecha", "Sports")

    val filteredResults = remember(searchResults, dubbedOnly) {
        if (!dubbedOnly) searchResults
        else searchResults.filter { m -> m.externalLinks.any { it.isEnglishDub } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // MAudio Exact Glass Input
        Surface(
            shape = MaruInputShape,
            color = Color(0x33000000),
            border = BorderStroke(1.dp, MaruGlassBorderSoft),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaruAccentPink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                TextField(
                    value = query,
                    onValueChange = {
                        query = it
                        selectedGenre = null
                        onSearch(it)
                    },
                    placeholder = { Text("Search anime, movies, OVAs...", color = MaruTextMuted.copy(alpha = 0.6f)) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedTextColor = MaruTextStrong,
                        unfocusedTextColor = MaruTextStrong
                    ),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                if (query.isNotEmpty() || selectedGenre != null) {
                    IconButton(onClick = {
                        query = ""
                        selectedGenre = null
                        onSearch("")
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaruTextMuted)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Filter chips with MAudio pill style
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = { dubbedOnly = !dubbedOnly },
                shape = MaruPillShape,
                color = if (dubbedOnly) Color(0x334ADE80) else MaruGlassSubtleBg,
                border = BorderStroke(1.dp, if (dubbedOnly) MaruAccentGreen else MaruGlassBorderSoft)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "ðŸŽ™ï¸ DUBBED ONLY",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp
                        ),
                        color = if (dubbedOnly) MaruAccentGreen else MaruTextStrong
                    )
                }
            }

            genres.forEach { genre ->
                val isSelected = selectedGenre == genre
                Surface(
                    onClick = {
                        if (isSelected) {
                            selectedGenre = null
                            query = ""
                            onSearch("")
                        } else {
                            selectedGenre = genre
                            query = genre
                            onSearch(genre)
                        }
                    },
                    shape = MaruPillShape,
                    color = if (isSelected) MaruAccentPink.copy(alpha = 0.3f) else MaruGlassSubtleBg,
                    border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft)
                ) {
                    Text(
                        text = genre,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        color = if (isSelected) MaruAccentPink else MaruTextMuted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFF60E2FF))
            }
        } else if (filteredResults.isEmpty() && (query.isNotEmpty() || selectedGenre != null)) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No matching anime found",
                    color = Color(0x80FFFFFF),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredResults, key = { "search_${it.mediaId}" }) { media ->
                    AnimeListItemCard(
                        media = media,
                        onClick = { onAnimeClick(media) }
                    )
                }
            }
        }
    }
}
