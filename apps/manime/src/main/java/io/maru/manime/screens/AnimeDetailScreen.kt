package io.maru.manime.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
fun AnimeDetailScreen(
    media: AnimeMedia,
    anilistToken: String,
    onBack: () -> Unit,
    onMediaUpdated: (AnimeMedia) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentMedia by remember(media) { mutableStateOf(media) }
    var isSynopsisExpanded by remember { mutableStateOf(false) }
    var isUpdatingList by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }

    var streamingEpisodes by remember { mutableStateOf<List<StreamingEpisode>>(emptyList()) }
    var isEpisodesLoading by remember { mutableStateOf(true) }

    LaunchedEffect(currentMedia.mediaId) {
        isEpisodesLoading = true
        try {
            val eps = withContext(Dispatchers.IO) {
                AniListClient.getStreamingEpisodes(currentMedia.mediaId)
            }
            streamingEpisodes = eps
        } catch (_: Exception) {
            streamingEpisodes = emptyList()
        } finally {
            isEpisodesLoading = false
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

    val totalEpisodes = maxOf(currentMedia.episodes ?: 0, streamingEpisodes.size).takeIf { it > 0 } ?: 12
    val hasEnglishDub = currentMedia.externalLinks.any { it.isEnglishDub }

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
                Toast.makeText(context, "Progress: Ep $safeProgress (${resolvedStatus.lowercase()})", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to sync: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                isUpdatingList = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 36.dp)
    ) {
        // Hero Banner Header
        item(key = "header") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
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
                                    Color(0xDD0E0A1A),
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
                        .background(Color(0x66000000), CircleShape)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaruTextStrong
                    )
                }

                // Poster + Title Row
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
                            .width(100.dp)
                            .height(145.dp)
                            .clip(MaruInputShape)
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (hasEnglishDub) {
                            Surface(
                                color = Color(0x334ADE80),
                                shape = MaruPillShape,
                                border = BorderStroke(1.dp, MaruAccentGreen.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "ðŸŽ™ï¸ ENGLISH DUBBED",
                                    color = MaruAccentGreen,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Text(
                            text = currentMedia.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaruTextStrong,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (!currentMedia.titleEnglish.isNullOrEmpty() && currentMedia.titleEnglish != currentMedia.title) {
                            Text(
                                text = currentMedia.titleEnglish!!,
                                fontSize = 12.sp,
                                color = MaruTextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (currentMedia.averageScore != null) {
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
                                        Icon(Icons.Default.Star, contentDescription = null, tint = MaruAccentYellow, modifier = Modifier.size(12.dp))
                                        Text(text = "${currentMedia.averageScore}%", fontSize = 10.5.sp, color = MaruAccentYellow, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Text(text = "${currentMedia.episodes ?: "?"} eps", fontSize = 12.sp, color = MaruTextMuted)
                            if (currentMedia.seasonYear != null) {
                                Text(text = "${currentMedia.season ?: ""} ${currentMedia.seasonYear}", fontSize = 12.sp, color = MaruTextMuted)
                            }
                        }
                    }
                }
            }
        }

        // AniList Tracker Bar Card
        item(key = "anilist_tracker") {
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassSectionHeader(
                            title = "ANILIST TRACKER",
                            icon = Icons.Default.Bookmark,
                            color = MaruAccentPurple
                        )

                        // Status Badge / Edit Button
                        val statusText = currentMedia.listStatus ?: "ADD TO LIST"
                        Surface(
                            onClick = { showEditDialog = true },
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
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = statusText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaruTextStrong
                                )
                                Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(12.dp), tint = MaruTextMuted)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Progress Stepper Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "EPISODE PROGRESS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                    fontSize = 10.sp
                                ),
                                color = MaruTextMuted
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${currentMedia.progress} / ${currentMedia.episodes ?: "?"}",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaruTextStrong
                            )
                        }

                        // Stepper Controls
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
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
        }

        // Genres & Formats Row
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

        // Synopsis Card
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
                        maxLines = if (isSynopsisExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis
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

        // IsThisDubbed Applet Card
        item(key = "is_this_dubbed_card") {
            var dubDetails by remember { mutableStateOf<DubDetails?>(null) }
            var isDubLoading by remember { mutableStateOf(true) }

            LaunchedEffect(currentMedia.mediaId) {
                isDubLoading = true
                try {
                    dubDetails = AniListClient.getDubDetails(currentMedia.mediaId)
                } catch (_: Exception) {} finally {
                    isDubLoading = false
                }
            }

            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassSectionHeader(
                            title = "IS THIS DUBBED? APPLET",
                            icon = Icons.Default.AutoAwesome,
                            color = MaruAccentGreen
                        )

                        val isConfirmed = dubDetails?.isDubbed == true
                        Surface(
                            color = if (isConfirmed) Color(0x334ADE80) else Color(0x33FF5252),
                            shape = MaruPillShape,
                            border = BorderStroke(1.dp, if (isConfirmed) MaruAccentGreen else MaruDanger)
                        ) {
                            Text(
                                text = if (isConfirmed) "ENGLISH DUBBED" else "SUB ONLY",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.5.sp
                                ),
                                color = if (isConfirmed) MaruAccentGreen else MaruDanger,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (isDubLoading) {
                        Text(text = "Scanning voice cast & dub registers...", fontSize = 12.sp, color = MaruTextMuted)
                    } else if (dubDetails?.isDubbed == true) {
                        if (dubDetails?.streamingPlatforms?.isNotEmpty() == true) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(bottom = 8.dp)
                            ) {
                                dubDetails!!.streamingPlatforms.forEach { platform ->
                                    Surface(
                                        shape = MaruPillShape,
                                        color = Color(0x2260E2FF),
                                        border = BorderStroke(1.dp, MaruAccentBlue.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = platform,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaruAccentBlue,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (dubDetails?.englishCast?.isNotEmpty() == true) {
                            Text(
                                text = "ENGLISH VOICE CAST",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaruTextMuted,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                dubDetails!!.englishCast.take(4).forEach { role ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = role.characterName,
                                            fontSize = 12.sp,
                                            color = MaruTextStrong,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = role.voiceActor?.name ?: "English VA",
                                            fontSize = 11.5.sp,
                                            color = MaruAccentPink
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "No official English dub found for this anime.",
                            fontSize = 12.sp,
                            color = MaruTextMuted
                        )
                    }
                }
            }
        }

        // Episodes Tracker List Section Header
        item(key = "episodes_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassSectionHeader(
                    title = "EPISODES LIST",
                    icon = Icons.Default.CheckCircle,
                    color = MaruAccentBlue
                )
                Text(
                    text = "TAP TO MARK WATCHED",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = MaruTextMuted
                )
            }
        }

        // Vertical List of Episodes with Titles & Thumbnails
        items(totalEpisodes, key = { "ep_$it" }) { index ->
            val episodeNum = index + 1
            val isWatched = episodeNum <= currentMedia.progress
            val streamEp = streamingEpisodes.find { it.episodeNumber == episodeNum }
                ?: streamingEpisodes.getOrNull(index)

            // Extract title cleanly without duplicate Episode prefix
            val rawTitle = streamEp?.title?.trim()
            val cleanSubTitle = if (!rawTitle.isNullOrBlank()) {
                rawTitle.replaceFirst(Regex("^Episode\\s*\\d+\\s*[-:–\\.]\\s*", RegexOption.IGNORE_CASE), "").trim()
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
                    // Episode Thumbnail or Number Pill
                    Box(
                        modifier = Modifier
                            .width(108.dp)
                            .height(64.dp)
                            .clip(MaruInputShape)
                            .background(Color(0x33000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!streamEp?.thumbnail.isNullOrBlank()) {
                            AsyncImage(
                                model = streamEp!!.thumbnail,
                                contentDescription = displayTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Dim overlay if watched
                            if (isWatched) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0x66000000))
                                )
                            }
                        }

                        // Episode badge on top of thumbnail
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

                    // Episode Number + Full Name Column (no cut-off)
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
    }

    // Quick AniList Status Editor Sheet
    if (showEditDialog) {
        var selectedStatus by remember { mutableStateOf(currentMedia.listStatus ?: "CURRENT") }
        var selectedScore by remember { mutableStateOf(currentMedia.score?.toString() ?: "") }

        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = {
                Text(
                    text = "Update AniList Status",
                    fontWeight = FontWeight.Bold,
                    color = MaruTextStrong
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Select status:", fontSize = 12.sp, color = MaruTextMuted)
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
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Score (1-100):", fontSize = 12.sp, color = MaruTextMuted)
                    OutlinedTextField(
                        value = selectedScore,
                        onValueChange = { selectedScore = it },
                        placeholder = { Text("e.g. 85", color = MaruTextMuted) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaruTextStrong,
                            unfocusedTextColor = MaruTextStrong,
                            focusedBorderColor = MaruAccentPink,
                            unfocusedBorderColor = MaruGlassBorderSoft
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEditDialog = false
                        val parsedScore = selectedScore.toDoubleOrNull()
                        scope.launch {
                            isUpdatingList = true
                            try {
                                val updated = withContext(Dispatchers.IO) {
                                    AniListClient.saveEntry(
                                        mediaId = currentMedia.mediaId,
                                        status = selectedStatus,
                                        progress = currentMedia.progress,
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
                    }
                ) {
                    Text("SAVE", color = MaruAccentPink, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = false }) {
                    Text("CANCEL", color = MaruTextMuted)
                }
            },
            containerColor = Color(0xFF18122B)
        )
    }
}
