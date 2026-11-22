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
import io.maru.manime.extensions.AnimeIdMappings
import io.maru.manime.extensions.CrunchyEpisodeMeta
import io.maru.manime.extensions.CrunchyrollStreamEngine
import io.maru.manime.player.PlayerScreen
import io.maru.manime.torrent.TorrentStreamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailScreen(
    media: AnimeMedia,
    anilistToken: String,
    prefs: MAnimePrefs,
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

    val preferredAudioFlow by prefs.preferredAudio.collectAsState(initial = "SUB")
    var selectedAudioMode by remember(preferredAudioFlow) { mutableStateOf(preferredAudioFlow) }

    var activePlayingEp by remember { mutableStateOf<CrunchyEpisodeMeta?>(null) }
    var animeMappings by remember { mutableStateOf<AnimeIdMappings?>(null) }

    val offlineVaultList by TorrentStreamService.offlineEpisodes.collectAsState()

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
        TorrentStreamService.loadOfflineVault(context)

        scope.launch {
            try {
                animeMappings = withContext(Dispatchers.IO) {
                    CrunchyrollStreamEngine.getAnimeMappings(currentMedia.mediaId, currentMedia.title)
                }
            } catch (_: Exception) {}
        }

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

    val isReleasing = currentMedia.status?.equals("RELEASING", ignoreCase = true) == true
    val latestAiredEp = currentMedia.latestAiredEpisode

    val completeEpisodes = remember(animeMappings, streamingEpisodes, currentMedia.episodes, latestAiredEp) {
        CrunchyrollStreamEngine.buildCompleteEpisodeList(
            mappings = animeMappings,
            anilistStreamingEps = streamingEpisodes,
            totalEpisodes = currentMedia.episodes,
            latestAired = latestAiredEp
        )
    }

    // Crunchyroll puts EPISODES right up front!
    val availableTabs = remember(completeEpisodes) {
        listOf("Episodes (${completeEpisodes.size})", "Overview", "Cast")
    }

    var selectedEpRangeIndex by remember(completeEpisodes.size) { mutableIntStateOf(0) }
    val episodeChunkSize = 50
    val episodeChunks = remember(completeEpisodes) {
        if (completeEpisodes.isEmpty()) listOf(emptyList())
        else completeEpisodes.chunked(episodeChunkSize)
    }
    val visibleEpisodes = episodeChunks.getOrElse(selectedEpRangeIndex.coerceIn(0, (episodeChunks.size - 1).coerceAtLeast(0))) { completeEpisodes }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val currentTabName = availableTabs.getOrElse(selectedTabIndex) { "Episodes" }

    // Accurate English Dub validation matching IsThisDubbed: Must have English VA in Cast OR explicit English Dub link
    val hasEnglishDub = remember(castList, currentMedia) {
        currentMedia.hasEnglishDub || castList.any { it.englishVa != null }
    }

    LaunchedEffect(hasEnglishDub) {
        if (!hasEnglishDub && selectedAudioMode == "DUB") {
            selectedAudioMode = "SUB"
        }
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

    if (activePlayingEp != null) {
        val ep = activePlayingEp!!
        PlayerScreen(
            media = currentMedia,
            episodeNum = ep.episodeNumber,
            episodeTitle = ep.title,
            initialAudioMode = if (hasEnglishDub) selectedAudioMode else "SUB",
            hasEnglishDub = hasEnglishDub,
            prefs = prefs,
            onEpisodeWatched = { watchedNum ->
                if (watchedNum > currentMedia.progress) {
                    updateProgress(watchedNum)
                }
            },
            onSwitchEpisode = { targetNum ->
                val nextMeta = completeEpisodes.firstOrNull { it.episodeNumber == targetNum }
                    ?: CrunchyEpisodeMeta(episodeNumber = targetNum, title = "Episode $targetNum")
                activePlayingEp = nextMeta
            },
            onBack = { activePlayingEp = null }
        )
        return
    }

    androidx.activity.compose.BackHandler {
        onBack()
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            Surface(
                color = CrunchySurface,
                border = BorderStroke(1.dp, CrunchyBorder),
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
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    Icon(Icons.Default.Star, contentDescription = null, tint = MaruAccentYellow, modifier = Modifier.size(11.dp))
    Text(text = "${currentMedia.score}", fontSize = 10.5.sp, color = MaruAccentYellow, fontWeight = FontWeight.Bold)
}
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

            // Crunchyroll Action Block: START WATCHING CTA + Season Switcher + SUB / DUB Audio Switcher
            item(key = "crunchy_cta_bar") {
                val seasonRelations = remember(similarAnime) {
                    similarAnime.filter {
                        it.relationType == "PREQUEL" ||
                        it.relationType == "SEQUEL" ||
                        it.relationType == "SIDE_STORY" ||
                        it.relationType == "PARENT" ||
                        it.relationType == "ALTERNATIVE"
                    }
                }
                val nextEpNum = (currentMedia.progress + 1).coerceAtMost((latestAiredEp ?: currentMedia.episodes ?: 9999).coerceAtLeast(1))
                val targetEpMeta = completeEpisodes.firstOrNull { it.episodeNumber == nextEpNum }
                    ?: completeEpisodes.firstOrNull()
                    ?: CrunchyEpisodeMeta(1, title = "Episode 1")

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Big Crunchyroll Orange START WATCHING Button
                    Button(
                        onClick = { activePlayingEp = targetEpMeta },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange)
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val btnText = if (currentMedia.progress > 0) {
                            "CONTINUE WATCHING S${targetEpMeta.seasonNumber} E${targetEpMeta.episodeNumber}"
                        } else {
                            "START WATCHING S${targetEpMeta.seasonNumber} E1"
                        }
                        Text(
                            text = btnText,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.Black,
                            letterSpacing = 0.6.sp
                        )
                    }

                    // Related Seasons / Prequel / Sequel Quick Switcher Pill Bar
                    if (seasonRelations.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SEASONS:",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CrunchyOrange,
                                letterSpacing = 0.6.sp
                            )
                            seasonRelations.forEach { rel ->
                                val tag = when (rel.relationType) {
                                    "PREQUEL" -> "PREV SEASON"
                                    "SEQUEL" -> "NEXT SEASON"
                                    "SIDE_STORY" -> "OVA / SIDE"
                                    else -> "RELATED"
                                }
                                Surface(
                                    onClick = {
                                        currentMedia = rel
                                        onMediaUpdated(rel)
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    color = CrunchyCardBg,
                                    border = BorderStroke(1.dp, CrunchyBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = tag,
                                            fontSize = 9.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = CrunchyTeal
                                        )
                                        Text(
                                            text = rel.title,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Audio Language Selector: Smartly reflects whether an English Dub actually exists!
                    if (hasEnglishDub) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "AUDIO:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaruTextMuted,
                                letterSpacing = 0.8.sp
                            )
                            listOf("SUB" to "Japanese (Sub)", "DUB" to "English Dub").forEach { (mode, label) ->
                                val isSelected = selectedAudioMode == mode
                                Surface(
                                    onClick = {
                                        selectedAudioMode = mode
                                        scope.launch { prefs.setPreferredAudio(mode) }
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSelected) CrunchyOrange.copy(alpha = 0.2f) else CrunchyCardBg,
                                    border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = CrunchyOrange,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                        }
                                        Text(
                                            text = label,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) CrunchyOrange else MaruTextStrong
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = CrunchyCardBg,
                            border = BorderStroke(1.dp, CrunchyBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "AUDIO: Japanese (Subtitled)",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaruTextStrong
                                )
                                Text(
                                    text = "Subtitled Only (No English Dub)",
                                    fontSize = 10.5.sp,
                                    color = MaruTextMuted
                                )
                            }
                        }
                    }
                }
            }

            // Sub Navigation Tab Bar: [Episodes] [Overview] [Cast]
            item(key = "sub_tabs") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableTabs.forEachIndexed { index, title ->
                        val isSelected = selectedTabIndex == index
                        Surface(
                            onClick = { selectedTabIndex = index },
                            shape = MaruPillShape,
                            color = if (isSelected) CrunchyOrange.copy(alpha = 0.22f) else MaruGlassSubtleBg,
                            border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else MaruGlassBorderSoft),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 9.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = title.uppercase(),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isSelected) CrunchyOrange else MaruTextMuted
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
                // ================= CRUNCHYROLL EPISODES TAB =================
                if (episodeChunks.size > 1) {
                    item(key = "ep_range_chunks") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            episodeChunks.forEachIndexed { idx, chunk ->
                                val firstEp = chunk.firstOrNull()?.episodeNumber ?: 1
                                val lastEp = chunk.lastOrNull()?.episodeNumber ?: firstEp
                                val isRangeSelected = selectedEpRangeIndex == idx
                                Surface(
                                    onClick = { selectedEpRangeIndex = idx },
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isRangeSelected) CrunchyOrange else CrunchyCardBg,
                                    border = BorderStroke(1.dp, if (isRangeSelected) CrunchyOrange else CrunchyBorder)
                                ) {
                                    Text(
                                        text = "EP $firstEp - $lastEp",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isRangeSelected) Color.Black else MaruTextStrong,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                items(visibleEpisodes, key = { "crunchy_ep_${it.episodeNumber}" }) { streamEp ->
                    val episodeNum = streamEp.episodeNumber
                    val isWatched = episodeNum <= currentMedia.progress
                    val displayTitle = streamEp.title.ifBlank { "Episode $episodeNum" }
                    val offlineEntry = offlineVaultList.firstOrNull {
                        it.mediaId == currentMedia.mediaId && it.episodeNumber == episodeNum
                    }

                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clickable { activePlayingEp = streamEp }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 16:9 Episode Thumbnail with Play Badge
                            Box(
                                modifier = Modifier
                                    .width(124.dp)
                                    .height(70.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF0B0C0F)),
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = streamEp.thumbnail ?: currentMedia.bannerUrl ?: currentMedia.coverUrl,
                                    contentDescription = displayTitle,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(if (isWatched) Color(0x88000000) else Color(0x33000000))
                                )

                                // Center Play Icon
                                Surface(
                                    color = if (isWatched) Color(0xCC2ECC71) else Color(0xDD0B0C0F),
                                    shape = CircleShape,
                                    border = BorderStroke(1.dp, if (isWatched) Color.Transparent else CrunchyOrange),
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = if (isWatched) Icons.Default.Check else Icons.Default.PlayArrow,
                                            contentDescription = "Play",
                                            tint = if (isWatched) Color.Black else CrunchyOrange,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                // Duration pill on bottom right
                                Surface(
                                    color = Color(0xCC0B0C0F),
                                    shape = RoundedCornerShape(2.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                ) {
                                    Text(
                                        text = "${streamEp.durationMinutes ?: 24}m",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (streamEp.absoluteEpisodeNumber != null && streamEp.absoluteEpisodeNumber != episodeNum) {
                                            "S${streamEp.seasonNumber} E$episodeNum (Ep ${streamEp.absoluteEpisodeNumber})"
                                        } else {
                                            "S${streamEp.seasonNumber} E$episodeNum"
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 10.5.sp,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = if (isWatched) MaruAccentGreen else CrunchyOrange
                                    )
                                    Text(
                                        text = "•",
                                        fontSize = 10.sp,
                                        color = MaruTextMuted
                                    )
                                    Text(
                                        text = if (hasEnglishDub) "Sub | Dub" else "Subtitled",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = CrunchyTeal
                                    )
                                    if (!streamEp.airDate.isNullOrBlank()) {
                                        Text(
                                            text = "• ${streamEp.airDate}",
                                            fontSize = 9.5.sp,
                                            color = MaruTextMuted
                                        )
                                    }
                                }

                                Text(
                                    text = displayTitle,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaruTextStrong,
                                    maxLines = 1,
                                    lineHeight = 16.sp
                                )

                                if (!streamEp.overview.isNullOrBlank()) {
                                    Text(
                                        text = streamEp.overview,
                                        fontSize = 11.sp,
                                        color = MaruTextMuted,
                                        maxLines = 2,
                                        lineHeight = 14.sp
                                    )
                                }

                                if (offlineEntry != null) {
                                    Text(
                                        text = if (offlineEntry.isCompleted) "Saved in Offline Vault" else "Syncing Offline (${offlineEntry.progressPercent}%)",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (offlineEntry.isCompleted) CrunchyTeal else CrunchyOrange
                                    )
                                }
                            }

                            // 1-Tap Private Offline Vault Download Button
                            IconButton(
                                onClick = {
                                    if (offlineEntry != null) {
                                        TorrentStreamService.deleteOfflineEpisode(context, currentMedia.mediaId, episodeNum)
                                        Toast.makeText(context, "Removed Episode $episodeNum from Offline Vault", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Saving Episode $episodeNum to Offline Vault...", Toast.LENGTH_SHORT).show()
                                        scope.launch {
                                            val streams = CrunchyrollStreamEngine.resolveRankedStreams(
                                                anilistId = currentMedia.mediaId,
                                                animeTitle = currentMedia.title,
                                                episodeNum = episodeNum,
                                                preferredAudio = selectedAudioMode
                                            )
                                            val bestTorrent = streams.firstOrNull { it.isTorrent }
                                            if (bestTorrent != null) {
                                                TorrentStreamService.startOfflineDownload(
                                                    context = context,
                                                    magnetUri = bestTorrent.url,
                                                    fileIdx = bestTorrent.fileIdx,
                                                    mediaId = currentMedia.mediaId,
                                                    animeTitle = currentMedia.title,
                                                    episodeNum = episodeNum,
                                                    episodeTitle = displayTitle,
                                                    coverUrl = currentMedia.coverUrl,
                                                    thumbnailUrl = streamEp.thumbnail ?: currentMedia.bannerUrl ?: currentMedia.coverUrl,
                                                    audioType = bestTorrent.audioType,
                                                    quality = bestTorrent.quality
                                                )
                                            } else {
                                                Toast.makeText(context, "Offline stream not available for Episode $episodeNum yet", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                }
                            ) {
                                when {
                                    offlineEntry?.isCompleted == true -> {
                                        Icon(
                                            Icons.Default.DownloadDone,
                                            contentDescription = "Offline Ready",
                                            tint = CrunchyTeal
                                        )
                                    }
                                    offlineEntry != null -> {
                                        CircularProgressIndicator(
                                            progress = { (offlineEntry.progressPercent / 100f).coerceIn(0.05f, 1f) },
                                            color = CrunchyOrange,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                    else -> {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = "Download Offline",
                                            tint = MaruTextMuted
                                        )
                                    }
                                }
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
                                                text = "★ ${workMedia.averageScore}%",
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

