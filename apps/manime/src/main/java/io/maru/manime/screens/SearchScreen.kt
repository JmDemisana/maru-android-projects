package io.maru.manime.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Search Input Bar
        item(span = { GridItemSpan(2) }, key = "search_bar") {
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
        }

        // Filter chips bar
        item(span = { GridItemSpan(2) }, key = "filters_bar") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Dubbed Only Pill
                Surface(
                    onClick = { dubbedOnly = !dubbedOnly },
                    shape = MaruPillShape,
                    color = if (dubbedOnly) Color(0x334ADE80) else MaruGlassSubtleBg,
                    border = BorderStroke(1.dp, if (dubbedOnly) MaruAccentGreen else MaruGlassBorderSoft)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (dubbedOnly) MaruAccentGreen else MaruTextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "DUBBED ONLY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
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
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaruAccentPink else MaruTextMuted,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        if (isSearching) {
            item(span = { GridItemSpan(2) }, key = "searching") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaruAccentBlue)
                }
            }
        } else if (filteredResults.isEmpty() && (query.isNotEmpty() || selectedGenre != null)) {
            item(span = { GridItemSpan(2) }, key = "empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (dubbedOnly) "No dubbed results found" else "No matching anime found",
                        color = MaruTextMuted,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            items(filteredResults, key = { "search_${it.mediaId}" }) { media ->
                PosterCard(
                    media = media,
                    onClick = { onAnimeClick(media) }
                )
            }
        }
    }
}
