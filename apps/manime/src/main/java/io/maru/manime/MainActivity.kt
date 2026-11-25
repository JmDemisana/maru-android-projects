package io.maru.manime

import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.maru.manime.screens.*
import io.maru.manime.torrent.TorrentStreamService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class NavigationScreen(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
) {
    DASHBOARD("Home", "Featured & Simulcasts", Icons.Default.Home),
    PROFILE("My Lists", "Downloads & Watchlists", Icons.Default.BookmarkBorder),
    SEARCH("Search", "Search & Browse Categories", Icons.Default.Search),
    SETTINGS("Account", "Accounts, Sync & Preferences", Icons.Default.PersonOutline)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val prefs = remember { MAnimePrefs(applicationContext) }
            MaterialTheme(colorScheme = NanamiColors) {
                AppNavigation(prefs)
            }
        }
    }
}

private val NanamiColors = darkColorScheme(
    primary = CrunchyOrange,
    onPrimary = Color.Black,
    secondary = CrunchyTeal,
    background = CrunchyBgBlack,
    surface = CrunchySurface,
    surfaceVariant = CrunchyCardBg,
    onSurface = Color.White,
    onSurfaceVariant = MaruTextMuted,
    outline = CrunchyBorder
)

private sealed interface ScreenRoute {
    data class Tab(val screen: NavigationScreen) : ScreenRoute
    data class Anime(val media: AnimeMedia, val id: Int) : ScreenRoute
    data class User(val username: String, val id: Int) : ScreenRoute

    val stateKey: String
        get() = when (this) {
            is Tab -> screen.name
            is Anime -> "anime_$id"
            is User -> "user_$id"
        }

    val depth: Int
        get() = when (this) {
            is Tab -> 0
            is Anime -> 1 + id
            is User -> 1 + id
        }
}

