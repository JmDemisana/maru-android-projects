package io.maru.manime.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UserProfileScreen(
    username: String,
    anilistToken: String,
    viewerUsername: String,
    isActive: Boolean = true,
    hideNsfwContent: Boolean = true,
    onBack: () -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit,
    onUserClick: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val token = anilistToken.ifBlank { null }

    var profile by remember(username, anilistToken) { mutableStateOf<AniListProfile?>(null) }
    var isLoading by remember(username, anilistToken) { mutableStateOf(true) }
    var profileLoadFailed by remember(username, anilistToken) { mutableStateOf(false) }
    var retryProfile by remember { mutableIntStateOf(0) }
    var isTogglingFollow by remember { mutableStateOf(false) }

    var selectedTab by rememberSaveable(username) { mutableIntStateOf(0) }
    var watchlist by remember(username, token) { mutableStateOf<Map<String, List<AnimeMedia>>?>(null) }
    var watchlistLoadFailed by remember(username, token) { mutableStateOf(false) }
    var retryWatchlist by remember { mutableIntStateOf(0) }
    var followersRevision by remember { mutableIntStateOf(0) }
    val showPeople = isActive && selectedTab != 0
    val following = rememberProfilePeople(profile?.id, AniListPeopleList.FOLLOWING, token, showPeople)
    val followers = rememberProfilePeople(profile?.id, AniListPeopleList.FOLLOWERS, token, showPeople, followersRevision)

    androidx.activity.compose.BackHandler(enabled = isActive) {
        onBack()
    }

    LaunchedEffect(username, anilistToken, retryProfile) {
        isLoading = true
        profileLoadFailed = false
        try {
            profile = withContext(Dispatchers.IO) {
                AniListClient.getUserProfile(username, token)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            profileLoadFailed = true
        }
        isLoading = false
    }

    val showWatchlist = isActive && selectedTab == 0
    val profileName = profile?.name
    LaunchedEffect(profileName, showWatchlist, token, retryWatchlist) {
        if (profileName == null || !showWatchlist || watchlist != null || watchlistLoadFailed) return@LaunchedEffect
        try {
            watchlist = withContext(Dispatchers.IO) {
                AniListClient.getUserList(profileName, token)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            watchlistLoadFailed = true
        }
    }

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CrunchyBgBlack)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.Center
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            CircularProgressIndicator(color = CrunchyOrange)
        }
    } else if (profile == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CrunchyBgBlack),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (profileLoadFailed) "Couldn't load this AniList profile." else "User not found or profile is private.",
                    color = MaruTextMuted
                )
                if (profileLoadFailed) {
                    TextButton(onClick = { retryProfile++ }) {
                        Text("Try again", color = CrunchyOrange)
                    }
                }
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange, contentColor = Color.Black)
                ) {
                    Text("Go Back", fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    } else {
        val u = profile!!
        val visibleLists = remember(watchlist, hideNsfwContent) {
            watchlist.orEmpty().mapValues { (_, entries) -> entries.filterNsfw(hideNsfwContent) }
        }
        val availableCategories = remember(visibleLists) { visibleLists.keys.toList() }
        var selectedCategory by rememberSaveable(username) { mutableStateOf<String?>(null) }
        val category = selectedCategory?.takeIf { it in availableCategories } ?: availableCategories.firstOrNull()
        val currentCategoryList = remember(visibleLists, category) {
            category?.let { visibleLists[it] }.orEmpty().distinctBy { it.mediaId }
        }

        LaunchedEffect(currentCategoryList, showWatchlist) {
            if (showWatchlist && currentCategoryList.isNotEmpty()) {
                KnownDubRegistry.verifyCandidatesInBackground(context, currentCategoryList, maxProbes = 8)
            }
        }

        val compact = LocalConfiguration.current.screenHeightDp < 430
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(CrunchyBgBlack)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (compact) 164.dp else 208.dp)
            ) {
                if (u.banner != null) {
                    CrossfadeImage(
                        model = u.banner,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                }

                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0x66000000), Color(0xBB000000), CrunchyBgBlack)
                            )
                        )
                )

                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp)
                        .background(Color(0x99000000), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (u.avatar != null) {
                        CrossfadeImage(
                            model = u.avatar,
                            contentDescription = u.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .border(BorderStroke(2.dp, CrunchyOrange), CircleShape)
                        )
                    } else {
                        Surface(shape = CircleShape, color = CrunchyCardBg, modifier = Modifier.size(64.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(32.dp))
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = u.name,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            if (u.isFollowing && u.isFollower) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = CrunchyTeal.copy(alpha = 0.2f),
                                    border = BorderStroke(1.dp, CrunchyTeal)
                                ) {
                                    Text(
                                        text = "Mutual",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CrunchyTeal,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${u.animeCount} anime", fontSize = 11.5.sp, color = MaruTextMuted)
                            val daysWatched = (u.minutesWatched / 1440.0)
                            Text(String.format("%.1f days", daysWatched), fontSize = 11.5.sp, color = CrunchyTeal)
                            if (u.meanScore > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = CrunchyGold, modifier = Modifier.size(12.dp))
                                    Text("${u.meanScore.toInt()}%", fontSize = 11.5.sp, color = CrunchyGold, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    if (token != null && !u.name.equals(viewerUsername, ignoreCase = true)) {
                        Surface(
                            onClick = {
                                if (isTogglingFollow) return@Surface
                                isTogglingFollow = true
                                scope.launch {
                                    try {
                                        val next = withContext(Dispatchers.IO) {
                                            AniListClient.toggleFollow(u.id, token)
                                        }
                                        profile = u.copy(isFollowing = next)
                                        followersRevision++
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Could not update follow status.", Toast.LENGTH_SHORT).show()
                                    } finally {
                                        isTogglingFollow = false
                                    }
                                }
                            },
                            enabled = !isTogglingFollow,
                            shape = RoundedCornerShape(4.dp),
                            color = if (u.isFollowing) CrunchyCardBg else CrunchyOrange,
                            border = BorderStroke(1.dp, CrunchyOrange)
                        ) {
                            Text(
                                text = if (u.isFollowing) "Following" else "Follow",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (u.isFollowing) CrunchyOrange else Color.Black,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Watchlist", "Following", "Followers").forEachIndexed { idx, title ->
                    val isSelected = selectedTab == idx
                    Surface(
                        shape = MaruPillShape,
                        color = if (isSelected) CrunchyOrange else CrunchyCardBg,
                        border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .selectable(selected = isSelected, role = Role.Tab) { selectedTab = idx }
                    ) {
                        Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = title,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isSelected) Color.Black else Color.White
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

            when (selectedTab) {
                0 -> {
                    if (availableCategories.isNotEmpty()) {
                        Surface(
                            color = CrunchyBgBlack,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                availableCategories.forEach { listName ->
                                    val isSelected = listName == category
                                    val count = visibleLists[listName]?.size ?: 0
                                    Surface(
                                        onClick = { selectedCategory = listName },
                                        shape = MaruPillShape,
                                        color = if (isSelected) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                        border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder)
                                    ) {
                                        Text(
                                            text = if (count > 0) "$listName ($count)" else listName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.5.sp,
                                            color = if (isSelected) CrunchyOrange else Color.White,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (watchlistLoadFailed) {
                        ProfileListError("Couldn't load this watchlist.") {
                            watchlistLoadFailed = false
                            retryWatchlist++
                        }
                    } else if (watchlist == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CrunchyOrange)
                        }
                    } else if (currentCategoryList.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No anime in this list.", color = MaruTextMuted, fontSize = 13.sp)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(140.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(currentCategoryList, key = { it.mediaId }) { mediaItem ->
                                PosterCard(
                                    media = mediaItem,
                                    onClick = { onAnimeClick(mediaItem) }
                                )
                            }
                        }
                    }
                }

                1, 2 -> {
                    val list = if (selectedTab == 1) following else followers
                    val people = list.users
                    val followingUsers = following.users
                    val followerUsers = followers.users
                    val mutualIds = remember(followingUsers, followerUsers) {
                        val followingIds = followingUsers.orEmpty().mapTo(mutableSetOf()) { it.id }
                        followerUsers.orEmpty().mapNotNull { it.id.takeIf(followingIds::contains) }.toSet()
                    }

                    if (people == null && list.loadFailed) {
                        ProfileListError("Couldn't load this list.", list::loadNextPage)
                    } else if (people == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CrunchyOrange)
                        }
                    } else if (people.isEmpty() && list.nextPage == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (selectedTab == 1) "Not following anyone." else "No followers found.",
                                color = MaruTextMuted,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(people, key = { it.id }) { userItem ->
                                UserCardRow(
                                    user = userItem,
                                    isMutual = userItem.id in mutualIds,
                                    onClick = { onUserClick(userItem.name) }
                                )
                            }
                            if (list.nextPage != null) {
                                item(key = "more_people") {
                                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        if (list.requestedPage != null) {
                                            CircularProgressIndicator(color = CrunchyOrange, modifier = Modifier.size(24.dp))
                                        } else {
                                            TextButton(onClick = list::loadNextPage) {
                                                Text(if (list.loadFailed) "Couldn't load more. Try again" else "Load more", color = CrunchyOrange)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileListError(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaruTextMuted)
            TextButton(onClick = onRetry) { Text("Try again", color = CrunchyOrange) }
        }
    }
}

private class ProfilePeopleState {
    var users by mutableStateOf<List<AniListProfile>?>(null)
    var nextPage by mutableStateOf<Int?>(1)
    var requestedPage by mutableStateOf<Int?>(1)
    var loadFailed by mutableStateOf(false)

    fun loadNextPage() {
        if (requestedPage != null) return
        loadFailed = false
        requestedPage = nextPage
    }
}

@Composable
private fun rememberProfilePeople(
    userId: Int?,
    list: AniListPeopleList,
    token: String?,
    enabled: Boolean,
    revision: Int = 0
): ProfilePeopleState {
    val state = remember(userId, list, token, revision) { ProfilePeopleState() }
    val requestedPage = state.requestedPage
    LaunchedEffect(state, enabled, requestedPage) {
        if (!enabled || userId == null || requestedPage == null) return@LaunchedEffect
        try {
            val result = withContext(Dispatchers.IO) {
                AniListClient.getUserPeople(userId, list, requestedPage, token)
            }
            state.users = (state.users.orEmpty() + result.users).distinctBy { it.id }
            state.nextPage = (requestedPage + 1).takeIf { result.hasNextPage }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            state.loadFailed = true
        }
        state.requestedPage = null
    }
    return state
}

@Composable
fun UserCardRow(
    user: AniListProfile,
    isMutual: Boolean = false,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .pressableClick(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (user.avatar != null) {
                CrossfadeImage(
                    model = user.avatar,
                    contentDescription = user.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(BorderStroke(1.dp, CrunchyOrange), CircleShape)
                )
            } else {
                Surface(shape = CircleShape, color = CrunchySurface, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = CrunchyOrange)
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = user.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaruTextStrong
                    )
                    if (isMutual) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = CrunchyTeal.copy(alpha = 0.18f),
                            border = BorderStroke(1.dp, CrunchyTeal.copy(alpha = 0.7f))
                        ) {
                            Text(
                                text = "Mutual",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CrunchyTeal,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${user.animeCount} anime", fontSize = 11.sp, color = MaruTextMuted)
                    if (user.meanScore > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = CrunchyGold, modifier = Modifier.size(11.dp))
                            Text("${user.meanScore.toInt()}%", fontSize = 11.sp, color = CrunchyGold, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Icon(Icons.Default.ChevronRight, contentDescription = "View", tint = CrunchyOrange)
        }
    }
}
