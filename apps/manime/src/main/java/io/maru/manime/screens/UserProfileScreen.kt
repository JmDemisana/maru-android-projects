package io.maru.manime.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UserProfileScreen(
    username: String,
    anilistToken: String,
    onBack: () -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit,
    onUserClick: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var profile by remember(username) { mutableStateOf<AniListProfile?>(null) }
    var isLoading by remember(username) { mutableStateOf(true) }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Watchlist, 1: Following, 2: Followers
    var followingList by remember { mutableStateOf<List<AniListProfile>>(emptyList()) }
    var isFollowingLoading by remember { mutableStateOf(false) }
    var followersList by remember { mutableStateOf<List<AniListProfile>>(emptyList()) }
    var isFollowersLoading by remember { mutableStateOf(false) }

    LaunchedEffect(username) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                profile = AniListClient.getUserProfile(username, anilistToken.ifEmpty { null })
            } catch (_: Exception) {
                profile = null
            } finally {
                isLoading = false
            }
        }
    }

    fun loadFollowing(userId: Int) {
        isFollowingLoading = true
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    AniListClient.getUserFollowing(userId, 1, anilistToken.ifEmpty { null })
                }
                followingList = list
            } catch (_: Exception) {
                followingList = emptyList()
            } finally {
                isFollowingLoading = false
            }
        }
    }

    fun loadFollowers(userId: Int) {
        isFollowersLoading = true
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    AniListClient.getUserFollowers(userId, 1, anilistToken.ifEmpty { null })
                }
                followersList = list
            } catch (_: Exception) {
                followersList = emptyList()
            } finally {
                isFollowersLoading = false
            }
        }
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaruAccentPink)
        }
    } else if (profile == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("User not found or profile is private.", color = MaruTextMuted)
                Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = MaruAccentPink)) {
                    Text("Go Back")
                }
            }
        }
    } else {
        val u = profile!!
        val availableCategories = remember(u.userLists) {
            if (u.userLists.isEmpty()) listOf("Watching", "Completed", "Planning")
            else u.userLists.keys.toList()
        }
        var selectedCategory by remember(availableCategories) { mutableStateOf(availableCategories.firstOrNull() ?: "Watching") }
        val currentCategoryList = remember(u.userLists, selectedCategory) {
            u.userLists[selectedCategory] ?: emptyList()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Header with banner & avatar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
            ) {
                if (u.banner != null) {
                    AsyncImage(
                        model = u.banner,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0x77050507), Color(0xBB0E0A1A), Color(0xFF0E0A1A))
                            )
                        )
                )

                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(8.dp)
                        .background(Color(0x880E0A1A), CircleShape)
                        .border(BorderStroke(1.dp, MaruGlassBorderSoft), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaruTextStrong)
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (u.avatar != null) {
                        AsyncImage(
                            model = u.avatar,
                            contentDescription = u.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .border(BorderStroke(2.dp, MaruAccentPink), CircleShape)
                        )
                    } else {
                        Surface(shape = CircleShape, color = MaruGlassSubtleBg, modifier = Modifier.size(64.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = MaruAccentPink, modifier = Modifier.size(32.dp))
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = u.name,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaruTextStrong
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${u.animeCount} anime", fontSize = 11.5.sp, color = MaruTextMuted)
                            val daysWatched = (u.minutesWatched / 1440.0)
                            Text(String.format("%.1f days", daysWatched), fontSize = 11.5.sp, color = MaruAccentBlue)
                            if (u.meanScore > 0) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    Icon(Icons.Default.Star, contentDescription = null, tint = MaruAccentYellow, modifier = Modifier.size(12.dp))
    Text("${u.meanScore.toInt()}%", fontSize = 11.5.sp, color = MaruAccentYellow, fontWeight = FontWeight.Bold)
}
                            }
                        }
                    }
                }
            }

            // Sub Navigation Tab Bar: [Watchlist] [Following] [Followers]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("WATCHLIST", "FOLLOWING", "FOLLOWERS").forEachIndexed { idx, title ->
                    val isSelected = selectedTab == idx
                    Surface(
                        onClick = {
                            selectedTab = idx
                            if (idx == 1 && followingList.isEmpty()) loadFollowing(u.id)
                            if (idx == 2 && followersList.isEmpty()) loadFollowers(u.id)
                        },
                        shape = MaruPillShape,
                        color = if (isSelected) MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                        border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = title,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaruAccentPink else MaruTextMuted
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)

            when (selectedTab) {
                0 -> {
                    // Watchlist Tab with Sticky Categories!
                    if (availableCategories.isNotEmpty()) {
                        Surface(
                            color = Color(0xDD0E0A1A),
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
                                    Surface(
                                        onClick = { selectedCategory = category },
                                        shape = MaruPillShape,
                                        color = if (isSelected) MaruAccentPurple.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                                        border = BorderStroke(1.dp, if (isSelected) MaruAccentPurple else MaruGlassBorderSoft)
                                    ) {
                                        Text(
                                            text = category.uppercase(),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.5.sp
                                            ),
                                            color = if (isSelected) MaruAccentPurple else MaruTextMuted,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (currentCategoryList.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No anime in this category.", color = MaruTextMuted, fontSize = 13.sp)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
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

                1 -> {
                    // Following Tab
                    if (isFollowingLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaruAccentPink)
                        }
                    } else if (followingList.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Not following anyone.", color = MaruTextMuted, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(followingList, key = { "f_${it.id}" }) { userItem ->
                                UserCardRow(user = userItem, onClick = { onUserClick(userItem.name) })
                            }
                        }
                    }
                }

                2 -> {
                    // Followers Tab
                    if (isFollowersLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaruAccentPink)
                        }
                    } else if (followersList.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No followers found.", color = MaruTextMuted, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(followersList, key = { "fl_${it.id}" }) { userItem ->
                                UserCardRow(user = userItem, onClick = { onUserClick(userItem.name) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserCardRow(
    user: AniListProfile,
    onClick: () -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (user.avatar != null) {
                AsyncImage(
                    model = user.avatar,
                    contentDescription = user.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .border(BorderStroke(1.dp, MaruAccentPink), CircleShape)
                )
            } else {
                Surface(shape = CircleShape, color = MaruGlassSubtleBg, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, contentDescription = null, tint = MaruAccentPink)
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaruTextStrong
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${user.animeCount} anime", fontSize = 11.sp, color = MaruTextMuted)
                    if (user.meanScore > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    Icon(Icons.Default.Star, contentDescription = null, tint = MaruAccentYellow, modifier = Modifier.size(11.dp))
    Text("${user.meanScore.toInt()}%", fontSize = 11.sp, color = MaruAccentYellow, fontWeight = FontWeight.Bold)
}
                    }
                }
            }

            Icon(Icons.Default.ChevronRight, contentDescription = "View", tint = MaruAccentPink)
        }
    }
}

