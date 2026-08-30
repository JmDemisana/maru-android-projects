package io.maru.manime

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.maru.manime.extensions.CloudstreamRepoClient
import io.maru.manime.extensions.ExtensionRouter
import io.maru.manime.extensions.StreamLink
import io.maru.manime.player.PlayerScreen
import io.maru.manime.player.QualityPickerSheet
import io.maru.manime.screens.*
import io.maru.manime.torrent.TorrentStreamService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.foundation.BorderStroke

// --- MAudio Exact Cosmic Glassmorphic Design Tokens ---
val MaruCosmicTop = Color(0xFF211734)
val MaruCosmicMid = Color(0xFF100C19)
val MaruCosmicBot = Color(0xFF050507)

val MaruGlassPanelBg = Color(0x800E0A1A)
val MaruGlassCardBg = Color(0x6618122B)
val MaruGlassSubtleBg = Color(0x1AFFFFFF)
val MaruGlassBorder = Color(0x40FFFFFF)
val MaruGlassBorderSoft = Color(0x18FFFFFF)

val MaruTextStrong = Color(0xF4F4F9FA)
val MaruTextMuted = Color(0xB8EBEBF5)
val MaruAccentPink = Color(0xFFE85D9F)
val MaruAccentBlue = Color(0xFF60E2FF)
val MaruAccentPurple = Color(0xFFB388FF)
val MaruAccentGreen = Color(0xFF4ADE80)
val MaruAccentYellow = Color(0xFFFBBF24)
val MaruDanger = Color(0xFFFF5252)

val MaruCardShape = RoundedCornerShape(12.dp)
val MaruInputShape = RoundedCornerShape(10.dp)
val MaruPillShape = RoundedCornerShape(24.dp)

enum class NavigationGroup(val title: String) {
    RECOMMENDATION_ENGINE("RECOMMENDATION ENGINE"),
    CORE_FUNCTIONALITY("CORE FUNCTIONALITY")
}

enum class NavigationScreen(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val group: NavigationGroup
) {
    // Recommendation Engine Group
    DASHBOARD("Discovery", "Trending & Community Feed", Icons.Default.AutoAwesome, NavigationGroup.RECOMMENDATION_ENGINE),
    SEARCH("Search", "Anime, Genres & Cast", Icons.Default.Search, NavigationGroup.RECOMMENDATION_ENGINE),
    RECOMMENDATIONS("Recommendations", "Genre & Mood Browsing", Icons.Default.Stars, NavigationGroup.RECOMMENDATION_ENGINE),

    // Core Functionality Group
    PROFILE("My Library", "AniList Lists & Stats", Icons.Default.CollectionsBookmark, NavigationGroup.CORE_FUNCTIONALITY),
    SETTINGS("Settings", "Theme & App Options", Icons.Default.Settings, NavigationGroup.CORE_FUNCTIONALITY)
}

class MainActivity : ComponentActivity() {
    private lateinit var prefs: MAnimePrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = MAnimePrefs(this)

        enableEdgeToEdge()
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        handleIntent(intent)

        try {
            val filter = android.content.IntentFilter("io.maru.manime.TEST_SEARCH")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(debugReceiver, filter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(debugReceiver, filter)
            }
        } catch (_: Exception) {}

