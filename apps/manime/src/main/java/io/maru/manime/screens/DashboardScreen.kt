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
    onAnimeClick: (AnimeMedia) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 0: AniList Community Feed (Swapped to first!), 1: Discovery
    var selectedSubTab by remember { mutableIntStateOf(0) }

    // Post creation state
    var postText by remember { mutableStateOf("") }
    var isPosting by remember { mutableStateOf(false) }

    fun submitPost() {
        if (anilistToken.isBlank()) {
            Toast.makeText(context, "Please log in to post to AniList!", Toast.LENGTH_SHORT).show()
            return
        }
        if (postText.isBlank()) return

        scope.launch {
            isPosting = true
            try {
                val newActivity = withContext(Dispatchers.IO) {
                    AniListClient.postTextActivity(postText.trim(), anilistToken)
                }
                onPostCreated(newActivity)
                postText = ""
                Toast.makeText(context, "Posted to AniList Community!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to post: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                isPosting = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sub-Tab Bar: [AniList Community] [Discovery]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("AniList Community", "Discovery").forEachIndexed { index, title ->
                val isSelected = selectedSubTab == index
                Surface(
                    onClick = { selectedSubTab = index },
                    shape = MaruPillShape,
                    color = if (isSelected) MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                    border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = title.uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaruAccentPink else MaruTextMuted
                        )
                    }
                }
            }
        }

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            if (selectedSubTab == 0) {
                // ================= TAB 0: ANILIST COMMUNITY FEED =================
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Compose Post Box
                    item(key = "compose_post") {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    if (user?.avatarUrl != null) {
                                        AsyncImage(
                                            model = user.avatarUrl,
                                            contentDescription = user.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .border(BorderStroke(1.dp, MaruAccentPink), CircleShape)
                                        )
                                    } else {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaruGlassSubtleBg,
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(Icons.Default.Person, contentDescription = null, tint = MaruTextMuted, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }

                                    OutlinedTextField(
                                        value = postText,
                                        onValueChange = { postText = it },
                                        placeholder = { Text("What's on your mind? Share a thought with AniList...", color = MaruTextMuted.copy(alpha = 0.6f), fontSize = 12.5.sp) },
                                        singleLine = false,
                                        maxLines = 4,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = MaruTextStrong,
                                            unfocusedTextColor = MaruTextStrong,
                                            focusedBorderColor = MaruAccentPink,
                                            unfocusedBorderColor = MaruGlassBorderSoft
                                        )
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = { submitPost() },
                                        enabled = postText.isNotBlank() && !isPosting,
                                        shape = MaruPillShape,
                                        colors = ButtonDefaults.buttonColors(containerColor = MaruAccentPink)
                                    ) {
                                        if (isPosting) {
                                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(Icons.Default.Send, contentDescription = "Post", modifier = Modifier.size(14.dp), tint = Color.White)
                                                Text("POST TO ANILIST", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (activitiesList.isEmpty()) {
                        item(key = "empty_activities") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isLoading) "Loading community posts..." else "No recent community activity found.",
                                    color = MaruTextMuted,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    } else {
                        items(activitiesList, key = { "act_${it.id}" }) { act ->
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
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
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = act.userName,
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaruTextStrong
                                            )
                                            if (act.status != null) {
                                                val statusDesc = if (act.progress != null) "${act.status.lowercase()} ep ${act.progress} of" else act.status.lowercase()
                                                Text(
                                                    text = statusDesc,
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
                                            color = MaruTextStrong.copy(alpha = 0.9f),
                                            lineHeight = 18.sp
                                        )
                                    }

                                    if (!act.mediaTitle.isNullOrBlank()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0x33000000))
                                                .padding(8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (act.mediaCover != null) {
                                                AsyncImage(
                                                    model = act.mediaCover,
                                                    contentDescription = act.mediaTitle,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                )
                                            }
                                            Text(
                                                text = act.mediaTitle,
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaruTextStrong,
                                                lineHeight = 16.sp
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Default.Favorite, contentDescription = null, tint = MaruAccentPink, modifier = Modifier.size(13.dp))
                                            Text(text = "${act.likeCount}", fontSize = 11.sp, color = MaruTextMuted)
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Default.ChatBubble, contentDescription = null, tint = MaruAccentBlue, modifier = Modifier.size(13.dp))
                                            Text(text = "${act.replyCount}", fontSize = 11.sp, color = MaruTextMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ================= TAB 1: DISCOVERY =================
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Continue Watching Rail
                    if (watchingList.isNotEmpty()) {
                        item(span = { GridItemSpan(2) }, key = "continue_watching") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                GlassSectionHeader(
                                    title = "CONTINUE WATCHING",
                                    icon = Icons.Default.AutoAwesome,
                                    color = MaruAccentPink
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    watchingList.forEach { media ->
                                        WatchingCard(media = media, onClick = { onAnimeClick(media) })
                                    }
                                }
                            }
                        }
                    }

                    // Trending Header
                    item(span = { GridItemSpan(2) }, key = "trending_header") {
                        GlassSectionHeader(
                            title = "TRENDING THIS SEASON",
                            icon = Icons.Default.AutoAwesome,
                            color = MaruAccentBlue
                        )
                    }

                    // 2-Column Poster Cards
                    items(trendingList, key = { "trending_${it.mediaId}" }) { media ->
                        PosterCard(
                            media = media,
                            onClick = { onAnimeClick(media) }
                        )
                    }
                }
            }
        }
    }
}

