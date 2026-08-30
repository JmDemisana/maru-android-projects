package io.maru.manime.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
fun ProfileScreen(
    user: AniListUser?,
    allLists: Map<String, List<AnimeMedia>>,
    watchingCount: Int,
    completedCount: Int,
    planningCount: Int,
    anilistToken: String,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onAnimeClick: (AnimeMedia) -> Unit,
    onMediaUpdated: (AnimeMedia) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf("Watching") }

    val currentList = remember(allLists, selectedCategory) {
        allLists[selectedCategory]
            ?: allLists[selectedCategory.lowercase()]
            ?: allLists.entries.find { it.key.equals(selectedCategory, ignoreCase = true) }?.value
            ?: emptyList()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // User Header Card
        item(key = "user_header") {
            GlassCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (user?.avatarUrl != null) {
                        AsyncImage(
                            model = user.avatarUrl,
                            contentDescription = user.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = null,
                            tint = MaruAccentPink,
                            modifier = Modifier.size(56.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user?.name ?: "Guest User",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaruTextStrong
                        )
                        Text(
                            text = if (user != null) "AniList Connected" else "Sign in to sync your library",
                            fontSize = 12.sp,
                            color = if (user != null) MaruAccentGreen else MaruTextMuted
                        )
                    }

                    if (user != null) {
                        IconButton(onClick = onLogoutClick) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Log Out", tint = MaruDanger)
                        }
                    } else {
                        GlassButton(
                            onClick = onLoginClick,
                            modifier = Modifier.width(96.dp),
                            background = MaruAccentPink.copy(alpha = 0.2f),
                            borderColor = MaruAccentPink
                        ) {
                            Text("LOGIN", color = MaruTextStrong, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Stats Row
        if (user != null) {
            item(key = "stats") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatBox(title = "WATCHING", value = "$watchingCount", color = MaruAccentBlue, modifier = Modifier.weight(1f))
                    StatBox(title = "COMPLETED", value = "$completedCount", color = MaruAccentGreen, modifier = Modifier.weight(1f))
                    StatBox(title = "PLANNING", value = "$planningCount", color = MaruAccentPurple, modifier = Modifier.weight(1f))
                }
            }

            // Library Categories Scrollable Filter Bar
            item(key = "categories_bar") {
                val categories = listOf("Watching", "Planning", "Completed", "Paused", "Dropped", "Repeating")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    categories.forEach { cat ->
                        val isSelected = selectedCategory.equals(cat, ignoreCase = true)
                        val count = allLists[cat]?.size ?: allLists.entries.find { it.key.equals(cat, ignoreCase = true) }?.value?.size ?: 0
                        Surface(
                            onClick = { selectedCategory = cat },
                            shape = MaruPillShape,
                            color = if (isSelected) MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                            border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = cat.uppercase(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaruAccentPink else MaruTextMuted
                                )
                                if (count > 0) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isSelected) MaruAccentPink.copy(alpha = 0.4f) else Color(0x33FFFFFF)
                                    ) {
                                        Text(
                                            text = "$count",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaruTextStrong,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Library items header
            item(key = "library_header") {
                GlassSectionHeader(
                    title = "$selectedCategory (${currentList.size})",
                    icon = Icons.Default.CollectionsBookmark,
                    color = MaruAccentPink
                )
            }

            if (currentList.isEmpty()) {
                item(key = "empty_list") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No anime in $selectedCategory.", color = MaruTextMuted, fontSize = 13.sp)
                    }
                }
            } else {
                items(currentList, key = { "media_${it.mediaId}" }) { item ->
                    var itemMedia by remember(item) { mutableStateOf(item) }
                    var isSyncing by remember { mutableStateOf(false) }

                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAnimeClick(itemMedia) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = itemMedia.coverUrl,
                                contentDescription = itemMedia.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(52.dp)
                                    .height(74.dp)
                                    .clip(MaruInputShape)
                            )

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = itemMedia.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaruTextStrong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (itemMedia.score != null && itemMedia.score!! > 0) {
                                        Surface(
                                            color = Color(0x22FBBF24),
                                            shape = MaruPillShape,
                                            border = BorderStroke(1.dp, MaruAccentYellow.copy(alpha = 0.3f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Icon(Icons.Default.Star, contentDescription = null, tint = MaruAccentYellow, modifier = Modifier.size(10.dp))
                                                Text(text = "${itemMedia.score}", fontSize = 10.sp, color = MaruAccentYellow, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }

                                    Text(
                                        text = "Ep ${itemMedia.progress} / ${itemMedia.episodes ?: "?"}",
                                        fontSize = 11.5.sp,
                                        color = MaruTextMuted
                                    )
                                }

                                // Linear Progress bar
                                if (itemMedia.episodes != null && itemMedia.episodes!! > 0) {
                                    val progressFraction = (itemMedia.progress.toFloat() / itemMedia.episodes!!.toFloat()).coerceIn(0f, 1f)
                                    LinearProgressIndicator(
                                        progress = { progressFraction },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(CircleShape),
                                        color = MaruAccentPink,
                                        trackColor = MaruGlassSubtleBg,
                                    )
                                }
                            }

                            // +1 Episode Quick Button
                            Surface(
                                onClick = {
                                    if (anilistToken.isNotBlank() && !isSyncing) {
                                        val newProgress = itemMedia.progress + 1
                                        val resolvedStatus = if (itemMedia.episodes != null && newProgress >= itemMedia.episodes!!) "COMPLETED" else (itemMedia.listStatus ?: "CURRENT")
                                        scope.launch {
                                            isSyncing = true
                                            try {
                                                val updated = withContext(Dispatchers.IO) {
                                                    AniListClient.saveEntry(
                                                        mediaId = itemMedia.mediaId,
                                                        status = resolvedStatus,
                                                        progress = newProgress,
                                                        score = itemMedia.score,
                                                        notes = itemMedia.notes,
                                                        isPrivate = itemMedia.isPrivate,
                                                        token = anilistToken
                                                    )
                                                }
                                                itemMedia = updated
                                                onMediaUpdated(updated)
                                                Toast.makeText(context, "+1 Ep (${updated.progress})", Toast.LENGTH_SHORT).show()
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                            } finally {
                                                isSyncing = false
                                            }
                                        }
                                    }
                                },
                                shape = CircleShape,
                                color = MaruAccentPink.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, MaruAccentPink),
                                modifier = Modifier.size(34.dp),
                                enabled = !isSyncing
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("+1", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = MaruAccentPink)
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
fun StatBox(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    GlassCard(
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = color)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                ),
                color = MaruTextMuted
            )
        }
    }
}
