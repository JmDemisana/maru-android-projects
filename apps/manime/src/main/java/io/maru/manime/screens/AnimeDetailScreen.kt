package io.maru.manime.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.*
import io.maru.manime.extensions.AnimeIdMappings
import io.maru.manime.extensions.CrunchyEpisodeMeta
import io.maru.manime.extensions.CrunchyrollStreamEngine
import io.maru.manime.extensions.StreamDubStatus
import io.maru.manime.player.PlayerScreen
import io.maru.manime.torrent.OfflineEpisodeEntry
import io.maru.manime.torrent.TorrentStreamService
import io.maru.manime.torrent.selectDownloadCandidates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetailScreen(
    media: AnimeMedia,
    anilistToken: String,
    anilistUsername: String = "",
    prefs: MAnimePrefs,
    isActive: Boolean = true,
    onBack: () -> Unit,
    onMediaUpdated: (AnimeMedia) -> Unit = {},
    onAnimeClick: (AnimeMedia) -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onLoginClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val localEntries by LocalLibraryStore.localEntries.collectAsState()
    val pendingQueue by LocalLibraryStore.pendingQueue.collectAsState()
    val knownDubIds by KnownDubRegistry.dubbedIds.collectAsState()

    var currentMedia by remember(media.mediaId, anilistToken, anilistUsername) {
        val savedLocal = localEntries[media.mediaId]
        val queued = if (anilistToken.isBlank()) null else pendingQueue.firstOrNull {
            it.mediaId == media.mediaId &&
                (it.accountUsername.isBlank() || it.accountUsername.equals(anilistUsername, ignoreCase = true))
        }
        val initial = if (anilistToken.isBlank()) {
            if (savedLocal != null) {
                media.copy(
                    listStatus = savedLocal.listStatus,
                    progress = savedLocal.progress,
                    score = savedLocal.score,
                    updatedAt = savedLocal.updatedAt,
                    hasEnglishDubCast = media.hasEnglishDubCast || savedLocal.hasEnglishDubCast
                )
            } else {
                media.copy(
                    listEntryId = null,
                    listStatus = null,
                    progress = 0,
                    score = null,
                    notes = null,
                    isPrivate = false,
                    updatedAt = null
                )
            }
        } else if (queued != null) {
            val baseProg = maxOf(media.progress, savedLocal?.progress ?: 0)
            media.copy(
                listStatus = queued.status ?: media.listStatus ?: savedLocal?.listStatus,
                progress = if (queued.isWatchedAutoBump) maxOf(baseProg, queued.progress ?: baseProg)
                else (queued.progress ?: baseProg),
                score = queued.score ?: media.score ?: savedLocal?.score
            )
        } else {
            media
        }
        mutableStateOf(initial)
    }

    LaunchedEffect(localEntries[media.mediaId], anilistToken) {
        if (anilistToken.isBlank()) {
            val savedLocal = localEntries[media.mediaId]
            if (savedLocal != null) {
                currentMedia = currentMedia.copy(
                    listStatus = savedLocal.listStatus,
                    progress = savedLocal.progress,
                    score = savedLocal.score,
                    updatedAt = savedLocal.updatedAt
                )
            }
        }
    }

    var isEntryLoading by remember(media.mediaId, anilistToken) { mutableStateOf(true) }
    var entryRetry by remember { mutableIntStateOf(0) }
    LaunchedEffect(media.mediaId, anilistToken, anilistUsername, entryRetry) {
        isEntryLoading = true
        try {
            val detail = withContext(Dispatchers.IO) {
                AniListClient.getMediaDetail(media.mediaId, anilistToken.ifBlank { null })
            }
            val queued = if (anilistToken.isBlank()) null else pendingQueue.firstOrNull {
                it.mediaId == media.mediaId &&
                    (it.accountUsername.isBlank() || it.accountUsername.equals(anilistUsername, ignoreCase = true))
            }
            val guestLocal = if (anilistToken.isBlank()) localEntries[media.mediaId] else null

            currentMedia = detail.copy(
                hasEnglishDubCast = detail.hasEnglishDubCast || media.hasEnglishDubCast,
                listStatus = when {
                    queued != null && !queued.isWatchedAutoBump -> queued.status ?: detail.listStatus
                    guestLocal != null -> guestLocal.listStatus
                    else -> detail.listStatus
                },
                progress = when {
                    queued != null && queued.isWatchedAutoBump -> maxOf(detail.progress, queued.progress ?: 0)
                    queued != null -> queued.progress ?: detail.progress
                    guestLocal != null -> guestLocal.progress
                    else -> detail.progress
                },
                score = when {
                    queued != null && queued.score != null -> queued.score
                    guestLocal != null -> guestLocal.score
                    else -> detail.score
                }
            )
            if (currentMedia.hasEnglishDubCast) {
                KnownDubRegistry.recordKnownDub(currentMedia.mediaId, context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            isEntryLoading = false
        }
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
                    isMovie = anime.format == "MOVIE",
                    altTitles = anime.candidateTitlesForStream,
                    isStandalone = anime.isStandaloneFeature,
                    isAdult = anime.isAdult,
                    genres = anime.genres,
                    isMusicOrConcert = anime.isMusicOrConcert,
                    trailerYoutubeId = anime.trailerYoutubeId,
                    externalLinks = anime.externalLinks
                )
                val candidates = selectDownloadCandidates(streams, audioMode)
                if (candidates.isEmpty()) {
                    val audioLabel = if (audioMode == "DUB") "English dub" else "playable"
                    Toast.makeText(context, "No $audioLabel download source found right now.", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val primary = candidates.first()
                TorrentStreamService.startOfflineDownload(
                    context = context,
                    magnetUri = primary.url,
                    fileIdx = primary.fileIdx,
                    mediaId = anime.mediaId,
                    animeTitle = anime.title,
                    episodeNum = episodeNum,
                    episodeTitle = episodeTitle,
                    coverUrl = anime.coverUrl,
                    thumbnailUrl = episode.thumbnail ?: anime.bannerUrl ?: anime.coverUrl,
                    audioType = primary.audioType,
                    quality = primary.quality,
                    candidates = candidates
                )
                val label = if (anime.isStandaloneFeature) anime.title else "Episode $episodeNum"
                Toast.makeText(context, "Downloading $label privately inside the app…", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, "Could not start the download. Try again.", Toast.LENGTH_SHORT).show()
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

    LaunchedEffect(similarAnime) {
        if (similarAnime.isNotEmpty()) {
            KnownDubRegistry.verifyCandidatesInBackground(context, similarAnime, maxProbes = 8)
        }
    }

    val isPlaybackOpen = activePlayingEp != null
    val hasListedDub = currentMedia.hasEnglishDub ||
        castList.any { it.englishVa != null } ||
        currentMedia.mediaId in knownDubIds

    LaunchedEffect(media.mediaId, isActive, isPlaybackOpen, hasListedDub) {
        if (hasListedDub) {
            KnownDubRegistry.recordKnownDub(media.mediaId, context)
            return@LaunchedEffect
        }
        if (!isActive || isPlaybackOpen || streamDubStatus.hasStreamDubOrDual) return@LaunchedEffect
        try {
            val detected = CrunchyrollStreamEngine.checkStreamDubAvailability(
                anilistId = media.mediaId,
                animeTitle = media.title,
                episodeNum = 1,
                isMovie = media.format == "MOVIE"
            )
            streamDubStatus = detected
            if (detected.hasStreamDubOrDual) {
                KnownDubRegistry.recordKnownDub(media.mediaId, context)
            }
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

    val completeEpisodes = remember(animeMappings, streamingEpisodes, currentMedia.episodes, latestAiredEp, hasUncertainReleaseCount) {
        CrunchyrollStreamEngine.buildCompleteEpisodeList(
            mappings = animeMappings,
            anilistStreamingEps = streamingEpisodes,
            totalEpisodes = currentMedia.episodes,
            latestAired = latestAiredEp,
            isReleasing = hasUncertainReleaseCount
        )
    }

    val isSingleStandalone = currentMedia.isStandaloneFeature && completeEpisodes.size <= 1
    val availableTabs = if (isSingleStandalone) listOf("Overview", "Cast") else listOf("Episodes", "Overview", "Cast")
    var selectedTabIndex by rememberSaveable(currentMedia.mediaId, isSingleStandalone) { mutableIntStateOf(0) }
    val safeTabIndex = selectedTabIndex.coerceIn(availableTabs.indices)
    val detailScroll = rememberLazyListState()

    var selectedEpRangeIndex by rememberSaveable { mutableIntStateOf(0) }
    val episodeChunkSize = 50
    val episodeChunks = remember(completeEpisodes) {
        if (completeEpisodes.isEmpty()) listOf(emptyList())
        else completeEpisodes.chunked(episodeChunkSize)
    }
    val selectedRange = selectedEpRangeIndex.coerceIn(episodeChunks.indices)
    val visibleEpisodes = episodeChunks[selectedRange]

    val hasAnyDub = hasListedDub || streamDubStatus.hasStreamDubOrDual || currentMedia.mediaId in knownDubIds
    val advisory = remember(currentMedia.isAdult, currentMedia.genres, currentMedia.tags) {
        currentMedia.contentAdvisory
    }

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

    fun saveEntry(newStatus: String?, newProgress: Int?, newScore: Double?) {
        if (isUpdatingList) return
        val safeProgress = (newProgress ?: currentMedia.progress).coerceIn(0, currentMedia.episodes ?: 9999)
        val resolvedStatus = (newStatus ?: currentMedia.listStatus ?: "CURRENT").uppercase()
        val previous = currentMedia
        isUpdatingList = true

        scope.launch {
            try {
                val localSaved = LocalLibraryStore.saveLocalEntry(
                    context = context,
                    media = previous,
                    status = resolvedStatus,
                    progress = safeProgress,
                    score = newScore
                )
                currentMedia = localSaved

                if (anilistToken.isBlank()) {
                    onMediaUpdated(localSaved)
                    Toast.makeText(
                        context,
                        "Saved to My Lists (${resolvedStatus.lowercase().replaceFirstChar { it.uppercase() }})",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
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
                        val merged = updated.copy(
                            hasEnglishDubCast = updated.hasEnglishDubCast || previous.hasEnglishDubCast
                        )
                        currentMedia = merged
                        LocalLibraryStore.saveLocalEntry(
                            context = context,
                            media = merged,
                            status = merged.listStatus,
                            progress = merged.progress,
                            score = merged.score
                        )
                        onMediaUpdated(merged)
                        Toast.makeText(
                            context,
                            "Updated AniList: ${resolvedStatus.lowercase().replaceFirstChar { it.uppercase() }} (Ep $safeProgress)",
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        LocalLibraryStore.enqueueAniListUpdate(
                            context = context,
                            update = PendingAniListUpdate(
                                mediaId = previous.mediaId,
                                title = previous.title,
                                status = resolvedStatus,
                                progress = safeProgress,
                                score = newScore,
                                totalEpisodes = previous.episodes,
                                isWatchedAutoBump = false,
                                accountUsername = anilistUsername
                            )
                        )
                        onMediaUpdated(localSaved)
                        Toast.makeText(
                            context,
                            "Saved offline • Will sync to AniList when online",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } finally {
                isUpdatingList = false
            }
        }
    }

    fun removeFromList() {
        if (isUpdatingList) return
        val previous = currentMedia
        isUpdatingList = true
        scope.launch {
            try {
                LocalLibraryStore.removeLocalEntry(context, previous.mediaId)
                if (anilistToken.isNotBlank() && previous.listEntryId != null) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            AniListClient.deleteEntry(previous.listEntryId, anilistToken)
                        }
                    }
                }
                val cleared = previous.copy(
                    listEntryId = null,
                    listStatus = null,
                    progress = 0,
                    score = null
                )
                currentMedia = cleared
                onMediaUpdated(cleared)
                Toast.makeText(context, "Removed from My Lists.", Toast.LENGTH_SHORT).show()
            } finally {
                isUpdatingList = false
            }
        }
    }

    if (activePlayingEp != null) {
        val ep = activePlayingEp!!
        val lastPlayable = completeEpisodes.lastOrNull()?.episodeNumber ?: latestAiredEp ?: ep.episodeNumber
        PlayerScreen(
            media = currentMedia,
            episodeNum = ep.episodeNumber,
            episodeTitle = ep.title,
            initialAudioMode = if (hasAnyDub) selectedAudioMode else "SUB",
            hasEnglishDub = hasAnyDub,
            prefs = prefs,
            onEpisodeWatched = { watchedNum ->
                if (watchedNum > currentMedia.progress) {
                    currentMedia = currentMedia.copy(
                        progress = watchedNum,
                        listStatus = currentMedia.listStatus ?: "CURRENT"
                    )
                }
                if (anilistToken.isBlank() || reportProgress) {
                    onMediaUpdated(currentMedia)
                }
            },
            onSwitchEpisode = { targetNum ->
                completeEpisodes.firstOrNull { it.episodeNumber == targetNum }?.let { activePlayingEp = it }
            },
            hasPrevEpisode = !isSingleStandalone && ep.episodeNumber > 1,
            hasNextEpisode = !isSingleStandalone && ep.episodeNumber < lastPlayable,
            lastPlayableEpisode = lastPlayable,
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
        // Keep LazyColumn mounted underneath overlays so scroll position never resets!
        LazyColumn(
            state = detailScroll,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 44.dp)
        ) {
            // 1. Hero Banner + Wrapped Title + Crunchyroll Content Advisory
            item(key = "header") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (compact) 232.dp else 274.dp)
                ) {
                    CrossfadeImage(
                        model = currentMedia.bannerUrl ?: currentMedia.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .matchParentSize()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
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
                            .background(Color(0x99000000), CircleShape)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        CrossfadeImage(
                            model = currentMedia.coverUrl,
                            contentDescription = currentMedia.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .width(if (compact) 88.dp else 102.dp)
                                .aspectRatio(2f / 3f)
                                .clip(RoundedCornerShape(4.dp))
                        )

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = currentMedia.title,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 18.sp,
                                color = Color.White,
                                lineHeight = 22.sp
                            )

                            val metaPieces = buildList {
                                if (hasAnyDub) add(if (streamDubStatus.hasStreamDubOrDual && !currentMedia.hasEnglishDubCast) "Dual Audio" else "Dub | Sub")
                                else add("Subtitled")
                                if (currentMedia.averageScore != null && currentMedia.averageScore!! > 0) add("★ ${currentMedia.averageScore}%")
                                if (!isSingleStandalone) {
                                    if (isReleasing && latestAiredEp != null) add("$latestAiredEp Released")
                                    else if (currentMedia.episodes != null) add("${currentMedia.episodes} Eps")
                                }
                                if (!currentMedia.format.isNullOrBlank()) add(currentMedia.format!!)
                            }
                            Text(
                                text = metaPieces.joinToString("  •  "),
                                fontSize = 12.sp,
                                color = MaruTextMuted,
                                fontWeight = FontWeight.Medium,
                                lineHeight = 16.sp
                            )

                            if (currentMedia.listStatus != null) {
                                val scoreStr = currentMedia.score?.let { formatScoreDisplay(it, userScoreFormat) }
                                Text(
                                    text = buildString {
                                        append(formatListStatusName(currentMedia.listStatus!!))
                                        if (!isSingleStandalone || currentMedia.progress > 0) {
                                            append(" • Ep ${currentMedia.progress}")
                                        }
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

            // 2. Primary CTA Row: [Play] + [My List] + [1-Tap Offline Download for Standalone Features]
            item(key = "crunchy_cta_bar") {
                val seasonRelations = remember(similarAnime) {
                    similarAnime.filter {
                        it.relationType == "PREQUEL" || it.relationType == "SEQUEL" || it.relationType == "SIDE_STORY"
                    }
                }

                val standaloneEp = remember(currentMedia, completeEpisodes, isSingleStandalone) {
                    if (!isSingleStandalone) null
                    else completeEpisodes.firstOrNull() ?: if (!currentMedia.status.equals("NOT_YET_RELEASED", true)) {
                        CrunchyEpisodeMeta(
                            episodeNumber = 1,
                            title = currentMedia.title,
                            overview = cleanDescription,
                            thumbnail = currentMedia.bannerUrl ?: currentMedia.coverUrl
                        )
                    } else null
                }

                val nextEpNum = (currentMedia.progress + 1).coerceAtMost(completeEpisodes.size.coerceAtLeast(1))
                val targetEpMeta = if (isSingleStandalone) {
                    standaloneEp
                } else {
                    completeEpisodes.firstOrNull { it.episodeNumber == nextEpNum } ?: completeEpisodes.firstOrNull()
                }

                val standaloneOfflineEntry = if (isSingleStandalone) {
                    offlineVaultList.firstOrNull { it.mediaId == currentMedia.mediaId && it.episodeNumber == 1 }
                } else null
                val standalonePending = pendingOfflineActions.containsKey(currentMedia.mediaId to 1)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { activePlayingEp = targetEpMeta },
                            enabled = targetEpMeta != null,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CrunchyOrange,
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            val btnText = when {
                                isSingleStandalone && targetEpMeta == null -> "Not Released Yet"
                                isSingleStandalone && (currentMedia.isMusicOrConcert || currentMedia.format.equals("MUSIC", true)) ->
                                    if (currentMedia.progress > 0) "Watch Again" else "Play Music Video"
                                isSingleStandalone && currentMedia.format.equals("MOVIE", true) ->
                                    if (currentMedia.progress > 0) "Watch Movie Again" else "Play Movie"
                                isSingleStandalone -> "Play Special"
                                targetEpMeta == null && isEpisodeInfoLoading -> "Loading episodes…"
                                targetEpMeta == null -> "No episodes released yet"
                                currentMedia.progress > 0 -> "Continue · E${targetEpMeta.episodeNumber}"
                                else -> "Start Watching E${targetEpMeta.episodeNumber}"
                            }
                            Text(
                                text = btnText,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        // Standalone 1-Tap Offline Download button
                        if (isSingleStandalone && standaloneEp != null) {
                            Surface(
                                onClick = {
                                    if (!standalonePending) {
                                        onOfflineAction(
                                            standaloneEp,
                                            currentMedia.title,
                                            standaloneOfflineEntry,
                                            if (hasAnyDub) selectedAudioMode else "SUB"
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(4.dp),
                                color = if (standaloneOfflineEntry?.isCompleted == true) CrunchyTeal.copy(alpha = 0.16f) else CrunchyCardBg,
                                border = BorderStroke(
                                    1.dp,
                                    if (standaloneOfflineEntry?.isCompleted == true) CrunchyTeal else CrunchyBorder
                                ),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    when {
                                        standalonePending -> CircularProgressIndicator(
                                            color = CrunchyOrange,
                                            strokeWidth = 2.dp,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        standaloneOfflineEntry?.isCompleted == true ->
                                            Icon(Icons.Default.DownloadDone, contentDescription = "Downloaded", tint = CrunchyTeal)
                                        standaloneOfflineEntry?.failure != null ->
                                            Icon(Icons.Default.Refresh, contentDescription = "Retry download", tint = CrunchyOrange)
                                        standaloneOfflineEntry != null ->
                                            Icon(Icons.Default.Close, contentDescription = "Cancel download", tint = CrunchyOrange)
                                        else ->
                                            Icon(Icons.Default.Download, contentDescription = "Download offline", tint = Color.White)
                                    }
                                }
                            }
                        }

                        // My List / AniList Status & Score Button (Works both Logged In AND Guest!)
                        Surface(
                            onClick = {
                                if (!isUpdatingList) showEditPage = true
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
                                    contentDescription = "My List",
                                    tint = CrunchyOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = currentMedia.listStatus?.let { formatListStatusName(it) } ?: "My List",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    if (isSingleStandalone && standaloneOfflineEntry != null && !standaloneOfflineEntry.isCompleted) {
                        val dlText = standaloneOfflineEntry.failure
                            ?: standaloneOfflineEntry.statusText
                            ?: "Downloading ${standaloneOfflineEntry.progressPercent}%"
                        Text(
                            text = dlText,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (standaloneOfflineEntry.failure != null) Color(0xFFFF8A80) else CrunchyOrange
                        )
                    }

                    // Audio Language Selector (Japanese Sub vs English Dub / Dual Audio)
                    if (hasAnyDub) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val dubLabel = if (streamDubStatus.hasStreamDubOrDual && !currentMedia.hasEnglishDubCast) {
                                "Dub / Dual Audio"
                            } else {
                                "English Dub"
                            }
                            listOf("SUB" to "Japanese (Sub)", "DUB" to dubLabel).forEach { (mode, label) ->
                                val isSelected = selectedAudioMode == mode
                                val bgColor by animateColorAsState(
                                    targetValue = if (isSelected) Color(0xFF1F2128) else Color.Transparent,
                                    animationSpec = tween(150),
                                    label = "audio_chip_bg"
                                )
                                Surface(
                                    onClick = {
                                        selectedAudioMode = mode
                                        scope.launch { prefs.setPreferredAudio(mode) }
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    color = bgColor,
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
                                    else -> "SPECIAL"
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
                                        Text(rel.title, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Crunchyroll Underline Navigation Tabs
            item(key = "underline_tabs") {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .selectableGroup()
                    ) {
                        availableTabs.forEachIndexed { idx, title ->
                            val isSelected = safeTabIndex == idx
                            val label = if (!isSingleStandalone && idx == 0) "$title (${completeEpisodes.size})" else title
                            val tabColor by animateColorAsState(
                                targetValue = if (isSelected) Color.White else MaruTextMuted,
                                animationSpec = tween(160),
                                label = "detail_tab_text"
                            )
                            val indColor by animateColorAsState(
                                targetValue = if (isSelected) CrunchyOrange else Color.Transparent,
                                animationSpec = tween(180),
                                label = "detail_tab_bar"
                            )

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .selectable(selected = isSelected, role = Role.Tab, onClick = { selectedTabIndex = idx })
                                    .padding(top = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    color = tabColor,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .background(indColor, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)
                }
            }

            val activeTabName = availableTabs[safeTabIndex]
            when (activeTabName) {
                "Episodes" -> {
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
                                        color = if (isRangeSelected) CrunchyOrange else CrunchyCardBg,
                                        border = BorderStroke(1.dp, if (isRangeSelected) CrunchyOrange else CrunchyBorder)
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
                                    Text("No released episodes available yet.", color = MaruTextMuted, fontSize = 13.5.sp)
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
                                offlineActionPending -> "Finding download source…"
                                offlineEntry == null -> null
                                offlineEntry.isCompleted -> "Downloaded • Ready offline"
                                offlineEntry.failure != null -> offlineEntry.failure
                                !offlineEntry.statusText.isNullOrBlank() -> offlineEntry.statusText
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
                                    Box(
                                        modifier = Modifier
                                            .width(138.dp)
                                            .aspectRatio(16f / 9f)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(CrunchyCardBg),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CrossfadeImage(
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

                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "S${streamEp.seasonNumber} E$episodeNum  •  ${if (hasAnyDub) "Dub | Sub" else "Subtitled"}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isWatched) CrunchyTeal else CrunchyOrange
                                        )

                                        Text(
                                            text = displayTitle,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
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
                                                color = when {
                                                    offlineEntry?.isCompleted == true -> CrunchyTeal
                                                    offlineEntry?.failure != null -> Color(0xFFFF8A80)
                                                    else -> CrunchyOrange
                                                }
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
                }

                "Overview" -> {
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
                                            color = CrunchyCardBg,
                                            border = BorderStroke(1.dp, CrunchyBorder)
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

                            // Explicit Crunchyroll Content Advisory & Age Rating card
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Content Advisory",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    CrunchyAdvisoryRow(advisory = advisory)
                                    if (advisory.warnings.isEmpty()) {
                                        Text(
                                            text = "Suitable for general audiences.",
                                            fontSize = 11.5.sp,
                                            color = MaruTextMuted
                                        )
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier
                                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                                    .clickable { isSynopsisExpanded = !isSynopsisExpanded },
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

                            if (anilistToken.isNotBlank()) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("Friends Watching", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                                    if (isFriendsLoading) {
                                        Text("Loading friends activity…", fontSize = 12.5.sp, color = MaruTextMuted)
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
                                                    CrossfadeImage(
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
                            }

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
                }

                else -> {
                    if (isCastLoading) {
                        item(key = "cast_loading") {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = CrunchyOrange)
                            }
                        }
                    } else if (castList.isEmpty()) {
                        item(key = "cast_empty") {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                Text("No voice cast listed yet.", color = MaruTextMuted, fontSize = 13.sp)
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
                                        CrossfadeImage(
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
                                                    CrossfadeImage(
                                                        model = va.image,
                                                        contentDescription = va.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.size(30.dp).clip(CircleShape)
                                                    )
                                                }
                                                Column {
                                                    Text("JAPANESE", fontSize = 9.sp, color = MaruTextMuted, fontWeight = FontWeight.Bold)
                                                    Text(va.name, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
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
                                                    CrossfadeImage(
                                                        model = va.image,
                                                        contentDescription = va.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.size(30.dp).clip(CircleShape)
                                                    )
                                                }
                                                Column {
                                                    Text("ENGLISH", fontSize = 9.sp, color = CrunchyTeal, fontWeight = FontWeight.Bold)
                                                    Text(va.name, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
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
        }

        // Animated Watchlist & Scoring Editor Overlay (Works in Guest Mode & Offline Queue!)
        AnimatedVisibility(
            visible = showEditPage,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 10 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { it / 10 }
        ) {
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
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            IconButton(onClick = { showEditPage = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            Column {
                                Text(
                                    text = if (anilistToken.isNotBlank()) "AniList Status & Rating" else "My List & Rating",
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.5.sp
                                )
                                Text(
                                    text = currentMedia.title,
                                    color = MaruTextMuted,
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                        TextButton(
                            onClick = {
                                showEditPage = false
                                saveEntry(
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
                        if (anilistToken.isBlank()) {
                            item {
                                GlassCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Saved locally on this device. Connect AniList anytime to sync across devices.",
                                            color = MaruTextMuted,
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = onLoginClick) {
                                            Text("Connect", color = CrunchyOrange, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

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
                                            Icon(Icons.Default.Remove, contentDescription = "Decrease episode", tint = Color.White)
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
                                            Icon(Icons.Default.Add, contentDescription = "Increase episode", tint = Color.Black)
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val fmtTitle = when (userScoreFormat) {
                                        "POINT_5" -> "YOUR SCORE (5-STAR SYSTEM)"
                                        "POINT_3" -> "YOUR SCORE (3-SMILEY SYSTEM)"
                                        "POINT_10" -> "YOUR SCORE (10-POINT SYSTEM)"
                                        "POINT_10_DECIMAL" -> "YOUR SCORE (10-POINT DECIMAL)"
                                        else -> "YOUR SCORE (100-POINT SYSTEM)"
                                    }
                                    Text(fmtTitle, fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold, color = CrunchyOrange, letterSpacing = 1.sp)
                                    if (selectedScore > 0.0) {
                                        TextButton(onClick = { selectedScore = 0.0 }) {
                                            Text("Clear Rating", color = MaruTextMuted, fontSize = 12.sp)
                                        }
                                    }
                                }

                                when (userScoreFormat) {
                                    "POINT_5" -> {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly
                                        ) {
                                            (1..5).forEach { star ->
                                                val filled = selectedScore >= star
                                                IconButton(
                                                    onClick = { selectedScore = if (selectedScore == star.toDouble()) 0.0 else star.toDouble() },
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
                                            listOf(1.0 to "Bad", 2.0 to "Okay", 3.0 to "Great").forEach { (valScore, label) ->
                                                val sel = selectedScore == valScore
                                                Surface(
                                                    onClick = { selectedScore = if (sel) 0.0 else valScore },
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
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        showEditPage = false
                                        saveEntry(
                                            newStatus = selectedStatus,
                                            newProgress = selectedProgress,
                                            newScore = selectedScore
                                        )
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange, contentColor = Color.Black)
                                ) {
                                    Text(
                                        text = if (anilistToken.isNotBlank()) "SAVE TO ANILIST" else "SAVE TO MY LIST",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 14.sp
                                    )
                                }

                                if (currentMedia.listStatus != null) {
                                    OutlinedButton(
                                        onClick = {
                                            showEditPage = false
                                            removeFromList()
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp),
                                        shape = RoundedCornerShape(4.dp),
                                        border = BorderStroke(1.dp, MaruDanger)
                                    ) {
                                        Text("Remove From My List", color = MaruDanger, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Animated Voice Actor Filmography Overlay
        AnimatedVisibility(
            visible = activeVoiceActor != null,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 10 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { it / 10 }
        ) {
            val actor = activeVoiceActor
            if (actor != null) {
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
                            Text(actor.name, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        }
                        if (isStaffLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = CrunchyOrange)
                            }
                        } else {
                            val works = activeStaffWorks?.works ?: emptyList()
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(140.dp),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp),
                                contentPadding = PaddingValues(vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp)
                            ) {
                                items(works, key = { "va_work_${it.mediaId}" }) { w ->
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
}

private fun formatListStatusName(status: String): String = when (status.uppercase()) {
    "CURRENT" -> "Watching"
    "PLANNING" -> "Planning"
    "COMPLETED" -> "Completed"
    "PAUSED" -> "Paused"
    "DROPPED" -> "Dropped"
    "REPEATING" -> "Rewatching"
    else -> status.lowercase().replaceFirstChar { it.uppercase() }
}

private fun formatScoreDisplay(score: Double, format: String): String {
    return when (format) {
        "POINT_5" -> "★ ${score.toInt()}/5"
        "POINT_3" -> when (score.toInt()) {
            3 -> "Great"
            2 -> "Okay"
            else -> "Bad"
        }
        "POINT_10" -> "★ ${score.toInt()}/10"
        "POINT_10_DECIMAL" -> "★ ${String.format("%.1f", score)}/10"
        else -> "★ ${score.toInt()}%"
    }
}
