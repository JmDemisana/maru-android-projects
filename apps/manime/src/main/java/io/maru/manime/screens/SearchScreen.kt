package io.maru.manime.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val browseCategories = listOf(
    "For You", "Simulcast Calendar", "Trending Now", "Romance & CGDCT", "Music & Idols",
    "Slice of Life", "Action & Shounen", "Sci-Fi & Cyberpunk", "Fantasy", "Comedy"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    anilistToken: String,
    browseItems: List<AnimeMedia> = emptyList(),
    isBrowseLoading: Boolean = false,
    browseLoadFailed: Boolean = false,
    onRetryBrowse: () -> Unit = {},
    isLoggedIn: Boolean = false,
    hasLocalSeeds: Boolean = false,
    username: String = "Guest",
    selectedBrowseCategory: String = "For You",
    onSelectBrowseCategory: (String) -> Unit = {},
    onLoginClick: () -> Unit = {},
    onAnimeClick: (AnimeMedia) -> Unit,
    onUserClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val knownDubIds by KnownDubRegistry.dubbedIds.collectAsState()

    var query by rememberSaveable { mutableStateOf("") }
    var searchUsers by rememberSaveable { mutableStateOf(false) }
    var dubbedOnly by rememberSaveable { mutableStateOf(false) }
    val term = query.trim()
    val token = anilistToken.takeUnless { searchUsers || it.isBlank() }
    var submitCount by remember(term, searchUsers) { mutableIntStateOf(0) }
    var animeResults by remember(term, searchUsers, token) { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var userResults by remember(term, searchUsers, token) { mutableStateOf<List<AniListProfile>>(emptyList()) }
    var isSearching by remember(term, searchUsers, token) { mutableStateOf(term.isNotEmpty()) }
    var isDebouncing by remember(term, searchUsers, token) { mutableStateOf(term.isNotEmpty()) }
    var searchFailed by remember(term, searchUsers, token) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun dismissKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    LaunchedEffect(term, searchUsers, token, submitCount) {
        if (term.isEmpty()) return@LaunchedEffect
        isSearching = true
        searchFailed = false
        try {
            if (submitCount == 0) delay(280)
            isDebouncing = false
            if (searchUsers) {
                userResults = withContext(Dispatchers.IO) {
                    AniListClient.searchUsers(term).distinctBy { it.id }
                }
            } else {
                animeResults = withContext(Dispatchers.IO) {
                    AniListClient.search(term, 1, 32, token).results.distinctBy { it.mediaId }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            searchFailed = true
        }
        isSearching = false
    }

    // Background stream dub check on visible search / browse items so new simuldubs flip to Dub | Sub!
    LaunchedEffect(term, animeResults, browseItems) {
        val pool = if (term.isNotEmpty()) animeResults else browseItems
        if (pool.isNotEmpty()) {
            KnownDubRegistry.verifyCandidatesInBackground(context, pool, maxProbes = 10)
        }
    }

    val filteredAnimeResults = remember(animeResults, dubbedOnly, knownDubIds) {
        if (!dubbedOnly) animeResults else animeResults.filter { it.hasEnglishDub || it.mediaId in knownDubIds }
    }
    val filteredBrowseItems = remember(browseItems, dubbedOnly, knownDubIds) {
        if (!dubbedOnly) browseItems else browseItems.filter { it.hasEnglishDub || it.mediaId in knownDubIds }
    }

    val isSimulcast = term.isEmpty() && selectedBrowseCategory == "Simulcast Calendar"
    val nowSec by produceState(System.currentTimeMillis() / 1000L, isSimulcast) {
        if (!isSimulcast) return@produceState
        while (true) {
            value = System.currentTimeMillis() / 1000L
            delay(60_000)
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 960.dp)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 14.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        text = if (searchUsers) "Search AniList usernames…" else "Search anime series, movies, or browse below…",
                        color = MaruTextMuted,
                        fontSize = 13.5.sp
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CrunchyOrange) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Color.White)
                        }
                    }
                },
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = CrunchySurface,
                    unfocusedContainerColor = CrunchySurface,
                    focusedBorderColor = CrunchyOrange,
                    unfocusedBorderColor = CrunchyBorder,
                    focusedTextColor = MaruTextStrong,
                    unfocusedTextColor = MaruTextStrong,
                    cursorColor = CrunchyOrange
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    dismissKeyboard()
                    if (term.isNotBlank() && (isDebouncing || searchFailed)) submitCount++
                }),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            // Search Mode + Dubbed Filter Chips (only show Anime/Users toggle when typing or in Users mode)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (term.isNotEmpty() || searchUsers) {
                    CrunchyChip(
                        label = "Anime",
                        selected = !searchUsers,
                        onClick = { searchUsers = false }
                    )
                    CrunchyChip(
                        label = "AniList Users",
                        selected = searchUsers,
                        onClick = { searchUsers = true }
                    )
                }
                if (!searchUsers) {
                    CrunchyChip(
                        label = "Dubbed Only",
                        selected = dubbedOnly,
                        accent = CrunchyTeal,
                        onClick = { dubbedOnly = !dubbedOnly }
                    )
                }
            }

            AnimatedContent(
                targetState = term.isEmpty() && !searchUsers,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "search_or_browse",
                modifier = Modifier.fillMaxSize()
            ) { showingBrowseHub ->
                if (showingBrowseHub) {
                    // ==================== EMBEDDED BROWSE HUB WHEN NOTHING IS SEARCHED ====================
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(136.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "browse_categories_bar") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(browseCategories, key = { it }) { category ->
                                    CrunchyChip(
                                        label = category,
                                        selected = category == selectedBrowseCategory,
                                        onClick = {
                                            if (category != selectedBrowseCategory) onSelectBrowseCategory(category)
                                        }
                                    )
                                }
                            }
                        }

                        item(span = { GridItemSpan(maxLineSpan) }, key = "browse_header_row") {
                            val titleText = when {
                                selectedBrowseCategory != "For You" -> selectedBrowseCategory
                                isLoggedIn -> "For You, $username"
                                hasLocalSeeds -> "Recommended From Your Watchlist"
                                else -> "Popular & Trending Picks"
                            }
                            Text(
                                text = titleText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        if (!isLoggedIn && !hasLocalSeeds && selectedBrowseCategory == "For You") {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "browse_guest_hint") {
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Add shows to My Lists or connect AniList to personalize For You.",
                                            color = MaruTextMuted,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = onLoginClick) {
                                            Text("Connect", color = CrunchyOrange, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        if (isBrowseLoading) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "browse_loading") {
                                if (filteredBrowseItems.isNotEmpty()) {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CrunchyOrange)
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 48.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = CrunchyOrange)
                                    }
                                }
                            }
                        } else if (browseLoadFailed) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "browse_error") {
                                SearchMessage("Couldn't load this category.", "Try again", onRetryBrowse)
                            }
                        } else if (filteredBrowseItems.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "browse_empty") {
                                if (dubbedOnly && browseItems.isNotEmpty()) {
                                    SearchMessage(
                                        "No dubbed titles matched in this view.",
                                        "Show all titles"
                                    ) { dubbedOnly = false }
                                } else {
                                    SearchMessage("No titles found in this category.")
                                }
                            }
                        }

                        items(filteredBrowseItems, key = { "browse_${it.mediaId}" }) { media ->
                            val episodeAt = media.nextEpisodeAt
                            val episode = media.nextEpisode
                            val countdown = if (isSimulcast && episodeAt != null && episode != null && episodeAt > nowSec) {
                                val minutes = (episodeAt - nowSec + 59) / 60
                                val hours = minutes / 60
                                val relativeTime = when {
                                    hours >= 24 -> "${hours / 24}d ${hours % 24}h"
                                    hours >= 1 -> "${hours}h"
                                    else -> "${minutes}m"
                                }
                                "Ep $episode in $relativeTime"
                            } else null

                            Box {
                                PosterCard(
                                    media = media,
                                    onClick = {
                                        dismissKeyboard()
                                        onAnimeClick(media)
                                    }
                                )
                                if (countdown != null) {
                                    Surface(
                                        color = CrunchyTeal,
                                        shape = MaruPillShape,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                    ) {
                                        Text(
                                            text = countdown,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.Black,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ==================== ACTIVE SEARCH RESULTS ====================
                    when {
                        term.isEmpty() -> SearchMessage("Type an AniList username above to search profiles.")
                        isSearching -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CrunchyOrange)
                        }
                        searchFailed -> SearchMessage("Couldn't load search results.", "Try again") { submitCount++ }
                        searchUsers && userResults.isEmpty() -> SearchMessage("No AniList users found for “$term”.")
                        searchUsers -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(userResults, key = { it.id }) { user ->
                                UserCardRow(user = user, onClick = {
                                    dismissKeyboard()
                                    onUserClick(user.name)
                                })
                            }
                        }
                        animeResults.isEmpty() -> SearchMessage("No anime found for “$term”.")
                        filteredAnimeResults.isEmpty() -> SearchMessage(
                            "None of these results have an English dub.",
                            "Show all results"
                        ) { dubbedOnly = false }
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(136.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(filteredAnimeResults, key = { it.mediaId }) { media ->
                                PosterCard(media = media, onClick = {
                                    dismissKeyboard()
                                    onAnimeClick(media)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrunchyChip(
    label: String,
    selected: Boolean,
    accent: Color = CrunchyOrange,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = if (selected) accent else CrunchyCardBg,
        border = BorderStroke(1.dp, if (selected) accent else CrunchyBorder)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = if (selected) Color.Black else Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun SearchMessage(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(message, color = MaruTextMuted, textAlign = TextAlign.Center)
            if (action != null) {
                TextButton(onClick = onAction) {
                    Text(action, color = CrunchyOrange, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
