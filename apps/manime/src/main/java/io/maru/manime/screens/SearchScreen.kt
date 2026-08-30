package io.maru.manime.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.maru.manime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen(
    searchResults: List<AnimeMedia>,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit,
    onUserClick: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searchType by remember { mutableIntStateOf(0) } // 0: Anime, 1: Users
    var dubbedOnly by remember { mutableStateOf(false) }

    var userResults by remember { mutableStateOf<List<AniListProfile>>(emptyList()) }
    var isUserSearching by remember { mutableStateOf(false) }

    fun performUserSearch(q: String) {
        if (q.isBlank()) {
            userResults = emptyList()
            return
        }
        isUserSearching = true
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    AniListClient.searchUsers(q)
                }
                userResults = list
            } catch (_: Exception) {
                userResults = emptyList()
            } finally {
                isUserSearching = false
            }
        }
    }

    val filteredAnimeResults = remember(searchResults, dubbedOnly) {
        if (!dubbedOnly) searchResults
        else searchResults.filter { m -> m.externalLinks.any { it.isEnglishDub } }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // Search Input Bar
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
                        if (searchType == 0) onSearch(it)
                        else performUserSearch(it)
                    },
                    placeholder = {
                        Text(
                            if (searchType == 0) "Search anime, movies, ONAs..." else "Search AniList users...",
                            color = MaruTextMuted.copy(alpha = 0.6f)
                        )
                    },
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
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        if (searchType == 0) onSearch("")
                        else userResults = emptyList()
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", tint = MaruTextMuted)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Search Mode Selector: [ANIME] [USERS]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    searchType = 0
                    if (query.isNotBlank()) onSearch(query)
                },
                shape = MaruPillShape,
                color = if (searchType == 0) MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                border = BorderStroke(1.dp, if (searchType == 0) MaruAccentPink else MaruGlassBorderSoft),
                modifier = Modifier.weight(1f)
            ) {
                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "ANIME",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (searchType == 0) MaruAccentPink else MaruTextMuted
                    )
                }
            }

            Surface(
                onClick = {
                    searchType = 1
                    if (query.isNotBlank()) performUserSearch(query)
                },
                shape = MaruPillShape,
                color = if (searchType == 1) MaruAccentBlue.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                border = BorderStroke(1.dp, if (searchType == 1) MaruAccentBlue else MaruGlassBorderSoft),
                modifier = Modifier.weight(1f)
            ) {
                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "USERS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (searchType == 1) MaruAccentBlue else MaruTextMuted
                    )
                }
            }
        }

        if (searchType == 0) {
            // Dubbed Only Toggle Chip for Anime
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                Surface(
                    onClick = { dubbedOnly = !dubbedOnly },
                    shape = MaruPillShape,
                    color = if (dubbedOnly) Color(0x334ADE80) else MaruGlassSubtleBg,
                    border = BorderStroke(1.dp, if (dubbedOnly) MaruAccentGreen else MaruGlassBorderSoft)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = if (dubbedOnly) MaruAccentGreen else MaruTextMuted, modifier = Modifier.size(13.dp))
                        Text(
                            text = "DUBBED ONLY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (dubbedOnly) MaruAccentGreen else MaruTextMuted
                        )
                    }
                }
            }

            if (isSearching) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaruAccentPink)
                }
            } else if (filteredAnimeResults.isEmpty() && query.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No anime found matching '$query'.", color = MaruTextMuted, fontSize = 13.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(filteredAnimeResults, key = { it.mediaId }) { mediaItem ->
                        PosterCard(media = mediaItem, onClick = { onAnimeClick(mediaItem) })
                    }
                }
            }
        } else {
            // User search results
            Spacer(Modifier.height(8.dp))
            if (isUserSearching) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaruAccentBlue)
                }
            } else if (userResults.isEmpty() && query.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No users found matching '$query'.", color = MaruTextMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(userResults, key = { "u_${it.id}" }) { userItem ->
                        UserCardRow(user = userItem, onClick = { onUserClick(userItem.name) })
                    }
                }
            }
        }
    }
}
