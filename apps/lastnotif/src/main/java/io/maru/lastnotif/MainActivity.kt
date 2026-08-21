package io.maru.lastnotif

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.ContentValues
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// --- Maru Cosmic Glassmorphic Design Tokens ---
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
val LastFmRed = Color(0xFFD51007)

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
    // Recommendation Engine Group (Discovery, exploration, stats, recap)
    DISCOVERY("Music Discovery", "Personalized Recommendations", Icons.Default.AutoAwesome, NavigationGroup.RECOMMENDATION_ENGINE),
    SEARCH("Search", "Tracks, Artists & Albums", Icons.Default.Search, NavigationGroup.RECOMMENDATION_ENGINE),
    PROFILE("Profile", "Scrobble Stats & Top Charts", Icons.Default.Person, NavigationGroup.RECOMMENDATION_ENGINE),
    NAMIREC("NamiRec", "Monthly Musical Recap", Icons.Default.Stars, NavigationGroup.RECOMMENDATION_ENGINE),

    // Core Functionality Group (Scrobbler & system listeners)
    SCROBBLING("Last.fm Scrobbler", "Accounts & Filters", Icons.Default.CloudUpload, NavigationGroup.CORE_FUNCTIONALITY),
    LOCAL("Local Monitor", "Media Controller", Icons.Default.GraphicEq, NavigationGroup.CORE_FUNCTIONALITY),
    RECEIVER("Remote Receiver", "Cross-device Sync", Icons.Default.Sensors, NavigationGroup.CORE_FUNCTIONALITY),
    COMMON("Common Settings", "Layout & Sync Alerts", Icons.Default.Settings, NavigationGroup.CORE_FUNCTIONALITY)
}

data class SongDetailState(
    val title: String,
    val artist: String,
    val album: String = "",
    val artworkUrl: String? = null,
    val appleMusicUrl: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null
)

class MainActivity : ComponentActivity() {
    private lateinit var prefs: LastNotifPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LastNotifPrefs(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        handleIntent(intent)
        setContent {
            LastNotifTheme {
                MainScreen(prefs)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null && data.scheme == "lastnotif" && data.host == "auth") {
            val token = data.getQueryParameter("token")
            if (token != null) {
                lifecycleScope.launch {
                    val session = withContext(Dispatchers.IO) {
                        LastFmScrobbler.getMobileSession(token)
                    }
                    if (session != null) {
                        prefs.setLastfmSessionKey(session.key)
                        prefs.setLastfmUsername(session.username)
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(prefs: LastNotifPrefs) {
    val context = LocalContext.current
    val scrobbleEnabled by prefs.scrobbleEnabled.collectAsStateWithLifecycle(initialValue = false)
    val receiverEnabled by prefs.receiverEnabled.collectAsStateWithLifecycle(initialValue = false)
    val localEnabled by prefs.localEnabled.collectAsStateWithLifecycle(initialValue = false)

    val lastfmUsername by prefs.lastfmUsername.collectAsStateWithLifecycle(initialValue = "")
    val sessionKey by prefs.lastfmSessionKey.collectAsStateWithLifecycle(initialValue = "")

    val scrobbleApps by prefs.scrobbleApps.collectAsStateWithLifecycle(initialValue = emptySet())
    val scrobblePercentage by prefs.scrobblePercentage.collectAsStateWithLifecycle(initialValue = 50)

    val receiverUsername by prefs.receiverUsername.collectAsStateWithLifecycle(initialValue = "")

    val localApps by prefs.localApps.collectAsStateWithLifecycle(initialValue = emptySet())
    val notifySongUpdate by prefs.notifySongUpdate.collectAsStateWithLifecycle(initialValue = true)
    val mainFmt by prefs.notifMainFormat.collectAsStateWithLifecycle(initialValue = "{song_name}")
    val subFmt by prefs.notifSubFormat.collectAsStateWithLifecycle(initialValue = "{artist}")
    val intervalEnabled by prefs.intervalEnabled.collectAsStateWithLifecycle(initialValue = false)
    val intervalMinutes by prefs.intervalMinutes.collectAsStateWithLifecycle(initialValue = 5)

    val preferredPlatform by prefs.preferredPlatform.collectAsStateWithLifecycle(initialValue = "Apple Music")

    val lastAlertTitle by prefs.lastAlertTitle.collectAsStateWithLifecycle(initialValue = "")
    val lastAlertSub by prefs.lastAlertSub.collectAsStateWithLifecycle(initialValue = "")
    val lastAlertSource by prefs.lastAlertSource.collectAsStateWithLifecycle(initialValue = "")

    val serviceRunning by prefs.serviceRunning.collectAsStateWithLifecycle(initialValue = false)
    val liveTrack by LastNotifPollerService.liveTrack.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()

    MainScreenContent(
        scrobbleEnabled = scrobbleEnabled,
        receiverEnabled = receiverEnabled,
        localEnabled = localEnabled,
        lastfmUsername = lastfmUsername,
        sessionKey = sessionKey,
        scrobbleApps = scrobbleApps,
        scrobblePercentage = scrobblePercentage,
        receiverUsername = receiverUsername,
        localApps = localApps,
        notifySongUpdate = notifySongUpdate,
        mainFmt = mainFmt,
        subFmt = subFmt,
        intervalEnabled = intervalEnabled,
        intervalMinutes = intervalMinutes,
        preferredPlatform = preferredPlatform,
        lastAlertTitle = lastAlertTitle,
        lastAlertSub = lastAlertSub,
        lastAlertSource = lastAlertSource,
        serviceRunning = serviceRunning,
        liveTrack = liveTrack,
        onToggleScrobble = { scope.launch { prefs.setScrobbleEnabled(it) } },
        onToggleReceiver = { scope.launch { prefs.setReceiverEnabled(it) } },
        onToggleLocal = { scope.launch { prefs.setLocalEnabled(it) } },
        onUsernameChange = { scope.launch { prefs.setLastfmUsername(it) } },
        onSessionKeyChange = { scope.launch { prefs.setLastfmSessionKey(it) } },
        onScrobbleAppsChange = { scope.launch { prefs.setScrobbleApps(it) } },
        onScrobblePercentageChange = { scope.launch { prefs.setScrobblePercentage(it) } },
        onReceiverUsernameChange = { scope.launch { prefs.setReceiverUsername(it) } },
        onLocalAppsChange = { scope.launch { prefs.setLocalApps(it) } },
        onToggleSongUpdate = { scope.launch { prefs.setNotifySongUpdate(it) } },
        onMainFmtChange = { scope.launch { prefs.setNotifMainFormat(it) } },
        onSubFmtChange = { scope.launch { prefs.setNotifSubFormat(it) } },
        onIntervalToggle = { scope.launch { prefs.setIntervalEnabled(it) } },
        onIntervalMinutesChange = { scope.launch { prefs.setIntervalMinutes(it) } },
        onPreferredPlatformChange = { scope.launch { prefs.setPreferredPlatform(it) } },
        onRestartService = {
            LastNotifPollerService.stop(context)
            LastNotifPollerService.start(context)
        },
        onSendTestNotification = {
            LastNotifNotificationManager(context).postTestAlert()
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    scrobbleEnabled: Boolean,
    receiverEnabled: Boolean,
    localEnabled: Boolean,
    lastfmUsername: String,
    sessionKey: String,
    scrobbleApps: Set<String>,
    scrobblePercentage: Int,
    receiverUsername: String,
    localApps: Set<String>,
    notifySongUpdate: Boolean,
    mainFmt: String,
    subFmt: String,
    intervalEnabled: Boolean,
    intervalMinutes: Int,
    preferredPlatform: String,
    lastAlertTitle: String,
    lastAlertSub: String,
    lastAlertSource: String,
    serviceRunning: Boolean,
    liveTrack: LastNotifPollerService.ActiveTrackState?,
    onToggleScrobble: (Boolean) -> Unit,
    onToggleReceiver: (Boolean) -> Unit,
    onToggleLocal: (Boolean) -> Unit,
    onUsernameChange: (String) -> Unit,
    onSessionKeyChange: (String) -> Unit,
    onScrobbleAppsChange: (Set<String>) -> Unit,
    onScrobblePercentageChange: (Int) -> Unit,
    onReceiverUsernameChange: (String) -> Unit,
    onLocalAppsChange: (Set<String>) -> Unit,
    onToggleSongUpdate: (Boolean) -> Unit,
    onMainFmtChange: (String) -> Unit,
    onSubFmtChange: (String) -> Unit,
    onIntervalToggle: (Boolean) -> Unit,
    onIntervalMinutesChange: (Int) -> Unit,
    onPreferredPlatformChange: (String) -> Unit,
    onRestartService: () -> Unit,
    onSendTestNotification: () -> Unit
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedScreen by remember(sessionKey.isNotEmpty()) {
        mutableStateOf(if (sessionKey.isNotEmpty()) NavigationScreen.DISCOVERY else NavigationScreen.SCROBBLING)
    }
    var viewedProfileUsername by remember { mutableStateOf<String?>(null) }
    val allApps = rememberAppList(context)
    var showManualCredsDialog by remember { mutableStateOf(false) }

    // Song Details Bottom Sheet State
    var selectedSongDetail by remember { mutableStateOf<SongDetailState?>(null) }

    // Discovery State
    var selectedCategory by remember { mutableStateOf(LastFmRecommendationsEngine.RecCategory.ALL) }
    var isGridView by remember { mutableStateOf(true) }
    var recPage by remember { mutableStateOf(1) }
    var recommendations by remember { mutableStateOf<List<LastFmRecommendationsEngine.RecommendedTrackItem>>(emptyList()) }
    var isRecommendationsLoading by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }

    fun refreshRecommendations(category: LastFmRecommendationsEngine.RecCategory = selectedCategory, reset: Boolean = true) {
        val user = lastfmUsername.ifEmpty { "JmDemisana" }
        scope.launch {
            if (reset) {
                isRecommendationsLoading = true
                recPage = 1
                recommendations = LastFmRecommendationsEngine.getRecommendations(user, category, page = 1)
                isRecommendationsLoading = false
            } else {
                isLoadingMore = true
                val nextPage = recPage + 1
                val items = LastFmRecommendationsEngine.getRecommendations(user, category, page = nextPage)
                if (items.isNotEmpty()) {
                    recPage = nextPage
                    recommendations = (recommendations + items).distinctBy { "${it.artist.lowercase()} - ${it.title.lowercase()}" }
                }
                isLoadingMore = false
            }
        }
    }

    LaunchedEffect(selectedScreen, selectedCategory, lastfmUsername) {
        if (selectedScreen == NavigationScreen.DISCOVERY && recommendations.isEmpty()) {
            refreshRecommendations(selectedCategory, reset = true)
        }
    }

    var hasNotifPerm by remember {
        mutableStateOf(LastNotifMediaMonitor.hasPostNotificationsPermission(context))
    }
    var hasMediaAccess by remember {
        mutableStateOf(LastNotifMediaMonitor.isNotificationAccessGranted(context))
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPerm = granted
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPerm) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(scrobbleEnabled, receiverEnabled, localEnabled) {
        if (scrobbleEnabled || receiverEnabled || localEnabled) {
            if (!LastNotifPollerService.isRunning()) {
                LastNotifPollerService.start(context)
            }
        } else {
            if (LastNotifPollerService.isRunning()) {
                LastNotifPollerService.stop(context)
            }
        }
    }

    val backgroundGradient = remember {
        Brush.radialGradient(
            colors = listOf(MaruCosmicTop, MaruCosmicMid, MaruCosmicBot),
            radius = 1600f
        )
    }

    val topBarGradient = remember {
        Brush.radialGradient(
            colors = listOf(MaruCosmicTop, MaruCosmicMid, MaruCosmicBot),
            radius = 1800f
        )
    }

    BackHandler {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (selectedSongDetail != null) {
            selectedSongDetail = null
        } else {
            Toast.makeText(
                context,
                "That back button did not do anything. If you want, close the app on your device's Recents. It will continue to scrobble in the background.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = selectedScreen != NavigationScreen.NAMIREC,
        drawerContent = {
            GlassNavigationDrawer(
                currentScreen = selectedScreen,
                username = lastfmUsername,
                serviceRunning = serviceRunning,
                onSelectScreen = { screen ->
                    if (screen == NavigationScreen.PROFILE) {
                        viewedProfileUsername = null
                    }
                    selectedScreen = screen
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(topBarGradient)
                        .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.Transparent, MaruGlassBorderSoft))))
                        .statusBarsPadding()
                ) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "Menu",
                                    tint = MaruAccentPink
                                )
                            }
                        },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_maru_heart),
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = Color.Unspecified
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "LASTNOTIF",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = MaruTextStrong,
                                        letterSpacing = 1.sp
                                    )
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .background(MaruAccentPink.copy(alpha = 0.15f), MaruPillShape)
                                        .border(BorderStroke(1.dp, MaruAccentPink.copy(alpha = 0.4f)), MaruPillShape)
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        selectedScreen.title.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                        color = MaruAccentPink
                                    )
                                }
                            }
                        },
                        actions = {
                            val animatedDotColor by animateColorAsState(
                                targetValue = if (serviceRunning) MaruAccentGreen else MaruTextMuted,
                                animationSpec = spring(stiffness = Spring.StiffnessLow),
                                label = "DotColor"
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .background(if (serviceRunning) MaruAccentGreen.copy(alpha = 0.12f) else MaruGlassSubtleBg, MaruPillShape)
                                    .border(BorderStroke(1.dp, if (serviceRunning) MaruAccentGreen.copy(alpha = 0.4f) else MaruGlassBorderSoft), MaruPillShape)
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Box(modifier = Modifier.size(7.dp).background(animatedDotColor, CircleShape))
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    if (serviceRunning) "ACTIVE" else "IDLE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    color = animatedDotColor
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaruTextStrong
                        )
                    )

