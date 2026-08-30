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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.maru.manime.*
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
    activitiesList: List<AniListActivity>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onPostCreated: (AniListActivity) -> Unit = {},
    onAnimeClick: (AnimeMedia) -> Unit,
    onUserClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 0: AniList Community Feed (Swapped to first!), 1: Discovery
    var selectedSubTab by remember { mutableIntStateOf(0) }

    // Post creation state
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
            // Sub Tabs: [ANILIST COMMUNITY] [DISCOVERY]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = { selectedSubTab = 0 },
                    shape = MaruPillShape,
                    color = if (selectedSubTab == 0) MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                    border = BorderStroke(1.dp, if (selectedSubTab == 0) MaruAccentPink else MaruGlassBorderSoft),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "ANILIST COMMUNITY",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedSubTab == 0) MaruAccentPink else MaruTextMuted
                        )
                    }
                }

                Surface(
                    onClick = { selectedSubTab = 1 },
                    shape = MaruPillShape,
                    color = if (selectedSubTab == 1) MaruAccentBlue.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                    border = BorderStroke(1.dp, if (selectedSubTab == 1) MaruAccentBlue else MaruGlassBorderSoft),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "DISCOVERY",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedSubTab == 1) MaruAccentBlue else MaruTextMuted
                        )
                    }
                }
            }

            HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)

            if (selectedSubTab == 0) {
                // ================= ANILIST COMMUNITY FEED =================
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Create Post Box
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
                                        Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaruAccentPink, modifier = Modifier.size(32.dp))
                                    }

                                    OutlinedTextField(
                                        value = postText,
                                        onValueChange = { postText = it },
                                        placeholder = { Text("Share your anime thoughts...", color = MaruTextMuted.copy(alpha = 0.6f), fontSize = 12.5.sp) },
                                        singleLine = false,
                                        maxLines = 3,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = MaruAccentPink,
                                            unfocusedBorderColor = MaruGlassBorderSoft,
                                            focusedTextColor = MaruTextStrong,
                                            unfocusedTextColor = MaruTextStrong
                                        )
                                    )

                                    Button(
                                        onClick = { submitPost() },
                                        enabled = !isPosting && postText.isNotBlank(),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaruAccentPink),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        if (isPosting) {
                                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.Send, contentDescription = "Post", modifier = Modifier.size(16.dp))
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
                                                    .border(BorderStroke(1.dp, MaruAccentPink), CircleShape)
                                                    .clickable { onUserClick(act.userName) }
                                            )
                                        } else {
                                            Surface(
                                                shape = CircleShape,
                                                color = MaruGlassSubtleBg,
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
                                                    color = MaruAccentPink
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
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0x33000000),
                                            border = BorderStroke(1.dp, MaruGlassBorderSoft),
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
                                                    Icon(Icons.Default.ChevronRight, contentDescription = "View Anime", tint = MaruAccentPink, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ================= DISCOVERY FEED =================
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (watchingList.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }, key = "watching_rail_header") {
                            GlassSectionHeader(
                                title = "CONTINUE WATCHING",
                                icon = Icons.Default.PlayCircle,
                                color = MaruAccentPink
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
                            title = "TRENDING & POPULAR ANIME",
                            icon = Icons.Default.TrendingUp,
                            color = MaruAccentBlue
                        )
                    }

                    items(trendingList, key = { "trend_${it.mediaId}" }) { anime ->
                        PosterCard(media = anime, onClick = { onAnimeClick(anime) })
                    }
                }
            }
        }
    }
}