        setContent {
            MaterialTheme {
                MainAppScreen(prefs)
            }
        }
    }

    private val debugReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "io.maru.manime.TEST_SEARCH") {
                val title = intent.getStringExtra("title") ?: "Bleach"
                val episode = intent.getIntExtra("episode", 1)
                android.util.Log.i("MAnimeDebug", "=== START DEBUG SEARCH FOR: $title EP: $episode ===")
                lifecycleScope.launch(Dispatchers.IO) {
                    val router = ExtensionRouter(this@MainActivity, prefs)
                    val streams = router.resolveStreamsForEpisode(title, episode) { liveList ->
                        android.util.Log.i("MAnimeDebug", "Progressive update: ${liveList.size} streams found so far")
                    }
                    android.util.Log.i("MAnimeDebug", "=== COMPLETED DEBUG SEARCH: ${streams.size} TOTAL STREAMS ===")
                    for (s in streams) {
                        android.util.Log.i("MAnimeDebug", "-> [${s.methodType}] ${s.sourceName} | ${s.quality} | ${s.audioType} | ${s.url}")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(debugReceiver)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "manime") {
            var token = data.getQueryParameter("access_token") ?: data.getQueryParameter("token")
            if (token.isNullOrBlank() && data.fragment?.contains("access_token=") == true) {
                val match = Regex("access_token=([^&]+)").find(data.fragment ?: "")
                token = match?.groupValues?.getOrNull(1)
            }

            if (!token.isNullOrBlank()) {
                lifecycleScope.launch {
                    prefs.setAnilistToken(token)
                    try {
                        val user = withContext(Dispatchers.IO) {
                            AniListClient.getViewer(token)
                        }
                        prefs.setAnilistUsername(user.name)
                        Toast.makeText(this@MainActivity, "Logged in as ${user.name}!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Connected to AniList!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(prefs: MAnimePrefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val anilistToken by prefs.anilistToken.collectAsStateWithLifecycle(initialValue = "")
    val anilistUsername by prefs.anilistUsername.collectAsStateWithLifecycle(initialValue = "")
    val reportProgress by prefs.reportProgress.collectAsStateWithLifecycle(initialValue = true)
    val rememberPosition by prefs.rememberPosition.collectAsStateWithLifecycle(initialValue = true)
    val stremioAddons by prefs.stremioAddons.collectAsStateWithLifecycle(initialValue = MAnimePrefs.DEFAULT_STREMIO_ADDONS)
    val cloudstreamReposSet by prefs.cloudstreamRepos.collectAsStateWithLifecycle(initialValue = emptySet())

    var selectedScreen by remember { mutableStateOf(NavigationScreen.DASHBOARD) }
    var selectedAnimeDetail by remember { mutableStateOf<AnimeMedia?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var isGlobalLoading by remember { mutableStateOf(false) }

    // Data States
    var userProfile by remember { mutableStateOf<AniListUser?>(null) }
    var watchingList by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var completedCount by remember { mutableIntStateOf(0) }
    var planningCount by remember { mutableIntStateOf(0) }
    var trendingList by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var isDashboardLoading by remember { mutableStateOf(false) }

    var searchResults by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    var recommendations by remember { mutableStateOf<List<AnimeMedia>>(emptyList()) }
    var selectedRecCategory by remember { mutableStateOf("For You") }
    var isRecLoading by remember { mutableStateOf(false) }
    var activitiesList by remember { mutableStateOf<List<AniListActivity>>(emptyList()) }

    // Cloudstream Extensions State
    val csRepoClient = remember { CloudstreamRepoClient(context) }
    val aniyomiLoader = remember { io.maru.manime.extensions.AniyomiExtensionLoader(context) }
    var savedRepos by remember { mutableStateOf<List<io.maru.manime.extensions.CloudstreamRepo>>(emptyList()) }
    var installedCloudstream by remember { mutableStateOf<List<String>>(emptyList()) }
    var installedAniyomi by remember { mutableStateOf<List<io.maru.manime.extensions.AniyomiExtensionInfo>>(emptyList()) }

    fun refreshInstalledCloudstream() {
        installedCloudstream = csRepoClient.getInstalledPlugins().map { it.nameWithoutExtension }
        installedAniyomi = aniyomiLoader.getInstalledExtensions()
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val pluginsDir = File(context.filesDir, "cloudstream_plugins")
                if (!pluginsDir.exists()) pluginsDir.mkdirs()
                val assetList = context.assets.list("cloudstream_plugins") ?: emptyArray()
                for (assetName in assetList) {
                    val destFile = File(pluginsDir, assetName)
                    try {
                        if (destFile.exists()) {
                            destFile.setWritable(true)
                            destFile.delete()
                        }
                        context.assets.open("cloudstream_plugins/$assetName").use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        destFile.setReadOnly()
                        android.util.Log.d("MAnime", "Unpacked asset plugin: $assetName (${destFile.length()} bytes)")
                    } catch (e: Exception) {
                        android.util.Log.e("MAnime", "Failed to unpack asset plugin $assetName", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MAnime", "Error in asset plugin unpacking", e)
            }
        }
        refreshInstalledCloudstream()
    }

    // Load saved repos from DataStore
    LaunchedEffect(cloudstreamReposSet) {
        withContext(Dispatchers.IO) {
            val loaded = mutableListOf<io.maru.manime.extensions.CloudstreamRepo>()
            for (repoUrl in cloudstreamReposSet) {
                try {
                    val r = csRepoClient.fetchRepo(repoUrl)
                    loaded.add(r)
                } catch (_: Exception) {}
            }
            withContext(Dispatchers.Main) {
                savedRepos = loaded
            }
        }
    }

    // Active Player State
    var activePlayerUrl by remember { mutableStateOf<String?>(null) }
    var activePlayerTitle by remember { mutableStateOf("") }
    var activePlayerEpisode by remember { mutableIntStateOf(1) }
    var activePlayerMediaId by remember { mutableIntStateOf(0) }

    // All user library lists
    var allUserLists by remember { mutableStateOf<Map<String, List<AnimeMedia>>>(emptyMap()) }

    // Stream picker sheet
    var availableStreams by remember { mutableStateOf<List<StreamLink>>(emptyList()) }
    var isSearchingStreams by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }

    val extensionRouter = remember { ExtensionRouter(context, prefs) }

    // Refresh Dashboard Data & AniList Activity Feed
    val refreshDashboard: () -> Unit = {
        scope.launch {
            isDashboardLoading = true
            try {
                val trending = withContext(Dispatchers.IO) { AniListClient.getTrending(25) }
                trendingList = trending

                val activities = withContext(Dispatchers.IO) {
                    AniListClient.getActivities(1, 25, isFollowing = anilistToken.isNotBlank(), token = anilistToken.ifEmpty { null })
                }
                activitiesList = activities

                if (anilistToken.isNotBlank()) {
                    val user = withContext(Dispatchers.IO) { AniListClient.getViewer(anilistToken) }
                    userProfile = user

                    val userLists = withContext(Dispatchers.IO) { AniListClient.getUserList(user.name, anilistToken) }
                    allUserLists = userLists
                    val current = userLists["Watching"] ?: userLists["Current"] ?: userLists["watching"] ?: emptyList()
                    val completed = userLists["Completed"] ?: userLists["completed"] ?: emptyList()
                    val planning = userLists["Planning"] ?: userLists["planning"] ?: emptyList()

                    watchingList = current
                    completedCount = completed.size
                    planningCount = planning.size
                }
            } catch (e: Exception) {
                // Ignore transient network errors
            } finally {
                isDashboardLoading = false
            }
        }
    }

    // Refresh Recommendations (Personalized & Categories)
    val loadCategoryRecs: (String) -> Unit = { cat ->
        selectedRecCategory = cat
        scope.launch {
            isRecLoading = true
            try {
                val results = withContext(Dispatchers.IO) {
                    if (cat == "For You") {
                        if (anilistToken.isNotBlank() && (watchingList.isNotEmpty() || completedCount > 0)) {
                            val userIds = (watchingList.map { it.mediaId } + listOfNotNull(watchingList.firstOrNull()?.mediaId)).distinct().take(15)
                            val recs = AniListClient.getRecommendations(userIds)
                            if (recs.isNotEmpty()) recs else AniListClient.getTrending(30)
                        } else {
                            // Curated taste profile (CGDCT, Slice of life, Music)
                            AniListClient.browseCategory(genre = "Slice of Life", perPage = 30)
                        }
                    } else {
                        AniListClient.browseCategory(genre = cat, perPage = 30)
                    }
                }
                recommendations = results
            } catch (_: Exception) {} finally {
                isRecLoading = false
            }
        }
    }

    LaunchedEffect(anilistToken) {
        refreshDashboard()
        loadCategoryRecs("For You")
    }

    // Back button handling
    BackHandler(enabled = true) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (activePlayerUrl != null) {
            activePlayerUrl = null
        } else if (selectedAnimeDetail != null) {
            selectedAnimeDetail = null
        } else if (selectedScreen != NavigationScreen.DASHBOARD) {
            selectedScreen = NavigationScreen.DASHBOARD
        } else {
            Toast.makeText(context, "MAnime running in background", Toast.LENGTH_SHORT).show()
        }
    }

    // Fullscreen In-App Player Active
    if (activePlayerUrl != null) {
        PlayerScreen(
            videoUrl = activePlayerUrl!!,
            title = activePlayerTitle,
            episodeNum = activePlayerEpisode,
            mediaId = activePlayerMediaId,
            prefs = prefs,
            onBack = { activePlayerUrl = null }
        )
        return
    }

    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(MaruCosmicTop, MaruCosmicMid, MaruCosmicBot)
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            GlassNavigationDrawer(
                currentScreen = selectedScreen,
                username = anilistUsername,
                onSelectScreen = { screen: NavigationScreen ->
                    selectedScreen = screen
                    selectedAnimeDetail = null
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                if (selectedAnimeDetail == null) {
                    TopAppBar(
                        modifier = Modifier
                            .background(Brush.verticalGradient(listOf(MaruCosmicTop.copy(alpha = 0.95f), Color.Transparent)))
                            .statusBarsPadding(),
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_maru_heart),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                    tint = Color.Unspecified
                                )
                                Text(
                                    selectedScreen.title,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    ),
                                    color = MaruTextStrong
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = MaruTextStrong)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaruTextStrong
                        )
                    )
                }
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundGradient)
                    .padding(if (selectedAnimeDetail != null) PaddingValues(0.dp) else innerPadding)
            ) {
                if (selectedAnimeDetail != null) {
                    AnimeDetailScreen(
                        media = selectedAnimeDetail!!,
                        anilistToken = anilistToken,
                        onBack = { selectedAnimeDetail = null },
                        onMediaUpdated = { updated ->
                            selectedAnimeDetail = updated
                            watchingList = watchingList.map { if (it.mediaId == updated.mediaId) updated else it }
                            allUserLists = allUserLists.mapValues { (_, list) ->
                                list.map { if (it.mediaId == updated.mediaId) updated else it }
                            }
                        }
                    )
                } else {
                    AnimatedContent(
                        targetState = selectedScreen,
                        transitionSpec = {
                            val enter = fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                                    scaleIn(initialScale = 0.97f, animationSpec = tween(220, easing = FastOutSlowInEasing))
                            val exit = fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing))
                            enter togetherWith exit
                        },
                        label = "ScreenTransition"
                    ) { screen ->
                        when (screen) {
                            NavigationScreen.DASHBOARD -> {
                                DashboardScreen(
                                    watchingList = watchingList,
                                    trendingList = trendingList,
                                    activitiesList = activitiesList,
                                    isLoading = isDashboardLoading,
                                    onRefresh = refreshDashboard,
                                    onAnimeClick = { selectedAnimeDetail = it }
                                )
                            }
                            NavigationScreen.SEARCH -> {
                                SearchScreen(
                                    searchResults = searchResults,
                                    isSearching = isSearching,
                                    onSearch = { q ->
                                        if (q.isBlank()) {
                                            searchResults = emptyList()
                                        } else {
                                            scope.launch {
                                                isSearching = true
                                                try {
                                                    val res = withContext(Dispatchers.IO) {
                                                        AniListClient.search(q, 1, 20, anilistToken.ifEmpty { null })
                                                    }
                                                    searchResults = res.results
                                                } catch (_: Exception) {} finally {
                                                    isSearching = false
                                                }
                                            }
                                        }
                                    },
                                    onAnimeClick = { selectedAnimeDetail = it }
                                )
                            }
                            NavigationScreen.RECOMMENDATIONS -> {
                                RecommendationsScreen(
                                    recommendations = recommendations,
                                    isLoading = isRecLoading,
                                    isLoggedIn = anilistToken.isNotBlank(),
                                    username = anilistUsername,
                                    selectedCategory = selectedRecCategory,
                                    onSelectCategory = loadCategoryRecs,
                                    onLoginClick = {
                                        val authUrl = "https://anilist.co/api/v2/oauth/authorize?client_id=49762&response_type=token"
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                                        context.startActivity(browserIntent)
                                    },
                                    onAnimeClick = { selectedAnimeDetail = it }
                                )
                            }
                            NavigationScreen.PROFILE -> {
                                ProfileScreen(
                                    user = userProfile,
                                    allLists = allUserLists,
                                    watchingCount = watchingList.size,
                                    completedCount = completedCount,
                                    planningCount = planningCount,
                                    anilistToken = anilistToken,
                                    onLoginClick = {
                                        val authUrl = "https://anilist.co/api/v2/oauth/authorize?client_id=49762&response_type=token"
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                                        context.startActivity(browserIntent)
                                    },
                                    onLogoutClick = {
                                        scope.launch {
                                            prefs.setAnilistToken("")
                                            prefs.setAnilistUsername("")
                                            userProfile = null
                                            watchingList = emptyList()
                                            allUserLists = emptyMap()
                                        }
                                    },
                                    onAnimeClick = { selectedAnimeDetail = it },
                                    onMediaUpdated = { updated ->
                                        watchingList = watchingList.map { if (it.mediaId == updated.mediaId) updated else it }
                                        allUserLists = allUserLists.mapValues { (_, list) ->
                                            list.map { if (it.mediaId == updated.mediaId) updated else it }
                                        }
                                    }
                                )
                            }
                            NavigationScreen.SETTINGS -> {
                                SettingsScreen(
                                    reportProgress = reportProgress,
                                    onToggleReportProgress = { scope.launch { prefs.setReportProgress(it) } },
                                    rememberPosition = rememberPosition,
                                    onToggleRememberPosition = { scope.launch { prefs.setRememberPosition(it) } }
                                )
                            }
                        }
                    }
                }

                // Stream Quality Bottom Sheet
                if (showQualityPicker) {
                    QualityPickerSheet(
                        streams = availableStreams,
                        animeTitle = selectedAnimeDetail?.titleEnglish ?: selectedAnimeDetail?.titleRomaji ?: selectedAnimeDetail?.title ?: "",
                        isSearching = isSearchingStreams,
                        onSearchCloudstream = { title ->
                            showQualityPicker = false
                            try {
                                val pm = context.packageManager
                                val candidatePackages = listOf(
                                    "com.lagradost.cloudstream3",
                                    "com.lagradost.cloudstream3.prerelease",
                                    "com.lagradost.cloudstream3.debug",
                                    "com.lagradost.cloudstream3.test"
                                )

                                var targetPkg: String? = null
                                for (pkg in candidatePackages) {
                                    try {
                                        pm.getPackageInfo(pkg, 0)
                                        targetPkg = pkg
                                        break
                                    } catch (_: Exception) {}
                                }

                                if (targetPkg == null) {
                                    val allApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                                    targetPkg = allApps.firstOrNull { it.packageName.contains("cloudstream") }?.packageName
                                }

                                if (targetPkg != null) {
                                    // 1. Try deep link intent first
                                    try {
                                        val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("cloudstream3://search?query=${Uri.encode(title)}")).apply {
                                            setPackage(targetPkg)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        if (deepLinkIntent.resolveActivity(pm) != null) {
                                            context.startActivity(deepLinkIntent)
                                            return@QualityPickerSheet
                                        }
                                    } catch (_: Exception) {}

                                    // 2. Try standard search intent
                                    try {
                                        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                                            setPackage(targetPkg)
                                            putExtra(android.app.SearchManager.QUERY, title)
                                            putExtra("query", title)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        if (searchIntent.resolveActivity(pm) != null) {
                                            context.startActivity(searchIntent)
                                            return@QualityPickerSheet
                                        }
                                    } catch (_: Exception) {}

                                    // 3. Fallback to launch intent
                                    val launchIntent = pm.getLaunchIntentForPackage(targetPkg)?.apply {
                                        putExtra(android.app.SearchManager.QUERY, title)
                                        putExtra("query", title)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    if (launchIntent != null) {
                                        context.startActivity(launchIntent)
                                        return@QualityPickerSheet
                                    }
                                }

                                // Try generic deep link
                                val genericIntent = Intent(Intent.ACTION_VIEW, Uri.parse("cloudstream3://search?query=${Uri.encode(title)}")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(genericIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open Cloudstream app: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onSelectStream = { stream ->
                            showQualityPicker = false
                            if (stream.isTorrent) {
                                TorrentStreamService.start(context, stream.url)
                                // Feed localhost stream to player
                                activePlayerUrl = "http://127.0.0.1:${TorrentStreamService.STREAM_PORT}/stream"
                            } else {
                                activePlayerUrl = stream.url
                            }
                        },
                        onDismiss = { showQualityPicker = false }
                    )
                }
            }
        }
    }

    // Global Loading Overlay
    if (isGlobalLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                // Intercept touches
                .pointerInput(Unit) {},
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = MaruAccentPink,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(50.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Working...",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// MAudio Exact Glass UI Shared Components
// -------------------------------------------------------------------------------------------------

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    border: Color = MaruGlassBorderSoft,
    glowColor: Color? = null,
    background: Color = MaruGlassCardBg,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaruCardShape,
        color = background,
        border = BorderStroke(1.dp, border)
    ) {
        content()
    }
}

@Composable
fun GlassSectionHeader(title: String, icon: ImageVector, color: Color = MaruAccentPink) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            ),
            color = color
        )
    }
}