                    // --- HERO LIVE NOTIFICATION PREVIEW (Click to launch playing player) ---
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)) {
                        NotificationPreviewBanner(
                            liveTrack = liveTrack,
                            lastAlertTitle = lastAlertTitle,
                            lastAlertSub = lastAlertSub,
                            lastAlertSource = lastAlertSource,
                            mainFmt = mainFmt,
                            subFmt = subFmt,
                            preferredPlatform = preferredPlatform,
                            onClick = {
                                val active = LastNotifMediaMonitor.getActiveTrack(context)
                                if (active != null && active.packageName.isNotEmpty()) {
                                    val launchIntent = context.packageManager.getLaunchIntentForPackage(active.packageName)
                                    if (launchIntent != null) {
                                        context.startActivity(launchIntent)
                                        return@NotificationPreviewBanner
                                    }
                                }
                                val title = liveTrack?.title?.ifEmpty { lastAlertTitle } ?: "Tell Your World"
                                val artist = liveTrack?.artist?.ifEmpty { lastAlertSub } ?: "kz (livetune)"
                                ItunesClient.openInPreferredPlayer(context, preferredPlatform, title, artist, null)
                            }
                        )
                    }
                }
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundGradient)
                    .padding(innerPadding)
            ) {
                Crossfade(
                    targetState = selectedScreen,
                    animationSpec = tween(150),
                    label = "ScreenCrossfade"
                ) { targetScreen ->
                    when (targetScreen) {
                        NavigationScreen.DISCOVERY -> {
                            DiscoveryScreen(
                                recommendations = recommendations,
                                isLoading = isRecommendationsLoading,
                                isLoadingMore = isLoadingMore,
                                selectedCategory = selectedCategory,
                                isGridView = isGridView,
                                preferredPlatform = preferredPlatform,
                                onCategoryChange = { cat ->
                                    selectedCategory = cat
                                    refreshRecommendations(cat, reset = true)
                                },
                                onToggleGridView = { isGridView = !isGridView },
                                onRefresh = { refreshRecommendations(selectedCategory, reset = true) },
                                onLoadMore = { refreshRecommendations(selectedCategory, reset = false) },
                                onSongClick = { item ->
                                    selectedSongDetail = SongDetailState(
                                        title = item.title,
                                        artist = item.artist,
                                        album = item.album,
                                        artworkUrl = item.effectiveArtworkUrl,
                                        appleMusicUrl = item.itunesMatch?.appleMusicUrl
                                    )
                                }
                            )
                        }

                        NavigationScreen.SEARCH -> {
                            SearchScreen(
                                preferredPlatform = preferredPlatform,
                                onSongClick = { match ->
                                    selectedSongDetail = SongDetailState(
                                        title = match.trackName,
                                        artist = match.artistName,
                                        album = match.collectionName,
                                        artworkUrl = match.artworkUrl,
                                        appleMusicUrl = match.appleMusicUrl
                                    )
                                },
                                onOpenProfile = { profileUser ->
                                    viewedProfileUsername = profileUser
                                    selectedScreen = NavigationScreen.PROFILE
                                }
                            )
                        }

                        NavigationScreen.PROFILE -> {
                            ProfileScreen(
                                username = viewedProfileUsername ?: lastfmUsername.ifEmpty { "JmDemisana" },
                                preferredPlatform = preferredPlatform,
                                onSongClick = { title, artist, art ->
                                    selectedSongDetail = SongDetailState(
                                        title = title,
                                        artist = artist,
                                        artworkUrl = art
                                    )
                                }
                            )
                        }

                        NavigationScreen.NAMIREC -> {
                            NamiRecScreen(
                                username = lastfmUsername.ifEmpty { "JmDemisana" }
                            )
                        }

                        NavigationScreen.SCROBBLING -> {
                            ScrobblingScreen(
                                scrobbleEnabled = scrobbleEnabled,
                                onToggleScrobble = onToggleScrobble,
                                sessionKey = sessionKey,
                                lastfmUsername = lastfmUsername,
                                onSessionKeyChange = onSessionKeyChange,
                                onUsernameChange = onUsernameChange,
                                scrobblePercentage = scrobblePercentage,
                                onScrobblePercentageChange = onScrobblePercentageChange,
                                allApps = allApps,
                                scrobbleApps = scrobbleApps,
                                onScrobbleAppsChange = onScrobbleAppsChange,
                                onShowManualDialog = { showManualCredsDialog = true }
                            )
                        }

                        NavigationScreen.LOCAL -> {
                            LocalScreen(
                                localEnabled = localEnabled,
                                onToggleLocal = onToggleLocal,
                                allApps = allApps,
                                localApps = localApps,
                                onLocalAppsChange = onLocalAppsChange
                            )
                        }

                        NavigationScreen.RECEIVER -> {
                            ReceiverScreen(
                                receiverEnabled = receiverEnabled,
                                onToggleReceiver = onToggleReceiver,
                                receiverUsername = receiverUsername,
                                lastfmUsername = lastfmUsername,
                                onReceiverUsernameChange = onReceiverUsernameChange
                            )
                        }

                        NavigationScreen.COMMON -> {
                            CommonSettingsScreen(
                                mainFmt = mainFmt,
                                subFmt = subFmt,
                                onMainFmtChange = onMainFmtChange,
                                onSubFmtChange = onSubFmtChange,
                                preferredPlatform = preferredPlatform,
                                onPreferredPlatformChange = onPreferredPlatformChange,
                                notifySongUpdate = notifySongUpdate,
                                onToggleSongUpdate = onToggleSongUpdate,
                                intervalEnabled = intervalEnabled,
                                onIntervalToggle = onIntervalToggle,
                                intervalMinutes = intervalMinutes,
                                onIntervalMinutesChange = onIntervalMinutesChange,
                                onSendTestNotification = onSendTestNotification,
                                onRestartService = onRestartService
                            )
                        }
                    }
                }

                // Song Detail Bottom Sheet
                selectedSongDetail?.let { detail ->
                    SongDetailBottomSheet(
                        song = detail,
                        preferredPlatform = preferredPlatform,
                        onDismiss = { selectedSongDetail = null },
                        onSelectSimilarSong = { newSong ->
                            selectedSongDetail = newSong
                        }
                    )
                }

                // Manual Credentials Dialog
                if (showManualCredsDialog) {
                    ManualSessionKeyDialog(
                        currentKey = sessionKey,
                        currentUsername = lastfmUsername,
                        onSave = { key, user ->
                            onSessionKeyChange(key)
                            onUsernameChange(user)
                            showManualCredsDialog = false
                        },
                        onDismiss = { showManualCredsDialog = false }
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 1. DISCOVERY SCREEN (Swipe-to-Refresh & Category Tabs)
// -------------------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    recommendations: List<LastFmRecommendationsEngine.RecommendedTrackItem>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    selectedCategory: LastFmRecommendationsEngine.RecCategory,
    isGridView: Boolean,
    preferredPlatform: String,
    onCategoryChange: (LastFmRecommendationsEngine.RecCategory) -> Unit,
    onToggleGridView: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSongClick: (LastFmRecommendationsEngine.RecommendedTrackItem) -> Unit
) {
    val pullRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()

    val shouldLoadMore by remember(recommendations.size, isLoading, isLoadingMore) {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 4 && lastVisible >= total - 3 && !isLoading && !isLoadingMore
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        state = pullRefreshState,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Category Tab Bar: All Recommendations | Artists | Albums | Tracks
            item(key = "category_tabs") {
                ScrollableTabRow(
                    selectedTabIndex = selectedCategory.ordinal,
                    containerColor = Color.Transparent,
                    contentColor = MaruAccentPink,
                    edgePadding = 0.dp,
                    divider = { HorizontalDivider(color = MaruGlassBorderSoft) },
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedCategory.ordinal]),
                            color = MaruAccentPink
                        )
                    }
                ) {
                    LastFmRecommendationsEngine.RecCategory.entries.forEach { cat ->
                        val selected = selectedCategory == cat
                        Tab(
                            selected = selected,
                            onClick = {
                                if (selectedCategory != cat) {
                                    onCategoryChange(cat)
                                }
                            },
                            text = {
                                Text(
                                    if (cat == LastFmRecommendationsEngine.RecCategory.ALL) "All Recommendations" else cat.label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        letterSpacing = 0.5.sp,
                                        fontSize = 11.5.sp
                                    ),
                                    color = if (selected) MaruAccentPink else MaruTextMuted
                                )
                            }
                        )
                    }
                }
            }

            // Sub-header row with Category Title and Grid Toggle
            item(key = "subheader") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome, null, Modifier.size(15.dp), tint = MaruAccentPink)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "DISCOVERY FEED",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaruAccentPink,
                                letterSpacing = 0.8.sp
                            )
                        )
                    }

                    IconButton(
                        onClick = onToggleGridView,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (isGridView) Icons.Default.List else Icons.Default.GridView,
                            contentDescription = "Toggle View",
                            tint = MaruAccentBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (isLoading && recommendations.isEmpty()) {
                item(key = "loading_full") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(36.dp))
                            Text(
                                "Curating personalized recommendations...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaruTextMuted
                            )
                        }
                    }
                }
            } else if (recommendations.isEmpty()) {
                item(key = "empty_state") {
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.MusicOff, null, tint = MaruTextMuted, modifier = Modifier.size(36.dp))
                            Text(
                                "No recommendations found",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaruTextStrong
                            )
                            Text(
                                "Swipe down to refresh or check your Last.fm scrobbler settings!",
                                style = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center),
                                color = MaruTextMuted
                            )
                        }
                    }
                }
            } else {
                if (isGridView) {
                    val chunked = recommendations.chunked(2)
                    items(chunked, key = { row -> row.joinToString { "${it.artist}_${it.title}" } }) { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowItems.forEach { item ->
                                GlassRecommendationGridCard(
                                    item = item,
                                    onClick = { onSongClick(item) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (rowItems.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    items(recommendations, key = { "${it.artist}_${it.title}" }) { item ->
                        GlassRecommendationCard(
                            item = item,
                            onClick = { onSongClick(item) }
                        )
                    }
                }

                // Automatic Infinite Scroll Loading Indicator (No extra button clicks needed!)
                if (isLoadingMore) {
                    item(key = "loading_more_indicator") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    "DISCOVERING MORE MUSIC...",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = MaruTextMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 2. SEARCH SCREEN (Instant Debounced Search + Filtering + Profile Lookup)
// -------------------------------------------------------------------------------------------------
enum class SearchScope(val label: String) {
    MUSIC("Songs, Artists, Albums"),
    PROFILES("Last.fm Profiles")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    preferredPlatform: String,
    onSongClick: (ItunesClient.ItunesSongMatch) -> Unit,
    onOpenProfile: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedScope by remember { mutableStateOf(SearchScope.MUSIC) }
    var searchQuery by remember { mutableStateOf("") }

    // Music search states
    var musicResults by remember { mutableStateOf<List<ItunesClient.ItunesSongMatch>>(emptyList()) }
    var isMusicSearching by remember { mutableStateOf(false) }

    // Profile search states
    var profileResult by remember { mutableStateOf<LastNotifApiClient.UserProfile?>(null) }
    var profileRecentTracks by remember { mutableStateOf<List<LastNotifApiClient.ScrobbleItem>>(emptyList()) }
    var isProfileSearching by remember { mutableStateOf(false) }
    var profileNotFound by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(searchQuery, selectedScope) {
        val q = searchQuery.trim()
        if (selectedScope == SearchScope.MUSIC) {
            if (q.length >= 2) {
                delay(350)
                isMusicSearching = true
                musicResults = withContext(Dispatchers.IO) { ItunesClient.searchInstant(q) }
                isMusicSearching = false
            } else if (q.isEmpty()) {
                musicResults = emptyList()
                isMusicSearching = false
            }
        } else {
            if (q.length >= 2) {
                delay(400)
                isProfileSearching = true
                profileNotFound = false
                val (user, recents) = withContext(Dispatchers.IO) {
                    val u = LastNotifApiClient.getUserInfo(q)
                    val r = if (u != null) LastNotifApiClient.getRecentTracks(q, limit = 4) else emptyList()
                    u to r
                }
                profileResult = user
                profileRecentTracks = recents
                profileNotFound = (user == null)
                isProfileSearching = false
            } else if (q.isEmpty()) {
                profileResult = null
                profileRecentTracks = emptyList()
                profileNotFound = false
                isProfileSearching = false
            }
        }
    }

    val pullRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = if (selectedScope == SearchScope.MUSIC) isMusicSearching else isProfileSearching,
        onRefresh = {
            val q = searchQuery.trim()
            if (q.isNotBlank()) {
                scope.launch {
                    if (selectedScope == SearchScope.MUSIC) {
                        isMusicSearching = true
                        musicResults = withContext(Dispatchers.IO) { ItunesClient.searchInstant(q) }
                        isMusicSearching = false
                    } else {
                        isProfileSearching = true
                        profileNotFound = false
                        val (user, recents) = withContext(Dispatchers.IO) {
                            val u = LastNotifApiClient.getUserInfo(q)
                            val r = if (u != null) LastNotifApiClient.getRecentTracks(q, limit = 4) else emptyList()
                            u to r
                        }
                        profileResult = user
                        profileRecentTracks = recents
                        profileNotFound = (user == null)
                        isProfileSearching = false
                    }
                }
            }
        },
        state = pullRefreshState,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Scope Picker Tabs (Songs, Artists, Albums vs Last.fm Profiles)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaruGlassCardBg, MaruPillShape)
                    .border(BorderStroke(1.dp, MaruGlassBorderSoft), MaruPillShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SearchScope.entries.forEach { scopeItem ->
                    val isSelected = selectedScope == scopeItem
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isSelected) MaruAccentPink.copy(alpha = 0.22f) else Color.Transparent,
                                MaruPillShape
                            )
                            .border(
                                BorderStroke(
                                    1.dp,
                                    if (isSelected) MaruAccentPink.copy(alpha = 0.6f) else Color.Transparent
                                ),
                                MaruPillShape
                            )
                            .clickable { selectedScope = scopeItem }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            scopeItem.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 11.sp
                            ),
                            color = if (isSelected) MaruAccentPink else MaruTextMuted,
                            maxLines = 1
                        )
                    }
                }
            }

            // Search Input Field
            GlassCard(
                border = MaruAccentPink.copy(alpha = 0.5f),
                background = Color(0x9918122B)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (selectedScope == SearchScope.MUSIC) Icons.Default.Search else Icons.Default.PersonSearch,
                        null,
                        tint = MaruAccentPink,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                if (selectedScope == SearchScope.MUSIC) "Search songs, artists, albums..." else "Search Last.fm username...",
                                color = MaruTextMuted.copy(alpha = 0.6f)
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = MaruTextStrong,
                            unfocusedTextColor = MaruTextStrong
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, null, tint = MaruTextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Explicit content limitation notice (for Music search)
            if (selectedScope == SearchScope.MUSIC) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaruAccentPurple.copy(alpha = 0.85f),
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Note: Search only supports songs that are not explicit.",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaruTextMuted.copy(alpha = 0.75f)
                    )
                }
            }

            // Results Section
            if (selectedScope == SearchScope.MUSIC) {
                if (isMusicSearching && musicResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(32.dp))
                    }
                } else if (musicResults.isNotEmpty()) {
                    Text(
                        "RESULTS (${musicResults.size})",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaruAccentPink,
                            letterSpacing = 0.8.sp
                        )
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        musicResults.forEach { match ->
                            GlassCard(
                                border = MaruGlassBorderSoft,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSongClick(match) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!match.artworkUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = match.artworkUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(54.dp)
                                                .clip(MaruCardShape)
                                                .background(MaruGlassSubtleBg),
                                            contentScale = ContentScale.Crop,
                                            error = painterResource(id = R.drawable.ic_maru_heart),
                                            placeholder = painterResource(id = R.drawable.ic_maru_heart)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(54.dp)
                                                .clip(MaruCardShape)
                                                .background(MaruGlassSubtleBg)
                                                .border(BorderStroke(1.dp, MaruGlassBorderSoft), MaruCardShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.ic_maru_heart),
                                                contentDescription = null,
                                                tint = Color.Unspecified,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            match.trackName,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaruTextStrong,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            match.artistName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaruTextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (match.collectionName.isNotEmpty()) {
                                            Text(
                                                match.collectionName,
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = MaruAccentPurple.copy(alpha = 0.8f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = "Details",
                                        tint = MaruAccentPink,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                } else if (searchQuery.isNotEmpty()) {
                    GlassCard {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No songs found for \"$searchQuery\"", color = MaruTextMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    // Empty search suggestions
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.TrendingUp, null, tint = MaruAccentPink, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("POPULAR DISCOVERIES", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruAccentPink)
                            }
                            val sampleQueries = listOf("YOASOBI", "Hatsune Miku", "GUMI", "Kenshi Yonezu", "Pastel*Palettes", "DECO*27")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                sampleQueries.take(3).forEach { q ->
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .background(MaruGlassSubtleBg, MaruPillShape)
                                            .border(BorderStroke(1.dp, MaruGlassBorderSoft), MaruPillShape)
                                            .clickable { searchQuery = q }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(q, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = MaruTextStrong, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Profile Search Results
                if (isProfileSearching && profileResult == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(32.dp))
                    }
                } else if (profileResult != null) {
                    val user = profileResult!!
                    Text(
                        "LAST.FM PROFILE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaruAccentPink,
                            letterSpacing = 0.8.sp
                        )
                    )

                    GlassCard(
                        border = MaruAccentPink.copy(alpha = 0.4f),
                        glowColor = MaruAccentPink.copy(alpha = 0.15f)
                    ) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(CircleShape)
                                        .background(MaruGlassSubtleBg)
                                        .border(1.5.dp, MaruAccentPink, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!user.avatarUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = user.avatarUrl,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop,
                                            error = painterResource(id = R.drawable.ic_maru_heart),
                                            placeholder = painterResource(id = R.drawable.ic_maru_heart)
                                        )
                                    } else {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_maru_heart),
                                            contentDescription = null,
                                            tint = Color.Unspecified,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        user.realName.ifEmpty { user.username },
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaruTextStrong
                                    )
                                    Text(
                                        "@${user.username}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaruAccentPink
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${user.playcount} scrobbles",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaruAccentGreen
                                    )
                                }
                            }

                            if (profileRecentTracks.isNotEmpty()) {
                                HorizontalDivider(color = MaruGlassBorderSoft)
                                Text(
                                    "RECENT ACTIVITY",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = MaruAccentBlue)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    profileRecentTracks.take(3).forEach { track ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (!track.artworkUrl.isNullOrBlank()) {
                                                AsyncImage(
                                                    model = track.artworkUrl,
                                                    contentDescription = null,
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(MaruCardShape)
                                                        .background(MaruGlassSubtleBg),
                                                    contentScale = ContentScale.Crop,
                                                    error = painterResource(id = R.drawable.ic_maru_heart),
                                                    placeholder = painterResource(id = R.drawable.ic_maru_heart)
                                                )
                                            } else {
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(MaruCardShape)
                                                        .background(MaruGlassSubtleBg)
                                                        .border(BorderStroke(1.dp, MaruGlassBorderSoft), MaruCardShape),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        painter = painterResource(id = R.drawable.ic_maru_heart),
                                                        contentDescription = null,
                                                        tint = Color.Unspecified,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(track.title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold), color = MaruTextStrong, maxLines = 1)
                                                Text(track.artist, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaruTextMuted, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                GlassButton(
                                    onClick = { onOpenProfile(user.username) },
                                    modifier = Modifier.weight(1f),
                                    borderColor = MaruAccentPink,
                                    background = MaruAccentPink.copy(alpha = 0.2f)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Person, null, tint = MaruAccentPink, modifier = Modifier.size(16.dp))
                                        Text("VIEW FULL PROFILE", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruAccentPink)
                                    }
                                }

                                GlassButton(
                                    onClick = {
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(user.url.ifEmpty { "https://www.last.fm/user/${user.username}" }))
                                            context.startActivity(intent)
                                        } catch (_: Exception) {}
                                    },
                                    modifier = Modifier.weight(1f),
                                    borderColor = MaruAccentBlue,
                                    background = MaruAccentBlue.copy(alpha = 0.15f)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.OpenInNew, null, tint = MaruAccentBlue, modifier = Modifier.size(16.dp))
                                        Text("OPEN ON LAST.FM", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruAccentBlue)
                                    }
                                }
                            }
                        }
                    }
                } else if (profileNotFound) {
                    GlassCard {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No Last.fm user found for \"$searchQuery\"", color = MaruTextMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    // Quick profile suggestions
                    GlassCard {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Group, null, tint = MaruAccentPink, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("QUICK PROFILE LOOKUP", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruAccentPink)
                            }
                            val sampleProfiles = listOf("JmDemisana")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                sampleProfiles.forEach { u ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaruGlassSubtleBg, MaruPillShape)
                                            .border(BorderStroke(1.dp, MaruGlassBorderSoft), MaruPillShape)
                                            .clickable { searchQuery = u }
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("@$u", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), color = MaruTextStrong, maxLines = 1)
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

// -------------------------------------------------------------------------------------------------
// 3. PROFILE SCREEN (User Stats, History & Top Charts across timeframes)
// -------------------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    username: String,
    preferredPlatform: String,
    onSongClick: (String, String, String?) -> Unit
) {
    var userProfile by remember { mutableStateOf<LastNotifApiClient.UserProfile?>(null) }
    var recentScrobbles by remember { mutableStateOf<List<LastNotifApiClient.ScrobbleItem>>(emptyList()) }
    var topTracks by remember { mutableStateOf<List<LastNotifApiClient.TopItem>>(emptyList()) }
    var topArtists by remember { mutableStateOf<List<LastNotifApiClient.TopItem>>(emptyList()) }
    var topAlbums by remember { mutableStateOf<List<LastNotifApiClient.TopItem>>(emptyList()) }

    var selectedTab by remember { mutableStateOf(0) } // 0: History, 1: Top Tracks, 2: Top Artists, 3: Top Albums
    var selectedPeriod by remember { mutableStateOf("1month") } // 7day, 1month, 3month, 12month, overall
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun loadProfileData() {
        scope.launch {
            isLoading = true
            withContext(Dispatchers.IO) {
                userProfile = LastNotifApiClient.getUserInfo(username)
                recentScrobbles = LastNotifApiClient.getRecentTracks(username, limit = 25)
                topTracks = LastNotifApiClient.getTopTracks(username, period = selectedPeriod, limit = 15)
                topArtists = LastNotifApiClient.getTopArtists(username, period = selectedPeriod, limit = 15)
                topAlbums = LastNotifApiClient.getTopAlbums(username, period = selectedPeriod, limit = 15)
            }
            isLoading = false
        }
    }

    LaunchedEffect(username, selectedPeriod) {
        loadProfileData()
    }

    val pullRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = { loadProfileData() },
        state = pullRefreshState,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Profile Header Card
            GlassCard(
                border = MaruAccentPink.copy(alpha = 0.4f),
                glowColor = MaruAccentPink.copy(alpha = 0.15f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(MaruGlassSubtleBg)
                            .border(1.5.dp, MaruAccentPink, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (userProfile?.avatarUrl != null) {
                            AsyncImage(
                                model = userProfile?.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.Person, null, tint = MaruAccentPink, modifier = Modifier.size(36.dp))
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            userProfile?.realName?.ifEmpty { username } ?: username,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaruTextStrong
                        )
                        Text(
                            "@$username",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaruAccentPink
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column {
                                Text(
                                    "${userProfile?.playcount ?: 0}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaruTextStrong
                                )
                                Text("SCROBBLES", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaruTextMuted)
                            }
                        }
                    }
                }
            }

            // Profile Tabs: Recent History | Top Tracks | Top Artists | Top Albums
            val tabs = listOf("Recent History", "Top Tracks", "Top Artists", "Top Albums")
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaruAccentPink,
                edgePadding = 0.dp,
                divider = { HorizontalDivider(color = MaruGlassBorderSoft) },
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = MaruAccentPink
                    )
                }
            ) {
                tabs.forEachIndexed { idx, title ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (selectedTab == idx) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                ),
                                color = if (selectedTab == idx) MaruAccentPink else MaruTextMuted
                            )
                        }
                    )
                }
            }

            // Period Selector Chips (Only for Top charts)
            if (selectedTab > 0) {
                val periods = listOf(
                    "7day" to "7 Days",
                    "1month" to "1 Month",
                    "3month" to "3 Months",
                    "12month" to "1 Year",
                    "overall" to "All Time"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    periods.forEach { (key, label) ->
                        val isSelected = selectedPeriod == key
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(if (isSelected) MaruAccentPink.copy(alpha = 0.2f) else MaruGlassSubtleBg, MaruPillShape)
                                .border(BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft), MaruPillShape)
                                .clickable { selectedPeriod = key }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (isSelected) MaruAccentPink else MaruTextMuted
                            )
                        }
                    }
                }
            }

            // Tab Content
            when (selectedTab) {
                0 -> { // Recent History
                    if (recentScrobbles.isEmpty()) {
                        GlassCard {
                            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text("No recent scrobbles found", color = MaruTextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            recentScrobbles.forEach { item ->
                                GlassCard(
                                    border = if (item.isNowPlaying) MaruAccentGreen.copy(alpha = 0.5f) else MaruGlassBorderSoft,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSongClick(item.title, item.artist, item.artworkUrl) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AutoArtworkImage(
                                            title = item.title,
                                            artist = item.artist,
                                            initialArtworkUrl = item.artworkUrl,
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                item.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaruTextStrong,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                item.artist,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaruTextMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (item.isNowPlaying) {
                                            Box(
                                                modifier = Modifier
                                                    .background(MaruAccentGreen.copy(alpha = 0.15f), MaruPillShape)
                                                    .border(BorderStroke(1.dp, MaruAccentGreen.copy(alpha = 0.5f)), MaruPillShape)
                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Text("SCROBBLING", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold), color = MaruAccentGreen)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> { // Top Tracks
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        topTracks.forEach { item ->
                            GlassRankRow(
                                rank = item.rank,
                                title = item.name,
                                subtitle = item.subtext,
                                playcount = item.playcount,
                                artworkUrl = item.artworkUrl,
                                onClick = { onSongClick(item.name, item.subtext, item.artworkUrl) }
                            )
                        }
                    }
                }

                2 -> { // Top Artists
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        topArtists.forEach { item ->
                            GlassRankRow(
                                rank = item.rank,
                                title = item.name,
                                subtitle = item.subtext,
                                playcount = item.playcount,
                                artworkUrl = null,
                                isArtistMode = true,
                                onClick = { onSongClick("", item.name, null) }
                            )
                        }
                    }
                }

                3 -> { // Top Albums
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        topAlbums.forEach { item ->
                            GlassRankRow(
                                rank = item.rank,
                                title = item.name,
                                subtitle = item.subtext,
                                playcount = item.playcount,
                                artworkUrl = item.artworkUrl,
                                isAlbumMode = true,
                                onClick = { onSongClick(item.name, item.subtext, item.artworkUrl) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 3.5. AUTO ARTWORK RESOLVER (iTunes Auto Lookup with fallback)
// -------------------------------------------------------------------------------------------------
@Composable
fun AutoArtworkImage(
    title: String,
    artist: String,
    initialArtworkUrl: String?,
    modifier: Modifier = Modifier,
    isArtistMode: Boolean = false,
    isAlbumMode: Boolean = false
) {
    var artworkUrl by remember(title, artist, initialArtworkUrl) { mutableStateOf(initialArtworkUrl) }

    LaunchedEffect(title, artist, initialArtworkUrl) {
        if (artworkUrl.isNullOrBlank() && (title.isNotEmpty() || artist.isNotEmpty())) {
            withContext(Dispatchers.IO) {
                val match = when {
                    isArtistMode -> ItunesClient.searchArtist(artist.ifEmpty { title })
                    isAlbumMode -> ItunesClient.searchAlbum(title, artist)
                    else -> ItunesClient.searchSong(title, artist)
                }
                if (!match?.artworkUrl.isNullOrBlank()) {
                    artworkUrl = match?.artworkUrl
                }
            }
        }
    }

    if (!artworkUrl.isNullOrBlank()) {
        AsyncImage(
            model = artworkUrl,
            contentDescription = null,
            modifier = modifier
                .clip(MaruCardShape)
                .background(MaruGlassSubtleBg),
            contentScale = ContentScale.Crop,
            error = painterResource(id = R.drawable.ic_maru_heart),
            placeholder = painterResource(id = R.drawable.ic_maru_heart)
        )
    } else {
        Box(
            modifier = modifier
                .clip(MaruCardShape)
                .background(MaruGlassSubtleBg)
                .border(BorderStroke(1.dp, MaruGlassBorderSoft), MaruCardShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_maru_heart),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun GlassRankRow(
    rank: Int,
    title: String,
    subtitle: String,
    playcount: Long,
    artworkUrl: String?,
    isArtistMode: Boolean = false,
    isAlbumMode: Boolean = false,
    onClick: () -> Unit
) {
    GlassCard(
        border = MaruGlassBorderSoft,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "#$rank",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (rank <= 3) MaruAccentPink else MaruTextMuted
                )
            }

            AutoArtworkImage(
                title = title,
                artist = subtitle,
                initialArtworkUrl = artworkUrl,
                isArtistMode = isArtistMode,
                isAlbumMode = isAlbumMode,
                modifier = Modifier.size(46.dp)
            )
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaruTextStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaruTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (playcount > 0) {
                Text(
                    "$playcount plays",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaruAccentPurple
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 4. NAMIREC SCREEN (WebView Embed of Month In Songs without Navbar & Splash)
// -------------------------------------------------------------------------------------------------
class NamiRecJsBridge(private val context: Context) {
    @JavascriptInterface
    fun downloadBase64Image(base64Data: String, fileName: String) {
        try {
            val cleanBase64 = if (base64Data.contains(",")) {
                base64Data.substringAfter(",")
            } else {
                base64Data
            }
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            val finalName = if (fileName.isNotBlank()) fileName else "nami-recap-${System.currentTimeMillis()}.png"

            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LastNotif")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { stream ->
                    stream.write(bytes)
                    stream.flush()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Saved $finalName to Gallery!", Toast.LENGTH_SHORT).show()
                }
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val targetDir = java.io.File(picturesDir, "LastNotif").apply { mkdirs() }
                val targetFile = java.io.File(targetDir, finalName)
                targetFile.outputStream().use { it.write(bytes) }
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Saved $finalName to Pictures/LastNotif", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e("NamiRecScreen", "Failed to download image: ${e.message}", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NamiRecScreen(
    username: String
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val targetUrl = "https://maruchansquigle.vercel.app/month-in-songs/webview?user=" + Uri.encode(username)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaruCosmicBot)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.parseColor("#0B0813"))
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = false
                    settings.displayZoomControls = false
                    settings.userAgentString = settings.userAgentString + " LastNotifMobileApp/1.0"

                    addJavascriptInterface(NamiRecJsBridge(ctx), "AndroidBridge")

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url?.toString() ?: return false
                            return if (url.contains("/month-in-songs")) {
                                false
                            } else {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    ctx.startActivity(intent)
                                } catch (_: Exception) {}
                                true
                            }
                        }
                    }
                    loadUrl(targetUrl)
                    webViewRef = this
                }
            },
            update = { wv ->
                webViewRef = wv
            }
        )

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC0B0813)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(36.dp))
                    Text(
                        "Opening Nami's Month in Songs...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaruTextMuted
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 4.5. STREAMING PLATFORM ICON BUTTON
// -------------------------------------------------------------------------------------------------
@Composable
fun StreamingPlatformIconButton(
    platform: String,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val pkg = when (platform) {
        "Apple Music" -> "com.apple.android.music"
        "Spotify" -> "com.spotify.music"
        "YouTube Music" -> "com.google.android.apps.youtube.music"
        "Tidal" -> "com.aspiro.tidal"
        else -> ""
    }

    val installedIcon = remember(pkg) {
        if (pkg.isNotEmpty()) {
            try {
                context.packageManager.getApplicationIcon(pkg)
            } catch (_: Exception) {
                null
            }
        } else null
    }

    val fallbackVectorRes = when (platform) {
        "Apple Music" -> R.drawable.ic_brand_apple_music
        "Spotify" -> R.drawable.ic_brand_spotify
        "YouTube Music" -> R.drawable.ic_brand_youtube_music
        "Tidal" -> R.drawable.ic_brand_tidal
        else -> null
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) MaruAccentPink.copy(alpha = 0.25f) else MaruGlassSubtleBg,
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) MaruAccentPink else MaruGlassBorderSoft
        ),
        modifier = modifier.height(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (installedIcon != null) {
                AsyncImage(
                    model = installedIcon,
                    contentDescription = platform,
                    modifier = Modifier.size(28.dp)
                )
            } else if (fallbackVectorRes != null) {
                Image(
                    painter = painterResource(id = fallbackVectorRes),
                    contentDescription = platform,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Text(
                    platform.take(3),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaruTextStrong
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 5. SONG DETAIL BOTTOM SHEET WITH SIMILAR TRACKS
// -------------------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailBottomSheet(
    song: SongDetailState,
    preferredPlatform: String,
    onDismiss: () -> Unit,
    onSelectSimilarSong: (SongDetailState) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var artworkUrl by remember(song) { mutableStateOf(song.artworkUrl) }
    var appleMusicUrl by remember(song) { mutableStateOf(song.appleMusicUrl) }
    var similarTracks by remember(song) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var isSimilarLoading by remember(song) { mutableStateOf(true) }

    LaunchedEffect(song) {
        withContext(Dispatchers.IO) {
            if (artworkUrl == null) {
                val match = ItunesClient.searchSong(song.title, song.artist)
                artworkUrl = match?.artworkUrl
                appleMusicUrl = match?.appleMusicUrl
            }
            val similar = LastNotifApiClient.getSimilarTracks(song.artist, song.title, limit = 8)
            similarTracks = similar
            isSimilarLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF140D24),
        contentColor = MaruTextStrong,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Album Artwork with glowing halo and fallback to Maru blue heart
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaruGlassSubtleBg)
                    .border(1.5.dp, MaruAccentPink.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (!artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = artworkUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = painterResource(id = R.drawable.ic_maru_heart),
                        placeholder = painterResource(id = R.drawable.ic_maru_heart)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_maru_heart),
                        contentDescription = null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            // Title & Artist
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaruTextStrong,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaruAccentPink,
                    textAlign = TextAlign.Center
                )
                if (song.album.isNotEmpty()) {
                    Text(
                        song.album,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaruTextMuted,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Launch in Players Row
            Text("OPEN IN STREAMING PLAYER", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruTextMuted)
            val platforms = listOf("Apple Music", "Spotify", "YouTube Music", "Tidal")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                platforms.forEach { platform ->
                    StreamingPlatformIconButton(
                        platform = platform,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ItunesClient.openInPreferredPlayer(
                                context,
                                platform,
                                song.title,
                                song.artist,
                                appleMusicUrl
                            )
                        }
                    )
                }
            }

            // Similar Tracks Section - Only show if loading or has similar tracks (hide completely if niche / no similar tracks)
            if (isSimilarLoading) {
                HorizontalDivider(color = MaruGlassBorderSoft)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaruAccentBlue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("SIMILAR TRACKS", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruAccentBlue)
                }
                Box(modifier = Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaruAccentBlue, modifier = Modifier.size(24.dp))
                }
            } else if (similarTracks.isNotEmpty()) {
                HorizontalDivider(color = MaruGlassBorderSoft)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.AutoAwesome, null, tint = MaruAccentBlue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("SIMILAR TRACKS", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruAccentBlue)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    similarTracks.forEach { (simTitle, simArtist) ->
                        GlassCard(
                            border = MaruGlassBorderSoft,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectSimilarSong(
                                        SongDetailState(
                                            title = simTitle,
                                            artist = simArtist
                                        )
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PlayArrow, null, tint = MaruAccentBlue, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(simTitle, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaruTextStrong, maxLines = 1)
                                    Text(simArtist, style = MaterialTheme.typography.bodySmall, color = MaruTextMuted, maxLines = 1)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = MaruTextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 6. HERO LIVE NOTIFICATION BANNER (Interactive Click to Open Player)
// -------------------------------------------------------------------------------------------------
@Composable
fun NotificationPreviewBanner(
    liveTrack: LastNotifPollerService.ActiveTrackState?,
    lastAlertTitle: String,
    lastAlertSub: String,
    lastAlertSource: String,
    mainFmt: String,
    subFmt: String,
    preferredPlatform: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displayTitle: String
    val displaySub: String
    val displayBadge: String
    val isPlaying: Boolean

    when {
        liveTrack != null && liveTrack.pollingMethod == "Local" && (liveTrack.title.isNotEmpty() || liveTrack.artist.isNotEmpty()) -> {
            displayTitle = LastNotifNotificationManager.applyFormat(mainFmt, liveTrack.title, liveTrack.artist, liveTrack.album, "Local")
            displaySub = LastNotifNotificationManager.applyFormat(subFmt, liveTrack.title, liveTrack.artist, liveTrack.album, "Local")
            displayBadge = "LOCAL PLAYBACK"
            isPlaying = liveTrack.isPlaying
        }
        liveTrack != null && liveTrack.pollingMethod == "Receiver" && (liveTrack.title.isNotEmpty() || liveTrack.artist.isNotEmpty()) -> {
            displayTitle = LastNotifNotificationManager.applyFormat(mainFmt, liveTrack.title, liveTrack.artist, liveTrack.album, "Receiver")
            displaySub = LastNotifNotificationManager.applyFormat(subFmt, liveTrack.title, liveTrack.artist, liveTrack.album, "Receiver")
            displayBadge = "LAST.FM SCROBBLING"
            isPlaying = true
        }
        lastAlertTitle.isNotEmpty() || lastAlertSub.isNotEmpty() -> {
            displayTitle = lastAlertTitle
            displaySub = lastAlertSub
            displayBadge = "LAST ALERT • ${lastAlertSource.uppercase()}"
            isPlaying = false
        }
        else -> {
            val sampleTitle = "Tell Your World"
            val sampleArtist = "kz (livetune)"
            val sampleAlbum = "Tell Your World EP"
            displayTitle = LastNotifNotificationManager.applyFormat(mainFmt, sampleTitle, sampleArtist, sampleAlbum, "Preview")
            displaySub = LastNotifNotificationManager.applyFormat(subFmt, sampleTitle, sampleArtist, sampleAlbum, "Preview")
            displayBadge = "LIVE MIRROR PREVIEW"
            isPlaying = false
        }
    }

    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
        border = if (isPlaying) MaruAccentPink.copy(alpha = 0.6f) else MaruGlassBorderSoft,
        glowColor = if (isPlaying) MaruAccentPink.copy(alpha = 0.2f) else null,
        background = Color(0x99161026)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_lastnotif_monochrome),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaruAccentPink
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "NOTIFICATION MIRROR",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                    color = MaruTextMuted
                )

                if (isPlaying) {
                    Spacer(Modifier.width(8.dp))
                    EqualizerVisualizer()
                }

                Spacer(Modifier.weight(1f))

                Box(
                    modifier = Modifier
                        .background(
                            if (displayBadge.contains("LOCAL")) MaruAccentBlue.copy(alpha = 0.15f) else MaruAccentPink.copy(alpha = 0.15f),
                            MaruPillShape
                        )
                        .border(
                            BorderStroke(1.dp, if (displayBadge.contains("LOCAL")) MaruAccentBlue.copy(alpha = 0.45f) else MaruAccentPink.copy(alpha = 0.45f)),
                            MaruPillShape
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        displayBadge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold),
                        color = if (displayBadge.contains("LOCAL")) MaruAccentBlue else MaruAccentPink
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        displayTitle.ifEmpty { "Song Title" },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                        color = MaruTextStrong,
                        maxLines = 1
                    )
                    Text(
                        displaySub.ifEmpty { "Artist Name" },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaruTextMuted,
                        maxLines = 1
                    )
                }
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = "Open player",
                    tint = MaruAccentPink.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 7. NAVIGATION DRAWER (Organized by Recommendation Engine & Core Functionality)
// -------------------------------------------------------------------------------------------------
@Composable
fun GlassNavigationDrawer(
    currentScreen: NavigationScreen,
    username: String,
    serviceRunning: Boolean,
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Brand & User Profile in Drawer
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_maru_heart),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color.Unspecified
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "LASTNOTIF",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaruTextStrong,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        if (username.isNotEmpty()) "Logged in as @$username" else "Guest Mode",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaruAccentPink
                    )
                }
            }

            HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)

            // GROUP 1: RECOMMENDATION ENGINE
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
                NavigationDrawerItemRow(screen, isSelected = currentScreen == screen) { onSelectScreen(screen) }
            }

            Spacer(Modifier.height(6.dp))

            // GROUP 2: CORE FUNCTIONALITY
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
                NavigationDrawerItemRow(screen, isSelected = currentScreen == screen) { onSelectScreen(screen) }
            }

            Spacer(Modifier.height(10.dp))

            // Bottom Status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (serviceRunning) MaruAccentGreen.copy(alpha = 0.12f) else MaruGlassSubtleBg, MaruPillShape)
                    .border(BorderStroke(1.dp, if (serviceRunning) MaruAccentGreen.copy(alpha = 0.4f) else MaruGlassBorderSoft), MaruPillShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(if (serviceRunning) MaruAccentGreen else MaruTextMuted, CircleShape)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (serviceRunning) "BACKGROUND SERVICE ACTIVE" else "SERVICE IDLE",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    color = if (serviceRunning) MaruAccentGreen else MaruTextMuted
                )
            }

            Spacer(Modifier.height(16.dp))

            // Footer
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "with <3, Maru & Nanami",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.5.sp
                    ),
                    color = MaruTextMuted.copy(alpha = 0.65f)
                )
            }
        }
    }
}

