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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.maru.manime.*
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
    activitiesList: List<AniListActivity>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onPostCreated: (AniListActivity) -> Unit = {},
    onAnimeClick: (AnimeMedia) -> Unit,
    onUserClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 0: Crunchyroll Home Feed (Default!), 1: AniList Social Feed
    var selectedSubTab by remember { mutableIntStateOf(0) }
    var heroIndex by remember { mutableIntStateOf(0) }

    var postText by remember { mutableStateOf("") }
    var isPosting by remember { mutableStateOf(false) }

    fun submitPost() {
        if (postText.isBlank()) return
        if (anilistToken.isBlank()) {
            Toast.makeText(context, "Sign in with AniList to post!", Toast.LENGTH_SHORT).show()
            return
        }
        val textToPost = postText.trim()
        isPosting = true
        scope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    AniListClient.postTextActivity(textToPost, anilistToken)
                }
                postText = ""
                onPostCreated(created)
                Toast.makeText(context, "Posted to AniList!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to post: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                isPosting = false
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Sub Tabs: [CRUNCHYROLL FEED] [ANILIST SOCIAL]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CrunchySurface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = { selectedSubTab = 0 },
                    shape = RoundedCornerShape(4.dp),
                    color = if (selectedSubTab == 0) CrunchyOrange else CrunchyCardBg,
                    border = BorderStroke(1.dp, if (selectedSubTab == 0) CrunchyOrange else CrunchyBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "FEATURED & SIMULCASTS",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (selectedSubTab == 0) Color.Black else MaruTextStrong
                        )
                    }
                }

                Surface(
                    onClick = { selectedSubTab = 1 },
                    shape = RoundedCornerShape(4.dp),
                    color = if (selectedSubTab == 1) CrunchyOrange else CrunchyCardBg,
                    border = BorderStroke(1.dp, if (selectedSubTab == 1) CrunchyOrange else CrunchyBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "ANILIST SOCIAL FEED",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (selectedSubTab == 1) Color.Black else MaruTextStrong
                        )
                    }
                }
            }

            HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

            if (selectedSubTab == 0) {
                // ================= CRUNCHYROLL HOME FEED =================
                val heroItems = remember(trendingList) { trendingList.take(5) }
                val activeHero = heroItems.getOrNull(heroIndex.coerceIn(0, (heroItems.size - 1).coerceAtLeast(0)))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Crunchyroll Top Hero Spotlight Banner
                    if (activeHero != null) {
                        item(span = { GridItemSpan(2) }, key = "crunchy_hero_banner") {
                            val cleanHeroDesc = remember(activeHero.description) {
                                activeHero.description?.let {
                                    try { Jsoup.parse(it).text() } catch (_: Exception) { it }
                                } ?: ""
                            }

                            Surface(
                                onClick = { onAnimeClick(activeHero) },
                                shape = RoundedCornerShape(6.dp),
                                color = CrunchyCardBg,
                                border = BorderStroke(1.dp, CrunchyBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(235.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = activeHero.bannerUrl ?: activeHero.coverUrl,
                                        contentDescription = activeHero.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(
                                                        Color(0x220B0C0F),
                                                        Color(0xAA0B0C0F),
                                                        Color(0xF50B0C0F)
                                                    )
                                                )
                                            )
                                    )

                                    // Top Left Crunchyroll Spotlight Tag
                                    Surface(
                                        color = CrunchyOrange,
                                        shape = RoundedCornerShape(2.dp),
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .padding(12.dp)
                                    ) {
                                        Text(
                                            text = "TOP PICK FOR YOU",
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.Black,
                                            letterSpacing = 0.8.sp,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                        )
                                    }

                                    // Hero Slide Selector Dots
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        heroItems.forEachIndexed { idx, _ ->
                                            Box(
                                                modifier = Modifier
                                                    .width(if (idx == heroIndex) 18.dp else 7.dp)
                                                    .height(7.dp)
                                                    .clip(RoundedCornerShape(3.dp))
                                                    .background(if (idx == heroIndex) CrunchyOrange else Color(0x66FFFFFF))
                                                    .clickable { heroIndex = idx }
                                            )
                                        }
                                    }

                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = activeHero.title,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (cleanHeroDesc.isNotBlank()) {
                                            Text(
                                                text = cleanHeroDesc,
                                                fontSize = 11.5.sp,
                                                color = Color(0xFFD0D4DF),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                lineHeight = 15.sp
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                onClick = { onAnimeClick(activeHero) },
                                                shape = RoundedCornerShape(4.dp),
                                                color = CrunchyOrange
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                                    Text(
                                                        text = "START WATCHING S1 E1",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = Color.Black
                                                    )
                                                }
                                            }
                                            Text(
                                                text = "Sub | Dub",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = CrunchyTeal
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (watchingList.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }, key = "watching_rail_header") {
                            GlassSectionHeader(
                                title = "CONTINUE WATCHING",
                                icon = Icons.Default.PlayCircle,
                                color = CrunchyOrange
                            )
                        }

                        item(span = { GridItemSpan(2) }, key = "watching_rail") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                watchingList.forEach { anime ->
                                    WatchingCard(media = anime, onClick = { onAnimeClick(anime) })
                                }
                            }
                        }
                    }

                    item(span = { GridItemSpan(2) }, key = "trending_header") {
                        GlassSectionHeader(
                            title = "MOST POPULAR & SIMULCASTS",
                            icon = Icons.Default.LocalFireDepartment,
                            color = CrunchyOrange
                        )
                    }

                    items(trendingList, key = { "trend_${it.mediaId}" }) { anime ->
                        PosterCard(media = anime, onClick = { onAnimeClick(anime) })
                    }
                }
            } else {
                // ================= ANILIST COMMUNITY FEED =================
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "create_post_box") {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (user?.avatarUrl != null) {
                                        AsyncImage(
                                            model = user.avatarUrl,
                                            contentDescription = user.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = CrunchyOrange, modifier = Modifier.size(32.dp))
                                    }

                                    OutlinedTextField(
                                        value = postText,
                                        onValueChange = { postText = it },
                                        placeholder = { Text("Share your anime thoughts...", color = MaruTextMuted.copy(alpha = 0.6f), fontSize = 12.5.sp) },
                                        singleLine = false,
                                        maxLines = 3,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = CrunchyOrange,
                                            unfocusedBorderColor = CrunchyBorder,
                                            focusedTextColor = MaruTextStrong,
                                            unfocusedTextColor = MaruTextStrong
                                        )
                                    )

                                    Button(
                                        onClick = { submitPost() },
                                        enabled = !isPosting && postText.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        if (isPosting) {
                                            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.Send, contentDescription = "Post", tint = Color.Black, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (activitiesList.isEmpty() && !isLoading) {
                        item(key = "empty_feed") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No recent community activity found.", color = MaruTextMuted, fontSize = 13.sp)
                            }
                        }
                    } else {
                        items(activitiesList, key = { "act_${it.id}" }) { act ->
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .border(BorderStroke(1.dp, CrunchyOrange), CircleShape)
                                                    .clickable { onUserClick(act.userName) }
                                            )
                                        } else {
                                            Surface(
                                                shape = CircleShape,
                                                color = CrunchyCardBg,
                                                modifier = Modifier
                                                    .size(34.dp)
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
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaruTextStrong,
                                                modifier = Modifier.clickable { onUserClick(act.userName) }
                                            )
                                            if (act.type == "ANIME_LIST" && act.status != null) {
                                                val prog = act.progress?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                                val statusStr = if (prog != null) "${act.status.lowercase()} ep $prog" else act.status.lowercase()
                                                Text(
                                                    text = statusStr,
                                                    fontSize = 11.sp,
                                                    color = CrunchyOrange
                                                )
                                            }
                                        }
                                    }

                                    if (!act.text.isNullOrBlank()) {
                                        Text(
                                            text = act.text,
                                            fontSize = 13.sp,
                                            color = MaruTextStrong,
                                            lineHeight = 18.sp
                                        )
                                    }

                                    if (act.mediaTitle != null) {
                                        Surface(
                                            onClick = {
                                                if (act.rawMedia != null) {
                                                    onAnimeClick(act.rawMedia)
                                                }
                                            },
                                            shape = RoundedCornerShape(4.dp),
                                            color = CrunchySurface,
                                            border = BorderStroke(1.dp, CrunchyBorder),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (act.mediaCover != null) {
                                                    AsyncImage(
                                                        model = act.mediaCover,
                                                        contentDescription = act.mediaTitle,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = act.mediaTitle,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaruTextStrong,
                                                        maxLines = 1
                                                    )
                                                }
                                                if (act.rawMedia != null) {
                                                    Icon(Icons.Default.ChevronRight, contentDescription = "View Anime", tint = CrunchyOrange, modifier = Modifier.size(16.dp))
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
}
