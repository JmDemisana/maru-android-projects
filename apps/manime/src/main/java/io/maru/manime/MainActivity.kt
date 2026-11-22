package io.maru.manime

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import io.maru.manime.screens.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    val preferredAudio by prefs.preferredAudio.collectAsStateWithLifecycle(initialValue = "SUB")
    val preferredQuality by prefs.preferredQuality.collectAsStateWithLifecycle(initialValue = "1080p")
    val autoSkipIntro by prefs.autoSkipIntro.collectAsStateWithLifecycle(initialValue = false)

    // Crunchyroll launches directly onto Home feed!
    var selectedScreen by remember { mutableStateOf(NavigationScreen.DASHBOARD) }
    var selectedAnimeDetail by remember { mutableStateOf<AnimeMedia?>(null) }
    var selectedUserProfile by remember { mutableStateOf<String?>(null) }

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

    var userAnimeLists by remember(initialCachedLists) {
        mutableStateOf<Map<String, List<AnimeMedia>>>(initialCachedLists)
    }

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

    val refreshDashboard: () -> Unit = {
        scope.launch {
            isDashboardLoading = true
            try {
                val trending = withContext(Dispatchers.IO) { AniListClient.getTrending(25) }
                trendingList = trending

                val activities = withContext(Dispatchers.IO) {
                    AniListClient.getActivities(page = 1, perPage = 25, isFollowing = true, token = anilistToken.ifEmpty { null })
                }
                activitiesList = activities
            } catch (e: Exception) {
                Toast.makeText(context, "Error loading feed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                isDashboardLoading = false
            }
        }
    }

    val refreshProfile: () -> Unit = {
        val usernameToFetch = anilistUsername.ifEmpty { "PpolHi" }
        scope.launch {
            try {
                val (rawJson, lists) = withContext(Dispatchers.IO) {
                    AniListClient.getUserListRawJson(usernameToFetch, anilistToken.ifEmpty { null })
                }
                userAnimeLists = lists

                withContext(Dispatchers.IO) {
                    try {
                        cacheFile.writeText(rawJson)
                    } catch (_: Exception) {}
                }

                val totalWatching = mutableListOf<AnimeMedia>()
                var totalCompleted = 0
                var totalPlanning = 0

                for ((k, items) in lists) {
                    val lower = k.lowercase()
                    if (lower.contains("watch") || lower == "current") {
                        totalWatching.addAll(items)
                    }
                    if (lower.contains("complet") || lower.contains("finish")) {
                        totalCompleted += items.size
                    }
                    if (lower.contains("plan")) {
                        totalPlanning += items.size
                    }
                }
                watchingList = totalWatching.distinctBy { it.mediaId }
                completedCount = totalCompleted
                planningCount = totalPlanning
            } catch (_: Exception) {}
        }
    }

    val fetchRecommendations: (String) -> Unit = { category ->
        selectedRecCategory = category
        scope.launch {
            isRecLoading = true
            try {
                val recs = withContext(Dispatchers.IO) {
                    when (category) {
                        "For You" -> {
                            val seedIds = watchingList.map { it.mediaId }.take(10)
                            if (seedIds.isNotEmpty()) {
                                AniListClient.getRecommendations(seedIds)
                            } else {
                                AniListClient.getTrending(30)
                            }
                        }
                        "Trending Now" -> AniListClient.getTrending(30)
                        "Romance & CGDCT" -> AniListClient.browseCategory(genre = "Romance", tag = "Cute Girls Doing Cute Things", perPage = 30)
                        "Action & Shounen" -> AniListClient.browseCategory(genre = "Action", perPage = 30)
                        "Sci-Fi & Cyberpunk" -> AniListClient.browseCategory(genre = "Sci-Fi", tag = "Cyberpunk", perPage = 30)
                        "Music & Idols" -> AniListClient.browseCategory(genre = "Music", tag = "Idol", perPage = 30)
                        "Dubbed Only" -> {
                            val trending = AniListClient.getTrending(50)
                            trending.filter { m -> m.externalLinks.any { it.isEnglishDub } }
                        }
                        else -> AniListClient.getTrending(30)
                    }
                }
                recommendations = recs
            } catch (e: Exception) {
                Toast.makeText(context, "Error fetching catalog: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            } finally {
                isRecLoading = false
            }
        }
    }

    val performSearch: (String) -> Unit = { query ->
        if (query.isNotBlank()) {
            scope.launch {
                isSearching = true
                try {
                    val page = withContext(Dispatchers.IO) {
                        AniListClient.search(query, 1, 30, anilistToken.ifEmpty { null })
                    }
                    searchResults = page.results
                } catch (e: Exception) {
                    Toast.makeText(context, "Search error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                } finally {
                    isSearching = false
                }
            }
        }
    }

    LaunchedEffect(anilistToken, anilistUsername) {
        refreshProfile()
        refreshDashboard()
        fetchRecommendations("For You")
    }

    var effectiveUser by remember { mutableStateOf<AniListUser?>(null) }
    LaunchedEffect(anilistUsername, anilistAvatar) {
        if (anilistUsername.isNotBlank()) {
            effectiveUser = AniListUser(id = 0, name = anilistUsername, avatarUrl = anilistAvatar.ifEmpty { null })
        } else {
            effectiveUser = AniListUser(id = 0, name = "PpolHi", avatarUrl = "https://s4.anilist.co/file/anilistcdn/user/avatar/medium/b6197170-zN9M2ZgZg9Yh.png")
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = CrunchyBgBlack
    ) {
        Scaffold(
            containerColor = CrunchyBgBlack,
            topBar = {
                if (selectedAnimeDetail == null && selectedUserProfile == null) {
                    Column {
                        TopAppBar(
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_nanami_anime_logo),
                                        contentDescription = "Nanami Anime",
                                        modifier = Modifier.size(34.dp)
                                    )
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "nanami",
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = 19.sp,
                                                color = CrunchyOrange,
                                                letterSpacing = 0.5.sp
                                            )
                                            Text(
                                                text = "anime",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 19.sp,
                                                color = Color.White,
                                                letterSpacing = 0.5.sp
                                            )
                                        }
                                    }
                                }
                            },
                            actions = {
                                // Quick Audio Mode Indicator Pill (SUB / DUB)
                                Surface(
                                    onClick = {
                                        val nextMode = if (preferredAudio == "SUB") "DUB" else "SUB"
                                        scope.launch { prefs.setPreferredAudio(nextMode) }
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    color = CrunchyCardBg,
                                    border = BorderStroke(1.dp, CrunchyOrange),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text(
                                        text = if (preferredAudio == "DUB") "DUB" else "SUB",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = CrunchyOrange,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        selectedAnimeDetail = null
                                        selectedUserProfile = null
                                        selectedScreen = NavigationScreen.SEARCH
                                    }
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                                }

                                Surface(
                                    onClick = {
                                        selectedAnimeDetail = null
                                        selectedUserProfile = null
                                        selectedScreen = NavigationScreen.SETTINGS
                                    },
                                    shape = RoundedCornerShape(4.dp),
                                    color = CrunchyCardBg,
                                    border = BorderStroke(1.dp, CrunchyBorder),
                                    modifier = Modifier.padding(end = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (effectiveUser?.avatarUrl != null) {
                                            AsyncImage(
                                                model = effectiveUser?.avatarUrl,
                                                contentDescription = effectiveUser?.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .clip(CircleShape)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.AccountCircle,
                                                contentDescription = null,
                                                tint = CrunchyOrange,
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
                                containerColor = CrunchySurface,
                                titleContentColor = MaruTextStrong
                            )
                        )
                        HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)
                    }
                }
            },
            bottomBar = {
                if (selectedAnimeDetail == null && selectedUserProfile == null) {
                    Column {
                        HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)
                        NavigationBar(
                            containerColor = CrunchySurface,
                            contentColor = MaruTextStrong,
                            tonalElevation = 0.dp
                        ) {
                            NavigationScreen.values().forEach { screen ->
                                val isSelected = selectedScreen == screen
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = {
                                        selectedAnimeDetail = null
                                        selectedUserProfile = null
                                        selectedScreen = screen
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = screen.icon,
                                            contentDescription = screen.title,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = screen.title,
                                            fontSize = 10.5.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = CrunchyOrange,
                                        selectedTextColor = CrunchyOrange,
                                        indicatorColor = CrunchyOrange.copy(alpha = 0.14f),
                                        unselectedIconColor = MaruTextMuted,
                                        unselectedTextColor = MaruTextMuted
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
            ) {
                if (selectedAnimeDetail != null) {
                    AnimeDetailScreen(
                        media = selectedAnimeDetail!!,
                        anilistToken = anilistToken,
                        prefs = prefs,
                        onBack = { selectedAnimeDetail = null },
                        onMediaUpdated = { updated ->
                            selectedAnimeDetail = updated
                            refreshProfile()
                        },
                        onUserClick = { userClicked ->
                            selectedUserProfile = userClicked
                        }
                    )
                } else if (selectedUserProfile != null) {
                    UserProfileScreen(
                        username = selectedUserProfile!!,
                        anilistToken = anilistToken,
                        onBack = { selectedUserProfile = null },
                        onAnimeClick = { selectedAnimeDetail = it },
                        onUserClick = { newProfile -> selectedUserProfile = newProfile }
                    )
                } else {
                    AnimatedContent(
                        targetState = selectedScreen,
                        transitionSpec = {
                            val enter = fadeIn(animationSpec = tween(200, easing = FastOutSlowInEasing))
                            val exit = fadeOut(animationSpec = tween(140, easing = FastOutLinearInEasing))
                            enter togetherWith exit
                        },
                        label = "CrunchyMainScreenTransition"
                    ) { screen ->
                        when (screen) {
                            NavigationScreen.DASHBOARD -> DashboardScreen(
                                user = effectiveUser,
                                anilistToken = anilistToken,
                                watchingList = watchingList,
                                trendingList = trendingList,
                                activitiesList = activitiesList,
                                isLoading = isDashboardLoading,
                                onRefresh = refreshDashboard,
                                onPostCreated = { newAct ->
                                    activitiesList = listOf(newAct) + activitiesList
                                },
                                onAnimeClick = { selectedAnimeDetail = it },
                                onUserClick = { selectedUserProfile = it }
                            )
                            NavigationScreen.PROFILE -> ProfileScreen(
                                user = effectiveUser,
                                allLists = userAnimeLists,
                                watchingCount = watchingList.size,
                                completedCount = completedCount,
                                planningCount = planningCount,
                                anilistToken = anilistToken,
                                prefs = prefs,
                                isLoading = isDashboardLoading,
                                onLoginClick = {
                                    val authUrl = "https://anilist.co/api/v2/oauth/authorize?client_id=22736&response_type=token"
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                                    context.startActivity(intent)
                                },
                                onLogoutClick = {
                                    scope.launch {
                                        prefs.setAnilistToken("")
                                        prefs.setAnilistUsername("")
                                        prefs.setAnilistAvatar("")
                                    }
                                },
                                onAnimeClick = { selectedAnimeDetail = it }
                            )
                            NavigationScreen.RECOMMENDATIONS -> RecommendationsScreen(
                                recommendations = recommendations,
                                isLoading = isRecLoading,
                                isLoggedIn = anilistToken.isNotBlank(),
                                username = effectiveUser?.name ?: "PpolHi",
                                selectedCategory = selectedRecCategory,
                                onSelectCategory = fetchRecommendations,
                                onLoginClick = {
                                    val authUrl = "https://anilist.co/api/v2/oauth/authorize?client_id=22736&response_type=token"
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                                    context.startActivity(intent)
                                },
                                onAnimeClick = { selectedAnimeDetail = it }
                            )
                            NavigationScreen.SEARCH -> SearchScreen(
                                searchResults = searchResults,
                                isSearching = isSearching,
                                onSearch = performSearch,
                                onAnimeClick = { selectedAnimeDetail = it },
                                onUserClick = { selectedUserProfile = it }
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
                                onLoginClick = {
                                    val authUrl = "https://anilist.co/api/v2/oauth/authorize?client_id=22736&response_type=token"
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                                    context.startActivity(intent)
                                },
                                onLogoutClick = {
                                    scope.launch {
                                        prefs.setAnilistToken("")
                                        prefs.setAnilistUsername("")
                                        prefs.setAnilistAvatar("")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