@Composable
fun NavigationDrawerItemRow(
    screen: NavigationScreen,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaruInputShape,
        color = if (isSelected) MaruAccentPink.copy(alpha = 0.16f) else Color.Transparent,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaruAccentPink.copy(alpha = 0.6f) else Color.Transparent
        ),
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
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    ),
                    color = if (isSelected) MaruTextStrong else MaruTextMuted
                )
                Text(
                    screen.subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaruTextMuted.copy(alpha = 0.6f)
                )
            }
        }
    }
}

fun isNotificationListenerAccessGranted(context: Context): Boolean {
    return try {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    } catch (_: Exception) {
        false
    }
}

@Composable
fun NotificationAccessWarningBanner() {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(isNotificationListenerAccessGranted(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasAccess = isNotificationListenerAccessGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!hasAccess) {
        GlassCard(
            border = MaruAccentPink.copy(alpha = 0.8f),
            glowColor = MaruAccentPink.copy(alpha = 0.25f),
            background = Color(0x33F43F5E)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaruAccentPink,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        "NOTIFICATION ACCESS REQUIRED",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        ),
                        color = MaruAccentPink
                    )
                }

                Text(
                    "LastNotif needs Notification Access to detect current media playback from Spotify, Apple Music, YouTube Music, and other music players on your device.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaruTextStrong
                )

                GlassButton(
                    onClick = {
                        try {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    },
                    borderColor = MaruAccentPink,
                    background = MaruAccentPink.copy(alpha = 0.25f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Security, null, tint = MaruAccentPink, modifier = Modifier.size(16.dp))
                        Text(
                            "GRANT NOTIFICATION ACCESS",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaruAccentPink
                        )
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 8. OTHER SUB-SCREENS (Scrobbling, Local, Receiver, Common)
// -------------------------------------------------------------------------------------------------
@Composable
fun ScrobblingScreen(
    scrobbleEnabled: Boolean,
    onToggleScrobble: (Boolean) -> Unit,
    sessionKey: String,
    lastfmUsername: String,
    onSessionKeyChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    scrobblePercentage: Int,
    onScrobblePercentageChange: (Int) -> Unit,
    allApps: List<AppInfo>,
    scrobbleApps: Set<String>,
    onScrobbleAppsChange: (Set<String>) -> Unit,
    onShowManualDialog: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        NotificationAccessWarningBanner()

        GlassMasterTile(
            title = "LAST.FM SCROBBLING",
            description = "Automatically submit local media playback to your Last.fm profile.",
            isEnabled = scrobbleEnabled,
            onToggle = onToggleScrobble
        )

        AnimatedVisibility(
            visible = scrobbleEnabled,
            enter = expandVertically(spring()) + fadeIn(),
            exit = shrinkVertically(spring()) + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassSectionHeader("ACCOUNT", Icons.Default.Person)
                if (sessionKey.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton(
                            onClick = {
                                val url = "https://www.last.fm/api/auth/?api_key=${LastFmScrobbler.API_KEY}&cb=https://maruchansquigle.vercel.app/lastnotif-auth.html"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                            borderColor = LastFmRed.copy(alpha = 0.7f),
                            background = LastFmRed.copy(alpha = 0.12f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(painterResource(id = R.drawable.ic_lastfm_logo), null, modifier = Modifier.size(20.dp), tint = Color.Unspecified)
                                Spacer(Modifier.width(12.dp))
                                Text("CONNECT VIA BROWSER", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaruTextStrong)
                            }
                        }

                        GlassButton(
                            onClick = onShowManualDialog,
                            borderColor = MaruGlassBorderSoft
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Key, null, modifier = Modifier.size(16.dp), tint = MaruAccentBlue)
                                Spacer(Modifier.width(10.dp))
                                Text("ENTER SESSION KEY MANUALLY", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaruAccentBlue)
                            }
                        }
                    }
                } else {
                    GlassCard(
                        border = MaruAccentGreen.copy(alpha = 0.4f),
                        glowColor = MaruAccentGreen.copy(alpha = 0.12f)
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("LOGGED IN", color = MaruAccentGreen, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), fontWeight = FontWeight.Bold)
                                Text(lastfmUsername.ifEmpty { "Connected User" }, color = MaruTextStrong, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { onSessionKeyChange(""); onUsernameChange("") }, shape = MaruInputShape) {
                                Text("LOGOUT", color = MaruTextMuted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                GlassSectionHeader("TRIGGER THRESHOLD", Icons.Default.Timelapse)
                GlassCard {
                    Column(Modifier.padding(16.dp)) {
                        Text("Submit scrobble at $scrobblePercentage% track duration", style = MaterialTheme.typography.bodySmall, color = MaruTextStrong)
                        Slider(
                            value = scrobblePercentage.toFloat(),
                            onValueChange = { raw ->
                                val snapped = kotlin.math.round(raw / 5f).toInt() * 5
                                onScrobblePercentageChange(snapped.coerceIn(10, 90))
                            },
                            valueRange = 10f..90f,
                            steps = 15,
                            colors = SliderDefaults.colors(
                                thumbColor = MaruAccentPink,
                                activeTrackColor = MaruAccentPink,
                                inactiveTrackColor = MaruGlassBorderSoft
                            )
                        )
                    }
                }

                GlassSectionHeader("SCROBBLE APPS FILTER", Icons.Default.Apps)
                GlassAppSelectionTile(allApps, scrobbleApps, onScrobbleAppsChange)
            }
        }
    }
}

@Composable
fun LocalScreen(
    localEnabled: Boolean,
    onToggleLocal: (Boolean) -> Unit,
    allApps: List<AppInfo>,
    localApps: Set<String>,
    onLocalAppsChange: (Set<String>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        NotificationAccessWarningBanner()

        GlassMasterTile(
            title = "LOCAL MEDIA MONITOR",
            description = "Track songs currently playing on this device and display them on your smart band/watch.",
            isEnabled = localEnabled,
            onToggle = onToggleLocal
        )

        AnimatedVisibility(
            visible = localEnabled,
            enter = expandVertically(spring()) + fadeIn(),
            exit = shrinkVertically(spring()) + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassSectionHeader("TRACKED APPS", Icons.Default.Apps)
                GlassAppSelectionTile(allApps, localApps, onLocalAppsChange)
            }
        }
    }
}

@Composable
fun ReceiverScreen(
    receiverEnabled: Boolean,
    onToggleReceiver: (Boolean) -> Unit,
    receiverUsername: String,
    lastfmUsername: String,
    onReceiverUsernameChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GlassMasterTile(
            title = "REMOTE RECEIVER",
            description = "Monitor scrobbles on other devices via Last.fm profile and display them on your device/band.",
            isEnabled = receiverEnabled,
            onToggle = onToggleReceiver
        )

        AnimatedVisibility(
            visible = receiverEnabled,
            enter = expandVertically(spring()) + fadeIn(),
            exit = shrinkVertically(spring()) + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassSectionHeader("TARGET PROFILE", Icons.AutoMirrored.Filled.LastPage)
                GlassTextField(
                    value = receiverUsername.ifEmpty { lastfmUsername },
                    onValueChange = onReceiverUsernameChange,
                    placeholder = "LAST.FM USERNAME"
                )
            }
        }
    }
}

