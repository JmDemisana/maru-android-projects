package io.maru.manime.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import io.maru.manime.*
import io.maru.manime.tv.nanamiFocusHighlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

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
    onActivityPosted: (AniListActivity) -> Unit = {},
    loadFailed: Boolean = false,
    isActivityLoading: Boolean = false,
    activityLoadFailed: Boolean = false,
    onLoginClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val posterWidth = if (LocalConfiguration.current.screenWidthDp < 360) 132.dp else 152.dp
    val knownDubIds by KnownDubRegistry.dubbedIds.collectAsState()

    // Background stream dub check for visible Home items
    LaunchedEffect(watchingList, trendingList, simulcastList) {
        val combined = (watchingList + trendingList + simulcastList).distinctBy { it.mediaId }
        if (combined.isNotEmpty()) {
            KnownDubRegistry.verifyCandidatesInBackground(context, combined, maxProbes = 12)
        }
    }

    val likedStateMap = remember(anilistToken) { mutableStateMapOf<Int, Pair<Boolean, Int>>() }
    val pendingLikes = remember(anilistToken) { mutableStateMapOf<Int, Boolean>() }
    var expandedReplyActivityId by remember(anilistToken) { mutableStateOf<Int?>(null) }
    val repliesByActivity = remember(anilistToken) { mutableStateMapOf<Int, List<ActivityReply>>() }
    var isLoadingReplies by remember(expandedReplyActivityId) { mutableStateOf(expandedReplyActivityId != null) }
    var repliesLoadFailed by remember(expandedReplyActivityId) { mutableStateOf(false) }
    var replyReload by remember { mutableIntStateOf(0) }
    val draftsByActivity = remember(anilistToken) { mutableStateMapOf<Int, String>() }
    val pendingReplies = remember(anilistToken) { mutableStateMapOf<Int, Boolean>() }

    var statusPostDraft by remember(anilistToken) { mutableStateOf("") }
    var isPostingStatus by remember { mutableStateOf(false) }

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

    val spotlightItems = remember(trendingList, simulcastList) {
        (trendingList + simulcastList)
            .distinctBy { it.mediaId }
            .filter { !it.bannerUrl.isNullOrBlank() || !it.coverUrl.isNullOrBlank() }
            .take(5)
    }

    val dubbedFavorites = remember(trendingList, simulcastList, knownDubIds) {
        (trendingList + simulcastList)
            .distinctBy { it.mediaId }
            .filter { it.hasEnglishDub || it.mediaId in knownDubIds }
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
            contentPadding = PaddingValues(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)
        ) {
            // 0. Crunchyroll Featured Hero Spotlight Carousel
            if (spotlightItems.isNotEmpty()) {
                item(key = "hero_spotlight") {
                    val pagerState = rememberPagerState(pageCount = { spotlightItems.size })
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 290.dp)
                        ) { page ->
                            val hero = spotlightItems[page]
                            val heroIsDubbed = hero.hasEnglishDub || hero.mediaId in knownDubIds
                            val cleanHeroDesc = remember(hero.description) {
                                val raw = hero.description
                                if (raw.isNullOrBlank()) ""
                                else runCatching { Jsoup.parse(raw).text() }.getOrDefault(raw)
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 290.dp)
                                    .clickable { onAnimeClick(hero) }
                            ) {
                                CrossfadeImage(
                                    model = hero.bannerUrl ?: hero.coverUrl,
                                    contentDescription = hero.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.matchParentSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color(0x33000000),
                                                    Color(0xAA000000),
                                                    CrunchyBgBlack
                                                )
                                            )
                                        )
                                )

                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = hero.title,
                                        fontSize = 21.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White,
                                        lineHeight = 25.sp
                                    )

                                    Text(
                                        text = buildString {
                                            append(if (heroIsDubbed) "Dub | Sub" else "Subtitled")
                                            if (hero.averageScore != null && hero.averageScore > 0) {
                                                append("  •  ★ ${hero.averageScore}%")
                                            }
                                        },
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (heroIsDubbed) CrunchyTeal else MaruTextMuted
                                    )

                                    if (cleanHeroDesc.isNotBlank()) {
                                        Text(
                                            text = cleanHeroDesc,
                                            fontSize = 12.5.sp,
                                            color = Color(0xFFD0D3DC),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            lineHeight = 17.sp
                                        )
                                    }

                                    Button(
                                        onClick = { onAnimeClick(hero) },
                                        modifier = Modifier.nanamiFocusHighlight(Color.White, cornerRadius = 4.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = CrunchyOrange,
                                            contentColor = Color.Black
                                        ),
                                        shape = RoundedCornerShape(4.dp),
                                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("WATCH NOW", fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
                                    }
                                }
                            }
                        }

                        if (spotlightItems.size > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(spotlightItems.size) { idx ->
                                    val active = pagerState.currentPage == idx
                                    val dotWidth by animateDpAsState(
                                        targetValue = if (active) 20.dp else 6.dp,
                                        animationSpec = tween(200),
                                        label = "pager_dot"
                                    )
                                    val dotColor by animateColorAsState(
                                        targetValue = if (active) CrunchyOrange else CrunchyBorder,
                                        animationSpec = tween(200),
                                        label = "pager_dot_color"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 3.dp)
                                            .width(dotWidth)
                                            .height(6.dp)
                                            .background(dotColor, CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1. Continue Watching Lane (Works for BOTH AniList & Local Guest Mode!)
            if (watchingList.isNotEmpty()) {
                item(key = "lane_continue_watching") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Continue Watching",
                            fontSize = 19.sp,
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

            // 2. Trending Now Lane
            if (trendingList.isNotEmpty()) {
                item(key = "lane_trending") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Trending Now",
                            fontSize = 19.sp,
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        val message = if (loadFailed) "Couldn't load Home right now." else "No anime to show yet."
                        Text(message, color = MaruTextMuted)
                        TextButton(onClick = onRefresh, enabled = !isLoading) {
                            Text("Try again", color = CrunchyOrange, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 3. Airing Simulcasts Lane
            if (simulcastList.isNotEmpty()) {
                item(key = "lane_simulcasts") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Airing Simulcasts",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(simulcastList, key = { "sim_${it.mediaId}" }) { anime ->
                                val aired = anime.latestAiredEpisode
                                val isDub = anime.hasEnglishDub || anime.mediaId in knownDubIds
                                val sub = buildString {
                                    append(if (isDub) "Dub | Sub" else "Subtitled")
                                    if (aired != null && aired > 0) append(" • $aired Out")
                                }
                                PosterCard(
                                    media = anime,
                                    subtitleOverride = sub,
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
                            fontSize = 19.sp,
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

            // 5. Friends & Watch Activity (Shown cleanly only when signed into AniList!)
            if (anilistToken.isNotBlank()) {
                item(key = "social_header") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Friends & Watch Activity",
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            if (user != null) {
                                Surface(
                                    onClick = { onUserClick(user.name) },
                                    shape = RoundedCornerShape(4.dp),
                                    color = CrunchyCardBg,
                                    border = BorderStroke(1.dp, CrunchyBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(Icons.Default.People, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(14.dp))
                                        Text(
                                            text = "MY PROFILE",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = CrunchyOrange
                                        )
                                    }
                                }
                            }
                        }

                        // Quick Status Post Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = statusPostDraft,
                                onValueChange = { statusPostDraft = it },
                                placeholder = { Text("Share an anime update…", fontSize = 12.sp, color = MaruTextMuted) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(6.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = CrunchySurface,
                                    unfocusedContainerColor = CrunchySurface,
                                    focusedBorderColor = CrunchyOrange,
                                    unfocusedBorderColor = CrunchyBorder,
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                )
                            )
                            Button(
                                onClick = {
                                    val text = statusPostDraft.trim()
                                    if (text.isEmpty() || isPostingStatus) return@Button
                                    isPostingStatus = true
                                    scope.launch {
                                        try {
                                            val posted = withContext(Dispatchers.IO) {
                                                AniListClient.postTextActivity(text, anilistToken)
                                            }
                                            statusPostDraft = ""
                                            onActivityPosted(posted)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (_: Exception) {
                                            Toast.makeText(context, "Couldn't post status right now.", Toast.LENGTH_SHORT).show()
                                        } finally {
                                            isPostingStatus = false
                                        }
                                    }
                                },
                                enabled = statusPostDraft.isNotBlank() && !isPostingStatus,
                                colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange, contentColor = Color.Black),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("POST", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                            }
                        }
                    }
                }

                if (activitiesList.isEmpty() || activityLoadFailed) {
                    item(key = "activity_status") {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            val message = when {
                                isActivityLoading -> "Loading activity…"
                                activityLoadFailed -> "Couldn't load AniList activity."
                                else -> "No recent activity from people you follow."
                            }
                            Text(message, color = MaruTextMuted, style = MaterialTheme.typography.bodyMedium)
                            if (activityLoadFailed) {
                                TextButton(onClick = onRefresh, enabled = !isActivityLoading) {
                                    Text("Try again", color = CrunchyOrange)
                                }
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
                                    CrossfadeImage(
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
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = act.userName,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onUserClick(act.userName) }
                                        )
                                        if (act.createdAt > 0L) {
                                            Text(
                                                text = formatRelativeTime(act.createdAt),
                                                fontSize = 11.sp,
                                                color = MaruTextMuted
                                            )
                                        }
                                    }
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
                                val rawMedia = act.rawMedia
                                val mediaIsDub = rawMedia != null && (rawMedia.hasEnglishDub || rawMedia.mediaId in knownDubIds)
                                Surface(
                                    onClick = { rawMedia?.let(onAnimeClick) },
                                    enabled = rawMedia != null,
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
                                            CrossfadeImage(
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
                                                color = Color.White
                                            )
                                            if (rawMedia != null) {
                                                Text(
                                                    text = if (mediaIsDub) "Dub | Sub • Tap to Watch" else "Subtitled • Tap to Watch",
                                                    fontSize = 11.sp,
                                                    color = if (mediaIsDub) CrunchyTeal else MaruTextMuted
                                                )
                                            }
                                        }
                                        if (rawMedia != null) {
                                            Icon(Icons.Default.PlayCircleOutline, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable(enabled = pendingLikes[act.id] != true) {
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
                                        .heightIn(min = 44.dp)
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
                                        .heightIn(min = 44.dp)
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

                            AnimatedVisibility(
                                visible = isRepliesOpen,
                                enter = expandVertically(tween(200)) + fadeIn(tween(160)),
                                exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
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
                                                    CrossfadeImage(
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
                                        TextButton(onClick = { replyReload++ }) {
                                            Text("Try again", color = CrunchyOrange)
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = replyDraftText,
                                            onValueChange = { draftsByActivity[act.id] = it },
                                            placeholder = { Text("Write a reply…", fontSize = 11.5.sp, color = MaruTextMuted) },
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
                                                    } catch (_: Exception) {
                                                        Toast.makeText(context, "Couldn't send the reply.", Toast.LENGTH_SHORT).show()
                                                    } finally {
                                                        pendingReplies.remove(act.id)
                                                    }
                                                }
                                            },
                                            enabled = !isSendingReply && replyDraftText.isNotBlank(),
                                            colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange, contentColor = Color.Black),
                                            shape = RoundedCornerShape(4.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Text("REPLY", fontWeight = FontWeight.ExtraBold, fontSize = 11.sp)
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

private fun formatRelativeTime(epochSec: Long): String {
    val nowSec = System.currentTimeMillis() / 1000L
    val diff = (nowSec - epochSec).coerceAtLeast(0L)
    return when {
        diff < 60 -> "Just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86_400 -> "${diff / 3600}h ago"
        diff < 604_800 -> "${diff / 86_400}d ago"
        else -> "${diff / 604_800}w ago"
    }
}
