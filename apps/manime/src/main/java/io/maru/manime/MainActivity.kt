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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
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
import io.maru.manime.screens.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NavigationScreen(val label: String, val icon: ImageVector) {
    DASHBOARD("Discovery", Icons.Default.Explore),
    PROFILE("Library", Icons.Default.CollectionsBookmark),
    RECOMMENDATIONS("Taste", Icons.Default.AutoAwesome),
    SEARCH("Search", Icons.Default.Search),
    SETTINGS("Settings", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    private var pendingAuthCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        intent?.data?.let { uri -> handleDeepLink(uri) }

        setContent {
            val prefs = remember { MAnimePrefs(applicationContext) }
            AppNavigation(prefs = prefs, initialAuthCode = pendingAuthCode)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri -> handleDeepLink(uri) }
    }

    private fun handleDeepLink(uri: Uri) {
        if (uri.scheme == "manime" && uri.host == "auth") {
            val code = uri.getQueryParameter("code")
            val fragment = uri.fragment
            val token = if (!fragment.isNullOrBlank()) {
                val params = fragment.split("&").associate {
                    val parts = it.split("=")
                    if (parts.size == 2) parts[0] to parts[1] else "" to ""
                }
                params["access_token"]
            } else null

            val finalToken = token ?: code
            if (!finalToken.isNullOrBlank()) {
                val prefs = MAnimePrefs(applicationContext)
                lifecycleScope.launch {
                    prefs.setAnilistToken(finalToken)
                    try {
                        val user = AniListClient.getViewer(finalToken)
                        prefs.setAnilistUsername(user.name)
                        if (user.avatarUrl != null) prefs.setAnilistAvatar(user.avatarUrl)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(prefs: MAnimePrefs, initialAuthCode: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val anilistTokenFlow = prefs.anilistToken.collectAsStateWithLifecycle(initialValue = "")
    var anilistToken by remember { mutableStateOf("") }
    LaunchedEffect(anilistTokenFlow.value) { anilistToken = anilistTokenFlow.value }

    val anilistUsername by prefs.anilistUsername.collectAsStateWithLifecycle(initialValue = "")
    val anilistAvatar by prefs.anilistAvatar.collectAsStateWithLifecycle(initialValue = "")
    val reportProgress by prefs.reportProgress.collectAsStateWithLifecycle(initialValue = true)
    val rememberPosition by prefs.rememberPosition.collectAsStateWithLifecycle(initialValue = true)
    val cloudstreamReposSet by prefs.cloudstreamRepos.collectAsStateWithLifecycle(initialValue = emptySet())

    var selectedScreen by remember { mutableStateOf(NavigationScreen.PROFILE) }
    var selectedAnimeDetail by remember { mutableStateOf<AnimeMedia?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    var isGlobalLoading by remember { mutableStateOf(false) }

    // User library list disk cache for 0ms instant startup
    val cacheFile = remember { File(context.filesDir, "cached_user_lists.json") }
    val initialCachedLists = remember {
        if (cacheFile.exists()) {
            try {
                AniListClient.parseUserListJsonString(cacheFile.readText())
            } catch (_: Exception) {
                emptyMap()
            }
        } else emptyMap()
    }

    var allUserLists by remember { mutableStateOf(initialCachedLists) }

    val cachedUser = remember(anilistUsername, anilistAvatar) {
        if (anilistUsername.isNotBlank()) {
            AniListUser(id = 0, name = anilistUsername, avatarUrl = anilistAvatar.ifEmpty { null })
        } else null
    }
    var userProfile by remember { mutableStateOf<AniListUser?>(null) }
    val effectiveUser = userProfile ?: cachedUser

    var watchingList by remember(initialCachedLists) {
        val totalWatching = mutableListOf<AnimeMedia>()
        for ((k, items) in initialCachedLists) {
            val lower = k.lowercase()
            if (lower.contains("watch") || lower == "current") totalWatching.addAll(items)
        }
        mutableStateOf(totalWatching.distinctBy { it.mediaId })
    }

    var completedCount by remember(initialCachedLists) {
        val total = mutableListOf<AnimeMedia>()
        for ((k, items) in initialCachedLists) {
            val lower = k.lowercase()
            if (lower.contains("complet") || lower.contains("finish")) total.addAll(items)
        }
        mutableIntStateOf(total.distinctBy { it.mediaId }.size)
    }

    var planningCount by remember(initialCachedLists) {
        val total = mutableListOf<AnimeMedia>()
        for ((k, items) in initialCachedLists) {
            val lower = k.lowercase()
            if (lower.contains("plan")) total.addAll(items)
        }
        mutableIntStateOf(total.distinctBy { it.mediaId }.size)
    }

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
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        refreshInstalledCloudstream()
    }

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
                    prefs.setAnilistUsername(user.name)
                    if (user.avatarUrl != null) prefs.setAnilistAvatar(user.avatarUrl)

                    val (rawJson, userLists) = withContext(Dispatchers.IO) { AniListClient.getUserListRawJson(user.name, anilistToken) }
                    try {
                        withContext(Dispatchers.IO) {
                            cacheFile.writeText(rawJson)
                        }
                    } catch (_: Exception) {}
                    allUserLists = userLists

                    val totalWatching = mutableListOf<AnimeMedia>()
                    val totalCompleted = mutableListOf<AnimeMedia>()
                    val totalPlanning = mutableListOf<AnimeMedia>()

                    for ((listName, items) in userLists) {
                        val lower = listName.lowercase()
                        when {
                            lower.contains("watch") || lower == "current" -> totalWatching.addAll(items)
                            lower.contains("complet") || lower.contains("finish") -> totalCompleted.addAll(items)
                            lower.contains("plan") -> totalPlanning.addAll(items)
                        }
                    }

                    watchingList = totalWatching.distinctBy { it.mediaId }
                    completedCount = totalCompleted.distinctBy { it.mediaId }.size
                    planningCount = totalPlanning.distinctBy { it.mediaId }.size
                }
            } catch (_: Exception) {
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
                    when (cat) {
                        "For You" -> {
                            val allIds = (watchingList.map { it.mediaId } + allUserLists.values.flatten().map { it.mediaId }).distinct()
                            if (allIds.isNotEmpty()) {
                                val recs = AniListClient.getRecommendations(allIds.take(15))
                                if (recs.isNotEmpty()) recs else AniListClient.getTrending(30)
                            } else {
                                AniListClient.getTrending(30)
                            }
                        }
                        "CGDCT" -> AniListClient.browseCategory(genre = "Slice of Life", tag = "Cute Girls Doing Cute Things", perPage = 30)
                        "Idol" -> AniListClient.browseCategory(genre = "Music", tag = "Idol", perPage = 30)
                        else -> AniListClient.browseCategory(genre = cat, perPage = 30)
                    }
                }
                recommendations = results
            } catch (_: Exception) {
                try {
                    val fallback = withContext(Dispatchers.IO) { AniListClient.getTrending(30) }
                    recommendations = fallback
                } catch (_: Exception) {}
            } finally {
                isRecLoading = false
            }
        }
    }

    LaunchedEffect(anilistToken) {
        refreshDashboard()
        loadCategoryRecs("For You")
    }

    LaunchedEffect(selectedScreen) {
        if (selectedScreen == NavigationScreen.RECOMMENDATIONS && recommendations.isEmpty()) {
            loadCategoryRecs(selectedRecCategory)
        }
    }

    BackHandler(enabled = true) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (selectedAnimeDetail != null) {
            selectedAnimeDetail = null
        } else if (selectedScreen != NavigationScreen.PROFILE) {
            selectedScreen = NavigationScreen.PROFILE
        }
    }

    var isLoginDialogVisible by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = selectedAnimeDetail == null,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF0E0A1A),
                drawerContentColor = MaruTextStrong,
                modifier = Modifier.width(280.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(bottom = 16.dp, top = 8.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaruAccentPink.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, MaruAccentPink),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.PlayCircle,
                                        contentDescription = null,
                                        tint = MaruAccentPink,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "MANIME",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaruTextStrong,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "v2.0 â€¢ Maru Anime Client",
                                    fontSize = 10.sp,
                                    color = MaruTextMuted
                                )
                            }
                        }

                        HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(8.dp))

                        NavigationScreen.values().forEach { screen ->
                            val isSelected = selectedScreen == screen && selectedAnimeDetail == null
                            NavigationDrawerItem(
                                icon = {
                                    Icon(
                                        screen.icon,
                                        contentDescription = screen.label,
                                        tint = if (isSelected) MaruAccentPink else MaruTextMuted
                                    )
                                },
                                label = {
                                    Text(
                                        text = screen.label,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 14.sp,
                                        color = if (isSelected) MaruTextStrong else MaruTextMuted
                                    )
                                },
                                selected = isSelected,
                                onClick = {
                                    selectedAnimeDetail = null
                                    selectedScreen = screen
                                    scope.launch { drawerState.close() }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = NavigationDrawerItemDefaults.colors(
                                    selectedContainerColor = Color(0x33E85D9F),
                                    unselectedContainerColor = Color.Transparent
                                )
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x22FFFFFF),
                        border = BorderStroke(1.dp, MaruGlassBorderSoft),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    selectedAnimeDetail = null
                                    selectedScreen = NavigationScreen.SETTINGS
                                    scope.launch { drawerState.close() }
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (effectiveUser?.avatarUrl != null) {
                                AsyncImage(
                                    model = effectiveUser.avatarUrl,
                                    contentDescription = effectiveUser.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .border(BorderStroke(1.dp, MaruAccentPink), CircleShape)
                                )
                            } else {
                                Surface(
                                    shape = CircleShape,
                                    color = MaruGlassSubtleBg,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Person, contentDescription = null, tint = MaruTextMuted)
                                    }
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = effectiveUser?.name ?: "Guest User",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaruTextStrong
                                )
                                Text(
                                    text = if (anilistToken.isNotBlank()) "AniList Connected" else "Tap to login",
                                    fontSize = 10.sp,
                                    color = if (anilistToken.isNotBlank()) MaruAccentGreen else MaruAccentPink
                                )
                            }
                        }
                    }
                }
            }
        }
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaruBgBase
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF140D26),
                                    Color(0xFF090611),
                                    Color(0xFF040208)
                                )
                            )
                        )
                )

                Scaffold(
                    containerColor = Color.Transparent,
                    topBar = {
                        if (selectedAnimeDetail == null) {
                            TopAppBar(
                                title = {
                                    Text(
                                        text = selectedScreen.label,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 19.sp,
                                        color = MaruTextStrong
                                    )
                                },
                                navigationIcon = {
                                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                        Icon(Icons.Default.Menu, contentDescription = "Menu", tint = MaruTextStrong)
                                    }
                                },
                                actions = {
                                    Surface(
                                        onClick = {
                                            selectedAnimeDetail = null
                                            selectedScreen = NavigationScreen.SETTINGS
                                        },
                                        shape = MaruPillShape,
                                        color = Color(0x2218122B),
                                        border = BorderStroke(1.dp, MaruGlassBorderSoft),
                                        modifier = Modifier.padding(end = 12.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (effectiveUser?.avatarUrl != null) {
                                                AsyncImage(
                                                    model = effectiveUser.avatarUrl,
                                                    contentDescription = effectiveUser.name,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.AccountCircle,
                                                    contentDescription = null,
                                                    tint = MaruAccentPink,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Text(
                                                text = effectiveUser?.name ?: "Guest",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaruTextStrong
                                            )
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Color.Transparent,
                                    titleContentColor = MaruTextStrong
                                )
                            )
                        }
                    }
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        AnimatedContent(
                            targetState = selectedAnimeDetail,
                            transitionSpec = {
                                if (targetState != null) {
                                    (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                                            fadeIn(animationSpec = tween(280))) togetherWith
                                            (slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(240, easing = FastOutLinearInEasing)) +
                                                    fadeOut(animationSpec = tween(240)))
                                } else {
                                    (slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                                            fadeIn(animationSpec = tween(280))) togetherWith
                                            (slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(240, easing = FastOutLinearInEasing)) +
                                                    fadeOut(animationSpec = tween(240)))
                                }
                            },
                            label = "DetailTransition"
                        ) { detailMedia ->
                            if (detailMedia != null) {
                                AnimeDetailScreen(
                                    media = detailMedia,
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
                                        val enter = fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
                                                scaleIn(initialScale = 0.98f, animationSpec = tween(200, easing = FastOutSlowInEasing))
                                        val exit = fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing))
                                        enter togetherWith exit
                                    },
                                    label = "ScreenTransition"
                                ) { screen ->
                                    when (screen) {
                                        NavigationScreen.DASHBOARD -> {
                                            DashboardScreen(
                                                user = effectiveUser,
                                                anilistToken = anilistToken,
                                                watchingList = watchingList,
                                                trendingList = trendingList,
                                                activitiesList = activitiesList,
                                                isLoading = isDashboardLoading,
                                                onRefresh = refreshDashboard,
                                                onPostCreated = { act ->
                                                    activitiesList = listOf(act) + activitiesList
                                                },
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
                                                username = effectiveUser?.name ?: "User",
                                                selectedCategory = selectedRecCategory,
                                                onSelectCategory = { loadCategoryRecs(it) },
                                                onLoginClick = { isLoginDialogVisible = true },
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
                                                isLoading = isDashboardLoading,
                                                onLoginClick = { isLoginDialogVisible = true },
                                                onLogoutClick = {
                                                    anilistToken = ""
                                                    userProfile = null
                                                    allUserLists = emptyMap()
                                                    watchingList = emptyList()
                                                    completedCount = 0
                                                    planningCount = 0
                                                    scope.launch {
                                                        prefs.setAnilistToken("")
                                                        prefs.setAnilistUsername("")
                                                        prefs.setAnilistAvatar("")
                                                    }
                                                    try { if (cacheFile.exists()) cacheFile.delete() } catch (_: Exception) {}
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
                                                user = effectiveUser,
                                                anilistUsername = anilistUsername,
                                                isLoggedIn = anilistToken.isNotBlank(),
                                                reportProgress = reportProgress,
                                                onToggleReportProgress = { scope.launch { prefs.setReportProgress(it) } },
                                                rememberPosition = rememberPosition,
                                                onToggleRememberPosition = { scope.launch { prefs.setRememberPosition(it) } },
                                                onLoginClick = { isLoginDialogVisible = true },
                                                onLogoutClick = {
                                                    anilistToken = ""
                                                    userProfile = null
                                                    allUserLists = emptyMap()
                                                    watchingList = emptyList()
                                                    completedCount = 0
                                                    planningCount = 0
                                                    scope.launch {
                                                        prefs.setAnilistToken("")
                                                        prefs.setAnilistUsername("")
                                                        prefs.setAnilistAvatar("")
                                                    }
                                                    try { if (cacheFile.exists()) cacheFile.delete() } catch (_: Exception) {}
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
    }
}

