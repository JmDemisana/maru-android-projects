package io.maru.manime.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import org.jsoup.Jsoup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailScreen(
    media: AnimeMedia,
    anilistToken: String,
    onBack: () -> Unit,
    onMediaUpdated: (AnimeMedia) -> Unit = {},
    onUserClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentMedia by remember(media) { mutableStateOf(media) }
    var isSynopsisExpanded by remember { mutableStateOf(false) }
    var isUpdatingList by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }

    var streamingEpisodes by remember { mutableStateOf<List<StreamingEpisode>>(emptyList()) }
    var isEpisodesLoading by remember { mutableStateOf(true) }

    var castList by remember { mutableStateOf<List<CastMember>>(emptyList()) }
    var isCastLoading by remember { mutableStateOf(true) }

    var similarAnime by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var isSimilarLoading by remember { mutableStateOf(true) }

    var friendsStatus by remember { mutableStateOf<List<FriendAnimeStatus>>(emptyList()) }
    var isFriendsLoading by remember { mutableStateOf(true) }

    var activeVoiceActor by remember { mutableStateOf<VoiceActor?>(null) }
    var activeStaffWorks by remember { mutableStateOf<StaffWorks?>(null) }
    var isStaffLoading by remember { mutableStateOf(false) }

    LaunchedEffect(currentMedia.mediaId) {
        isEpisodesLoading = true
        isCastLoading = true
        isSimilarLoading = true
        isFriendsLoading = true

        scope.launch {
            try {
                streamingEpisodes = withContext(Dispatchers.IO) {
                    AniListClient.getStreamingEpisodes(currentMedia.mediaId)
                }
            } catch (_: Exception) {
                streamingEpisodes = emptyList()
            } finally {
                isEpisodesLoading = false
            }
        }
        scope.launch {
            try {
                castList = withContext(Dispatchers.IO) {
                    AniListClient.getCast(currentMedia.mediaId)
                }
            } catch (_: Exception) {
                castList = emptyList()
            } finally {
                isCastLoading = false
            }
        }
        scope.launch {
            try {
                similarAnime = withContext(Dispatchers.IO) {
                    AniListClient.getSimilarAnime(currentMedia.mediaId)
                }
            } catch (_: Exception) {
                similarAnime = emptyList()
            } finally {
                isSimilarLoading = false
            }
        }
        scope.launch {
            try {
                friendsStatus = withContext(Dispatchers.IO) {
                    AniListClient.getFriendsMediaStatus(currentMedia.mediaId, anilistToken.ifEmpty { null })
                }
            } catch (_: Exception) {
                friendsStatus = emptyList()
            } finally {
                isFriendsLoading = false
            }
        }
    }

    // Dynamic tabs: if no streaming episodes, hide Episodes tab completely!
    val availableTabs = remember(streamingEpisodes) {
        if (streamingEpisodes.isNotEmpty()) {
            listOf("Overview", "Episodes (${streamingEpisodes.size})", "Cast")
        } else {
            listOf("Overview", "Cast")
        }
    }

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val currentTabName = availableTabs.getOrElse(selectedTabIndex) { "Overview" }

    // Accurate English Dub validation: Must have English VA in Cast OR explicit English Dub link
    val hasEnglishDub = remember(castList, currentMedia.externalLinks) {
        castList.any { it.englishVa != null } || currentMedia.externalLinks.any { it.isEnglishDub }
    }

    fun openVoiceActorWorks(va: VoiceActor) {
        activeVoiceActor = va
        activeStaffWorks = null
        isStaffLoading = true
        scope.launch {
            try {
                val works = withContext(Dispatchers.IO) {
                    AniListClient.getStaffWorks(va.id)
                }
                activeStaffWorks = works
            } catch (_: Exception) {
                activeStaffWorks = null
            } finally {
                isStaffLoading = false
            }
        }
    }

    val rawDescription = currentMedia.description
    val cleanDescription: String = remember(rawDescription) {
        if (rawDescription.isNullOrBlank()) {
            "No synopsis available."
        } else {
            try {
                Jsoup.parse(rawDescription).text()
            } catch (_: Exception) {
                rawDescription
            }
        }
    }

    fun updateProgress(newProgress: Int, newStatus: String? = null) {
        if (anilistToken.isBlank()) {
            Toast.makeText(context, "Sign in with AniList to track episodes!", Toast.LENGTH_SHORT).show()
            return
        }
        val safeProgress = newProgress.coerceIn(0, currentMedia.episodes ?: 9999)
        val resolvedStatus = newStatus ?: if (currentMedia.episodes != null && safeProgress >= currentMedia.episodes!!) {
            "COMPLETED"
        } else if (currentMedia.listStatus == null || currentMedia.listStatus == "PLANNING") {
            "CURRENT"
        } else {
            currentMedia.listStatus ?: "CURRENT"
        }

        val optimisticMedia = currentMedia.copy(
            progress = safeProgress,
            listStatus = resolvedStatus
        )
        currentMedia = optimisticMedia
        onMediaUpdated(optimisticMedia)

        scope.launch {
            isUpdatingList = true
            try {
                val updated = withContext(Dispatchers.IO) {
                    AniListClient.saveEntry(
                        mediaId = currentMedia.mediaId,
                        status = resolvedStatus,
                        progress = safeProgress,
                        score = currentMedia.score,
                        notes = currentMedia.notes,
                        isPrivate = currentMedia.isPrivate,
                        token = anilistToken
                    )
                }
                currentMedia = updated
                onMediaUpdated(updated)
                Toast.makeText(context, "Saved: Ep $safeProgress (${resolvedStatus.lowercase()})", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Sync error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                isUpdatingList = false
            }
        }
    }

    val isReleasing = currentMedia.status?.equals("RELEASING", ignoreCase = true) == true
    val latestAiredEp = currentMedia.latestAiredEpisode

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            Surface(
                color = Color(0xEE0E0A1A),
                border = BorderStroke(1.dp, MaruGlassBorderSoft),
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val statusText = currentMedia.listStatus ?: "ADD TO LIST"
                    Surface(
                        onClick = { showEditSheet = true },
                        shape = MaruPillShape,
                        color = when (currentMedia.listStatus) {
                            "CURRENT" -> Color(0x3360E2FF)
                            "COMPLETED" -> Color(0x334ADE80)
                            "PLANNING" -> Color(0x33B388FF)
                            "PAUSED" -> Color(0x33FBBF24)
                            "DROPPED" -> Color(0x33FF5252)
                            else -> MaruGlassSubtleBg
                        },
                        border = BorderStroke(1.dp, when (currentMedia.listStatus) {
                            "CURRENT" -> MaruAccentBlue
                            "COMPLETED" -> MaruAccentGreen
                            "PLANNING" -> MaruAccentPurple
                            "PAUSED" -> MaruAccentYellow
                            "DROPPED" -> MaruDanger
                            else -> MaruGlassBorderSoft
                        })
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Bookmark, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaruAccentPink)
                            Text(
                                text = statusText,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaruTextStrong
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            onClick = { updateProgress(currentMedia.progress - 1) },
                            shape = CircleShape,
                            color = MaruGlassSubtleBg,
                            border = BorderStroke(1.dp, MaruGlassBorderSoft),
                            modifier = Modifier.size(36.dp),
                            enabled = !isUpdatingList && currentMedia.progress > 0
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Remove, contentDescription = "Minus", tint = MaruTextStrong, modifier = Modifier.size(18.dp))
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val epCounterText = if (isReleasing && latestAiredEp != null) {
                                "EP ${currentMedia.progress} / $latestAiredEp rel"
                            } else {
                                "EP ${currentMedia.progress} / ${currentMedia.episodes ?: "?"}"
                            }
                            Text(
                                text = epCounterText,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaruTextStrong
                            )
                            if (currentMedia.score != null && currentMedia.score!! > 0) {
                                Text(
                                    text = "â˜… ${currentMedia.score}",
                                    fontSize = 10.5.sp,
                                    color = MaruAccentYellow,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Surface(
                            onClick = { updateProgress(currentMedia.progress + 1) },
                            shape = CircleShape,
                            color = MaruAccentPink.copy(alpha = 0.3f),
                            border = BorderStroke(1.dp, MaruAccentPink),
                            modifier = Modifier.size(36.dp),
                            enabled = !isUpdatingList
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, contentDescription = "Plus", tint = MaruTextStrong, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding()),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item(key = "header") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(290.dp)
                ) {
                    AsyncImage(
                        model = currentMedia.bannerUrl ?: currentMedia.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color(0x80050507),
                                        Color(0xBB0E0A1A),
                                        Color(0xFF050507)
                                    )
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaruTextStrong
                        )
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        AsyncImage(
                            model = currentMedia.coverUrl,
                            contentDescription = currentMedia.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(96.dp)
                                .height(140.dp)
                                .clip(MaruInputShape)
                                .border(BorderStroke(1.dp, MaruGlassBorderSoft), MaruInputShape)
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isReleasing && latestAiredEp != null) {
                                    Surface(
                                        color = Color(0x3360E2FF),
                                        shape = MaruPillShape,
                                        border = BorderStroke(1.dp, MaruAccentBlue.copy(alpha = 0.5f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Box(modifier = Modifier.size(6.dp).background(MaruAccentBlue, CircleShape))
                                            Text(
                                                text = "EP $latestAiredEp RELEASED",
                                                color = MaruAccentBlue,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }
                                }

                                if (hasEnglishDub) {
                                    Surface(
                                        color = Color(0x334ADE80),
                                        shape = MaruPillShape,
                                        border = BorderStroke(1.dp, MaruAccentGreen.copy(alpha = 0.4f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Default.Mic, contentDescription = null, tint = MaruAccentGreen, modifier = Modifier.size(12.dp))
                                            Text(
                                                text = "ENGLISH DUB",
                                                color = MaruAccentGreen,
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = currentMedia.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = MaruTextStrong,
                                lineHeight = 20.sp
                            )

                            if (!currentMedia.titleEnglish.isNullOrEmpty() && currentMedia.titleEnglish != currentMedia.title) {
                                Text(
                                    text = currentMedia.titleEnglish!!,
                                    fontSize = 11.5.sp,
                                    color = MaruTextMuted,
                                    lineHeight = 15.sp
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (currentMedia.averageScore != null && currentMedia.averageScore!! > 0) {
                                    Surface(
                                        color = Color(0x22FBBF24),
                                        shape = MaruPillShape,
                                        border = BorderStroke(1.dp, MaruAccentYellow.copy(alpha = 0.3f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(Icons.Default.Star, contentDescription = null, tint = MaruAccentYellow, modifier = Modifier.size(11.dp))
                                            Text(text = "${currentMedia.averageScore}%", fontSize = 10.sp, color = MaruAccentYellow, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Text(text = "${currentMedia.episodes ?: "?"} eps", fontSize = 11.5.sp, color = MaruTextMuted)
                                if (!currentMedia.format.isNullOrBlank() && !currentMedia.format.equals("null", ignoreCase = true)) {
                                    Text(text = "${currentMedia.format}", fontSize = 11.5.sp, color = MaruTextMuted)
                                }
                            }
                        }
                    }
                }
            }

            // Sub Navigation Tab Bar: [Overview] [Episodes] [Cast]
            item(key = "sub_tabs") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableTabs.forEachIndexed { index, title ->
                        val isSelected = selectedTabIndex == index
                        Surface(
                            onClick = { selectedTabIndex = index },
                            shape = MaruPillShape,
                            color = if (isSelected) MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
                            border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title.uppercase(),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaruAccentPink else MaruTextMuted
                                )
                            }
                        }
                    }
                }
            }

            if (currentTabName.startsWith("Overview", ignoreCase = true)) {
                // ================= OVERVIEW TAB =================
                item(key = "genres_row") {
                    if (currentMedia.genres.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            currentMedia.genres.forEach { genre ->
                                Surface(
                                    shape = MaruPillShape,
                                    color = MaruGlassSubtleBg,
                                    border = BorderStroke(1.dp, MaruGlassBorderSoft)
                                ) {
                                    Text(
                                        text = genre,
                                        fontSize = 11.sp,
                                        color = MaruTextStrong,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "synopsis") {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clickable { isSynopsisExpanded = !isSynopsisExpanded }
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "SYNOPSIS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                ),
                                color = MaruAccentPink
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = cleanDescription,
                                fontSize = 13.sp,
                                color = MaruTextStrong.copy(alpha = 0.9f),
                                lineHeight = 18.sp,
                                maxLines = if (isSynopsisExpanded) Int.MAX_VALUE else 3
                            )
                            Text(
                                text = if (isSynopsisExpanded) "Show less" else "Read more...",
                                fontSize = 11.sp,
                                color = MaruAccentPink,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }

                // Friends' & Community Status Section (Accurate status text without "episode ep" or "null")
                item(key = "friends_status_section") {
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            GlassSectionHeader(
                                title = "FRIENDS & COMMUNITY STATUS",
                                icon = Icons.Default.People,
                                color = MaruAccentPurple
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            if (isFriendsLoading) {
                                Text("Checking activity from your following feed...", fontSize = 12.sp, color = MaruTextMuted)
                            } else if (friendsStatus.isEmpty()) {
                                Text("No recent friends or community activity recorded for this anime.", fontSize = 12.sp, color = MaruTextMuted)
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    friendsStatus.take(6).forEach { friend ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onUserClick(friend.userName) },
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            if (friend.userAvatar != null) {
                                                AsyncImage(
                                                    model = friend.userAvatar,
                                                    contentDescription = friend.userName,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(CircleShape)
                                                        .border(BorderStroke(1.dp, MaruAccentPurple), CircleShape)
                                                )
                                            } else {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MaruGlassSubtleBg,
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Icon(Icons.Default.Person, contentDescription = null, tint = MaruTextMuted, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            }

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = friend.userName,
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaruTextStrong
                                                )

                                                val cleanProgress = friend.progress?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                                val rawStatus = friend.status.lowercase().trim()
                                                val statusText = when {
                                                    rawStatus.contains("watched episode") && cleanProgress != null -> "watched ep $cleanProgress"
                                                    rawStatus.contains("watched episode") -> "watched an episode"
                                                    rawStatus.contains("plans to watch") || rawStatus.contains("planning") -> "plans to watch"
                                                    rawStatus.contains("completed") -> "completed"
                                                    rawStatus.contains("dropped") -> "dropped"
                                                    rawStatus.contains("paused") -> "paused"
                                                    cleanProgress != null -> "$rawStatus ep $cleanProgress"
                                                    else -> rawStatus
                                                }

                                                Text(
                                                    text = statusText,
                                                    fontSize = 11.sp,
                                                    color = MaruAccentPink
                                                )
                                            }

                                            Icon(Icons.Default.ChevronRight, contentDescription = "View Profile", tint = MaruTextMuted.copy(alpha = 0.5f), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Similar Anime & Relations Section (Strict Anime Only!)
                item(key = "similar_anime_section") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            GlassSectionHeader(
                                title = "SIMILAR & RELATED ANIME",
                                icon = Icons.Default.AutoAwesome,
                                color = MaruAccentPink
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        if (isSimilarLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = MaruAccentPink)
                            }
                        } else if (similarAnime.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                            ) {
                                Text("No similar or related anime found.", fontSize = 12.sp, color = MaruTextMuted)
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                similarAnime.forEach { sim ->
                                    WatchingCard(
                                        media = sim,
                                        onClick = {
                                            currentMedia = sim
                                            onMediaUpdated(sim)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                if (currentMedia.externalLinks.isNotEmpty()) {
                    item(key = "ext_links") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "EXTERNAL LINKS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = MaruTextMuted,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                currentMedia.externalLinks.forEach { link ->
                                    Surface(
                                        onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                        },
                                        shape = MaruPillShape,
                                        color = MaruGlassSubtleBg,
                                        border = BorderStroke(1.dp, MaruGlassBorderSoft)
                                    ) {
                                        Text(
                                            text = link.site,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaruAccentBlue,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (currentTabName.startsWith("Episodes", ignoreCase = true)) {
                // ================= EPISODES TAB =================
                items(streamingEpisodes, key = { "stream_ep_${it.episodeNumber}" }) { streamEp ->
                    val episodeNum = streamEp.episodeNumber
                    val isWatched = episodeNum <= currentMedia.progress

                    val rawTitle = streamEp.title?.trim()
                    val cleanSubTitle = if (!rawTitle.isNullOrBlank()) {
                        rawTitle.replaceFirst(Regex("^Episode\\s*\\d+\\s*[-:\\.]\\s*", RegexOption.IGNORE_CASE), "").trim()
                    } else ""

                    val displayTitle = if (cleanSubTitle.isNotBlank()) cleanSubTitle else if (!rawTitle.isNullOrBlank()) rawTitle else "Episode $episodeNum"

                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { updateProgress(episodeNum) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(112.dp)
                                    .height(66.dp)
                                    .clip(MaruInputShape)
                                    .background(Color(0x33000000)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!streamEp.thumbnail.isNullOrBlank()) {
                                    AsyncImage(
                                        model = streamEp.thumbnail,
                                        contentDescription = displayTitle,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (isWatched) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color(0x66000000))
                                        )
                                    }
                                }

                                Surface(
                                    color = if (isWatched) Color(0xDD4ADE80) else Color(0xCC0E0A1A),
                                    shape = CircleShape,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (isWatched) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Watched",
                                                tint = Color(0xFF0E0A1A),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        } else {
                                            Text(
                                                text = "$episodeNum",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaruTextStrong
                                            )
                                        }
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "Episode $episodeNum",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.5.sp,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = if (isWatched) MaruAccentGreen else MaruAccentBlue
                                )

                                Text(
                                    text = displayTitle,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (isWatched) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isWatched) MaruTextStrong else MaruTextStrong.copy(alpha = 0.9f),
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            } else {
                // ================= CAST TAB =================
                if (isCastLoading) {
                    item(key = "cast_loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaruAccentPink)
                        }
                    }
                } else if (castList.isEmpty()) {
                    item(key = "cast_empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No cast information found.", color = MaruTextMuted, fontSize = 13.sp)
                        }
                    }
                } else {
                    items(castList, key = { "char_${it.characterId}" }) { cast ->
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 5.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (cast.characterImage != null) {
                                        AsyncImage(
                                            model = cast.characterImage,
                                            contentDescription = cast.characterName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(CircleShape)
                                                .border(BorderStroke(1.dp, MaruGlassBorderSoft), CircleShape)
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = cast.characterName,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaruTextStrong
                                        )
                                        if (!cast.characterNameNative.isNullOrBlank()) {
                                            Text(
                                                text = cast.characterNameNative,
                                                fontSize = 11.sp,
                                                color = MaruTextMuted
                                            )
                                        }
                                    }

                                    if (cast.role != null) {
                                        Surface(
                                            color = if (cast.role.equals("MAIN", ignoreCase = true)) Color(0x33E85D9F) else MaruGlassSubtleBg,
                                            shape = MaruPillShape,
                                            border = BorderStroke(1.dp, if (cast.role.equals("MAIN", ignoreCase = true)) MaruAccentPink else MaruGlassBorderSoft)
                                        ) {
                                            Text(
                                                text = cast.role.uppercase(),
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (cast.role.equals("MAIN", ignoreCase = true)) MaruAccentPink else MaruTextMuted,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (cast.japaneseVa != null) {
                                        Surface(
                                            onClick = { openVoiceActorWorks(cast.japaneseVa) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color(0x2218122B),
                                            border = BorderStroke(1.dp, MaruAccentPurple.copy(alpha = 0.4f)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (cast.japaneseVa.image != null) {
                                                    AsyncImage(
                                                        model = cast.japaneseVa.image,
                                                        contentDescription = cast.japaneseVa.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .clip(CircleShape)
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "JAPANESE",
                                                        fontSize = 8.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaruAccentPurple
                                                    )
                                                    Text(
                                                        text = cast.japaneseVa.name,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaruTextStrong,
                                                        lineHeight = 14.sp
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (cast.englishVa != null) {
                                        Surface(
                                            onClick = { openVoiceActorWorks(cast.englishVa) },
                                            shape = RoundedCornerShape(10.dp),
                                            color = Color(0x2218122B),
                                            border = BorderStroke(1.dp, MaruAccentGreen.copy(alpha = 0.4f)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (cast.englishVa.image != null) {
                                                    AsyncImage(
                                                        model = cast.englishVa.image,
                                                        contentDescription = cast.englishVa.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .clip(CircleShape)
                                                    )
                                                }
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = "ENGLISH",
                                                        fontSize = 8.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaruAccentGreen
                                                    )
                                                    Text(
                                                        text = cast.englishVa.name,
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaruTextStrong,
                                                        lineHeight = 14.sp
                                                    )
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

    if (activeVoiceActor != null) {
        ModalBottomSheet(
            onDismissRequest = { activeVoiceActor = null },
            containerColor = Color(0xFF140E24),
            contentColor = MaruTextStrong
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (activeVoiceActor?.image != null) {
                        AsyncImage(
                            model = activeVoiceActor!!.image,
                            contentDescription = activeVoiceActor!!.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .border(BorderStroke(2.dp, MaruAccentPink), CircleShape)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeVoiceActor!!.name,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaruTextStrong
                        )
                        if (!activeStaffWorks?.nameNative.isNullOrBlank()) {
                            Text(
                                text = activeStaffWorks!!.nameNative!!,
                                fontSize = 12.sp,
                                color = MaruTextMuted
                            )
                        }
                        Text(
                            text = "${activeVoiceActor!!.language} Voice Actor",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaruAccentPink
                        )
                    }
                }

                HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)

                Text(
                    text = "OTHER ROLES & WORKS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    ),
                    color = MaruAccentBlue
                )

                if (isStaffLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaruAccentPink)
                    }
                } else if (activeStaffWorks?.works.isNullOrEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No other anime works cataloged.", color = MaruTextMuted, fontSize = 13.sp)
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        activeStaffWorks!!.works.forEach { workMedia ->
                            Surface(
                                onClick = {
                                    activeVoiceActor = null
                                    currentMedia = workMedia
                                    onMediaUpdated(workMedia)
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF1B1430),
                                border = BorderStroke(1.dp, MaruGlassBorderSoft),
                                modifier = Modifier
                                    .width(110.dp)
                                    .height(175.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    AsyncImage(
                                        model = workMedia.coverUrl,
                                        contentDescription = workMedia.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    listOf(Color.Transparent, Color(0x99000000), Color(0xF5000000))
                                                )
                                            )
                                    )
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(6.dp)
                                    ) {
                                        Text(
                                            text = workMedia.title,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            lineHeight = 14.sp
                                        )
                                        if (workMedia.averageScore != null) {
                                            Text(
                                                text = "â˜… ${workMedia.averageScore}%",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaruAccentYellow
                                            )
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

    if (showEditSheet) {
        var selectedStatus by remember { mutableStateOf(currentMedia.listStatus ?: "CURRENT") }
        var selectedScore by remember { mutableStateOf(currentMedia.score?.toString() ?: "") }
        var selectedProgress by remember { mutableStateOf(currentMedia.progress.toString()) }

        ModalBottomSheet(
            onDismissRequest = { showEditSheet = false },
            containerColor = Color(0xFF161026),
            contentColor = MaruTextStrong
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Edit AniList Entry",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaruTextStrong
                )

                Text("STATUS", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruTextMuted)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("CURRENT", "PLANNING", "COMPLETED", "PAUSED", "DROPPED").forEach { status ->
                        val isSelected = selectedStatus == status
                        Surface(
                            onClick = { selectedStatus = status },
                            shape = MaruPillShape,
                            color = if (isSelected) MaruAccentPurple.copy(alpha = 0.3f) else MaruGlassSubtleBg,
                            border = BorderStroke(1.dp, if (isSelected) MaruAccentPurple else MaruGlassBorderSoft)
                        ) {
                            Text(
                                text = status,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaruAccentPurple else MaruTextMuted,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = selectedProgress,
                        onValueChange = { selectedProgress = it },
                        label = { Text("Episode Progress", color = MaruTextMuted, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaruTextStrong,
                            unfocusedTextColor = MaruTextStrong,
                            focusedBorderColor = MaruAccentPink,
                            unfocusedBorderColor = MaruGlassBorderSoft
                        )
                    )

                    OutlinedTextField(
                        value = selectedScore,
                        onValueChange = { selectedScore = it },
                        label = { Text("Score (1-100)", color = MaruTextMuted, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaruTextStrong,
                            unfocusedTextColor = MaruTextStrong,
                            focusedBorderColor = MaruAccentPink,
                            unfocusedBorderColor = MaruGlassBorderSoft
                        )
                    )
                }

                Button(
                    onClick = {
                        showEditSheet = false
                        val parsedScore = selectedScore.toDoubleOrNull()
                        val parsedProgress = selectedProgress.toIntOrNull() ?: currentMedia.progress
                        scope.launch {
                            isUpdatingList = true
                            try {
                                val updated = withContext(Dispatchers.IO) {
                                    AniListClient.saveEntry(
                                        mediaId = currentMedia.mediaId,
                                        status = selectedStatus,
                                        progress = parsedProgress,
                                        score = parsedScore,
                                        notes = currentMedia.notes,
                                        isPrivate = currentMedia.isPrivate,
                                        token = anilistToken
                                    )
                                }
                                currentMedia = updated
                                onMediaUpdated(updated)
                                Toast.makeText(context, "Saved to AniList!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isUpdatingList = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaruAccentPink)
                ) {
                    Text("SAVE CHANGES", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