@Composable
fun GlassFeatureRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaruTextStrong, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaruAccentPink,
                uncheckedThumbColor = MaruTextMuted,
                uncheckedTrackColor = MaruGlassSubtleBg
            )
        )
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String? = null
) {
    Column {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaruAccentPink, modifier = Modifier.padding(bottom = 4.dp))
        }
        Surface(
            shape = MaruInputShape,
            color = Color(0x33000000),
            border = BorderStroke(1.dp, MaruGlassBorderSoft),
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(placeholder, color = MaruTextMuted.copy(alpha = 0.6f)) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = MaruTextStrong,
                    unfocusedTextColor = MaruTextStrong
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    borderColor: Color = MaruAccentPink.copy(alpha = 0.6f),
    background: Color = MaruGlassSubtleBg,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaruInputShape,
        color = background,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

@Composable
fun GlassNavigationDrawer(
    currentScreen: NavigationScreen,
    username: String,
    onSelectScreen: (NavigationScreen) -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF0E0A1A),
        drawerContentColor = MaruTextStrong,
        drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp),
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF1E1433), Color(0xFF100B1D), Color(0xFF07050A))
                    )
                )
                .statusBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaruAccentPink
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "MANIME",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaruTextStrong,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        if (username.isNotEmpty()) "Logged in as @$username" else "AniList Companion",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaruAccentPink
                    )
                }
            }

            HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)

            Text(
                NavigationGroup.RECOMMENDATION_ENGINE.title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaruAccentPink,
                    letterSpacing = 1.sp,
                    fontSize = 10.sp
                )
            )

            NavigationScreen.entries.filter { it.group == NavigationGroup.RECOMMENDATION_ENGINE }.forEach { screen ->
                DrawerItemRow(screen, isSelected = currentScreen == screen) { onSelectScreen(screen) }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                NavigationGroup.CORE_FUNCTIONALITY.title,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaruAccentBlue,
                    letterSpacing = 1.sp,
                    fontSize = 10.sp
                )
            )

            NavigationScreen.entries.filter { it.group == NavigationGroup.CORE_FUNCTIONALITY }.forEach { screen ->
                DrawerItemRow(screen, isSelected = currentScreen == screen) { onSelectScreen(screen) }
            }
        }
    }
}

@Composable
private fun DrawerItemRow(
    screen: NavigationScreen,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaruCardShape,
        color = if (isSelected) Color(0x33E85D9F) else Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, MaruAccentPink.copy(alpha = 0.5f)) else null,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                screen.icon,
                contentDescription = null,
                tint = if (isSelected) MaruAccentPink else MaruTextMuted,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    screen.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaruTextStrong else MaruTextMuted
                    )
                )
                Text(
                    screen.subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = if (isSelected) MaruAccentPink.copy(alpha = 0.8f) else MaruTextMuted.copy(alpha = 0.6f)
                )
            }
        }
    }
}


