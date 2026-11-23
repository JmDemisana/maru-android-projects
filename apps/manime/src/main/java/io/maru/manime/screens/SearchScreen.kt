package io.maru.manime.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.maru.manime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    anilistToken: String,
    onAnimeClick: (AnimeMedia) -> Unit,
    onUserClick: (String) -> Unit = {}
) {
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
            if (submitCount == 0) delay(300)
            isDebouncing = false
            if (searchUsers) {
                userResults = withContext(Dispatchers.IO) {
                    AniListClient.searchUsers(term).distinctBy { it.id }
                }
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
        if (!dubbedOnly) animeResults else animeResults.filter { it.hasEnglishDub }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 900.dp)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 14.dp)
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(if (searchUsers) "AniList username" else "Anime title") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = CrunchyOrange) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CrunchySurface,
                    unfocusedContainerColor = CrunchySurface,
                    focusedIndicatorColor = CrunchyOrange,
                    unfocusedIndicatorColor = CrunchyBorder,
                    focusedTextColor = MaruTextStrong,
                    unfocusedTextColor = MaruTextStrong
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    dismissKeyboard()
                    if (term.isNotBlank() && (isDebouncing || searchFailed)) submitCount++
                }),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                FilterChip(selected = !searchUsers, onClick = { searchUsers = false }, label = { Text("Anime") })
                FilterChip(selected = searchUsers, onClick = { searchUsers = true }, label = { Text("Users") })
                if (!searchUsers) {
                    FilterChip(
                        selected = dubbedOnly,
                        onClick = { dubbedOnly = !dubbedOnly },
                        label = { Text("Dubbed only") }
                    )
                }
            }

            when {
                term.isEmpty() -> SearchMessage(
                    if (searchUsers) "Enter an AniList username." else "Enter an anime title."
                )
                isSearching -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CrunchyOrange)
                }
                searchFailed -> SearchMessage("Couldn't load search results.", "Try again") { submitCount++ }
                searchUsers && userResults.isEmpty() -> SearchMessage("No AniList users found for “$term”.")
                searchUsers -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
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
                    "None of these results are marked as dubbed.",
                    "Show all results"
                ) { dubbedOnly = false }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
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

@Composable
private fun SearchMessage(message: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(message, color = MaruTextMuted, textAlign = TextAlign.Center)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}