@Composable
fun CommonSettingsScreen(
    mainFmt: String,
    subFmt: String,
    onMainFmtChange: (String) -> Unit,
    onSubFmtChange: (String) -> Unit,
    preferredPlatform: String,
    onPreferredPlatformChange: (String) -> Unit,
    notifySongUpdate: Boolean,
    onToggleSongUpdate: (Boolean) -> Unit,
    intervalEnabled: Boolean,
    onIntervalToggle: (Boolean) -> Unit,
    intervalMinutes: Int,
    onIntervalMinutesChange: (Int) -> Unit,
    onSendTestNotification: () -> Unit,
    onRestartService: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        GlassSectionHeader("NOTIFICATION FORMAT", Icons.Default.TextFields)
        GlassCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassTextField(value = mainFmt, onValueChange = onMainFmtChange, placeholder = "{artist} - {title}", label = "MAIN LINE (TITLE)")
                GlassTextField(value = subFmt, onValueChange = onSubFmtChange, placeholder = "{source} • {album}", label = "SUB LINE (BODY)")
                Text(
                    "Placeholders: {title}, {song_name}, {artist}, {album}, {source}, {media_player}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaruTextMuted
                )
            }
        }

        GlassSectionHeader("PREFERRED MUSIC PLAYER", Icons.Default.MusicNote)
        GlassCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Open recommended tracks and albums in:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaruTextMuted
                )
                val platforms = listOf("Apple Music", "Spotify", "YouTube Music", "Tidal")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    platforms.forEach { platform ->
                        val isSelected = preferredPlatform == platform
                        StreamingPlatformIconButton(
                            platform = platform,
                            isSelected = isSelected,
                            modifier = Modifier.weight(1f),
                            onClick = { onPreferredPlatformChange(platform) }
                        )
                    }
                }
            }
        }

        GlassSectionHeader("SYNC ALERTS & TRIGGERS", Icons.Default.NotificationsActive)
        GlassCard {
            Column {
                GlassFeatureRow(label = "Song Change Alerts", checked = notifySongUpdate, onCheckedChange = onToggleSongUpdate)
                HorizontalDivider(color = MaruGlassBorderSoft)
                GlassFeatureRow(label = "Interval Sync Reminders", checked = intervalEnabled, onCheckedChange = onIntervalToggle)

                AnimatedVisibility(visible = intervalEnabled) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text("Alert every $intervalMinutes minutes while playing", style = MaterialTheme.typography.bodySmall, color = MaruTextStrong)
                        Slider(
                            value = intervalMinutes.toFloat(),
                            onValueChange = { onIntervalMinutesChange(it.toInt()) },
                            valueRange = 1f..30f,
                            colors = SliderDefaults.colors(thumbColor = MaruAccentBlue, activeTrackColor = MaruAccentBlue)
                        )
                    }
                }
            }
        }

        GlassSectionHeader("ACTIONS & DIAGNOSTICS", Icons.Default.Build)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassButton(onClick = onSendTestNotification, modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Send, null, modifier = Modifier.size(16.dp), tint = MaruAccentPink)
                    Spacer(Modifier.width(8.dp))
                    Text("TEST ALERT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaruAccentPink)
                }
            }
            GlassButton(onClick = onRestartService, modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = MaruAccentBlue)
                    Spacer(Modifier.width(8.dp))
                    Text("RESTART", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaruAccentBlue)
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 9. REUSABLE GLASS UI COMPONENTS
// -------------------------------------------------------------------------------------------------
@Composable
fun EqualizerVisualizer(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "Equalizer")
    val bar1 by infiniteTransition.animateFloat(
        initialValue = 3f, targetValue = 13f,
        animationSpec = infiniteRepeatable(animation = tween(450), repeatMode = RepeatMode.Reverse),
        label = "Bar1"
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 13f, targetValue = 4f,
        animationSpec = infiniteRepeatable(animation = tween(350), repeatMode = RepeatMode.Reverse),
        label = "Bar2"
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 5f, targetValue = 14f,
        animationSpec = infiniteRepeatable(animation = tween(550), repeatMode = RepeatMode.Reverse),
        label = "Bar3"
    )

    androidx.compose.foundation.Canvas(modifier = modifier.size(16.dp, 14.dp)) {
        val w = 2.5.dp.toPx()
        val spacing = 2.dp.toPx()
        val corner = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())

        val h1 = bar1.dp.toPx()
        drawRoundRect(
            color = MaruAccentPink,
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h1),
            size = androidx.compose.ui.geometry.Size(w, h1),
            cornerRadius = corner
        )

        val h2 = bar2.dp.toPx()
        drawRoundRect(
            color = MaruAccentBlue,
            topLeft = androidx.compose.ui.geometry.Offset(w + spacing, size.height - h2),
            size = androidx.compose.ui.geometry.Size(w, h2),
            cornerRadius = corner
        )

        val h3 = bar3.dp.toPx()
        drawRoundRect(
            color = MaruAccentGreen,
            topLeft = androidx.compose.ui.geometry.Offset((w + spacing) * 2, size.height - h3),
            size = androidx.compose.ui.geometry.Size(w, h3),
            cornerRadius = corner
        )
    }
}

