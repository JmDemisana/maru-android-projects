package io.maru.manime.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.maru.manime.*
import io.maru.manime.extensions.AnimeIdMappings
import io.maru.manime.extensions.CrunchyEpisodeMeta
import io.maru.manime.extensions.CrunchyrollStreamEngine
import io.maru.manime.extensions.StreamDubStatus
import io.maru.manime.player.PlayerScreen
import io.maru.manime.torrent.OfflineEpisodeEntry
import io.maru.manime.torrent.TorrentStreamService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailScreen(
    media: AnimeMedia,
    anilistToken: String,
    prefs: MAnimePrefs,
    isActive: Boolean = true,
    onBack: () -> Unit,
    onMediaUpdated: (AnimeMedia) -> Unit = {},
    onAnimeClick: (AnimeMedia) -> Unit = {},
    onUserClick: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentMedia by remember(media.mediaId, anilistToken) {
        mutableStateOf(media.copy(listEntryId = null, listStatus = null, progress = 0,
            score = null, notes = null, isPrivate = false, updatedAt = null))
    }
    var isEntryReady by remember(media.mediaId, anilistToken) { mutableStateOf(false) }
    var isEntryLoading by remember(media.mediaId, anilistToken) { mutableStateOf(anilistToken.isNotBlank()) }
    var entryRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(media.mediaId, anilistToken, entryRetry) {
        if (anilistToken.isBlank()) return@LaunchedEffect
        isEntryLoading = true
        try {
            val detail = withContext(Dispatchers.IO) { AniListClient.getMediaDetail(media.mediaId, anilistToken) }
            currentMedia = detail.copy(hasEnglishDubCast = detail.hasEnglishDubCast || media.hasEnglishDubCast)
            isEntryReady = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            isEntryReady = false
        }
        isEntryLoading = false
    }
    var isSynopsisExpanded by rememberSaveable { mutableStateOf(false) }
    var isUpdatingList by remember { mutableStateOf(false) }
    var showEditPage by remember { mutableStateOf(false) }

    val preferredAudioFlow by prefs.preferredAudio.collectAsState(initial = "SUB")
    val reportProgress by prefs.reportProgress.collectAsState(initial = true)
    val userScoreFormat by prefs.anilistScoreFormat.collectAsState(initial = "POINT_100")
    var selectedAudioMode by remember(preferredAudioFlow) { mutableStateOf(preferredAudioFlow) }

    var activePlayingEp by remember { mutableStateOf<CrunchyEpisodeMeta?>(null) }
    var animeMappings by remember { mutableStateOf<AnimeIdMappings?>(null) }
    var streamDubStatus by remember { mutableStateOf(StreamDubStatus()) }

    val offlineVaultList by TorrentStreamService.offlineEpisodes.collectAsState()
    val pendingOfflineActions = remember { mutableStateMapOf<Pair<Int, Int>, Unit>() }

    fun onOfflineAction(episode: CrunchyEpisodeMeta, episodeTitle: String, entry: OfflineEpisodeEntry?, audioMode: String) {
        val anime = currentMedia
        val episodeNum = episode.episodeNumber
        val key = anime.mediaId to episodeNum
        if (pendingOfflineActions.put(key, Unit) != null) return
        scope.launch {
            try {
                if (entry != null && entry.failure == null) {
                    val removed = TorrentStreamService.deleteOfflineEpisode(context, anime.mediaId, episodeNum)
                    val message = when {
                        !removed -> "Could not remove Episode $episodeNum."
                        entry.isCompleted -> "Removed Episode $episodeNum from Downloads."
                        else -> "Canceled download for Episode $episodeNum."
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val streams = CrunchyrollStreamEngine.resolveRankedStreams(
                    anilistId = anime.mediaId,
                    animeTitle = anime.title,
                    episodeNum = episodeNum,
                    preferredAudio = audioMode,
                    preferredQuality = prefs.preferredQuality.first(),
                    isMovie = anime.format == "MOVIE"
                )
                val source = streams.firstOrNull {
                    it.isTorrent && it.url.startsWith("magnet:", ignoreCase = true) &&
                        (it.audioType == audioMode || it.audioType == "DUAL_AUDIO")
                }
                if (source == null) {
                    val audioLabel = if (audioMode == "DUB") "English dub" else "Japanese audio"
                    Toast.makeText(context, "No $audioLabel download found for Episode $episodeNum.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                TorrentStreamService.startOfflineDownload(
                    context = context,
                    magnetUri = source.url,
                    fileIdx = source.fileIdx,
                    mediaId = anime.mediaId,
                    animeTitle = anime.title,
                    episodeNum = episodeNum,
                    episodeTitle = episodeTitle,
                    coverUrl = anime.coverUrl,
                    thumbnailUrl = episode.thumbnail ?: anime.bannerUrl ?: anime.coverUrl,
                    audioType = source.audioType,
                    quality = source.quality
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, "Could not change the download for Episode $episodeNum.", Toast.LENGTH_SHORT).show()
            } finally {
                pendingOfflineActions.remove(key)
            }
        }
    }

    var streamingEpisodes by remember { mutableStateOf<List<StreamingEpisode>>(emptyList()) }
    var castList by remember { mutableStateOf<List<CastMember>>(emptyList()) }
    var isCastLoading by remember { mutableStateOf(true) }
    var similarAnime by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var isSimilarLoading by remember { mutableStateOf(true) }
    var friendsStatus by remember { mutableStateOf<List<FriendAnimeStatus>>(emptyList()) }
    var isFriendsLoading by remember { mutableStateOf(true) }
    var isEpisodeInfoLoading by remember { mutableStateOf(true) }

    var activeVoiceActor by remember { mutableStateOf<VoiceActor?>(null) }
    var activeStaffWorks by remember { mutableStateOf<StaffWorks?>(null) }
    var isStaffLoading by remember { mutableStateOf(false) }

    LaunchedEffect(currentMedia.mediaId) {
        isCastLoading = true
        isSimilarLoading = true
        isEpisodeInfoLoading = true
        launch { TorrentStreamService.loadOfflineVault(context) }

        val mappingsJob = launch {
            try {
                animeMappings = withContext(Dispatchers.IO) {
                    CrunchyrollStreamEngine.getAnimeMappings(currentMedia.mediaId, currentMedia.title)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }

        val airingJob = launch {
            try {
                streamingEpisodes = withContext(Dispatchers.IO) {
                    AniListClient.getStreamingEpisodes(currentMedia.mediaId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                streamingEpisodes = emptyList()
            }
        }
        launch {
            mappingsJob.join()
            airingJob.join()
            isEpisodeInfoLoading = false
        }
        launch {
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
        launch {
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
    }

    val isPlaybackOpen = activePlayingEp != null
    val hasListedDub = currentMedia.hasEnglishDub || castList.any { it.englishVa != null }
    LaunchedEffect(media.mediaId, isActive, isPlaybackOpen, hasListedDub) {
        if (!isActive || isPlaybackOpen || hasListedDub || streamDubStatus.hasStreamDubOrDual) return@LaunchedEffect
        delay(1200)
        try {
            streamDubStatus = CrunchyrollStreamEngine.checkStreamDubAvailability(
                anilistId = media.mediaId,
                animeTitle = media.title,
                episodeNum = 1,
                isMovie = media.format == "MOVIE"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    LaunchedEffect(media.mediaId, anilistToken) {
        friendsStatus = emptyList()
        isFriendsLoading = anilistToken.isNotBlank()
        if (anilistToken.isNotBlank()) {
            try {
                friendsStatus = withContext(Dispatchers.IO) {
                    AniListClient.getFriendsMediaStatus(media.mediaId, anilistToken)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                friendsStatus = emptyList()
            }
        }
        isFriendsLoading = false
    }

    val isReleasing = currentMedia.status?.equals("RELEASING", ignoreCase = true) == true
    val latestAiredEp = currentMedia.latestAiredEpisode
    val hasUncertainReleaseCount = currentMedia.hasUncertainReleaseCount

    // Strictly cap episodes at released count! Never show unreleased future episodes.
    val completeEpisodes = remember(animeMappings, streamingEpisodes, currentMedia.episodes, latestAiredEp, hasUncertainReleaseCount) {
        CrunchyrollStreamEngine.buildCompleteEpisodeList(
            mappings = animeMappings,
            anilistStreamingEps = streamingEpisodes,
            totalEpisodes = currentMedia.episodes,
            latestAired = latestAiredEp,
            isReleasing = hasUncertainReleaseCount
        )
    }

    val availableTabs = listOf("Episodes", "Overview", "Cast")
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val detailScroll = rememberLazyListState()

    var selectedEpRangeIndex by rememberSaveable { mutableIntStateOf(0) }
    val episodeChunkSize = 50
    val episodeChunks = remember(completeEpisodes) {
        if (completeEpisodes.isEmpty()) listOf(emptyList())
        else completeEpisodes.chunked(episodeChunkSize)
    }
    val selectedRange = selectedEpRangeIndex.coerceIn(episodeChunks.indices)
    val visibleEpisodes = episodeChunks[selectedRange]

    // True Dub / Dual-Audio availability: AniList English Cast OR Stream Dual-Audio / Dub!
    val hasAnyDub = hasListedDub || streamDubStatus.hasStreamDubOrDual

    LaunchedEffect(activeVoiceActor?.id) {
        val actor = activeVoiceActor ?: return@LaunchedEffect
        activeStaffWorks = null
        isStaffLoading = true
        try {
            activeStaffWorks = withContext(Dispatchers.IO) { AniListClient.getStaffWorks(actor.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            activeStaffWorks = null
        }
        isStaffLoading = false
    }

    fun openVoiceActorWorks(va: VoiceActor) {
        activeStaffWorks = null
        isStaffLoading = true
        activeVoiceActor = va
    }

    val cleanDescription: String = remember(currentMedia.description) {
        val raw = currentMedia.description
        if (raw.isNullOrBlank()) "No synopsis available."
        else try { Jsoup.parse(raw).text() } catch (_: Exception) { raw }
    }

    fun saveAniListEntry(newStatus: String?, newProgress: Int?, newScore: Double?) {
        if (isUpdatingList) return
        if (anilistToken.isBlank()) {
            Toast.makeText(context, "Connect your AniList account in Account tab first!", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isEntryReady) return
        val safeProgress = (newProgress ?: currentMedia.progress).coerceIn(0, currentMedia.episodes ?: 9999)
        val resolvedStatus = newStatus ?: currentMedia.listStatus ?: "CURRENT"

        val previous = currentMedia
        isUpdatingList = true
        val optimistic = previous.copy(
            progress = safeProgress,
            listStatus = resolvedStatus,
            score = newScore ?: currentMedia.score
        )
        currentMedia = optimistic
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    AniListClient.saveEntry(
                        mediaId = previous.mediaId,
                        status = resolvedStatus,
                        progress = safeProgress,
                        score = newScore,
                        notes = previous.notes,
                        isPrivate = previous.isPrivate,
                        token = anilistToken,
                        scoreFormat = userScoreFormat
                    )
                }
                currentMedia = updated.copy(hasEnglishDubCast = currentMedia.hasEnglishDubCast)
                onMediaUpdated(currentMedia)
                Toast.makeText(context, "Updated AniList: ${resolvedStatus.lowercase().replaceFirstChar { it.uppercase() }} (Ep $safeProgress)", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentMedia = previous
                Toast.makeText(context, "AniList update failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
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
            initialAudioMode = if (hasAnyDub) selectedAudioMode else "SUB",
            hasEnglishDub = hasAnyDub,
            prefs = prefs,
            onEpisodeWatched = { watchedNum ->
                if (watchedNum > currentMedia.progress) {
                    currentMedia = currentMedia.copy(progress = watchedNum)
                }
                if (reportProgress && anilistToken.isNotBlank()) onMediaUpdated(currentMedia)
            },
            onSwitchEpisode = { targetNum ->
                completeEpisodes.firstOrNull { it.episodeNumber == targetNum }?.let { activePlayingEp = it }
            },
            lastPlayableEpisode = completeEpisodes.lastOrNull()?.episodeNumber ?: latestAiredEp,
            onBack = { activePlayingEp = null }
        )
        return
    }

    BackHandler(enabled = isActive) {
        when {
            showEditPage -> showEditPage = false
            activeVoiceActor != null -> activeVoiceActor = null
            else -> onBack()
        }
    }

    val compact = LocalConfiguration.current.screenHeightDp < 430
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CrunchyBgBlack)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
    ) {
        if (!showEditPage && activeVoiceActor == null) LazyColumn(
            state = detailScroll,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            // 1. Clean Hero Banner + 2:3 Cover Header
            item(key = "header") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compact) 222.dp else 264.dp)
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
                                    listOf(Color(0x66000000), Color(0xBB000000), CrunchyBgBlack)
                                )
                            )
                    )

                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(8.dp)
                            .background(Color(0x88000000), CircleShape)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        AsyncImage(
                            model = currentMedia.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(if (compact) 88.dp else 100.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(4.dp))
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                text = currentMedia.title,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 22.sp
                            )

                            val metaPieces = buildList {
                                if (hasAnyDub) add(if (streamDubStatus.hasStreamDubOrDual && !currentMedia.hasEnglishDub) "Dual Audio" else "Dub | Sub")
                                else add("Subtitled")
                                if (currentMedia.averageScore != null && currentMedia.averageScore!! > 0) add("★ ${currentMedia.averageScore}%")
                                if (isReleasing && latestAiredEp != null) add("$latestAiredEp Released")
                                else if (currentMedia.episodes != null) add("${currentMedia.episodes} Eps")
                                if (!currentMedia.format.isNullOrBlank()) add(currentMedia.format!!)
                            }
                            Text(
                                text = metaPieces.joinToString("  •  "),
                                fontSize = 12.sp,
                                color = MaruTextMuted,
                                fontWeight = FontWeight.Medium
                            )

                            if (currentMedia.listStatus != null) {
                                val scoreStr = currentMedia.score?.let { formatScoreDisplay(it, userScoreFormat) }
                                Text(
                                    text = buildString {
                                        append(currentMedia.listStatus!!.lowercase().replaceFirstChar { it.uppercase() })
                                        append(" • Ep ${currentMedia.progress}")
                                        if (scoreStr != null) append(" • $scoreStr")
                                    },
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CrunchyTeal
                                )
                            }
                        }
                    }
                }
            }

            // 2. Crunchyroll Primary CTA Row: [START WATCHING] + [AniList Watchlist/Score Button]
            item(key = "crunchy_cta_bar") {
                val seasonRelations = remember(similarAnime) {
                    similarAnime.filter {
                        it.relationType == "PREQUEL" || it.relationType == "SEQUEL" || it.relationType == "SIDE_STORY"
                    }
                }
                val nextEpNum = (currentMedia.progress + 1).coerceAtMost(completeEpisodes.size.coerceAtLeast(1))
                val targetEpMeta = completeEpisodes.firstOrNull { it.episodeNumber == nextEpNum }
                    ?: completeEpisodes.firstOrNull()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { activePlayingEp = targetEpMeta },
                            enabled = targetEpMeta != null,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            val btnText = when {
                                targetEpMeta == null && isEpisodeInfoLoading -> "Loading episodes…"
                                targetEpMeta == null -> "No episodes available"
                                currentMedia.progress > 0 -> "Continue · E${targetEpMeta.episodeNumber}"
                                else -> "Play episode ${targetEpMeta.episodeNumber}"
                            }
                            Text(
                                text = btnText,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Clean Bookmark / AniList Status & Score Button
                        Surface(
                            onClick = {
                                when {
                                    anilistToken.isBlank() -> Toast.makeText(context, "Connect AniList from the Account tab.", Toast.LENGTH_SHORT).show()
                                    isEntryLoading -> Toast.makeText(context, "Loading your AniList entry…", Toast.LENGTH_SHORT).show()
                                    isUpdatingList -> Unit
                                    isEntryReady -> showEditPage = true
                                    else -> {
                                        Toast.makeText(context, "Retrying your AniList entry…", Toast.LENGTH_SHORT).show()
                                        entryRetry++
                                    }
                                }
                            },
                            shape = RoundedCornerShape(4.dp),
                            color = if (currentMedia.listStatus != null) CrunchyOrange.copy(alpha = 0.16f) else CrunchyCardBg,
                            border = BorderStroke(1.dp, if (currentMedia.listStatus != null) CrunchyOrange else CrunchyBorder),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (currentMedia.listStatus != null) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = "AniList Status",
                                    tint = CrunchyOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "AniList",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    // Audio Language Selector (only shows Dub option if AniList OR live streams confirm Dual Audio / Dub!)
                    if (hasAnyDub) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val dubLabel = if (streamDubStatus.hasStreamDubOrDual && !currentMedia.hasEnglishDub) "Dub / Dual Audio" else "English Dub"
                            listOf("SUB" to "Japanese (Sub)", "DUB" to dubLabel).forEach { (mode, label) ->
                                val isSelected = selectedAudioMode == mode
                                Surface(
                                    onClick = {
                                        selectedAudioMode = mode
                                        scope.launch { prefs.setPreferredAudio(mode) }
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSelected) Color(0xFF1F2128) else Color.Transparent,
                                    border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) CrunchyOrange else MaruTextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Related Seasons Bar
                    if (seasonRelations.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            seasonRelations.forEach { rel ->
                                val tag = when (rel.relationType) {
                                    "PREQUEL" -> "PREV"
                                    "SEQUEL" -> "NEXT"
                                    else -> "OVA"
                                }
                                Surface(
                                    onClick = { onAnimeClick(rel) },
                                    shape = RoundedCornerShape(4.dp),
                                    color = CrunchyCardBg,
                                    border = BorderStroke(1.dp, CrunchyBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(tag, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, color = CrunchyTeal)
                                        Text(rel.title, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Crunchyroll Underline Navigation Tabs (Episodes | Overview | Cast)
            item(key = "underline_tabs") {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .selectableGroup()
                    ) {
                        availableTabs.forEachIndexed { idx, title ->
                            val isSelected = selectedTabIndex == idx
                            val label = if (idx == 0) "$title (${completeEpisodes.size})" else title
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .selectable(selected = isSelected, role = Role.Tab, onClick = { selectedTabIndex = idx })
                                    .padding(top = 8.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    color = if (isSelected) Color.White else MaruTextMuted,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .background(if (isSelected) CrunchyOrange else Color.Transparent)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)
                }
            }

            if (selectedTabIndex == 0) {
                // ================= CLEAN BORDERLESS CRUNCHYROLL EPISODES =================
                if (episodeChunks.size > 1) {
                    item(key = "ep_range_chunks") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            episodeChunks.forEachIndexed { idx, chunk ->
                                val firstEp = chunk.firstOrNull()?.episodeNumber ?: 1
                                val lastEp = chunk.lastOrNull()?.episodeNumber ?: firstEp
                                val isRangeSelected = selectedRange == idx
                                Surface(
                                    onClick = { selectedEpRangeIndex = idx },
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isRangeSelected) CrunchyOrange else CrunchyCardBg
                                ) {
                                    Text(
                                        text = "EP $firstEp–$lastEp",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (isRangeSelected) Color.Black else Color.White,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (visibleEpisodes.isEmpty()) {
                    item(key = "no_released_eps") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp, horizontal = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isEpisodeInfoLoading) {
                                CircularProgressIndicator(color = CrunchyOrange, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                            } else {
                                Text("No released episodes available.", color = MaruTextMuted, fontSize = 13.5.sp)
                            }
                        }
                    }
                } else {
                    items(visibleEpisodes, key = { "ep_row_${it.episodeNumber}" }) { streamEp ->
                        val episodeNum = streamEp.episodeNumber
                        val isWatched = episodeNum <= currentMedia.progress
                        val displayTitle = streamEp.title.ifBlank { "Episode $episodeNum" }
                        val offlineEntry = offlineVaultList.firstOrNull {
                            it.mediaId == currentMedia.mediaId && it.episodeNumber == episodeNum
                        }
                        val offlineActionPending = pendingOfflineActions.containsKey(currentMedia.mediaId to episodeNum)
                        val downloadStatus = when {
                            offlineActionPending -> "Working…"
                            offlineEntry == null -> null
                            offlineEntry.isCompleted -> "Downloaded"
                            offlineEntry.failure != null -> offlineEntry.failure
                            offlineEntry.progressPercent == 0 -> "Preparing download…"
                            else -> "Downloading ${offlineEntry.progressPercent}%"
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { activePlayingEp = streamEp }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Clean 16:9 Thumbnail (no thick borders)
                                Box(
                                    modifier = Modifier
                                        .width(142.dp)
                                        .aspectRatio(16f / 9f)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(CrunchyCardBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = streamEp.thumbnail ?: currentMedia.bannerUrl ?: currentMedia.coverUrl,
                                        contentDescription = displayTitle,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    if (isWatched) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(Color(0x77000000))
                                        )
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = "Watched",
                                            tint = CrunchyTeal,
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(6.dp)
                                                .size(18.dp)
                                        )
                                    }

                                    // Clean Duration Badge Bottom Right
                                    Surface(
                                        color = Color(0xCC000000),
                                        shape = RoundedCornerShape(2.dp),
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(5.dp)
                                    ) {
                                        Text(
                                            text = "${streamEp.durationMinutes ?: 24}m",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                        )
                                    }
                                }

                                // Right Metadata Column (zero date wrapping!)
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "S${streamEp.seasonNumber} E$episodeNum  •  ${if (hasAnyDub) "Dub | Sub" else "Subtitled"}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isWatched) CrunchyTeal else CrunchyOrange,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Text(
                                        text = displayTitle,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        lineHeight = 18.sp
                                    )

                                    if (!streamEp.overview.isNullOrBlank()) {
                                        Text(
                                            text = streamEp.overview,
                                            fontSize = 11.5.sp,
                                            color = MaruTextMuted,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            lineHeight = 15.sp
                                        )
                                    }

                                    if (downloadStatus != null) {
                                        Text(
                                            text = downloadStatus,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (offlineEntry?.isCompleted == true) CrunchyTeal else CrunchyOrange
                                        )
                                    }
                                }

                                IconButton(
                                    enabled = !offlineActionPending,
                                    onClick = {
                                        onOfflineAction(streamEp, displayTitle, offlineEntry, if (hasAnyDub) selectedAudioMode else "SUB")
                                    }
                                ) {
                                    when {
                                        offlineActionPending -> CircularProgressIndicator(
                                            color = CrunchyOrange,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        offlineEntry?.isCompleted == true ->
                                            Icon(Icons.Default.DownloadDone, contentDescription = "Remove download", tint = CrunchyTeal)
                                        offlineEntry?.failure != null ->
                                            Icon(Icons.Default.Refresh, contentDescription = "Retry download", tint = CrunchyOrange)
                                        offlineEntry != null ->
                                            Icon(Icons.Default.Close, contentDescription = "Cancel download", tint = CrunchyOrange)
                                        else -> Icon(Icons.Default.Download, contentDescription = "Download", tint = MaruTextMuted)
                                    }
                                }
                            }
                            HorizontalDivider(color = Color(0xFF15161B), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            } else if (selectedTabIndex == 1) {
                // ================= OVERVIEW & FRIENDS TAB =================
                item(key = "overview_body") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        if (currentMedia.genres.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                currentMedia.genres.forEach { genre ->
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = CrunchyCardBg
                                    ) {
                                        Text(
                                            text = genre,
                                            fontSize = 11.5.sp,
                                            color = Color.White,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier.clickable { isSynopsisExpanded = !isSynopsisExpanded },
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Synopsis", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            Text(
                                text = cleanDescription,
                                fontSize = 13.5.sp,
                                color = Color(0xFFD0D3DC),
                                lineHeight = 20.sp,
                                maxLines = if (isSynopsisExpanded) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isSynopsisExpanded) "Show less" else "Read more",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = CrunchyOrange
                            )
                        }

                        // Friends Watching Status
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Friends Watching", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                            if (isFriendsLoading) {
                                Text("Loading friends activity...", fontSize = 12.5.sp, color = MaruTextMuted)
                            } else if (friendsStatus.isEmpty()) {
                                Text("No recent friends activity for this anime.", fontSize = 12.5.sp, color = MaruTextMuted)
                            } else {
                                friendsStatus.take(6).forEach { friend ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onUserClick(friend.userName) }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        if (friend.userAvatar != null) {
                                            AsyncImage(
                                                model = friend.userAvatar,
                                                contentDescription = friend.userName,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(friend.userName, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            val prog = friend.progress?.takeIf { it.isNotBlank() && !it.equals("null", true) }
                                            Text(
                                                text = if (prog != null) "${friend.status} $prog" else friend.status,
                                                fontSize = 11.5.sp,
                                                color = CrunchyOrange
                                            )
                                        }
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaruTextMuted)
                                    }
                                }
                            }
                        }

                        // Similar & Related Anime
                        if (similarAnime.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("More Like This", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    similarAnime.forEach { sim ->
                                        PosterCard(
                                            media = sim,
                                            onClick = { onAnimeClick(sim) },
                                            modifier = Modifier.width(136.dp)
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
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CrunchyOrange)
                        }
                    }
                } else {
                    items(castList, key = { "char_${it.characterId}" }) { cast ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (cast.characterImage != null) {
                                    AsyncImage(
                                        model = cast.characterImage,
                                        contentDescription = cast.characterName,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(44.dp).clip(CircleShape)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cast.characterName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    if (!cast.role.isNullOrBlank()) {
                                        Text(cast.role, fontSize = 11.sp, color = CrunchyOrange, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                cast.japaneseVa?.let { va ->
                                    Surface(
                                        onClick = { openVoiceActorWorks(va) },
                                        shape = RoundedCornerShape(4.dp),
                                        color = CrunchyCardBg,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (va.image != null) {
                                                AsyncImage(model = va.image, contentDescription = va.name, contentScale = ContentScale.Crop, modifier = Modifier.size(30.dp).clip(CircleShape))
                                            }
                                            Column {
                                                Text("JAPANESE", fontSize = 9.sp, color = MaruTextMuted, fontWeight = FontWeight.Bold)
                                                Text(va.name, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                                cast.englishVa?.let { va ->
                                    Surface(
                                        onClick = { openVoiceActorWorks(va) },
                                        shape = RoundedCornerShape(4.dp),
                                        color = CrunchyCardBg,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            if (va.image != null) {
                                                AsyncImage(model = va.image, contentDescription = va.name, contentScale = ContentScale.Crop, modifier = Modifier.size(30.dp).clip(CircleShape))
                                            }
                                            Column {
                                                Text("ENGLISH", fontSize = 9.sp, color = CrunchyTeal, fontWeight = FontWeight.Bold)
                                                Text(va.name, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = Color(0xFF15161B), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }

        // Full-Page AniList Status & Native Scoring Editor (NO awkward bottom sheet drag!)
        AnimatedVisibility(visible = showEditPage, enter = fadeIn(tween(120)), exit = ExitTransition.None) {
            var selectedStatus by remember(currentMedia) { mutableStateOf(currentMedia.listStatus ?: "CURRENT") }
            var selectedProgress by remember(currentMedia) { mutableIntStateOf(currentMedia.progress) }
            var selectedScore by remember(currentMedia) { mutableStateOf(currentMedia.score ?: 0.0) }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = CrunchyBgBlack
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CrunchySurface)
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { showEditPage = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            Text("AniList Status & Rating", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        }
                        TextButton(
                            onClick = {
                                showEditPage = false
                                saveAniListEntry(
                                    newStatus = selectedStatus,
                                    newProgress = selectedProgress,
                                    newScore = selectedScore
                                )
                            }
                        ) {
                            Text("SAVE", color = CrunchyOrange, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                        }
                    }

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        contentPadding = PaddingValues(vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        // Watch Status Picker
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("WATCHLIST STATUS", fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = CrunchyOrange, letterSpacing = 1.sp)
                                val statuses = listOf(
                                    "CURRENT" to "Watching",
                                    "PLANNING" to "Plan to Watch",
                                    "COMPLETED" to "Completed",
                                    "PAUSED" to "Paused",
                                    "DROPPED" to "Dropped",
                                    "REPEATING" to "Rewatching"
                                )
                                statuses.chunked(2).forEach { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        rowItems.forEach { (key, label) ->
                                            val isSel = selectedStatus == key
                                            Surface(
                                                onClick = { selectedStatus = key },
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isSel) CrunchyOrange else CrunchyCardBg,
                                                border = BorderStroke(1.dp, if (isSel) CrunchyOrange else CrunchyBorder),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Box(modifier = Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = label,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        color = if (isSel) Color.Black else Color.White
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Episode Progress Stepper
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("EPISODE PROGRESS", fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = CrunchyOrange, letterSpacing = 1.sp)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Surface(
                                        onClick = { selectedProgress = (selectedProgress - 1).coerceAtLeast(0) },
                                        shape = CircleShape,
                                        color = CrunchyCardBg,
                                        border = BorderStroke(1.dp, CrunchyBorder),
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Remove, contentDescription = "Minus", tint = Color.White)
                                        }
                                    }

                                    Text(
                                        text = "Episode $selectedProgress / ${currentMedia.episodes ?: "?"}",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )

                                    Surface(
                                        onClick = { selectedProgress = (selectedProgress + 1).coerceAtMost(currentMedia.episodes ?: 9999) },
                                        shape = CircleShape,
                                        color = CrunchyOrange,
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Add, contentDescription = "Plus", tint = Color.Black)
                                        }
                                    }
                                }
                            }
                        }

                        // Native AniList Scoring System Picker (Detects user's exact AniList scoreFormat!)
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val fmtTitle = when (userScoreFormat) {
                                    "POINT_5" -> "YOUR SCORE (5-STAR SYSTEM)"
                                    "POINT_3" -> "YOUR SCORE (3-SMILEY SYSTEM)"
                                    "POINT_10" -> "YOUR SCORE (10-POINT SYSTEM)"
                                    "POINT_10_DECIMAL" -> "YOUR SCORE (10-POINT DECIMAL)"
                                    else -> "YOUR SCORE (100-POINT SYSTEM)"
                                }
                                Text(fmtTitle, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = CrunchyOrange, letterSpacing = 1.sp)

                                when (userScoreFormat) {
                                    "POINT_5" -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly
                                        ) {
                                            (1..5).forEach { star ->
                                                val filled = selectedScore >= star
                                                IconButton(
                                                    onClick = { selectedScore = star.toDouble() },
                                                    modifier = Modifier.size(52.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = if (filled) Icons.Default.Star else Icons.Default.StarBorder,
                                                        contentDescription = "$star Stars",
                                                        tint = if (filled) CrunchyGold else MaruTextMuted,
                                                        modifier = Modifier.size(38.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    "POINT_3" -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            listOf(1.0 to "😞 Bad", 2.0 to "😐 Okay", 3.0 to "😊 Great").forEach { (valScore, label) ->
                                                val sel = selectedScore == valScore
                                                Surface(
                                                    onClick = { selectedScore = valScore },
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = if (sel) CrunchyOrange else CrunchyCardBg,
                                                    border = BorderStroke(1.dp, if (sel) CrunchyOrange else CrunchyBorder),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Box(modifier = Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                                                        Text(label, color = if (sel) Color.Black else Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    "POINT_10", "POINT_10_DECIMAL" -> {
                                        val step = if (userScoreFormat == "POINT_10_DECIMAL") 0.5 else 1.0
                                        Text(
                                            text = if (selectedScore > 0) String.format("%.1f / 10", selectedScore) else "Not Rated",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = CrunchyGold
                                        )
                                        Slider(
                                            value = selectedScore.toFloat().coerceIn(0f, 10f),
                                            onValueChange = { raw ->
                                                selectedScore = (Math.round(raw / step) * step)
                                            },
                                            valueRange = 0f..10f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = CrunchyOrange,
                                                activeTrackColor = CrunchyOrange,
                                                inactiveTrackColor = CrunchyBorder
                                            )
                                        )
                                    }
                                    else -> {
                                        Text(
                                            text = if (selectedScore > 0) "${selectedScore.toInt()} / 100" else "Not Rated",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = CrunchyGold
                                        )
                                        Slider(
                                            value = selectedScore.toFloat().coerceIn(0f, 100f),
                                            onValueChange = { selectedScore = it.toInt().toDouble() },
                                            valueRange = 0f..100f,
                                            colors = SliderDefaults.colors(
                                                thumbColor = CrunchyOrange,
                                                activeTrackColor = CrunchyOrange,
                                                inactiveTrackColor = CrunchyBorder
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Button(
                                onClick = {
                                    showEditPage = false
                                    saveAniListEntry(
                                        newStatus = selectedStatus,
                                        newProgress = selectedProgress,
                                        newScore = selectedScore
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange)
                            ) {
                                Text("SAVE TO ANILIST", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        // Voice Actor Other Works Full-Page Overlay
        if (activeVoiceActor != null) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = CrunchyBgBlack
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CrunchySurface)
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { activeVoiceActor = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Text(activeVoiceActor!!.name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                    }
                    if (isStaffLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = CrunchyOrange)
                        }
                    } else {
                        val works = activeStaffWorks?.works ?: emptyList()
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(140.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            contentPadding = PaddingValues(vertical = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            items(works.size) { idx ->
                                val w = works[idx]
                                PosterCard(
                                    media = w,
                                    onClick = {
                                        activeVoiceActor = null
                                        onAnimeClick(w)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatScoreDisplay(score: Double, format: String): String {
    return when (format) {
        "POINT_5" -> "★ ${score.toInt()}/5"
        "POINT_3" -> when (score.toInt()) {
            3 -> "😊"
            2 -> "😐"
            else -> "😞"
        }
        "POINT_10" -> "★ ${score.toInt()}/10"
        "POINT_10_DECIMAL" -> "★ ${String.format("%.1f", score)}/10"
        else -> "★ ${score.toInt()}%"
    }
}
