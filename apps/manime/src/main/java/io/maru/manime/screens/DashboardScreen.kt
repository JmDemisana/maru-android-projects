package io.maru.manime.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.maru.manime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    user: AniListUser?,
    anilistToken: String,
    watchingList: List<AnimeMedia>,
    trendingList: List<AnimeMedia>,
    simulcastList: List<AnimeMedia> = emptyList(),
    activitiesList: List<AniListActivity>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit,
    onUserClick: (String) -> Unit = {},
    loadFailed: Boolean = false,
    isActivityLoading: Boolean = false,
    activityLoadFailed: Boolean = false,
    onLoginClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val posterWidth = if (LocalConfiguration.current.screenWidthDp < 360) 128.dp else 152.dp

    // Social likes & replies local state map
    val likedStateMap = remember(anilistToken) { mutableStateMapOf<Int, Pair<Boolean, Int>>() }
    val pendingLikes = remember(anilistToken) { mutableStateMapOf<Int, Boolean>() }
    var expandedReplyActivityId by remember(anilistToken) { mutableStateOf<Int?>(null) }
    val repliesByActivity = remember(anilistToken) { mutableStateMapOf<Int, List<ActivityReply>>() }
    var isLoadingReplies by remember(expandedReplyActivityId) { mutableStateOf(expandedReplyActivityId != null) }
    var repliesLoadFailed by remember(expandedReplyActivityId) { mutableStateOf(false) }
    var replyReload by remember { mutableIntStateOf(0) }
    val draftsByActivity = remember(anilistToken) { mutableStateMapOf<Int, String>() }
    val pendingReplies = remember(anilistToken) { mutableStateMapOf<Int, Boolean>() }

    LaunchedEffect(expandedReplyActivityId, anilistToken, replyReload) {
        val id = expandedReplyActivityId ?: return@LaunchedEffect
        isLoadingReplies = true
        repliesLoadFailed = false
        try {
            val replies = withContext(Dispatchers.IO) {
                AniListClient.getActivityReplies(id, anilistToken.ifBlank { null })
            }
            repliesByActivity[id] = (replies + repliesByActivity[id].orEmpty()).distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            repliesLoadFailed = true
        }
        isLoadingReplies = false
    }

    val dubbedFavorites = remember(trendingList, simulcastList) {
        (trendingList + simulcastList).distinctBy { it.mediaId }.filter { it.hasEnglishDub }
    }

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .background(CrunchyBgBlack)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 12.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 1. Continue Watching Lane (if user has active shows)
            if (watchingList.isNotEmpty()) {
                item(key = "lane_continue_watching") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Continue Watching",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(watchingList, key = { "watch_${it.mediaId}" }) { anime ->
                                WatchingCard(
                                    media = anime,
                                    onClick = { onAnimeClick(anime) },
                                    modifier = Modifier.width(posterWidth)
                                )
                            }
                        }
                    }
                }
            }

            if (trendingList.isNotEmpty()) {
                item(key = "lane_trending") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Trending Now",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(trendingList, key = { "trend_${it.mediaId}" }) { anime ->
                                PosterCard(
                                    media = anime,
                                    onClick = { onAnimeClick(anime) },
                                    modifier = Modifier.width(posterWidth)
                                )
                            }
                        }
                    }
                }
            }

            if (loadFailed || (!isLoading && trendingList.isEmpty() && simulcastList.isEmpty())) {
                item(key = "home_status") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val message = if (loadFailed) "Couldn't load all anime on Home." else "No anime to show yet."
                        Text(message, color = MaruTextMuted)
                        TextButton(onClick = onRefresh, enabled = !isLoading) { Text("Try again") }
                    }
                }
            }

            // 3. Seasonal Simulcasts Lane (Matches Reference Screenshot 1: "October 2026 Simulcasts")
            if (simulcastList.isNotEmpty()) {
                item(key = "lane_simulcasts") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Upcoming Simulcasts",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(simulcastList, key = { "sim_${it.mediaId}" }) { anime ->
                                PosterCard(
                                    media = anime,
                                    onClick = { onAnimeClick(anime) },
                                    modifier = Modifier.width(posterWidth)
                                )
                            }
                        }
                    }
                }
            }

            // 4. English & Dual-Audio Dubbed Lane
            if (dubbedFavorites.isNotEmpty()) {
                item(key = "lane_dubbed") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Popular Dubs & Dual Audio",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(dubbedFavorites, key = { "dub_${it.mediaId}" }) { anime ->
                                PosterCard(
                                    media = anime,
                                    onClick = { onAnimeClick(anime) },
                                    modifier = Modifier.width(posterWidth)
                                )
                            }
                        }
                    }
                }
            }

            // 5. Friends & Watch Activity Feed Header (Automatic status updates from watching!)
            item(key = "social_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Friends & Watch Activity",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    if (user != null) {
                        Surface(
                            onClick = { onUserClick(user.name) },
                            shape = RoundedCornerShape(4.dp),
                            color = CrunchyCardBg,
                            border = BorderStroke(1.dp, CrunchyBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(Icons.Default.People, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(14.dp))
                                Text(
                                    text = "MY FRIENDS",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = CrunchyOrange
                                )
                            }
                        }
                    }
                }
            }

            if (activitiesList.isEmpty() || activityLoadFailed) {
                item(key = "activity_status") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        val message = when {
                            anilistToken.isBlank() -> "Connect AniList to see activity from people you follow."
                            isActivityLoading -> "Loading activity…"
                            activityLoadFailed -> "Couldn't load AniList activity."
                            else -> "No recent activity from people you follow."
                        }
                        Text(message, color = MaruTextMuted, style = MaterialTheme.typography.bodyMedium)
                        if (anilistToken.isBlank() && onLoginClick != null) {
                            TextButton(onClick = onLoginClick) { Text("Connect AniList") }
                        } else if (activityLoadFailed) {
                            TextButton(onClick = onRefresh, enabled = !isActivityLoading) { Text("Try again") }
                        }
                    }
                }
            }

            items(activitiesList, key = { "act_${it.id}" }) { act ->
                val likeInfo = likedStateMap[act.id] ?: Pair(act.isLiked, act.likeCount)
                val isLiked = likeInfo.first
                val currentLikes = likeInfo.second
                val isRepliesOpen = expandedReplyActivityId == act.id
                val loadedReplies = repliesByActivity[act.id].orEmpty()
                val replyDraftText = draftsByActivity[act.id].orEmpty()
                val isSendingReply = pendingReplies[act.id] == true

                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (act.userAvatar != null) {
                                AsyncImage(
                                    model = act.userAvatar,
                                    contentDescription = act.userName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .border(BorderStroke(1.dp, CrunchyOrange), CircleShape)
                                        .clickable { onUserClick(act.userName) }
                                )
                            } else {
                                Surface(
                                    shape = CircleShape,
                                    color = CrunchyCardBg,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clickable { onUserClick(act.userName) }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Person, contentDescription = null, tint = MaruTextMuted)
                                    }
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = act.userName,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    modifier = Modifier.clickable { onUserClick(act.userName) }
                                )
                                if (act.type == "ANIME_LIST" && act.status != null) {
                                    val prog = act.progress?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                    val statusStr = if (prog != null) "${act.status} $prog" else act.status
                                    Text(
                                        text = statusStr.replaceFirstChar { it.uppercase() },
                                        fontSize = 11.5.sp,
                                        color = CrunchyOrange
                                    )
                                }
                            }
                        }

                        if (!act.text.isNullOrBlank()) {
                            Text(
                                text = act.text,
                                fontSize = 13.5.sp,
                                color = Color.White,
                                lineHeight = 19.sp
                            )
                        }

                        if (act.mediaTitle != null) {
                            Surface(
                                onClick = { act.rawMedia?.let(onAnimeClick) },
                                enabled = act.rawMedia != null,
                                shape = RoundedCornerShape(4.dp),
                                color = CrunchySurface,
                                border = BorderStroke(1.dp, CrunchyBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (act.mediaCover != null) {
                                        AsyncImage(
                                            model = act.mediaCover,
                                            contentDescription = act.mediaTitle,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .width(42.dp)
                                                .height(60.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = act.mediaTitle,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (act.rawMedia != null) {
                                            Text(
                                                text = if (act.rawMedia.hasEnglishDub) "Dub | Sub • Tap to Watch" else "Subtitled • Tap to Watch",
                                                fontSize = 11.sp,
                                                color = CrunchyTeal
                                            )
                                        }
                                    }
                                    if (act.rawMedia != null) {
                                        Icon(Icons.Default.PlayCircleOutline, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(22.dp))
                                    }
                                }
                            }
                        }

                        // Social Action Bar: Like + Comments Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable(enabled = pendingLikes[act.id] != true) {
                                        if (anilistToken.isBlank()) {
                                            Toast.makeText(context, "Sign in with AniList to like posts!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            val nextLiked = !isLiked
                                            val nextCount = (currentLikes + if (nextLiked) 1 else -1).coerceAtLeast(0)
                                            if (pendingLikes[act.id] == true) return@clickable
                                            pendingLikes[act.id] = true
                                            likedStateMap[act.id] = Pair(nextLiked, nextCount)
                                            scope.launch {
                                                try {
                                                    val res = AniListClient.toggleActivityLike(act.id, anilistToken)
                                                    likedStateMap[act.id] = res
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (_: Exception) {
                                                    likedStateMap[act.id] = likeInfo
                                                    Toast.makeText(context, "Couldn't update the like.", Toast.LENGTH_SHORT).show()
                                                } finally {
                                                    pendingLikes.remove(act.id)
                                                }
                                            }
                                        }
                                    }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (isLiked) "Remove like" else "Like",
                                    tint = if (isLiked) CrunchyOrange else MaruTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "$currentLikes",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isLiked) CrunchyOrange else MaruTextMuted
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        expandedReplyActivityId = if (isRepliesOpen) null else act.id
                                    }
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChatBubbleOutline,
                                    contentDescription = if (isRepliesOpen) "Hide replies" else "Show replies",
                                    tint = if (isRepliesOpen) CrunchyTeal else MaruTextMuted,
                                    modifier = Modifier.size(16.dp)
                                )
                                val replyTotal = loadedReplies.size.takeIf { it > act.replyCount } ?: act.replyCount
                                Text(
                                    text = "$replyTotal Replies",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRepliesOpen) CrunchyTeal else MaruTextMuted
                                )
                            }
                        }

                        // Expandable Replies Drawer
                        AnimatedVisibility(
                            visible = isRepliesOpen,
                            enter = fadeIn(tween(120)),
                            exit = ExitTransition.None
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(CrunchySurface, RoundedCornerShape(4.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isLoadingReplies && loadedReplies.isEmpty()) {
                                    Text("Loading replies…", fontSize = 11.5.sp, color = MaruTextMuted)
                                } else if (loadedReplies.isEmpty() && !repliesLoadFailed) {
                                    Text("No replies yet.", fontSize = 11.5.sp, color = MaruTextMuted)
                                } else {
                                    loadedReplies.forEach { rep ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.Top
                                        ) {
                                            if (rep.userAvatar != null) {
                                                AsyncImage(
                                                    model = rep.userAvatar,
                                                    contentDescription = rep.userName,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                        .clickable { onUserClick(rep.userName) }
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = rep.userName,
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = CrunchyOrange,
                                                    modifier = Modifier.clickable { onUserClick(rep.userName) }
                                                )
                                                Text(
                                                    text = rep.text,
                                                    fontSize = 12.sp,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                                if (repliesLoadFailed) {
                                    Text("Couldn't load replies.", color = MaruTextMuted, fontSize = 12.sp)
                                    TextButton(onClick = { replyReload++ }) { Text("Try again") }
                                }

                                // Reply Input
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = replyDraftText,
                                        onValueChange = { draftsByActivity[act.id] = it },
                                        placeholder = { Text("Write a reply...", fontSize = 11.5.sp, color = MaruTextMuted) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CrunchyOrange,
                                            unfocusedBorderColor = CrunchyBorder,
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        )
                                    )
                                    Button(
                                        onClick = {
                                            if (anilistToken.isBlank()) {
                                                Toast.makeText(context, "Sign in with AniList to reply!", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            val draft = draftsByActivity[act.id].orEmpty()
                                            if (draft.isBlank() || pendingReplies[act.id] == true) return@Button
                                            pendingReplies[act.id] = true
                                            scope.launch {
                                                try {
                                                    val created = withContext(Dispatchers.IO) {
                                                        AniListClient.postActivityReply(act.id, draft.trim(), anilistToken)
                                                    }
                                                    val existing = repliesByActivity[act.id].orEmpty()
                                                    repliesByActivity[act.id] = (existing + created).distinctBy { it.id }
                                                    if (draftsByActivity[act.id] == draft) draftsByActivity.remove(act.id)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Couldn't send the reply.", Toast.LENGTH_SHORT).show()
                                                } finally {
                                                    pendingReplies.remove(act.id)
                                                }
                                            }
                                        },
                                        enabled = !isSendingReply && replyDraftText.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange),
                                        shape = RoundedCornerShape(4.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text("REPLY", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
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