@Composable
fun GlassRecommendationGridCard(
    item: LastFmRecommendationsEngine.RecommendedTrackItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categoryBadge = when (item.category) {
        LastFmRecommendationsEngine.RecCategory.ARTISTS -> "ARTIST"
        LastFmRecommendationsEngine.RecCategory.ALBUMS -> "ALBUM"
        LastFmRecommendationsEngine.RecCategory.TRACKS -> "TRACK"
        else -> null
    }

    val artwork = item.effectiveArtworkUrl

    GlassCard(
        border = MaruGlassBorderSoft,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(MaruGlassSubtleBg)
            ) {
                if (!artwork.isNullOrBlank()) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = painterResource(id = R.drawable.ic_maru_heart),
                        placeholder = painterResource(id = R.drawable.ic_maru_heart)
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_maru_heart),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                categoryBadge?.let { badge ->
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .align(Alignment.TopStart)
                            .background(
                                color = when (item.category) {
                                    LastFmRecommendationsEngine.RecCategory.ARTISTS -> MaruAccentPurple.copy(alpha = 0.85f)
                                    LastFmRecommendationsEngine.RecCategory.ALBUMS -> MaruAccentBlue.copy(alpha = 0.85f)
                                    else -> MaruAccentPink.copy(alpha = 0.85f)
                                },
                                shape = MaruPillShape
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp
                            ),
                            color = Color.White
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    ),
                    color = MaruTextStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp
                    ),
                    color = MaruTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun GlassRecommendationCard(
    item: LastFmRecommendationsEngine.RecommendedTrackItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categoryBadge = when (item.category) {
        LastFmRecommendationsEngine.RecCategory.ARTISTS -> "ARTIST"
        LastFmRecommendationsEngine.RecCategory.ALBUMS -> "ALBUM"
        LastFmRecommendationsEngine.RecCategory.TRACKS -> "TRACK"
        else -> null
    }

    val artwork = item.effectiveArtworkUrl

    GlassCard(
        border = MaruGlassBorderSoft,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaruCardShape)
                    .background(MaruGlassSubtleBg)
            ) {
                if (!artwork.isNullOrBlank()) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        error = painterResource(id = R.drawable.ic_maru_heart),
                        placeholder = painterResource(id = R.drawable.ic_maru_heart)
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_maru_heart),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                categoryBadge?.let { badge ->
                    Box(
                        modifier = Modifier
                            .background(
                                color = when (item.category) {
                                    LastFmRecommendationsEngine.RecCategory.ARTISTS -> MaruAccentPurple.copy(alpha = 0.25f)
                                    LastFmRecommendationsEngine.RecCategory.ALBUMS -> MaruAccentBlue.copy(alpha = 0.25f)
                                    else -> MaruAccentPink.copy(alpha = 0.25f)
                                },
                                shape = MaruPillShape
                            )
                            .border(
                                width = 1.dp,
                                color = when (item.category) {
                                    LastFmRecommendationsEngine.RecCategory.ARTISTS -> MaruAccentPurple.copy(alpha = 0.5f)
                                    LastFmRecommendationsEngine.RecCategory.ALBUMS -> MaruAccentBlue.copy(alpha = 0.5f)
                                    else -> MaruAccentPink.copy(alpha = 0.5f)
                                },
                                shape = MaruPillShape
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.5.sp, fontWeight = FontWeight.Bold),
                            color = when (item.category) {
                                LastFmRecommendationsEngine.RecCategory.ARTISTS -> MaruAccentPurple
                                LastFmRecommendationsEngine.RecCategory.ALBUMS -> MaruAccentBlue
                                else -> MaruAccentPink
                            }
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                }

                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaruTextStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaruTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "Details",
                tint = MaruAccentPink,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun GlassMasterTile(
    title: String,
    description: String,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    GlassCard(
        border = if (isEnabled) MaruAccentPink.copy(alpha = 0.6f) else MaruGlassBorderSoft,
        glowColor = if (isEnabled) MaruAccentPink.copy(alpha = 0.15f) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = MaruTextStrong, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(description, color = MaruTextMuted, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = MaruAccentPink,
                    uncheckedThumbColor = MaruTextMuted,
                    uncheckedTrackColor = MaruGlassSubtleBg
                )
            )
        }
    }
}

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
fun GlassSectionHeader(title: String, icon: ImageVector) {
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaruAccentPink, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp), color = MaruAccentPink)
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
fun GlassAppSelectionTile(
    allApps: List<AppInfo>,
    selectedPackages: Set<String>,
    onSelectionChange: (Set<String>) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    GlassCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDialog = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("SELECTED APPS", color = MaruAccentPink, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), fontWeight = FontWeight.Bold)
                Text(
                    if (selectedPackages.isEmpty()) "All Media Apps (Default)" else "${selectedPackages.size} Apps Selected",
                    color = MaruTextStrong,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaruTextMuted)
        }
    }

    if (showDialog) {
        AppSelectionDialog(
            allApps = allApps,
            initialSelection = selectedPackages,
            onDismiss = { showDialog = false },
            onAppsSelected = {
                onSelectionChange(it)
                showDialog = false
            }
        )
    }
}