private suspend fun <T> loadAniList(request: suspend () -> T): Result<T> {
    return try {
        Result.success(withContext(Dispatchers.IO) { request() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(prefs: MAnimePrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val session by prefs.anilistSession.collectAsStateWithLifecycle(initialValue = null)
    val savedAccounts by prefs.savedAnilistAccounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val trackingAccounts by prefs.trackingAccounts.collectAsStateWithLifecycle(initialValue = AniListTrackingAccounts())
    val anilistToken = session?.token.orEmpty()
    val anilistUsername = session?.username.orEmpty()
    val scoreFormat by prefs.anilistScoreFormat.collectAsStateWithLifecycle(initialValue = "POINT_100")
    val reportProgress by prefs.reportProgress.collectAsStateWithLifecycle(initialValue = true)
    val trackWatchedPercent by prefs.trackWatchedPercent.collectAsStateWithLifecycle(initialValue = 80)
    val rememberPosition by prefs.rememberPosition.collectAsStateWithLifecycle(initialValue = true)
    val playbackSpeed by prefs.playbackSpeed.collectAsStateWithLifecycle(initialValue = 1.0f)
    val allowCellularDownload by prefs.allowCellularDownload.collectAsStateWithLifecycle(initialValue = false)
    val seedDownloadOnWifi by prefs.seedDownloadOnWifi.collectAsStateWithLifecycle(initialValue = false)
    val preferredAudio by prefs.preferredAudio.collectAsStateWithLifecycle(initialValue = "SUB")
    val preferredQuality by prefs.preferredQuality.collectAsStateWithLifecycle(initialValue = "1080p")
    val autoSkipIntro by prefs.autoSkipIntro.collectAsStateWithLifecycle(initialValue = false)
    val swipeGesturesEnabled by prefs.swipeGesturesEnabled.collectAsStateWithLifecycle(initialValue = true)
    val subtitleBlackFill by prefs.subtitleBlackFill.collectAsStateWithLifecycle(initialValue = true)
    val subtitleSize by prefs.subtitleSize.collectAsStateWithLifecycle(initialValue = "MEDIUM")
    val persistentDurationLine by prefs.persistentDurationLine.collectAsStateWithLifecycle(initialValue = false)
    val hideNsfwPreference by prefs.hideNsfwContent.collectAsStateWithLifecycle(initialValue = true)
    val doNotTrackNsfw by prefs.doNotTrackNsfw.collectAsStateWithLifecycle(initialValue = false)
    val nsfwAnilistAccountId by prefs.nsfwAnilistAccountId.collectAsStateWithLifecycle(initialValue = null)
    val contentOptions = session?.contentOptions
    val canShowNsfwContent = anilistToken.isNotBlank() && contentOptions?.displayAdultContent == true
    val hideNsfwContent = hideNsfwPreference || !canShowNsfwContent
    val hideNsfwSearch = hideNsfwContent || contentOptions?.includeAdultContentInSearch != true
    val hideNsfwActivities = hideNsfwContent || contentOptions?.displayAdultActivities != true

    val localEntries by LocalLibraryStore.localEntries.collectAsStateWithLifecycle()
    val pendingQueue by LocalLibraryStore.pendingQueue.collectAsStateWithLifecycle()
    val isSyncingQueue by LocalLibraryStore.isSyncing.collectAsStateWithLifecycle()
    val isOnline by LocalLibraryStore.observeNetworkOnline(context).collectAsStateWithLifecycle(initialValue = true)
    val offlineEpisodes by TorrentStreamService.offlineEpisodes.collectAsStateWithLifecycle()

    var selectedScreen by rememberSaveable { mutableStateOf(NavigationScreen.DASHBOARD) }
    var detailStack by remember { mutableStateOf<List<ScreenRoute>>(emptyList()) }
    var nextRouteId by rememberSaveable { mutableIntStateOf(0) }
    val currentRoute = detailStack.lastOrNull() ?: ScreenRoute.Tab(selectedScreen)
    val screenStateHolder = rememberSaveableStateHolder()

    var showAnilistAuthDialog by remember { mutableStateOf(false) }
    var authClearCookies by remember { mutableStateOf(false) }
    var showTopAccountMenu by remember { mutableStateOf(false) }
    var isOfflinePlaybackVisible by remember { mutableStateOf(false) }

    val showNavigationBars = currentRoute is ScreenRoute.Tab &&
        !(currentRoute.screen == NavigationScreen.PROFILE && isOfflinePlaybackVisible)

    fun openAuthDialog(clearCookies: Boolean = false) {
        authClearCookies = clearCookies
        showAnilistAuthDialog = true
    }

    fun openAnime(media: AnimeMedia) {
        if (hideNsfwContent && !media.isVisibleWithNsfwHidden) return
        if ((detailStack.lastOrNull() as? ScreenRoute.Anime)?.media?.mediaId == media.mediaId) return
        detailStack = detailStack + ScreenRoute.Anime(media, nextRouteId++)
    }

    fun openUser(username: String) {
        if ((detailStack.lastOrNull() as? ScreenRoute.User)?.username.equals(username, true)) return
        detailStack = detailStack + ScreenRoute.User(username, nextRouteId++)
    }

    fun closeRoute(route: ScreenRoute) {
        if (detailStack.lastOrNull() == route) {
            screenStateHolder.removeState(route.stateKey)
            detailStack = detailStack.dropLast(1)
        }
    }

    LaunchedEffect(hideNsfwContent, detailStack) {
        if (hideNsfwContent) {
            val hiddenRoutes = detailStack.filterIsInstance<ScreenRoute.Anime>()
                .filterNot { it.media.isVisibleWithNsfwHidden }
            if (hiddenRoutes.isNotEmpty()) {
                hiddenRoutes.forEach { screenStateHolder.removeState(it.stateKey) }
                detailStack = detailStack - hiddenRoutes.toSet()
            }
        }
    }

    // Initialize persistent stores on launch
    val legacyCacheFile = remember { File(context.filesDir, "cached_user_lists.json") }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { legacyCacheFile.delete() }
        LocalLibraryStore.ensureLoaded(context)
        KnownDubRegistry.ensureLoaded(context)
        TorrentStreamService.loadOfflineVault(context)
    }

    val accountKey = anilistToken to anilistUsername
    var userAnimeLists by remember(accountKey) { mutableStateOf<Map<String, List<AnimeMedia>>>(emptyMap()) }
    var isProfileLoading by remember(accountKey) { mutableStateOf(accountKey.first.isNotBlank()) }
    var profileLoadFailed by remember(accountKey) { mutableStateOf(false) }
    var profileRefresh by remember { mutableIntStateOf(0) }

    // Automatically flush queued 80%-watch & offline AniList edits as soon as internet reconnects!
    val accountPendingCount = remember(pendingQueue, trackingAccounts) {
        pendingQueue.count { trackingAccounts.forUpdate(it) != null }
    }

    LaunchedEffect(isOnline, trackingAccounts, accountPendingCount) {
        if (isOnline && accountPendingCount > 0) {
            val synced = LocalLibraryStore.flushPendingQueue(context, trackingAccounts)
            if (synced > 0) {
                profileRefresh++
                Toast.makeText(
                    context,
                    "Synced $synced offline update${if (synced == 1) "" else "s"} to AniList!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Unified lists: works seamlessly in Guest Mode (localEntries) AND AniList Mode (remote + offline queue)!
    val activeTrackingAccountId = trackingAccounts.active?.id?.takeIf { it > 0 }
    val mergedLists = remember(userAnimeLists, localEntries, pendingQueue, anilistToken, anilistUsername, activeTrackingAccountId) {
        LocalLibraryStore.buildMergedLists(
            remoteLists = userAnimeLists,
            localMap = localEntries,
            pending = pendingQueue,
            isLoggedIn = anilistToken.isNotBlank(),
            activeUsername = anilistUsername,
            activeAccountId = activeTrackingAccountId
        )
    }
    val visibleLists = remember(mergedLists, hideNsfwContent) {
        mergedLists.mapValues { (_, entries) -> entries.filterNsfw(hideNsfwContent) }
    }
    val legacyAdultRatingIds = remember(localEntries, offlineEpisodes) {
        (localEntries.values.filterNot { it.hasKnownAdultRating }.map { it.mediaId } +
            offlineEpisodes.filter { it.isCompleted && it.isAdultContent == null }.map { it.mediaId })
            .distinct()
    }
    LaunchedEffect(isOnline, anilistToken, legacyAdultRatingIds) {
        if (isOnline && legacyAdultRatingIds.isNotEmpty()) {
            loadAniList {
                val ratings = AniListClient.getAdultContentRatings(legacyAdultRatingIds, anilistToken.ifBlank { null })
                TorrentStreamService.applyOfflineAdultRatings(context, ratings)
                LocalLibraryStore.applyAdultRatings(context, ratings)
            }
        }
    }
    val library = remember(visibleLists) { visibleLists.values.flatten().distinctBy { it.mediaId } }
    val watchingList = remember(library) {
        library.filter { it.listStatus in listOf("CURRENT", "REPEATING") }
    }

    var trendingList by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var simulcastList by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var isDashboardLoading by remember { mutableStateOf(true) }
    var dashboardLoadFailed by remember { mutableStateOf(false) }
    var dashboardRefresh by remember { mutableIntStateOf(0) }
    var activitiesList by remember(anilistToken) { mutableStateOf<List<AniListActivity>>(emptyList()) }
    var isActivityLoading by remember(anilistToken) { mutableStateOf(anilistToken.isNotBlank()) }
    var activityLoadFailed by remember(anilistToken) { mutableStateOf(false) }

    var selectedRecCategory by rememberSaveable { mutableStateOf("For You") }
    LaunchedEffect(hideNsfwContent, selectedRecCategory) {
        if (hideNsfwContent && selectedRecCategory == HENTAI_BROWSE_CATEGORY) {
            selectedRecCategory = "For You"
        }
    }
    var recommendations by remember(accountKey, selectedRecCategory) { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var isRecLoading by remember(accountKey, selectedRecCategory) { mutableStateOf(true) }
    var recLoadFailed by remember(accountKey, selectedRecCategory) { mutableStateOf(false) }
    var recRefresh by remember { mutableIntStateOf(0) }
    val recommendationSeeds = if (selectedRecCategory == "For You") watchingList.take(12).map { it.mediaId } else emptyList()
    val hentaiRecommendationSeeds = remember(mergedLists, selectedRecCategory) {
        if (selectedRecCategory != HENTAI_BROWSE_CATEGORY) emptyList()
        else mergedLists.values.asSequence().flatten()
            .filter { isExplicitAdultAnime(it.isAdult, it.genres) }
            .map { it.mediaId }
            .distinct()
            .take(12)
            .toList()
    }
    val waitingForSeeds = selectedRecCategory == "For You" && isProfileLoading && recommendationSeeds.isEmpty()

    LaunchedEffect(anilistToken) {
        if (anilistToken.isNotBlank()) {
            val viewer = loadAniList { AniListClient.getViewer(anilistToken) }.getOrNull()
            if (viewer != null) prefs.updateAnilistViewer(anilistToken, viewer)
        }
    }

    LaunchedEffect(accountKey, profileRefresh) {
        if (anilistToken.isBlank() || anilistUsername.isBlank()) {
            isProfileLoading = false
            return@LaunchedEffect
        }
        isProfileLoading = true
        profileLoadFailed = false
        val result = loadAniList { AniListClient.getUserListRawJson(anilistUsername, anilistToken).second }
        result.onSuccess { userAnimeLists = it }
        profileLoadFailed = result.isFailure
        isProfileLoading = false
    }

    LaunchedEffect(dashboardRefresh) {
        isDashboardLoading = true
        dashboardLoadFailed = false
        val trending = async { loadAniList { AniListClient.getTrending(18) } }
        val simulcasts = async { loadAniList { AniListClient.getSimulcastSchedule(18) } }
        val trendingResult = trending.await().onSuccess { trendingList = it }
        val simulcastResult = simulcasts.await().onSuccess { simulcastList = it }
        dashboardLoadFailed = trendingResult.isFailure || simulcastResult.isFailure
        isDashboardLoading = false
    }

    LaunchedEffect(anilistToken, dashboardRefresh) {
        if (anilistToken.isBlank()) return@LaunchedEffect
        isActivityLoading = true
        activityLoadFailed = false
        val result = loadAniList {
            AniListClient.getActivities(page = 1, perPage = 20, isFollowing = true, token = anilistToken)
        }
        result.onSuccess { activitiesList = it.distinctBy(AniListActivity::id) }
        activityLoadFailed = result.isFailure
        isActivityLoading = false
    }

    val isSearchTabActive = selectedScreen == NavigationScreen.SEARCH
    LaunchedEffect(isSearchTabActive, accountKey, selectedRecCategory, recommendationSeeds, hentaiRecommendationSeeds, waitingForSeeds, recRefresh, hideNsfwContent) {
        if (!isSearchTabActive || waitingForSeeds) return@LaunchedEffect
        if (selectedRecCategory == HENTAI_BROWSE_CATEGORY && hideNsfwContent) return@LaunchedEffect
        isRecLoading = true
        recLoadFailed = false
        val result = loadAniList {
            when (selectedRecCategory) {
                HENTAI_BROWSE_CATEGORY -> AniListClient.browseHentaiSuggestions(
                    seedMediaIds = hentaiRecommendationSeeds,
                    perPage = 40,
                    token = anilistToken.ifBlank { null }
                )
                "For You" -> {
                    if (recommendationSeeds.isEmpty()) AniListClient.getTrending(36)
                    else AniListClient.getRecommendations(recommendationSeeds)
                }
                "Simulcast Calendar" -> AniListClient.getSimulcastSchedule(45)
                "Trending Now" -> AniListClient.getTrending(36)
                "Slice of Life" -> AniListClient.browseCategory(genre = "Slice of Life", perPage = 40)
                "Romance & CGDCT" -> AniListClient.browseCategory(genre = "Romance", tag = "Cute Girls Doing Cute Things", perPage = 40)
                "Action & Shounen" -> AniListClient.browseCategory(genre = "Action", perPage = 40)
                "Sci-Fi & Cyberpunk" -> AniListClient.browseCategory(genre = "Sci-Fi", tag = "Cyberpunk", perPage = 40)
                "Music & Idols" -> AniListClient.browseCategory(genre = "Music", tag = "Idol", perPage = 40)
                "Fantasy" -> AniListClient.browseCategory(genre = "Fantasy", perPage = 40)
                "Comedy" -> AniListClient.browseCategory(genre = "Comedy", perPage = 40)
                else -> AniListClient.getTrending(36)
            }
        }
        result.onSuccess { recommendations = it.distinctBy(AnimeMedia::mediaId) }
        recLoadFailed = result.isFailure
        isRecLoading = false
    }

    val effectiveUser = session?.takeIf { it.token.isNotBlank() && it.username.isNotBlank() }?.let {
        AniListUser(id = 0, name = it.username, avatarUrl = it.avatarUrl, scoreFormat = scoreFormat)
    }

    val completedDownloads = remember(offlineEpisodes, hideNsfwContent) {
        offlineEpisodes.filter { it.isCompleted && (!hideNsfwContent || it.isAdultContent == false) }
    }
    val offlineTotalMb = remember(completedDownloads) {
        completedDownloads.sumOf { it.sizeBytes } / (1024L * 1024L)
    }

    androidx.activity.compose.BackHandler(enabled = detailStack.isNotEmpty() || selectedScreen != NavigationScreen.DASHBOARD) {
        if (detailStack.isNotEmpty()) closeRoute(currentRoute)
        else selectedScreen = NavigationScreen.DASHBOARD
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = CrunchyBgBlack
    ) {
        Scaffold(
            containerColor = CrunchyBgBlack,
            contentWindowInsets = if (showNavigationBars) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
            topBar = {
                if (showNavigationBars) {
                    TopAppBar(
                        title = {
                            if (selectedScreen == NavigationScreen.DASHBOARD) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_nanami_anime_logo),
                                        contentDescription = "Nanami Anime",
                                        modifier = Modifier.size(34.dp)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "nanami",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 19.sp,
                                            color = CrunchyOrange
                                        )
                                        Text(
                                            text = "anime",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 19.sp,
                                            color = Color.White
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = selectedScreen.title,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 21.sp,
                                    color = Color.White
                                )
                            }
                        },
                        actions = {
                            if (selectedScreen == NavigationScreen.DASHBOARD) {
                                IconButton(onClick = { selectedScreen = NavigationScreen.SEARCH }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                                }
                            }

                            // Quick 1-Tap Account Switcher in TopAppBar when accounts are saved!
                            if (savedAccounts.isNotEmpty()) {
                                Box {
                                    Surface(
                                        onClick = { showTopAccountMenu = true },
                                        shape = RoundedCornerShape(18.dp),
                                        color = CrunchyCardBg,
                                        border = BorderStroke(1.dp, if (anilistToken.isNotBlank()) CrunchyOrange else CrunchyBorder),
                                        modifier = Modifier.padding(end = 8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (effectiveUser?.avatarUrl != null) {
                                                CrossfadeImage(
                                                    model = effectiveUser.avatarUrl,
                                                    contentDescription = effectiveUser.name,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .clip(CircleShape)
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.PersonOutline,
                                                    contentDescription = null,
                                                    tint = CrunchyOrange,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            Text(
                                                text = if (anilistToken.isNotBlank()) anilistUsername else "Guest",
                                                color = Color.White,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (savedAccounts.size > 1) {
                                                Icon(
                                                    Icons.Default.ArrowDropDown,
                                                    contentDescription = null,
                                                    tint = MaruTextMuted,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = showTopAccountMenu,
                                        onDismissRequest = { showTopAccountMenu = false },
                                        containerColor = CrunchySurface
                                    ) {
                                        savedAccounts.forEach { acc ->
                                            val isCurrent = anilistToken.isNotBlank() && acc.username.equals(anilistUsername, ignoreCase = true)
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = if (isCurrent) "@${acc.username} (Active)" else "@${acc.username}",
                                                        color = if (isCurrent) CrunchyOrange else Color.White,
                                                        fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium
                                                    )
                                                },
                                                onClick = {
                                                    showTopAccountMenu = false
                                                    if (!isCurrent) {
                                                        scope.launch { prefs.switchAnilistAccount(acc) }
                                                    }
                                                }
                                            )
                                        }
                                        HorizontalDivider(color = CrunchyBorder)
                                        DropdownMenuItem(
                                            text = { Text("+ Add AniList Account", color = CrunchyOrange, fontWeight = FontWeight.Bold) },
                                            onClick = {
                                                showTopAccountMenu = false
                                                openAuthDialog(clearCookies = true)
                                            }
                                        )
                                        if (anilistToken.isNotBlank()) {
                                            DropdownMenuItem(
                                                text = { Text("Switch to Guest Mode", color = MaruTextMuted) },
                                                onClick = {
                                                    showTopAccountMenu = false
                                                    scope.launch { prefs.useGuestMode() }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = CrunchyBgBlack,
                            titleContentColor = Color.White
                        )
                    )
                }
            },
            bottomBar = {
                if (showNavigationBars) {
                    Column {
                        HorizontalDivider(color = Color(0xFF1A1B20), thickness = 1.dp)
                        NavigationBar(
                            containerColor = Color(0xFF0D0E12),
                            contentColor = Color.White,
                            tonalElevation = 0.dp
                        ) {
                            NavigationScreen.values().forEach { screen ->
                                val isSelected = selectedScreen == screen
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = {
                                        selectedScreen = screen
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = screen.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(23.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = screen.title,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = CrunchyOrange,
                                        selectedTextColor = CrunchyOrange,
                                        indicatorColor = CrunchyOrange.copy(alpha = 0.14f),
                                        unselectedIconColor = Color(0xFFD5D7DF),
                                        unselectedTextColor = Color(0xFFD5D7DF)
                                    )
                                )
                            }
                        }
                    }
                }
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues)
            ) {
                AnimatedContent(
                    targetState = currentRoute,
                    transitionSpec = {
                        when {
                            targetState.depth > initialState.depth -> {
                                (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(180))) togetherWith
                                    (slideOutHorizontally(tween(200)) { -it / 10 } + fadeOut(tween(140)))
                            }
                            targetState.depth < initialState.depth -> {
                                (slideInHorizontally(tween(200)) { -it / 10 } + fadeIn(tween(160))) togetherWith
                                    (slideOutHorizontally(tween(200)) { it / 5 } + fadeOut(tween(140)))
                            }
                            else -> {
                                (fadeIn(tween(170)) + scaleIn(tween(170), initialScale = 0.985f)) togetherWith
                                    fadeOut(tween(120))
                            }
                        }.using(SizeTransform(clip = false))
                    },
                    label = "Screen transition"
                ) { route ->
                    when (route) {
                        is ScreenRoute.Anime -> screenStateHolder.SaveableStateProvider(route.stateKey) {
                            if (!hideNsfwContent || route.media.isVisibleWithNsfwHidden) {
                                AnimeDetailScreen(
                                    media = route.media,
                                    anilistToken = anilistToken,
                                    anilistUsername = anilistUsername,
                                    prefs = prefs,
                                    isActive = route == currentRoute,
                                    hideNsfwContent = hideNsfwContent,
                                    onBack = { closeRoute(route) },
                                    onMediaUpdated = { profileRefresh++ },
                                    onAnimeClick = { if (route == currentRoute) openAnime(it) },
                                    onUserClick = { if (route == currentRoute) openUser(it) },
                                    onLoginClick = { openAuthDialog(clearCookies = false) }
                                )
                            }
                        }
                        is ScreenRoute.User -> screenStateHolder.SaveableStateProvider(route.stateKey) {
                            UserProfileScreen(
                                username = route.username,
                                anilistToken = anilistToken,
                                viewerUsername = anilistUsername,
                                isActive = route == currentRoute,
                                hideNsfwContent = hideNsfwContent,
                                onBack = { closeRoute(route) },
                                onAnimeClick = { if (route == currentRoute) openAnime(it) },
                                onUserClick = { if (route == currentRoute) openUser(it) }
                            )
                        }
                        is ScreenRoute.Tab -> screenStateHolder.SaveableStateProvider(route.stateKey) {
                            when (route.screen) {
                                NavigationScreen.DASHBOARD -> DashboardScreen(
                                    user = effectiveUser,
                                    anilistToken = anilistToken,
                                    watchingList = watchingList,
                                    trendingList = trendingList.filterNsfw(hideNsfwContent),
                                    simulcastList = simulcastList.filterNsfw(hideNsfwContent),
                                    activitiesList = if (hideNsfwActivities) activitiesList.filter {
                                        it.type == "TEXT" || it.rawMedia?.isVisibleWithNsfwHidden == true
                                    } else activitiesList,
                                    isLoading = isDashboardLoading,
                                    loadFailed = dashboardLoadFailed,
                                    isActivityLoading = isActivityLoading,
                                    activityLoadFailed = activityLoadFailed,
                                    onLoginClick = { openAuthDialog(clearCookies = false) },
                                    onActivityPosted = { posted ->
                                        activitiesList = listOf(posted) + activitiesList
                                    },
                                    onRefresh = {
                                        dashboardRefresh++
                                        profileRefresh++
                                    },
                                    onAnimeClick = ::openAnime,
                                    onUserClick = ::openUser
                                )
                                NavigationScreen.PROFILE -> ProfileScreen(
                                    allLists = visibleLists,
                                    anilistToken = anilistToken,
                                    prefs = prefs,
                                    hideNsfwContent = hideNsfwContent,
                                    pendingSyncCount = accountPendingCount,
                                    isSyncingQueue = isSyncingQueue,
                                    isLoading = isProfileLoading,
                                    loadFailed = profileLoadFailed,
                                    onRetry = { profileRefresh++ },
                                    onLoginClick = { openAuthDialog(clearCookies = false) },
                                    onAnimeClick = ::openAnime,
                                    onMediaUpdated = { profileRefresh++ },
                                    onPlaybackVisibilityChange = { isOfflinePlaybackVisible = it }
                                )
                                NavigationScreen.SEARCH -> SearchScreen(
                                    anilistToken = anilistToken,
                                    browseItems = recommendations.filterNsfw(hideNsfwContent),
                                    hideNsfwContent = hideNsfwSearch,
                                    nsfwEnabled = !hideNsfwContent,
                                    isBrowseLoading = isRecLoading,
                                    browseLoadFailed = recLoadFailed,
                                    onRetryBrowse = { recRefresh++ },
                                    isLoggedIn = anilistToken.isNotBlank(),
                                    hasLocalSeeds = recommendationSeeds.isNotEmpty(),
                                    username = effectiveUser?.name ?: "Guest",
                                    selectedBrowseCategory = selectedRecCategory,
                                    onSelectBrowseCategory = { selectedRecCategory = it },
                                    onLoginClick = { openAuthDialog(clearCookies = false) },
                                    onAnimeClick = ::openAnime,
                                    onUserClick = ::openUser
                                )
                                NavigationScreen.SETTINGS -> SettingsScreen(
                                    user = effectiveUser,
                                    anilistUsername = anilistUsername,
                                    isLoggedIn = anilistToken.isNotBlank(),
                                    savedAccounts = savedAccounts,
                                    hideNsfwContent = hideNsfwContent,
                                    canShowNsfwContent = canShowNsfwContent,
                                    onToggleHideNsfwContent = { value -> scope.launch { prefs.setHideNsfwContent(value) } },
                                    doNotTrackNsfw = doNotTrackNsfw,
                                    onToggleDoNotTrackNsfw = { value -> scope.launch { prefs.setDoNotTrackNsfw(value) } },
                                    nsfwAnilistAccountId = nsfwAnilistAccountId,
                                    onSelectNsfwAnilistAccount = { accountId -> scope.launch { prefs.setNsfwAnilistAccountId(accountId) } },
                                    pendingSyncCount = accountPendingCount,
                                    isSyncingQueue = isSyncingQueue,
                                    onSyncNow = {
                                        scope.launch {
                                            val synced = LocalLibraryStore.flushPendingQueue(context, trackingAccounts)
                                            if (synced > 0) profileRefresh++
                                        }
                                    },
                                    offlineCount = completedDownloads.size,
                                    offlineTotalMb = offlineTotalMb,
                                    allowCellularDownload = allowCellularDownload,
                                    onToggleAllowCellularDownload = { scope.launch { prefs.setAllowCellularDownload(it) } },
                                    seedDownloadOnWifi = seedDownloadOnWifi,
                                    onToggleSeedDownloadOnWifi = { scope.launch { prefs.setSeedDownloadOnWifi(it) } },
                                    preferredAudio = preferredAudio,
                                    onSelectPreferredAudio = { scope.launch { prefs.setPreferredAudio(it) } },
                                    preferredQuality = preferredQuality,
                                    onSelectPreferredQuality = { scope.launch { prefs.setPreferredQuality(it) } },
                                    playbackSpeed = playbackSpeed,
                                    onSelectPlaybackSpeed = { scope.launch { prefs.setPlaybackSpeed(it) } },
                                    autoSkipIntro = autoSkipIntro,
                                    onToggleAutoSkipIntro = { scope.launch { prefs.setAutoSkipIntro(it) } },
                                    swipeGesturesEnabled = swipeGesturesEnabled,
                                    onToggleSwipeGestures = { scope.launch { prefs.setSwipeGesturesEnabled(it) } },
                                    persistentDurationLine = persistentDurationLine,
                                    onTogglePersistentDurationLine = { scope.launch { prefs.setPersistentDurationLine(it) } },
                                    subtitleBlackFill = subtitleBlackFill,
                                    onToggleSubtitleBlackFill = { scope.launch { prefs.setSubtitleBlackFill(it) } },
                                    subtitleSize = subtitleSize,
                                    onSelectSubtitleSize = { scope.launch { prefs.setSubtitleSize(it) } },
                                    reportProgress = reportProgress,
                                    onToggleReportProgress = { scope.launch { prefs.setReportProgress(it) } },
                                    trackWatchedPercent = trackWatchedPercent,
                                    onSelectTrackWatchedPercent = { scope.launch { prefs.setTrackWatchedPercent(it) } },
                                    rememberPosition = rememberPosition,
                                    onToggleRememberPosition = { scope.launch { prefs.setRememberPosition(it) } },
                                    onLoginClick = { openAuthDialog(clearCookies = false) },
                                    onAddAccountClick = { openAuthDialog(clearCookies = true) },
                                    onSwitchAccount = { acc ->
                                        scope.launch {
                                            prefs.switchAnilistAccount(acc)
                                            Toast.makeText(context, "Switched to @${acc.username}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onRemoveAccount = { acc ->
                                        scope.launch {
                                            prefs.removeAnilistAccount(acc.username)
                                            Toast.makeText(context, "Removed @${acc.username}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onGuestModeClick = {
                                        scope.launch {
                                            prefs.useGuestMode()
                                            Toast.makeText(context, "Switched to Local Guest Mode", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onLogoutClick = {
                                        scope.launch { prefs.clearAnilistSession(removeSavedAccount = true) }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showAnilistAuthDialog) {
            AniListAuthDialog(
                prefs = prefs,
                clearCookies = authClearCookies,
                onClose = { showAnilistAuthDialog = false }
            )
        }
    }
}

@Composable
private fun AniListAuthDialog(
    prefs: MAnimePrefs,
    clearCookies: Boolean = false,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val expectedRedirect = remember { Uri.parse(AniListClient.REDIRECT_URI) }
    val oauthState = remember { UUID.randomUUID().toString() }
    val authUrl = remember(oauthState) {
        Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", AniListClient.MASTER_CLIENT_ID)
            .appendQueryParameter("redirect_uri", AniListClient.REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", oauthState)
            .build().toString()
    }
    var code by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(code) {
        val authCode = code ?: return@LaunchedEffect
        val result = loadAniList {
            val token = AniListClient.exchangeAuthCode(authCode) ?: error("AniList did not return a token.")
            val viewer = AniListClient.getViewer(token)
            prefs.setAnilistSession(token, viewer)
            viewer
        }
        val message = result.getOrNull()?.let { "Connected as @${it.name}." }
            ?: "Couldn't connect to AniList. Please try again."
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onClose()
    }

    fun handleRedirect(uri: Uri): Boolean {
        if (uri.scheme != expectedRedirect.scheme || uri.host != expectedRedirect.host ||
            uri.port != expectedRedirect.port || uri.encodedPath != expectedRedirect.encodedPath || uri.userInfo != null
        ) return false
        if (code != null) return true
        val returnedCode = uri.getQueryParameter("code")
        if (uri.getQueryParameter("state") == oauthState && !returnedCode.isNullOrBlank()) {
            code = returnedCode
        } else {
            Toast.makeText(context, "AniList sign-in was interrupted. Please try again.", Toast.LENGTH_SHORT).show()
            onClose()
        }
        return true
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = CrunchyBgBlack) {
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
                    Text(
                        text = if (clearCookies) "Add Another AniList Account" else "Connect AniList",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                if (code != null) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = CrunchyOrange)
                            Text("Connecting your AniList account…", color = Color.White)
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            if (clearCookies) {
                                runCatching {
                                    CookieManager.getInstance().apply {
                                        removeAllCookies(null)
                                        flush()
                                    }
                                }
                            }
                            WebView(ctx).apply {
                                if (clearCookies) {
                                    clearCache(true)
                                    clearHistory()
                                }
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        return request.isForMainFrame &&
                                            (handleRedirect(request.url) || request.url.scheme != "https")
                                    }

                                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                        if (url != null && handleRedirect(Uri.parse(url))) view.stopLoading()
                                    }
                                }
                                loadUrl(authUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { webView ->
                            webView.stopLoading()
                            webView.destroy()
                        }
                    )
                }
            }
        }
    }
}
