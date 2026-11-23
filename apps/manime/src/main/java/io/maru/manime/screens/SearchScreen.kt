package io.maru.manime.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun SearchScreen(
    anilistToken: String,
    onAnimeClick: (AnimeMedia) -> Unit,
    onUserClick: (String) -> Unit = {}
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchUsers by rememberSaveable { mutableStateOf(false) }
    var dubbedOnly by rememberSaveable { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val term = query.trim()
    val token = anilistToken.takeUnless { searchUsers || it.isBlank() }
    var animeResults by remember(term, searchUsers, token) { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var userResults by remember(term, searchUsers, token) { mutableStateOf<List<AniListProfile>>(emptyList()) }
    var isSearching by remember(term, searchUsers, token) { mutableStateOf(term.isNotEmpty()) }
    var searchFailed by remember(term, searchUsers, token) { mutableStateOf(false) }

    LaunchedEffect(term, searchUsers, token, retry) {
        if (term.isEmpty()) return@LaunchedEffect
        isSearching = true
        searchFailed = false
        try {
            delay(300)
            if (searchUsers) {
                userResults = withContext(Dispatchers.IO) { AniListClient.searchUsers(term).distinctBy { it.id } }
            } else {
                animeResults = withContext(Dispatchers.IO) {
                    AniListClient.search(term, 1, 30, token).results.distinctBy { it.mediaId }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            searchFailed = true
        }
        isSearching = false
    }

    val filteredAnimeResults = remember(animeResults, dubbedOnly) {
        if (!dubbedOnly) animeResults
        else animeResults.filter { it.hasEnglishDub }
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
                    onValueChange = { query = it },
                    placeholder = {
                        Text(
                            if (searchUsers) "Search AniList users..." else "Search anime, movies, ONAs...",
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
                    IconButton(onClick = { query = "" }) {
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
                onClick = { searchUsers = false },
                shape = MaruPillShape,
                color = if (!searchUsers) MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                border = BorderStroke(1.dp, if (!searchUsers) MaruAccentPink else MaruGlassBorderSoft),
                modifier = Modifier.weight(1f)
            ) {
                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "ANIME",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (!searchUsers) MaruAccentPink else MaruTextMuted
                    )
                }
            }

            Surface(
                onClick = { searchUsers = true },
                shape = MaruPillShape,
                color = if (searchUsers) MaruAccentBlue.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                border = BorderStroke(1.dp, if (searchUsers) MaruAccentBlue else MaruGlassBorderSoft),
                modifier = Modifier.weight(1f)
            ) {
                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "USERS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (searchUsers) MaruAccentBlue else MaruTextMuted
                    )
                }
            }
        }

        if (!searchUsers) {
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
            } else if (searchFailed) {
                SearchRetry { retry++ }
            } else if (filteredAnimeResults.isEmpty() && term.isNotEmpty()) {
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
            if (isSearching) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaruAccentBlue)
                }
            } else if (searchFailed) {
                SearchRetry { retry++ }
            } else if (userResults.isEmpty() && term.isNotEmpty()) {
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

@Composable
private fun SearchRetry(onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Couldn't load search results.", color = MaruTextMuted)
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}