@Composable
fun ManualSessionKeyDialog(
    currentKey: String,
    currentUsername: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var keyText by remember { mutableStateOf(currentKey) }
    var userText by remember { mutableStateOf(currentUsername) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161026),
        title = { Text("Last.fm Session Credentials", color = MaruTextStrong, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassTextField(value = userText, onValueChange = { userText = it }, placeholder = "Username", label = "USERNAME")
                GlassTextField(value = keyText, onValueChange = { keyText = it }, placeholder = "32-character Session Key", label = "SESSION KEY")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(keyText.trim(), userText.trim()) }) {
                Text("SAVE", color = MaruAccentPink, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = MaruTextMuted) }
        }
    )
}

@Composable
fun AppSelectionDialog(
    allApps: List<AppInfo>,
    initialSelection: Set<String>,
    onDismiss: () -> Unit,
    onAppsSelected: (Set<String>) -> Unit
) {
    var currentSelection by remember { mutableStateOf(initialSelection) }
    var showMediaOnly by remember { mutableStateOf(true) }

    val filteredApps = remember(allApps, showMediaOnly) {
        if (showMediaOnly) allApps.filter { it.isMedia } else allApps
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF161026),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select Apps", color = MaruTextStrong, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (showMediaOnly) MaruAccentPink.copy(alpha = 0.2f) else MaruGlassSubtleBg, MaruPillShape)
                            .border(BorderStroke(1.dp, if (showMediaOnly) MaruAccentPink else MaruGlassBorderSoft), MaruPillShape)
                            .clickable { showMediaOnly = true }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("MEDIA APPS", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), fontWeight = FontWeight.Bold, color = if (showMediaOnly) MaruAccentPink else MaruTextMuted)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (!showMediaOnly) MaruAccentPink.copy(alpha = 0.2f) else MaruGlassSubtleBg, MaruPillShape)
                            .border(BorderStroke(1.dp, if (!showMediaOnly) MaruAccentPink else MaruGlassBorderSoft), MaruPillShape)
                            .clickable { showMediaOnly = false }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("ALL APPS", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), fontWeight = FontWeight.Bold, color = if (!showMediaOnly) MaruAccentPink else MaruTextMuted)
                    }
                }
            }
        },
        text = {
            if (filteredApps.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text("No apps found. Try switching to 'ALL APPS'.", color = MaruTextMuted, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                LazyColumn(modifier = Modifier.height(340.dp)) {
                    items(filteredApps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentSelection = if (currentSelection.contains(app.packageName)) currentSelection - app.packageName else currentSelection + app.packageName
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = currentSelection.contains(app.packageName), onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = MaruAccentPink))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.name, color = MaruTextStrong, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(app.packageName, color = MaruTextMuted.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                            }
                            if (app.isMedia) {
                                Box(
                                    modifier = Modifier
                                        .background(MaruAccentBlue.copy(alpha = 0.15f), MaruPillShape)
                                        .border(BorderStroke(1.dp, MaruAccentBlue.copy(alpha = 0.4f)), MaruPillShape)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("MEDIA", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold), color = MaruAccentBlue)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAppsSelected(currentSelection) }) { Text("SAVE (${currentSelection.size})", color = MaruAccentPink, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = MaruTextMuted) }
        }
    )
}

