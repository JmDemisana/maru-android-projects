package io.maru.manime

import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    PROFILE("My Lists", "Watchlist & Offline Vault", Icons.Default.BookmarkBorder),
    RECOMMENDATIONS("Browse", "Genres, Seasons & Dubs", Icons.Default.GridView),
    SEARCH("Search", "Find Series & Cast", Icons.Default.Search),
    SETTINGS("Account", "Audio, Sync & Preferences", Icons.Default.PersonOutline)
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
    val anilistToken = session?.token.orEmpty()
    val anilistUsername = session?.username.orEmpty()
    val reportProgress by prefs.reportProgress.collectAsStateWithLifecycle(initialValue = true)
    val rememberPosition by prefs.rememberPosition.collectAsStateWithLifecycle(initialValue = true)
    val preferredAudio by prefs.preferredAudio.collectAsStateWithLifecycle(initialValue = "SUB")
    val preferredQuality by prefs.preferredQuality.collectAsStateWithLifecycle(initialValue = "1080p")
    val autoSkipIntro by prefs.autoSkipIntro.collectAsStateWithLifecycle(initialValue = false)

    var selectedScreen by rememberSaveable { mutableStateOf(NavigationScreen.DASHBOARD) }
    var detailStack by remember { mutableStateOf<List<ScreenRoute>>(emptyList()) }
    var nextRouteId by rememberSaveable { mutableIntStateOf(0) }
    val currentRoute = detailStack.lastOrNull() ?: ScreenRoute.Tab(selectedScreen)
    val screenStateHolder = rememberSaveableStateHolder()
    var showAnilistAuthDialog by remember { mutableStateOf(false) }
    var isOfflinePlaybackVisible by remember { mutableStateOf(false) }
    val showNavigationBars = currentRoute is ScreenRoute.Tab &&
        !(currentRoute.screen == NavigationScreen.PROFILE && isOfflinePlaybackVisible)

    fun openAnime(media: AnimeMedia) {
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

    fun signOut() {
        scope.launch { prefs.clearAnilistSession() }
    }

    // The old unowned disk cache could expose another account's private lists.
    val cacheFile = remember { File(context.filesDir, "cached_user_lists.json") }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { cacheFile.delete() } }
    val accountKey = anilistToken to anilistUsername
    var userAnimeLists by remember(accountKey) { mutableStateOf<Map<String, List<AnimeMedia>>>(emptyMap()) }
    var isProfileLoading by remember(accountKey) { mutableStateOf(accountKey.first.isNotBlank()) }
    var profileLoadFailed by remember(accountKey) { mutableStateOf(false) }
    var profileRefresh by remember { mutableIntStateOf(0) }
    val library = remember(userAnimeLists) { userAnimeLists.values.flatten().distinctBy { it.mediaId } }
    val watchingList = remember(library) { library.filter { it.listStatus in listOf("CURRENT", "REPEATING") } }

    var trendingList by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var simulcastList by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var isDashboardLoading by remember { mutableStateOf(true) }
    var dashboardLoadFailed by remember { mutableStateOf(false) }
    var dashboardRefresh by remember { mutableIntStateOf(0) }
    var activitiesList by remember(anilistToken) { mutableStateOf<List<AniListActivity>>(emptyList()) }
    var isActivityLoading by remember(anilistToken) { mutableStateOf(anilistToken.isNotBlank()) }
    var activityLoadFailed by remember(anilistToken) { mutableStateOf(false) }

    var selectedRecCategory by rememberSaveable { mutableStateOf("For You") }
    var recommendations by remember(accountKey, selectedRecCategory) { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var isRecLoading by remember(accountKey, selectedRecCategory) { mutableStateOf(true) }
    var recLoadFailed by remember(accountKey, selectedRecCategory) { mutableStateOf(false) }
    var recRefresh by remember { mutableIntStateOf(0) }
    val recommendationSeeds = if (selectedRecCategory == "For You") watchingList.take(10).map { it.mediaId } else emptyList()
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

    val isBrowsing = selectedScreen == NavigationScreen.RECOMMENDATIONS
    LaunchedEffect(isBrowsing, accountKey, selectedRecCategory, recommendationSeeds, waitingForSeeds, recRefresh) {
        if (!isBrowsing || waitingForSeeds) return@LaunchedEffect
        isRecLoading = true
        recLoadFailed = false
        val result = loadAniList {
            when (selectedRecCategory) {
                "For You" -> {
                    if (recommendationSeeds.isEmpty()) AniListClient.getTrending(30)
                    else AniListClient.getRecommendations(recommendationSeeds)
                }
                "Simulcast Calendar" -> AniListClient.getSimulcastSchedule(45)
                "Trending Now" -> AniListClient.getTrending(30)
                "Slice of Life" -> AniListClient.browseCategory(genre = "Slice of Life", perPage = 30)
                "Romance & CGDCT" -> AniListClient.browseCategory(genre = "Romance", tag = "Cute Girls Doing Cute Things", perPage = 30)
                "Action & Shounen" -> AniListClient.browseCategory(genre = "Action", perPage = 30)
                "Sci-Fi & Cyberpunk" -> AniListClient.browseCategory(genre = "Sci-Fi", tag = "Cyberpunk", perPage = 30)
                "Music & Idols" -> AniListClient.browseCategory(genre = "Music", tag = "Idol", perPage = 30)
                "Fantasy" -> AniListClient.browseCategory(genre = "Fantasy", perPage = 30)
                "Comedy" -> AniListClient.browseCategory(genre = "Comedy", perPage = 30)
                "Dubbed Only" -> AniListClient.getTrending(50).filter { it.hasEnglishDub }
                else -> AniListClient.getTrending(30)
            }
        }
        result.onSuccess { recommendations = it.distinctBy(AnimeMedia::mediaId) }
        recLoadFailed = result.isFailure
        isRecLoading = false
    }

    val effectiveUser = session?.takeIf { it.token.isNotBlank() && it.username.isNotBlank() }?.let {
        AniListUser(id = 0, name = it.username, avatarUrl = it.avatarUrl)
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
                                            fontSize = 10.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
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
                        (fadeIn(tween(140)) togetherWith fadeOut(tween(100))).using(null)
                    },
                    label = "Screen transition"
                ) { route ->
                    when (route) {
                        is ScreenRoute.Anime -> screenStateHolder.SaveableStateProvider(route.stateKey) {
                            AnimeDetailScreen(
                                media = route.media,
                                anilistToken = anilistToken,
                                prefs = prefs,
                                isActive = route == currentRoute,
                                onBack = { closeRoute(route) },
                                onMediaUpdated = { profileRefresh++ },
                                onAnimeClick = { if (route == currentRoute) openAnime(it) },
                                onUserClick = { if (route == currentRoute) openUser(it) }
                            )
                        }
                        is ScreenRoute.User -> screenStateHolder.SaveableStateProvider(route.stateKey) {
                            UserProfileScreen(
                                username = route.username,
                                anilistToken = anilistToken,
                                isActive = route == currentRoute,
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
                                    trendingList = trendingList,
                                    simulcastList = simulcastList,
                                    activitiesList = activitiesList,
                                    isLoading = isDashboardLoading,
                                    loadFailed = dashboardLoadFailed,
                                    isActivityLoading = isActivityLoading,
                                    activityLoadFailed = activityLoadFailed,
                                    onLoginClick = { showAnilistAuthDialog = true },
                                    onRefresh = {
                                        dashboardRefresh++
                                        profileRefresh++
                                    },
                                    onAnimeClick = ::openAnime,
                                    onUserClick = ::openUser
                                )
                                NavigationScreen.PROFILE -> ProfileScreen(
                                    allLists = userAnimeLists,
                                    anilistToken = anilistToken,
                                    prefs = prefs,
                                    isLoading = isProfileLoading,
                                    loadFailed = profileLoadFailed,
                                    onRetry = { profileRefresh++ },
                                    onLoginClick = { showAnilistAuthDialog = true },
                                    onAnimeClick = ::openAnime,
                                    onMediaUpdated = { profileRefresh++ },
                                    onPlaybackVisibilityChange = { isOfflinePlaybackVisible = it }
                                )
                                NavigationScreen.RECOMMENDATIONS -> RecommendationsScreen(
                                    recommendations = recommendations,
                                    isLoading = isRecLoading,
                                    loadFailed = recLoadFailed,
                                    onRetry = { recRefresh++ },
                                    isLoggedIn = anilistToken.isNotBlank(),
                                    username = effectiveUser?.name ?: "Guest",
                                    selectedCategory = selectedRecCategory,
                                    onSelectCategory = { selectedRecCategory = it },
                                    onLoginClick = { showAnilistAuthDialog = true },
                                    onAnimeClick = ::openAnime
                                )
                                NavigationScreen.SEARCH -> SearchScreen(
                                    anilistToken = anilistToken,
                                    onAnimeClick = ::openAnime,
                                    onUserClick = ::openUser
                                )
                                NavigationScreen.SETTINGS -> SettingsScreen(
                                    user = effectiveUser,
                                    anilistUsername = anilistUsername,
                                    isLoggedIn = anilistToken.isNotBlank(),
                                    preferredAudio = preferredAudio,
                                    onSelectPreferredAudio = { scope.launch { prefs.setPreferredAudio(it) } },
                                    preferredQuality = preferredQuality,
                                    onSelectPreferredQuality = { scope.launch { prefs.setPreferredQuality(it) } },
                                    autoSkipIntro = autoSkipIntro,
                                    onToggleAutoSkipIntro = { scope.launch { prefs.setAutoSkipIntro(it) } },
                                    reportProgress = reportProgress,
                                    onToggleReportProgress = { scope.launch { prefs.setReportProgress(it) } },
                                    rememberPosition = rememberPosition,
                                    onToggleRememberPosition = { scope.launch { prefs.setRememberPosition(it) } },
                                    onLoginClick = { showAnilistAuthDialog = true },
                                    onLogoutClick = ::signOut
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showAnilistAuthDialog) {
            AniListAuthDialog(prefs) { showAnilistAuthDialog = false }
        }
    }
}

@Composable
private fun AniListAuthDialog(prefs: MAnimePrefs, onClose: () -> Unit) {
    val context = LocalContext.current
    val expectedRedirect = remember { Uri.parse(AniListClient.REDIRECT_URI) }
    val oauthState = remember { UUID.randomUUID().toString() }
    val authUrl = remember(oauthState) {
        Uri.parse("https://anilist.co/api/v2/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", AniListClient.MASTER_CLIENT_ID.toString())
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
                    Text("Connect AniList", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
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
                            Text("Connecting your AniList account...", color = Color.White)
                        }
                    }
                } else {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
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