data class AppInfo(
    val name: String,
    val packageName: String,
    val isMedia: Boolean = false
)

@Composable
fun rememberAppList(context: Context): List<AppInfo> {
    var apps by remember { mutableStateOf(emptyList<AppInfo>()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolveInfos = pm.queryIntentActivities(intent, 0)

            val mediaServiceIntent = Intent("android.media.browse.MediaBrowserService")
            val mediaBrowserPackages = try {
                pm.queryIntentServices(mediaServiceIntent, 0).map { it.serviceInfo.packageName }.toSet()
            } catch (_: Exception) { emptySet() }

            val media3Intent = Intent("androidx.media3.session.MediaSessionService")
            val media3Packages = try {
                pm.queryIntentServices(media3Intent, 0).map { it.serviceInfo.packageName }.toSet()
            } catch (_: Exception) { emptySet() }

            val knownMediaKeywords = setOf(
                "music", "audio", "player", "sound", "radio", "podcast", "spotify", "tidal",
                "deezer", "apple.android.music", "youtube.music", "vlc", "foobar", "poweramp",
                "musicolet", "retro", "oto", "symfonium", "rimusic", "vimusic", "innertune",
                "aimp", "blackplayer", "shuttle", "gonemad", "neutron", "stellio", "jetaudio",
                "pulsar", "audiomack", "pandora", "tunein", "soundcloud", "bandcamp", "qobuz",
                "amazon.mp3", "anghami", "gaana", "jiosaavn", "wynk", "kugou", "netease",
                "smartplayer", "audioplayer", "boom", "trebel", "evermusic", "cloudbeats",
                "sonos", "bubbleupnp", "fiio", "hiby", "hibymusic", "hificast", "poweramp2",
                "track", "tune", "ytmusic"
            )

            val nonMediaPackages = setOf(
                "com.facebook.orca", "com.whatsapp", "com.instagram.android", "com.discord",
                "org.telegram.messenger", "com.twitter.android", "com.google.android.talk",
                "com.google.android.apps.messaging", "com.samsung.android.messaging",
                "com.google.android.calculator", "com.sec.android.app.popupcalculator",
                "com.google.android.deskclock", "com.sec.android.app.clockpackage",
                "com.google.android.calendar", "com.samsung.android.calendar",
                "com.google.android.apps.maps", "com.samsung.android.maps",
                "com.google.android.gm", "com.microsoft.office.outlook", "com.android.chrome",
                "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.opera.browser",
                "com.samsung.android.bixby.agent", "com.samsung.android.app.camera",
                "com.google.android.apps.photos", "com.sec.android.gallery3d",
                "com.google.android.googlequicksearchbox", "com.samsung.android.honeyboard",
                "com.microsoft.bing", "com.microsoft.emmx", "com.microsoft.office.excel",
                "com.microsoft.office.word", "com.microsoft.office.powerpoint",
                "com.microsoft.office.onenote", "com.microsoft.office.officehubrow",
                "com.microsoft.teams", "com.sec.android.app.shealth",
                "com.samsung.android.app.notes", "com.google.android.keep"
            )

            val mediaList = mutableListOf<AppInfo>()
            val otherList = mutableListOf<AppInfo>()

            resolveInfos.forEach { ri ->
                val pkg = ri.activityInfo.packageName
                val name = ri.loadLabel(pm).toString()
                val lowerPkg = pkg.lowercase()
                val lowerName = name.lowercase()
                var isMedia = false

                val isExcluded = nonMediaPackages.contains(pkg) || 
                                 lowerName.contains("bixby") || 
                                 lowerName.contains("photos") || 
                                 lowerName.contains("gallery") ||
                                 lowerName.contains("camera") ||
                                 lowerName.contains("calculator") ||
                                 lowerName.contains("clock") ||
                                 lowerName.contains("calendar")

                if (!isExcluded) {
                    try {
                        val appInfo = pm.getApplicationInfo(pkg, 0)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            if (appInfo.category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO) {
                                isMedia = true
                            }
                        }
                    } catch (_: Exception) {}

                    if (mediaBrowserPackages.contains(pkg) || media3Packages.contains(pkg)) {
                        isMedia = true
                    }

                    if (knownMediaKeywords.any { lowerPkg.contains(it) || lowerName.contains(it) }) {
                        isMedia = true
                    }
                }

                val info = AppInfo(name, pkg, isMedia)
                if (isMedia) {
                    mediaList.add(info)
                } else {
                    otherList.add(info)
                }
            }

            val fullList = mediaList.distinctBy { it.packageName }.sortedBy { it.name } +
                           otherList.distinctBy { it.packageName }.sortedBy { it.name }

            withContext(Dispatchers.Main) {
                apps = fullList
            }
        }
    }
    return apps
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    LastNotifTheme {
        MainScreenContent(
            scrobbleEnabled = true, receiverEnabled = false, localEnabled = true,
            lastfmUsername = "MaruSenpai", sessionKey = "", scrobbleApps = emptySet(), scrobblePercentage = 50,
            receiverUsername = "", localApps = emptySet(), notifySongUpdate = true,
            mainFmt = "{song_name}", subFmt = "{artist}", intervalEnabled = true, intervalMinutes = 5,
            preferredPlatform = "Apple Music",
            lastAlertTitle = "Test Song", lastAlertSub = "Test Artist", lastAlertSource = "Local",
            serviceRunning = true, liveTrack = LastNotifPollerService.ActiveTrackState("Test Song", "Test Artist", "Test Album", true, "Local"),
            onToggleScrobble = {}, onToggleReceiver = {}, onToggleLocal = {},
            onUsernameChange = {}, onSessionKeyChange = {}, onScrobbleAppsChange = {}, onScrobblePercentageChange = {},
            onReceiverUsernameChange = {}, onLocalAppsChange = {}, onToggleSongUpdate = {},
            onMainFmtChange = {}, onSubFmtChange = {}, onIntervalToggle = {}, onIntervalMinutesChange = {},
            onPreferredPlatformChange = {},
            onRestartService = {}, onSendTestNotification = {}
        )
    }
}

@Composable
fun LastNotifTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MaruAccentPink,
            secondary = MaruAccentBlue,
            tertiary = MaruAccentPurple,
            surface = MaruGlassCardBg,
            background = MaruCosmicBot,
            onSurface = MaruTextStrong
        ),
        typography = Typography(
            bodySmall = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            labelSmall = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, letterSpacing = 0.5.sp)
        ),
        content = content
    )
}
