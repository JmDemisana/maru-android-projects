package io.maru.lastnotif

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import io.maru.lastnotif.cast.MarucastApiClient
import io.maru.lastnotif.cast.MarucastForegroundService
import io.maru.lastnotif.cast.MarucastScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// --- MAudio Cosmic Glassmorphic Design Tokens ---
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
    // Recommendation Engine Group
    DISCOVERY("Discovery", "Personalized Track Feed", Icons.Default.AutoAwesome, NavigationGroup.RECOMMENDATION_ENGINE),
    SEARCH("Search", "Tracks, Artists & Profiles", Icons.Default.Search, NavigationGroup.RECOMMENDATION_ENGINE),
    PROFILE("Profile", "Scrobble Stats & Charts", Icons.Default.Person, NavigationGroup.RECOMMENDATION_ENGINE),
    NAMIREC("NamiRec", "Monthly Musical Recap", Icons.Default.Stars, NavigationGroup.RECOMMENDATION_ENGINE),
    ARTIST_DETAIL("Artist Feature", "Discography & Highlights", Icons.Default.MicExternalOn, NavigationGroup.RECOMMENDATION_ENGINE),

    // Core Functionality Group
    MARUCAST("Marucast", "Lossless Wi-Fi Broadcaster", Icons.Default.Podcasts, NavigationGroup.CORE_FUNCTIONALITY),
    SCROBBLING("Scrobbler", "Accounts & Filters", Icons.Default.CloudUpload, NavigationGroup.CORE_FUNCTIONALITY),
    LOCAL("Local Monitor", "Media Controller", Icons.Default.GraphicEq, NavigationGroup.CORE_FUNCTIONALITY),
    RECEIVER("Receiver", "Cross-device Sync", Icons.Default.Sensors, NavigationGroup.CORE_FUNCTIONALITY),
    COMMON("Settings", "Layout & Player Options", Icons.Default.Settings, NavigationGroup.CORE_FUNCTIONALITY)
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
            MaterialTheme {
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
        if (data != null) {
            if (data.scheme == "lastnotif" && data.host == "auth") {
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
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val directSongLaunch by prefs.directSongLaunch.collectAsStateWithLifecycle(initialValue = false)

    val lastAlertTitle by prefs.lastAlertTitle.collectAsStateWithLifecycle(initialValue = "")
    val lastAlertSub by prefs.lastAlertSub.collectAsStateWithLifecycle(initialValue = "")
    val lastAlertSource by prefs.lastAlertSource.collectAsStateWithLifecycle(initialValue = "")

    val serviceRunning by prefs.serviceRunning.collectAsStateWithLifecycle(initialValue = false)
    val liveTrack by LastNotifPollerService.liveTrack.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    var selectedScreen by remember { mutableStateOf(NavigationScreen.DISCOVERY) }
    var previousScreen by remember { mutableStateOf(NavigationScreen.DISCOVERY) }
    var selectedArtistDetail by remember { mutableStateOf<String?>(null) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var showManualCredsDialog by remember { mutableStateOf(false) }
    var viewedProfileUsername by remember { mutableStateOf<String?>(null) }
    var selectedSongDetail by remember { mutableStateOf<SongDetailState?>(null) }

    // Screen audio capture projection data for Marucast (only requested on-demand when starting Marucast)
    var projectionIntentData by remember { mutableStateOf<Intent?>(null) }
    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            projectionIntentData = result.data
        }
    }

    // Intercept hardware Back Button
    BackHandler(enabled = true) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (selectedSongDetail != null) {
            selectedSongDetail = null
        } else if (selectedScreen == NavigationScreen.ARTIST_DETAIL) {
            selectedScreen = previousScreen
        } else if (selectedScreen != NavigationScreen.DISCOVERY) {
            selectedScreen = NavigationScreen.DISCOVERY
        } else {
            Toast.makeText(
                context,
                "MAudio continues to scrobble and broadcast in the background.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            allApps = AppUtils.getInstalledMediaApps(context)
        }
    }

    // Recommendation Engine state
    var recommendations by remember { mutableStateOf<List<LastFmRecommendationsEngine.RecommendedTrackItem>>(emptyList()) }
    var isRecommendationsLoading by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var recPage by remember { mutableStateOf(1) }
    var selectedCategory by remember { mutableStateOf(LastFmRecommendationsEngine.RecCategory.ALL) }

    val refreshRecommendations: (LastFmRecommendationsEngine.RecCategory, Boolean) -> Unit = { cat, reset ->
        val targetUser = lastfmUsername.ifEmpty { "JmDemisana" }
        scope.launch {
            if (reset) {
                isRecommendationsLoading = true
                recPage = 1
                recommendations = withContext(Dispatchers.IO) {
                    LastFmRecommendationsEngine.getRecommendations(targetUser, cat, page = 1)
                }
                isRecommendationsLoading = false
            } else {
                isLoadingMore = true
                val nextPage = recPage + 1
                val more = withContext(Dispatchers.IO) {
                    LastFmRecommendationsEngine.getRecommendations(targetUser, cat, page = nextPage)
                }
                if (more.isNotEmpty()) {
                    recPage = nextPage
                    recommendations = (recommendations + more).distinctBy { "${it.artist.lowercase()} - ${it.title.lowercase()}" }
                }
                isLoadingMore = false
            }
        }
    }

    LaunchedEffect(lastfmUsername) {
        refreshRecommendations(selectedCategory, true)
    }

    val onToggleScrobble: (Boolean) -> Unit = { enabled ->
        scope.launch {
            prefs.setScrobbleEnabled(enabled)
            val shouldRun = enabled || receiverEnabled || localEnabled
            if (shouldRun) LastNotifPollerAlarmScheduler.schedule(context) else LastNotifPollerAlarmScheduler.cancel(context)
        }
    }

    val onToggleReceiver: (Boolean) -> Unit = { enabled ->
        scope.launch {
            prefs.setReceiverEnabled(enabled)
            val shouldRun = enabled || scrobbleEnabled || localEnabled
            if (shouldRun) LastNotifPollerAlarmScheduler.schedule(context) else LastNotifPollerAlarmScheduler.cancel(context)
        }
    }

    val onToggleLocal: (Boolean) -> Unit = { enabled ->
        scope.launch {
            prefs.setLocalEnabled(enabled)
            val shouldRun = enabled || scrobbleEnabled || receiverEnabled
            if (shouldRun) LastNotifPollerAlarmScheduler.schedule(context) else LastNotifPollerAlarmScheduler.cancel(context)
        }
    }

    val onSendTestNotification: () -> Unit = {
        scope.launch {
            val title = if (mainFmt.isNotBlank()) LastNotifNotificationManager.applyFormat(mainFmt, "Tell Your World", "kz (livetune)", "Tell Your World EP", "Test") else "Tell Your World"
            val body = if (subFmt.isNotBlank()) LastNotifNotificationManager.applyFormat(subFmt, "Tell Your World", "kz (livetune)", "Tell Your World EP", "Test") else "kz (livetune) • Tell Your World EP"
            LastNotifNotificationManager(context).sendAlert(title, body)
        }
    }

    val onRestartService: () -> Unit = {
        LastNotifPollerService.stop(context)
        LastNotifPollerService.start(context)
        Toast.makeText(context, "MAudio background service restarted", Toast.LENGTH_SHORT).show()
    }

    val backgroundGradient = Brush.verticalGradient(
        colors = listOf(MaruCosmicTop, MaruCosmicMid, MaruCosmicBot)
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = selectedScreen != NavigationScreen.NAMIREC,
        drawerContent = {
            GlassNavigationDrawer(
                currentScreen = selectedScreen,
                username = lastfmUsername,
                serviceRunning = serviceRunning,
                onSelectScreen = { screen ->
                    selectedScreen = screen
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                if (selectedScreen != NavigationScreen.ARTIST_DETAIL) {
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
                                        letterSpacing = 0.5.sp,
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
                        actions = {
                            val isCasting = MarucastForegroundService.currentToken != null
                            IconButton(onClick = {
                                selectedScreen = NavigationScreen.MARUCAST
                            }) {
                                if (isCasting) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Podcasts,
                                            contentDescription = "Marucast Broadcasting",
                                            tint = MaruAccentPink,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .align(Alignment.TopEnd)
                                                .background(MaruAccentGreen, CircleShape)
                                        )
                                    }
                                } else {
                                    Icon(
                                        Icons.Default.Podcasts,
                                        contentDescription = "Marucast",
                                        tint = if (selectedScreen == NavigationScreen.MARUCAST) MaruAccentPink else MaruTextMuted.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
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
            },
            bottomBar = {
                NotificationMirrorBottomBar(
                    liveTrack = liveTrack,
                    mainFmt = mainFmt,
                    subFmt = subFmt,
                    onClick = { title, artist, album ->
                        if (directSongLaunch) {
                            ItunesClient.openInPreferredPlayer(context, preferredPlatform, title, artist, null)
                        } else {
                            selectedSongDetail = SongDetailState(title = title, artist = artist, album = album)
                        }
                    }
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundGradient)
                    .padding(innerPadding)
            ) {
                AnimatedContent(
                    targetState = selectedScreen,
                    transitionSpec = {
                        val enter = fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                                scaleIn(initialScale = 0.97f, animationSpec = tween(240, easing = FastOutSlowInEasing))
                        val exit = fadeOut(animationSpec = tween(160, easing = FastOutLinearInEasing)) +
                                scaleOut(targetScale = 1.02f, animationSpec = tween(160, easing = FastOutLinearInEasing))
                        enter togetherWith exit
                    },
                    label = "ScreenTransition"
                ) { targetScreen ->
                    when (targetScreen) {
                        NavigationScreen.DISCOVERY -> {
                            DiscoveryScreen(
                                recommendations = recommendations,
                                isLoading = isRecommendationsLoading,
                                isLoadingMore = isLoadingMore,
                                onRefresh = { refreshRecommendations(selectedCategory, true) },
                                onLoadMore = { refreshRecommendations(selectedCategory, false) },
                                onSongClick = { item ->
                                    if (directSongLaunch) {
                                        ItunesClient.openInPreferredPlayer(
                                            context,
                                            preferredPlatform,
                                            item.title,
                                            item.artist,
                                            item.itunesMatch?.appleMusicUrl
                                        )
                                    } else {
                                        selectedSongDetail = SongDetailState(
                                            title = item.title,
                                            artist = item.artist,
                                            album = item.album,
                                            artworkUrl = item.effectiveArtworkUrl,
                                            appleMusicUrl = item.itunesMatch?.appleMusicUrl
                                        )
                                    }
                                }
                            )
                        }

                        NavigationScreen.SEARCH -> {
                            SearchScreen(
                                preferredPlatform = preferredPlatform,
                                onSongClick = { match ->
                                    if (directSongLaunch) {
                                        ItunesClient.openInPreferredPlayer(
                                            context,
                                            preferredPlatform,
                                            match.trackName,
                                            match.artistName,
                                            match.appleMusicUrl
                                        )
                                    } else {
                                        selectedSongDetail = SongDetailState(
                                            title = match.trackName,
                                            artist = match.artistName,
                                            album = match.collectionName,
                                            artworkUrl = match.artworkUrl,
                                            appleMusicUrl = match.appleMusicUrl
                                        )
                                    }
                                },
                                onOpenProfile = { profileUser ->
                                    viewedProfileUsername = profileUser
                                    selectedScreen = NavigationScreen.PROFILE
                                },
                                onOpenArtist = { artistName ->
                                    previousScreen = selectedScreen
                                    selectedArtistDetail = artistName
                                    selectedScreen = NavigationScreen.ARTIST_DETAIL
                                }
                            )
                        }

                        NavigationScreen.PROFILE -> {
                            ProfileScreen(
                                username = viewedProfileUsername ?: lastfmUsername.ifEmpty { "JmDemisana" },
                                preferredPlatform = preferredPlatform,
                                onSongClick = { title, artist, art ->
                                    if (directSongLaunch) {
                                        ItunesClient.openInPreferredPlayer(context, preferredPlatform, title, artist, null)
                                    } else {
                                        selectedSongDetail = SongDetailState(
                                            title = title,
                                            artist = artist,
                                            artworkUrl = art
                                        )
                                    }
                                }
                            )
                        }

                        NavigationScreen.NAMIREC -> {
                            NamiRecScreen(
                                username = lastfmUsername.ifEmpty { "JmDemisana" }
                            )
                        }

                        NavigationScreen.ARTIST_DETAIL -> {
                            ArtistFeatureScreen(
                                artistName = selectedArtistDetail ?: "GUMI",
                                preferredPlatform = preferredPlatform,
                                onBack = {
                                    selectedScreen = previousScreen
                                },
                                onSelectSong = { title, artist, art ->
                                    if (directSongLaunch) {
                                        ItunesClient.openInPreferredPlayer(context, preferredPlatform, title, artist, null)
                                    } else {
                                        selectedSongDetail = SongDetailState(
                                            title = title,
                                            artist = artist,
                                            artworkUrl = art
                                        )
                                    }
                                },
                                onSelectArtist = { newArtist ->
                                    selectedArtistDetail = newArtist
                                }
                            )
                        }

                        NavigationScreen.MARUCAST -> {
                            MarucastScreen(
                                onStartStream = { pin, onResult ->
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            MarucastApiClient.lookupPin(pin, object : MarucastApiClient.Callback<String> {
                                                override fun onSuccess(token: String) {
                                                    val serviceIntent = Intent(context, MarucastForegroundService::class.java).apply {
                                                        action = MarucastForegroundService.ACTION_START
                                                        putExtra(MarucastForegroundService.EXTRA_TOKEN, token)
                                                        if (projectionIntentData != null) {
                                                            putExtra(MarucastForegroundService.EXTRA_PROJECTION_DATA, projectionIntentData)
                                                        }
                                                    }
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        context.startForegroundService(serviceIntent)
                                                    } else {
                                                        context.startService(serviceIntent)
                                                    }
                                                    onResult(true, null)
                                                }

                                                override fun onError(error: String) {
                                                    onResult(false, error)
                                                }
                                            })
                                        }
                                    }
                                },
                                onStopStream = {
                                    val stopIntent = Intent(context, MarucastForegroundService::class.java).apply {
                                        action = MarucastForegroundService.ACTION_STOP
                                    }
                                    context.startService(stopIntent)
                                }
                            )
                        }

                        NavigationScreen.SCROBBLING -> {
                            ScrobblingScreen(
                                scrobbleEnabled = scrobbleEnabled,
                                onToggleScrobble = onToggleScrobble,
                                sessionKey = sessionKey,
                                lastfmUsername = lastfmUsername,
                                onSessionKeyChange = { scope.launch { prefs.setLastfmSessionKey(it) } },
                                onUsernameChange = { scope.launch { prefs.setLastfmUsername(it) } },
                                scrobblePercentage = scrobblePercentage,
                                onScrobblePercentageChange = { scope.launch { prefs.setScrobblePercentage(it) } },
                                allApps = allApps,
                                scrobbleApps = scrobbleApps,
                                onScrobbleAppsChange = { scope.launch { prefs.setScrobbleApps(it) } },
                                onShowManualDialog = { showManualCredsDialog = true }
                            )
                        }

                        NavigationScreen.LOCAL -> {
                            LocalScreen(
                                localEnabled = localEnabled,
                                onToggleLocal = onToggleLocal,
                                allApps = allApps,
                                localApps = localApps,
                                onLocalAppsChange = { scope.launch { prefs.setLocalApps(it) } }
                            )
                        }

                        NavigationScreen.RECEIVER -> {
                            ReceiverScreen(
                                receiverEnabled = receiverEnabled,
                                onToggleReceiver = onToggleReceiver,
                                receiverUsername = receiverUsername,
                                lastfmUsername = lastfmUsername,
                                onReceiverUsernameChange = { scope.launch { prefs.setReceiverUsername(it) } }
                            )
                        }

                        NavigationScreen.COMMON -> {
                            CommonSettingsScreen(
                                mainFmt = mainFmt,
                                subFmt = subFmt,
                                onMainFmtChange = { scope.launch { prefs.setNotifMainFormat(it) } },
                                onSubFmtChange = { scope.launch { prefs.setNotifSubFormat(it) } },
                                preferredPlatform = preferredPlatform,
                                onPreferredPlatformChange = { scope.launch { prefs.setPreferredPlatform(it) } },
                                directSongLaunch = directSongLaunch,
                                onDirectSongLaunchChange = { scope.launch { prefs.setDirectSongLaunch(it) } },
                                notifySongUpdate = notifySongUpdate,
                                onToggleSongUpdate = { scope.launch { prefs.setNotifySongUpdate(it) } },
                                intervalEnabled = intervalEnabled,
                                onIntervalToggle = { scope.launch { prefs.setIntervalEnabled(it) } },
                                intervalMinutes = intervalMinutes,
                                onIntervalMinutesChange = { scope.launch { prefs.setIntervalMinutes(it) } },
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
                        },
                        onOpenArtist = { artist ->
                            selectedSongDetail = null
                            selectedArtistDetail = artist
                            previousScreen = selectedScreen
                            selectedScreen = NavigationScreen.ARTIST_DETAIL
                        }
                    )
                }

                // Manual Credentials Dialog
                if (showManualCredsDialog) {
                    ManualSessionKeyDialog(
                        currentKey = sessionKey,
                        currentUsername = lastfmUsername,
                        onSave = { key, user ->
                            scope.launch {
                                prefs.setLastfmSessionKey(key)
                                prefs.setLastfmUsername(user)
                            }
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
// 1. DISCOVERY SCREEN (Simplified Track-Only View with Contextual Seed Reasons)
// -------------------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    recommendations: List<LastFmRecommendationsEngine.RecommendedTrackItem>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSongClick: (LastFmRecommendationsEngine.RecommendedTrackItem) -> Unit
) {
    val pullRefreshState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    var isGridView by remember { mutableStateOf(false) }

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
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
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

                    Surface(
                        onClick = { isGridView = !isGridView },
                        shape = MaruPillShape,
                        color = MaruGlassSubtleBg,
                        border = BorderStroke(1.dp, MaruGlassBorderSoft)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                                contentDescription = "Toggle View",
                                tint = MaruAccentPink,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                if (isGridView) "LIST" else "GRID",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.5.sp
                                ),
                                color = MaruTextStrong
                            )
                        }
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
            } else if (isGridView) {
                val chunked = recommendations.chunked(2)
                items(chunked, key = { row -> row.joinToString("_") { "${it.artist}_${it.title}" } }) { rowItems ->
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
                items(recommendations, key = { "${it.artist}_${it.title}_${it.reason}" }) { item ->
                    GlassRecommendationCard(
                        item = item,
                        onClick = { onSongClick(item) }
                    )
                }
            }

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

// -------------------------------------------------------------------------------------------------
// 2. SEARCH SCREEN
// -------------------------------------------------------------------------------------------------
object ArtistCanonicalResolver {
    private val KNOWN_ALIASES = mapOf(
        "miku" to "Hatsune Miku",
        "hatsune" to "Hatsune Miku",
        "hatsunemiku" to "Hatsune Miku",
        "初音ミク" to "Hatsune Miku",
        "gumi" to "GUMI",
        "megpoid" to "GUMI",
        "rin" to "Kagamine Rin",
        "kagamine rin" to "Kagamine Rin",
        "len" to "Kagamine Len",
        "kagamine len" to "Kagamine Len",
        "luka" to "Megurine Luka",
        "megurine luka" to "Megurine Luka",
        "kaito" to "KAITO",
        "meiko" to "MEIKO",
        "teto" to "Kasane Teto",
        "kasane teto" to "Kasane Teto",
        "ia" to "IA",
        "deco 27" to "DECO*27",
        "deco27" to "DECO*27",
        "deco*27" to "DECO*27",
        "pinocchio-p" to "PinocchioP",
        "pinocchiop" to "PinocchioP",
        "pasupare" to "Pastel*Palettes",
        "pastel palettes" to "Pastel*Palettes",
        "popipa" to "Poppin'Party",
        "poppin party" to "Poppin'Party",
        "roselia" to "Roselia"
    )

    fun resolveCanonicalName(input: String, bioSummary: String? = null): String {
        val clean = input.trim()
        val lower = clean.lowercase()
        KNOWN_ALIASES[lower]?.let { return it }

        if (bioSummary != null && (bioSummary.contains("Incorrect tag for", ignoreCase = true) || bioSummary.contains("There is more than one artist named", ignoreCase = true))) {
            if (bioSummary.contains("初音ミク") || lower == "miku") return "Hatsune Miku"
            if (bioSummary.contains("Incorrect tag for", ignoreCase = true)) {
                val after = bioSummary.substringAfter("Incorrect tag for", "").trim()
                val candidate = after.substringBefore(".").substringBefore(",").substringBefore("\n").trim()
                if (candidate.isNotBlank() && candidate.length < 50) {
                    return candidate
                }
            }
        }
        return clean
    }
}

enum class SearchScope(val label: String) {
    ARTISTS("Artists & Albums"),
    SONGS("Songs"),
    PROFILES("Profiles")
}

data class ArtistOverviewResult(
    val artistName: String,
    val artistInfo: LastNotifApiClient.ArtistDetailInfo?,
    val highResArtwork: String?,
    val topTracks: List<LastNotifApiClient.TopItem>,
    val topAlbums: List<LastNotifApiClient.TopItem>,
    val albumMatch: ItunesClient.ItunesSongMatch? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    preferredPlatform: String,
    onSongClick: (ItunesClient.ItunesSongMatch) -> Unit,
    onOpenProfile: (String) -> Unit,
    onOpenArtist: (String) -> Unit
) {
    var selectedScope by remember { mutableStateOf(SearchScope.ARTISTS) }
    var searchQuery by remember { mutableStateOf("") }

    var artistOverview by remember { mutableStateOf<ArtistOverviewResult?>(null) }
    var isArtistSearching by remember { mutableStateOf(false) }

    var musicResults by remember { mutableStateOf<List<ItunesClient.ItunesSongMatch>>(emptyList()) }
    var isMusicSearching by remember { mutableStateOf(false) }

    var profileResult by remember { mutableStateOf<LastNotifApiClient.UserProfile?>(null) }
    var profileRecentTracks by remember { mutableStateOf<List<LastNotifApiClient.ScrobbleItem>>(emptyList()) }
    var isProfileSearching by remember { mutableStateOf(false) }
    var profileNotFound by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(searchQuery, selectedScope) {
        val q = searchQuery.trim()
        if (q.length < 2) {
            artistOverview = null
            musicResults = emptyList()
            profileResult = null
            profileRecentTracks = emptyList()
            profileNotFound = false
            return@LaunchedEffect
        }

        when (selectedScope) {
            SearchScope.ARTISTS -> {
                delay(350)
                isArtistSearching = true
                val result = withContext(Dispatchers.IO) {
                    val canonicalInput = ArtistCanonicalResolver.resolveCanonicalName(q)
                    var info = LastNotifApiClient.getArtistInfo(canonicalInput)
                    val resolvedName = ArtistCanonicalResolver.resolveCanonicalName(info?.name?.ifEmpty { canonicalInput } ?: canonicalInput, info?.bioSummary)
                    if (resolvedName != canonicalInput) {
                        info = LastNotifApiClient.getArtistInfo(resolvedName) ?: info
                    }

                    val itunesArtist = ItunesClient.searchArtist(resolvedName)
                    val tracks = LastNotifApiClient.getArtistTopTracks(resolvedName, limit = 5)
                    val albums = LastNotifApiClient.getArtistTopAlbums(resolvedName, limit = 4)
                    val albumMatch = ItunesClient.searchAlbum(q)
                    val art = itunesArtist?.artworkUrl ?: info?.artworkUrl ?: albumMatch?.artworkUrl

                    if (info != null || itunesArtist != null || albumMatch != null || tracks.isNotEmpty()) {
                        ArtistOverviewResult(
                            artistName = resolvedName,
                            artistInfo = info,
                            highResArtwork = art,
                            topTracks = tracks,
                            topAlbums = albums,
                            albumMatch = albumMatch
                        )
                    } else {
                        null
                    }
                }
                artistOverview = result
                isArtistSearching = false
            }
            SearchScope.SONGS -> {
                delay(350)
                isMusicSearching = true
                musicResults = withContext(Dispatchers.IO) { ItunesClient.searchInstant(q) }
                isMusicSearching = false
            }
            SearchScope.PROFILES -> {
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
            }
        }
    }

    val pullRefreshState = rememberPullToRefreshState()

    PullToRefreshBox(
        isRefreshing = when (selectedScope) {
            SearchScope.ARTISTS -> isArtistSearching
            SearchScope.SONGS -> isMusicSearching
            SearchScope.PROFILES -> isProfileSearching
        },
        onRefresh = {
            val q = searchQuery.trim()
            if (q.isNotBlank()) {
                scope.launch {
                    when (selectedScope) {
                        SearchScope.ARTISTS -> {
                            isArtistSearching = true
                            val result = withContext(Dispatchers.IO) {
                                val info = LastNotifApiClient.getArtistInfo(q)
                                val resolvedName = info?.name?.ifEmpty { q } ?: q
                                val itunesArtist = ItunesClient.searchArtist(resolvedName)
                                val tracks = LastNotifApiClient.getArtistTopTracks(resolvedName, limit = 5)
                                val albums = LastNotifApiClient.getArtistTopAlbums(resolvedName, limit = 4)
                                val albumMatch = ItunesClient.searchAlbum(q)
                                val art = itunesArtist?.artworkUrl ?: info?.artworkUrl ?: albumMatch?.artworkUrl

                                ArtistOverviewResult(
                                    artistName = resolvedName,
                                    artistInfo = info,
                                    highResArtwork = art,
                                    topTracks = tracks,
                                    topAlbums = albums,
                                    albumMatch = albumMatch
                                )
                            }
                            artistOverview = result
                            isArtistSearching = false
                        }
                        SearchScope.SONGS -> {
                            isMusicSearching = true
                            musicResults = withContext(Dispatchers.IO) { ItunesClient.searchInstant(q) }
                            isMusicSearching = false
                        }
                        SearchScope.PROFILES -> {
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
            // Scope Picker Tabs
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
                        when (selectedScope) {
                            SearchScope.ARTISTS -> Icons.Default.MicExternalOn
                            SearchScope.SONGS -> Icons.Default.MusicNote
                            SearchScope.PROFILES -> Icons.Default.PersonSearch
                        },
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
                                when (selectedScope) {
                                    SearchScope.ARTISTS -> "Search artist or album (e.g. GUMI, Yoasobi)..."
                                    SearchScope.SONGS -> "Search songs & tracks..."
                                    SearchScope.PROFILES -> "Enter Last.fm username..."
                                },
                                color = MaruTextMuted.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.bodyMedium
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
                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Clear, "Clear", tint = MaruTextMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // Results View
            when (selectedScope) {
                SearchScope.ARTISTS -> {
                    if (isArtistSearching) {
                        Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(28.dp))
                        }
                    } else if (artistOverview != null) {
                        val item = artistOverview!!
                        // 1. Artist Hero Overview Card
                        GlassCard(
                            border = MaruAccentPink.copy(alpha = 0.5f),
                            glowColor = MaruAccentPink.copy(alpha = 0.15f)
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(72.dp)
                                            .clip(CircleShape)
                                            .background(MaruGlassSubtleBg)
                                            .border(1.5.dp, MaruAccentPink, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (!item.highResArtwork.isNullOrBlank()) {
                                            AsyncImage(
                                                model = item.highResArtwork,
                                                contentDescription = item.artistName,
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

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            item.artistName,
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 20.sp
                                            ),
                                            color = MaruTextStrong,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )

                                        val listeners = item.artistInfo?.listeners ?: 0L
                                        val playcount = item.artistInfo?.playcount ?: 0L
                                        if (listeners > 0 || playcount > 0) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                "%,d listeners • %,d plays".format(listeners, playcount),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                color = MaruAccentPink
                                            )
                                        }

                                        if (!item.artistInfo?.tags.isNullOrEmpty()) {
                                            Spacer(Modifier.height(6.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                item.artistInfo!!.tags.take(3).forEach { tag ->
                                                    Box(
                                                        modifier = Modifier
                                                            .background(MaruGlassSubtleBg, MaruPillShape)
                                                            .border(1.dp, MaruGlassBorderSoft, MaruPillShape)
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            tag.lowercase(),
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                            color = MaruTextMuted
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (!item.artistInfo?.bioSummary.isNullOrBlank()) {
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        item.artistInfo!!.bioSummary,
                                        style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                        color = MaruTextMuted,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(Modifier.height(14.dp))

                                Surface(
                                    onClick = { onOpenArtist(item.artistName) },
                                    shape = MaruPillShape,
                                    color = MaruAccentPink,
                                    modifier = Modifier.fillMaxWidth().height(40.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "EXPLORE ARTIST FEATURE",
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.5.sp,
                                            letterSpacing = 0.6.sp
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Top Tracks Preview
                        if (item.topTracks.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "POPULAR TRACKS",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                                color = MaruAccentPink
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                item.topTracks.take(4).forEachIndexed { index, track ->
                                    GlassCard(
                                        border = MaruGlassBorderSoft,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onSongClick(
                                                    ItunesClient.ItunesSongMatch(
                                                        trackId = 0L,
                                                        trackName = track.name,
                                                        artistName = item.artistName,
                                                        collectionName = "",
                                                        artworkUrl = track.artworkUrl ?: item.highResArtwork,
                                                        appleMusicUrl = null,
                                                        previewUrl = null
                                                    )
                                                )
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                "#${index + 1}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaruAccentPink,
                                                modifier = Modifier.width(28.dp)
                                            )

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    track.name,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = MaruTextStrong,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    item.artistName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaruTextMuted,
                                                    maxLines = 1
                                                )
                                            }

                                            Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = "Play",
                                                tint = MaruAccentPink,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Album Match Card (if query was an album)
                        if (item.albumMatch != null && !item.albumMatch.collectionName.equals(item.artistName, ignoreCase = true)) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "MATCHED ALBUM",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                                color = MaruAccentBlue
                            )
                            GlassCard(
                                border = MaruAccentBlue.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenArtist(item.albumMatch.artistName) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(MaruCardShape)
                                            .background(MaruGlassSubtleBg)
                                    ) {
                                        if (!item.albumMatch.artworkUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = item.albumMatch.artworkUrl,
                                                contentDescription = item.albumMatch.collectionName,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop,
                                                error = painterResource(id = R.drawable.ic_maru_heart),
                                                placeholder = painterResource(id = R.drawable.ic_maru_heart)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.Album,
                                                contentDescription = null,
                                                tint = MaruAccentBlue,
                                                modifier = Modifier.size(28.dp).align(Alignment.Center)
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            item.albumMatch.collectionName,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaruTextStrong,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            "Album by ${item.albumMatch.artistName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaruAccentBlue,
                                            maxLines = 1
                                        )
                                    }

                                    Icon(
                                        Icons.Default.ChevronRight,
                                        contentDescription = "View Artist",
                                        tint = MaruAccentBlue,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    } else if (searchQuery.length >= 2) {
                        GlassCard {
                            Column(
                                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.SearchOff, null, tint = MaruTextMuted, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("No artist or album found", color = MaruTextStrong, fontWeight = FontWeight.Bold)
                                Text("Try searching by artist name (e.g. GUMI, DECO*27, Yoasobi)", color = MaruTextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                SearchScope.SONGS -> {
                    if (isMusicSearching) {
                        Box(modifier = Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(28.dp))
                        }
                    } else if (musicResults.isNotEmpty()) {
                        Text(
                            "SEARCH RESULTS (${musicResults.size})",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                            color = MaruAccentPink
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            musicResults.forEach { match ->
                                GlassMusicSearchItem(match = match, onClick = { onSongClick(match) })
                            }
                        }
                    } else if (searchQuery.length >= 2) {
                        GlassCard {
                            Column(
                                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.SearchOff, null, tint = MaruTextMuted, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("No songs found", color = MaruTextStrong, fontWeight = FontWeight.Bold)
                                Text("Try searching by song title", color = MaruTextMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                SearchScope.PROFILES -> {
                    if (isProfileSearching) {
                        Box(modifier = Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(28.dp))
                        }
                    } else if (profileResult != null) {
                        GlassProfileResultCard(
                            profile = profileResult!!,
                            recentTracks = profileRecentTracks,
                            onOpen = { onOpenProfile(profileResult!!.username) }
                        )
                    } else if (profileNotFound) {
                        GlassCard {
                            Column(
                                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(Icons.Default.PersonOff, null, tint = MaruTextMuted, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("Last.fm User Not Found", color = MaruTextStrong, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 3. ARTIST FEATURE SCREEN (Comprehensive Artist Profile & Discography)
// -------------------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistFeatureScreen(
    artistName: String,
    preferredPlatform: String,
    onBack: () -> Unit,
    onSelectSong: (title: String, artist: String, artworkUrl: String?) -> Unit,
    onSelectArtist: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var artistInfo by remember(artistName) { mutableStateOf<LastNotifApiClient.ArtistDetailInfo?>(null) }
    var topTracks by remember(artistName) { mutableStateOf<List<LastNotifApiClient.TopItem>>(emptyList()) }
    var topAlbums by remember(artistName) { mutableStateOf<List<LastNotifApiClient.TopItem>>(emptyList()) }
    var similarArtists by remember(artistName) { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember(artistName) { mutableStateOf(true) }
    var highResArtwork by remember(artistName) { mutableStateOf<String?>(null) }

    LaunchedEffect(artistName) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val canonical = ArtistCanonicalResolver.resolveCanonicalName(artistName)
            var info = LastNotifApiClient.getArtistInfo(canonical)
            val resolved = ArtistCanonicalResolver.resolveCanonicalName(info?.name?.ifEmpty { canonical } ?: canonical, info?.bioSummary)
            if (resolved != canonical) {
                info = LastNotifApiClient.getArtistInfo(resolved) ?: info
            }

            val tracks = LastNotifApiClient.getArtistTopTracks(resolved, limit = 10)
            val albums = LastNotifApiClient.getArtistTopAlbums(resolved, limit = 8)
            val similar = LastNotifApiClient.getSimilarArtists(resolved, limit = 8)
            val itunes = ItunesClient.searchArtist(resolved)

            artistInfo = info
            topTracks = tracks
            topAlbums = albums
            similarArtists = similar
            highResArtwork = itunes?.artworkUrl ?: info?.artworkUrl
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        artistName.uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        ),
                        color = MaruTextStrong
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MaruAccentPink)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(36.dp))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Artist Hero Card
                GlassCard(border = MaruAccentPink.copy(alpha = 0.5f), glowColor = MaruAccentPink.copy(alpha = 0.2f)) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(130.dp)
                                .clip(CircleShape)
                                .background(MaruGlassSubtleBg)
                                .border(2.dp, MaruAccentPink, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!highResArtwork.isNullOrBlank()) {
                                AsyncImage(
                                    model = highResArtwork,
                                    contentDescription = artistName,
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
                                    modifier = Modifier.size(48.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            artistName,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            ),
                            color = MaruTextStrong,
                            textAlign = TextAlign.Center
                        )

                        val listeners = artistInfo?.listeners ?: 0L
                        val playcount = artistInfo?.playcount ?: 0L
                        if (listeners > 0 || playcount > 0) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "%,d listeners • %,d scrobbles".format(listeners, playcount),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = MaruAccentPink
                            )
                        }

                        if (!artistInfo?.tags.isNullOrEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                artistInfo!!.tags.take(4).forEach { tag ->
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 4.dp)
                                            .background(MaruGlassSubtleBg, MaruPillShape)
                                            .border(1.dp, MaruGlassBorderSoft, MaruPillShape)
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            "#$tag",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold),
                                            color = MaruTextMuted
                                        )
                                    }
                                }
                            }
                        }

                        if (!artistInfo?.bioSummary.isNullOrBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                artistInfo!!.bioSummary,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 16.sp),
                                color = MaruTextMuted,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        Text("OPEN ARTIST IN STREAMING PLAYER", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaruTextMuted)
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val platforms = listOf("Apple Music", "Spotify", "YouTube Music", "Tidal")
                            platforms.forEach { platform ->
                                StreamingPlatformIconButton(
                                    platform = platform,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        ItunesClient.openInPreferredPlayer(context, platform, "", artistName, null)
                                    }
                                )
                            }
                        }
                    }
                }

                // Top Popular Songs
                if (topTracks.isNotEmpty()) {
                    GlassSectionHeader("POPULAR TRACKS", Icons.Default.MusicNote)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        topTracks.forEach { track ->
                            GlassCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectSong(track.name, artistName, track.artworkUrl)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(MaruAccentPink.copy(alpha = 0.15f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            track.rank.toString(),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaruAccentPink
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            track.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaruTextStrong,
                                            maxLines = 1
                                        )
                                        if (track.playcount > 0) {
                                            Text(
                                                "%,d scrobbles".format(track.playcount),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = MaruTextMuted
                                            )
                                        }
                                    }
                                    Icon(Icons.Default.PlayArrow, null, tint = MaruAccentPink, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }

                // Top Albums / Discography
                if (topAlbums.isNotEmpty()) {
                    GlassSectionHeader("ALBUMS & DISCOGRAPHY", Icons.Default.Album)
                    val chunkedAlbums = topAlbums.chunked(2)
                    chunkedAlbums.forEach { rowAlbums ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowAlbums.forEach { alb ->
                                GlassCard(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            scope.launch {
                                                ItunesClient.openAlbumFirstTrack(context, preferredPlatform, alb.name, artistName)
                                            }
                                        }
                                ) {
                                    Column {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(1f)
                                                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                                .background(MaruGlassSubtleBg)
                                        ) {
                                            AutoArtworkImage(
                                                title = alb.name,
                                                artist = artistName,
                                                initialUrl = alb.artworkUrl,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                alb.name,
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                                                color = MaruTextStrong,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (alb.playcount > 0) {
                                                Text(
                                                    "%,d plays".format(alb.playcount),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaruTextMuted
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            if (rowAlbums.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }

                // Similar Artists
                if (similarArtists.isNotEmpty()) {
                    GlassSectionHeader("SIMILAR ARTISTS", Icons.Default.AutoAwesome)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        similarArtists.forEach { simArtist ->
                            Box(
                                modifier = Modifier
                                    .background(MaruGlassCardBg, MaruPillShape)
                                    .border(BorderStroke(1.dp, MaruGlassBorderSoft), MaruPillShape)
                                    .clickable { onSelectArtist(simArtist) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Person, null, tint = MaruAccentPurple, modifier = Modifier.size(14.dp))
                                    Text(
                                        simArtist,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
                                        color = MaruTextStrong
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 4. PROFILE SCREEN & HISTORY
// -------------------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    username: String,
    preferredPlatform: String,
    onSongClick: (String, String, String?) -> Unit
) {
    val context = LocalContext.current
    var profile by remember(username) { mutableStateOf<LastNotifApiClient.UserProfile?>(null) }
    var recentTracks by remember(username) { mutableStateOf<List<LastNotifApiClient.ScrobbleItem>>(emptyList()) }
    var topTracks by remember(username) { mutableStateOf<List<LastNotifApiClient.TopItem>>(emptyList()) }
    var topArtists by remember(username) { mutableStateOf<List<LastNotifApiClient.TopItem>>(emptyList()) }
    var topAlbums by remember(username) { mutableStateOf<List<LastNotifApiClient.TopItem>>(emptyList()) }
    var selectedPeriod by remember { mutableStateOf("1month") }
    var isLoading by remember(username, selectedPeriod) { mutableStateOf(true) }

    LaunchedEffect(username, selectedPeriod) {
        isLoading = true
        withContext(Dispatchers.IO) {
            val u = LastNotifApiClient.getUserInfo(username)
            val r = LastNotifApiClient.getRecentTracks(username, limit = 25)
            val tt = LastNotifApiClient.getTopTracks(username, period = selectedPeriod, limit = 8)
            val ta = LastNotifApiClient.getTopArtists(username, period = selectedPeriod, limit = 8)
            val talb = LastNotifApiClient.getTopAlbums(username, period = selectedPeriod, limit = 6)

            profile = u
            recentTracks = r
            topTracks = tt
            topArtists = ta
            topAlbums = talb
            isLoading = false
        }
    }

    val pullRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()

    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = {
            scope.launch {
                isLoading = true
                val u = withContext(Dispatchers.IO) { LastNotifApiClient.getUserInfo(username) }
                val r = withContext(Dispatchers.IO) { LastNotifApiClient.getRecentTracks(username, limit = 25) }
                val tt = withContext(Dispatchers.IO) { LastNotifApiClient.getTopTracks(username, period = selectedPeriod, limit = 8) }
                val ta = withContext(Dispatchers.IO) { LastNotifApiClient.getTopArtists(username, period = selectedPeriod, limit = 8) }
                val talb = withContext(Dispatchers.IO) { LastNotifApiClient.getTopAlbums(username, period = selectedPeriod, limit = 6) }
                profile = u
                recentTracks = r
                topTracks = tt
                topArtists = ta
                topAlbums = talb
                isLoading = false
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Profile Card
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                border = MaruAccentPink.copy(alpha = 0.5f),
                glowColor = MaruAccentPink.copy(alpha = 0.15f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MaruGlassSubtleBg)
                            .border(2.dp, MaruAccentPink, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!profile?.avatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = profile!!.avatarUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                error = painterResource(id = R.drawable.ic_maru_heart),
                                placeholder = painterResource(id = R.drawable.ic_maru_heart)
                            )
                        } else {
                            Icon(Icons.Default.Person, null, tint = MaruAccentPink, modifier = Modifier.size(40.dp))
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Text(
                        profile?.username ?: username,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp),
                        color = MaruTextStrong
                    )

                    if (!profile?.realName.isNullOrBlank()) {
                        Text(
                            profile!!.realName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaruTextMuted
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    val playcount = profile?.playcount ?: 0L
                    Box(
                        modifier = Modifier
                            .background(MaruAccentPink.copy(alpha = 0.18f), MaruPillShape)
                            .border(1.dp, MaruAccentPink.copy(alpha = 0.5f), MaruPillShape)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "%,d total scrobbles".format(playcount),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                            color = MaruAccentPink
                        )
                    }
                }
            }

            // Period Selector Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaruGlassCardBg, MaruPillShape)
                    .border(BorderStroke(1.dp, MaruGlassBorderSoft), MaruPillShape)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                val periods = listOf(
                    "7day" to "7D",
                    "1month" to "1M",
                    "3month" to "3M",
                    "12month" to "1Y",
                    "overall" to "ALL"
                )
                periods.forEach { (key, label) ->
                    val isSelected = selectedPeriod == key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                if (isSelected) MaruAccentPink.copy(alpha = 0.25f) else Color.Transparent,
                                MaruPillShape
                            )
                            .border(
                                BorderStroke(
                                    1.dp,
                                    if (isSelected) MaruAccentPink else Color.Transparent
                                ),
                                MaruPillShape
                            )
                            .clickable { selectedPeriod = key }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 10.5.sp
                            ),
                            color = if (isSelected) MaruAccentPink else MaruTextMuted
                        )
                    }
                }
            }

            // Recent Scrobble Feed
            GlassSectionHeader("RECENT SCROBBLES (${recentTracks.size})", Icons.Default.History)
            if (recentTracks.isEmpty()) {
                GlassCard {
                    Text(
                        "No scrobbles found for @$username",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaruTextMuted,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentTracks.forEach { track ->
                        val timeString = remember(track.timestamp) {
                            if (track.timestamp == null) "" else {
                                val now = System.currentTimeMillis() / 1000
                                val diff = (now - track.timestamp).coerceAtLeast(0)
                                when {
                                    diff < 60 -> "just now"
                                    diff < 3600 -> "${diff / 60}m ago"
                                    diff < 86400 -> "${diff / 3600}h ago"
                                    diff < 604800 -> "${diff / 86400}d ago"
                                    else -> {
                                        val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                                        sdf.format(java.util.Date(track.timestamp * 1000))
                                    }
                                }
                            }
                        }

                        GlassCard(
                            border = if (track.isNowPlaying) MaruAccentPink.copy(alpha = 0.6f) else MaruGlassBorderSoft,
                            glowColor = if (track.isNowPlaying) MaruAccentPink.copy(alpha = 0.12f) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSongClick(track.title, track.artist, track.artworkUrl) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaruGlassSubtleBg)
                                ) {
                                    AutoArtworkImage(
                                        title = track.title,
                                        artist = track.artist,
                                        initialUrl = track.artworkUrl,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        track.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaruTextStrong,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        if (track.album.isNotBlank()) "${track.artist} • ${track.album}" else track.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaruTextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Spacer(Modifier.width(8.dp))

                                if (track.isNowPlaying) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier
                                            .background(MaruAccentPink.copy(alpha = 0.2f), MaruPillShape)
                                            .border(1.dp, MaruAccentPink.copy(alpha = 0.5f), MaruPillShape)
                                            .padding(horizontal = 7.dp, vertical = 3.dp)
                                    ) {
                                        EqualizerVisualizer()
                                        Text(
                                            "NOW",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold),
                                            color = MaruAccentPink
                                        )
                                    }
                                } else if (timeString.isNotEmpty()) {
                                    Text(
                                        timeString,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaruTextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Top Tracks
            if (topTracks.isNotEmpty()) {
                GlassSectionHeader("TOP TRACKS (${selectedPeriod.uppercase()})", Icons.Default.Leaderboard)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    topTracks.forEach { item ->
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSongClick(item.name, item.subtext, item.artworkUrl) }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(MaruAccentPink.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "#${item.rank}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                        color = MaruAccentPink
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaruTextStrong, maxLines = 1)
                                    Text(item.subtext, style = MaterialTheme.typography.bodySmall, color = MaruTextMuted, maxLines = 1)
                                }
                                if (item.playcount > 0) {
                                    Text(
                                        "%,d plays".format(item.playcount),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                                        color = MaruAccentPink
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Top Artists
            if (topArtists.isNotEmpty()) {
                GlassSectionHeader("TOP ARTISTS (${selectedPeriod.uppercase()})", Icons.Default.People)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    topArtists.forEach { item ->
                        GlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ItunesClient.openInPreferredPlayer(context, preferredPlatform, "", item.name, null)
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(MaruAccentBlue.copy(alpha = 0.15f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "#${item.rank}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                                        color = MaruAccentBlue
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaruTextStrong, maxLines = 1)
                                }
                                if (item.playcount > 0) {
                                    Text(
                                        "%,d plays".format(item.playcount),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                                        color = MaruAccentBlue
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Top Albums
            if (topAlbums.isNotEmpty()) {
                GlassSectionHeader("TOP ALBUMS (${selectedPeriod.uppercase()})", Icons.Default.Album)
                val chunkedAlbums = topAlbums.chunked(2)
                chunkedAlbums.forEach { rowAlbums ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowAlbums.forEach { alb ->
                            GlassCard(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        scope.launch {
                                            val artistName = alb.subtext.ifEmpty { username }
                                            ItunesClient.openAlbumFirstTrack(context, preferredPlatform, alb.name, artistName)
                                        }
                                    }
                            ) {
                                Column {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                                            .background(MaruGlassSubtleBg)
                                    ) {
                                        AutoArtworkImage(
                                            title = alb.name,
                                            artist = alb.subtext,
                                            initialUrl = alb.artworkUrl,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            alb.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                                            color = MaruTextStrong,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            alb.subtext,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = MaruTextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (alb.playcount > 0) {
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                "%,d plays".format(alb.playcount),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                                color = MaruAccentPurple
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (rowAlbums.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

// -------------------------------------------------------------------------------------------------
// 4.5. NamiRec Monthly Recap Screen
// -------------------------------------------------------------------------------------------------
class NamiRecJsBridge(private val context: Context) {
    @JavascriptInterface
    fun saveBase64Image(base64Data: String, filename: String): Boolean {
        return try {
            val cleanData = if (base64Data.contains(",")) base64Data.substringAfter(",") else base64Data
            val decodedBytes = Base64.decode(cleanData, Base64.DEFAULT)

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename.ifEmpty { "NamiRec_${System.currentTimeMillis()}.png" })
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MAudio")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false

            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(decodedBytes)
                stream.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Saved recap card to Pictures/MAudio!", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            Log.e("NamiRecJsBridge", "Failed to save base64 image", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Failed to save image card: ${e.message}", Toast.LENGTH_LONG).show()
            }
            false
        }
    }
}

@Composable
fun NamiRecScreen(username: String) {
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
                    settings.userAgentString = settings.userAgentString + " MAudioMobileApp/1.0"

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
// 5. STREAMING PLATFORM ICON BUTTON
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
// 6. SONG DETAIL BOTTOM SHEET (More by Artist & Similar Songs)
// -------------------------------------------------------------------------------------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailBottomSheet(
    song: SongDetailState,
    preferredPlatform: String,
    onDismiss: () -> Unit,
    onSelectSimilarSong: (SongDetailState) -> Unit,
    onOpenArtist: (String) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var artworkUrl by remember(song) { mutableStateOf(song.artworkUrl) }
    var appleMusicUrl by remember(song) { mutableStateOf(song.appleMusicUrl) }
    var artistTopTracks by remember(song) { mutableStateOf<List<LastNotifApiClient.TopItem>>(emptyList()) }
    var similarTracks by remember(song) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var isLoading by remember(song) { mutableStateOf(true) }

    LaunchedEffect(song) {
        withContext(Dispatchers.IO) {
            if (artworkUrl == null) {
                val match = ItunesClient.searchSong(song.title, song.artist)
                artworkUrl = match?.artworkUrl
                appleMusicUrl = match?.appleMusicUrl
            }
            val moreFromArtist = LastNotifApiClient.getArtistTopTracks(song.artist, limit = 5)
            val similar = LastNotifApiClient.getSimilarTracks(song.artist, song.title, limit = 6)
            artistTopTracks = moreFromArtist.filterNot { it.name.equals(song.title, ignoreCase = true) }.take(4)
            similarTracks = similar.filterNot { it.first.equals(song.title, ignoreCase = true) }
            isLoading = false
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
            // Album Artwork with glowing halo
            Box(
                modifier = Modifier
                    .size(190.dp)
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

            // Title & Interactive Artist Badge
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaruTextStrong,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))

                Surface(
                    onClick = { onOpenArtist(song.artist) },
                    shape = MaruPillShape,
                    color = MaruAccentPink.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, MaruAccentPink.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Person, null, tint = MaruAccentPink, modifier = Modifier.size(14.dp))
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.5.sp),
                            color = MaruAccentPink
                        )
                        Icon(Icons.Default.ChevronRight, null, tint = MaruAccentPink, modifier = Modifier.size(14.dp))
                    }
                }

                if (song.album.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
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

            // 1. FROM THE SAME ARTIST SECTION
            if (artistTopTracks.isNotEmpty()) {
                HorizontalDivider(color = MaruGlassBorderSoft)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.MicExternalOn, null, tint = MaruAccentPink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("MORE BY ${song.artist.uppercase()}", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruAccentPink)
                    }

                    Text(
                        "View All →",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                        color = MaruAccentPink,
                        modifier = Modifier.clickable { onOpenArtist(song.artist) }
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    artistTopTracks.forEach { track ->
                        GlassCard(
                            border = MaruGlassBorderSoft,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectSimilarSong(
                                        SongDetailState(
                                            title = track.name,
                                            artist = song.artist
                                        )
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PlayArrow, null, tint = MaruAccentPink, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(track.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaruTextStrong, maxLines = 1)
                                    Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaruTextMuted, maxLines = 1)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = MaruTextMuted, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

            // 2. SIMILAR TRACKS SECTION
            if (isLoading) {
                HorizontalDivider(color = MaruGlassBorderSoft)
                Box(modifier = Modifier.fillMaxWidth().height(60.dp), contentAlignment = Alignment.Center) {
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
                    Text("SIMILAR TO \"${song.title.uppercase()}\"", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruAccentBlue)
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
// 7. HERO LIVE NOTIFICATION BANNER
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
// 8. NAVIGATION DRAWER
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
                        "MAUDIO",
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

            NavigationScreen.entries.filter { it.group == NavigationGroup.RECOMMENDATION_ENGINE && it != NavigationScreen.ARTIST_DETAIL }.forEach { screen ->
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

// -------------------------------------------------------------------------------------------------
// 9. SUB-SCREENS (Scrobbling, Local, Receiver, Common)
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
                            Text("ENTER SESSION KEY MANUALLY", fontSize = 11.5.sp, color = MaruTextMuted, fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    GlassCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(painterResource(id = R.drawable.ic_lastfm_logo), null, modifier = Modifier.size(28.dp), tint = Color.Unspecified)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(lastfmUsername, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaruTextStrong)
                                Text("Connected & Authorized", style = MaterialTheme.typography.labelSmall, color = MaruAccentGreen)
                            }
                            IconButton(onClick = {
                                onSessionKeyChange("")
                                onUsernameChange("")
                            }) {
                                Icon(Icons.Default.Logout, "Disconnect", tint = MaruDanger)
                            }
                        }
                    }
                }

                GlassSectionHeader("SCROBBLE PERCENTAGE", Icons.Default.Tune)
                GlassCard {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Trigger Threshold", style = MaterialTheme.typography.bodyMedium, color = MaruTextStrong)
                            Text("$scrobblePercentage%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaruAccentPink)
                        }
                        Slider(
                            value = scrobblePercentage.toFloat(),
                            onValueChange = { onScrobblePercentageChange(it.toInt()) },
                            valueRange = 10f..90f,
                            steps = 7,
                            colors = SliderDefaults.colors(thumbColor = MaruAccentPink, activeTrackColor = MaruAccentPink)
                        )
                    }
                }

                GlassSectionHeader("APP FILTER", Icons.Default.Apps)
                GlassAppSelectionTile(
                    allApps = allApps,
                    selectedPackages = scrobbleApps,
                    onSelectionChange = onScrobbleAppsChange
                )
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
            description = "Intercept active Android media notifications and mirror them into MAudio alerts.",
            isEnabled = localEnabled,
            onToggle = onToggleLocal
        )

        AnimatedVisibility(
            visible = localEnabled,
            enter = expandVertically(spring()) + fadeIn(),
            exit = shrinkVertically(spring()) + fadeOut()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                GlassSectionHeader("APP FILTER", Icons.Default.Apps)
                GlassAppSelectionTile(
                    allApps = allApps,
                    selectedPackages = localApps,
                    onSelectionChange = onLocalAppsChange
                )
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
    directSongLaunch: Boolean,
    onDirectSongLaunchChange: (Boolean) -> Unit,
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
        GlassSectionHeader("PLAYER & LAUNCH BEHAVIOR", Icons.Default.PlayCircle)
        GlassCard {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Preferred streaming service:",
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

                HorizontalDivider(color = MaruGlassBorderSoft, modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDirectSongLaunchChange(!directSongLaunch) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Direct Player Launch", color = MaruTextStrong, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("Tapping a song opens streaming player immediately instead of showing song details.", color = MaruTextMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = directSongLaunch,
                        onCheckedChange = onDirectSongLaunchChange,
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
// 10. REUSABLE GLASS UI COMPONENTS
// -------------------------------------------------------------------------------------------------
@Composable
fun EqualizerVisualizer(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "Equalizer")
    val bar1 by infiniteTransition.animateFloat(
        initialValue = 4f, targetValue = 14f,
        animationSpec = infiniteRepeatable(animation = tween(420, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "Bar1"
    )
    val bar2 by infiniteTransition.animateFloat(
        initialValue = 14f, targetValue = 5f,
        animationSpec = infiniteRepeatable(animation = tween(320, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "Bar2"
    )
    val bar3 by infiniteTransition.animateFloat(
        initialValue = 6f, targetValue = 15f,
        animationSpec = infiniteRepeatable(animation = tween(520, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "Bar3"
    )
    val bar4 by infiniteTransition.animateFloat(
        initialValue = 12f, targetValue = 3f,
        animationSpec = infiniteRepeatable(animation = tween(380, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "Bar4"
    )

    androidx.compose.foundation.Canvas(modifier = modifier.size(18.dp, 16.dp)) {
        val barCount = 4
        val barWidth = 2.5.dp.toPx()
        val spacing = 1.8.dp.toPx()
        val corner = androidx.compose.ui.geometry.CornerRadius(1.2.dp.toPx())
        val totalBarsWidth = (barCount * barWidth) + ((barCount - 1) * spacing)
        val startX = (size.width - totalBarsWidth) / 2f

        val heights = listOf(bar1.dp.toPx(), bar2.dp.toPx(), bar3.dp.toPx(), bar4.dp.toPx())
        val colors = listOf(
            MaruAccentPink,
            MaruAccentPink.copy(alpha = 0.85f),
            MaruAccentBlue,
            MaruAccentGreen
        )

        heights.forEachIndexed { i, h ->
            val clampedH = h.coerceIn(2.dp.toPx(), size.height)
            val x = startX + i * (barWidth + spacing)
            drawRoundRect(
                color = colors[i % colors.size],
                topLeft = androidx.compose.ui.geometry.Offset(x, size.height - clampedH),
                size = androidx.compose.ui.geometry.Size(barWidth, clampedH),
                cornerRadius = corner
            )
        }
    }
}

@Composable
fun GlassRecommendationCard(
    item: LastFmRecommendationsEngine.RecommendedTrackItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    .size(52.dp)
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
                if (item.reason.isNotEmpty()) {
                    Text(
                        text = item.reason,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = MaruAccentPink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
fun GlassRecommendationGridCard(
    item: LastFmRecommendationsEngine.RecommendedTrackItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_maru_heart),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                if (item.reason.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .padding(8.dp)
                            .align(Alignment.TopStart)
                            .background(Color(0xD9161026), MaruPillShape)
                            .border(BorderStroke(1.dp, MaruAccentPink.copy(alpha = 0.6f)), MaruPillShape)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = item.reason,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaruAccentPink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
fun NotificationMirrorBottomBar(
    liveTrack: LastNotifPollerService.ActiveTrackState?,
    mainFmt: String,
    subFmt: String,
    onClick: (String, String, String) -> Unit
) {
    AnimatedVisibility(
        visible = liveTrack != null && liveTrack.isPlaying,
        enter = slideInVertically(
            animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
            initialOffsetY = { it }
        ) + fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)),
        exit = slideOutVertically(
            animationSpec = spring(dampingRatio = 1.0f, stiffness = Spring.StiffnessMedium),
            targetOffsetY = { it }
        ) + fadeOut(animationSpec = tween(160, easing = FastOutLinearInEasing))
    ) {
        val track = liveTrack ?: return@AnimatedVisibility
        val titleText = remember(track, mainFmt) {
            if (mainFmt.isNotBlank()) {
                LastNotifNotificationManager.applyFormat(mainFmt, track.title, track.artist, track.album, track.pollingMethod)
            } else track.title
        }
        val subText = remember(track, subFmt) {
            if (subFmt.isNotBlank()) {
                LastNotifNotificationManager.applyFormat(subFmt, track.title, track.artist, track.album, track.pollingMethod)
            } else "${track.artist}${if (track.album.isNotBlank()) " • ${track.album}" else ""}"
        }

        Surface(
            color = Color(0xF5161026),
            shape = androidx.compose.ui.graphics.RectangleShape,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .clickable { onClick(track.title, track.artist, track.album) }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(
                    color = MaruAccentPink.copy(alpha = 0.5f),
                    thickness = 1.dp
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaruAccentPink.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        EqualizerVisualizer()
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            titleText,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                            color = MaruTextStrong,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            subText,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaruTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(MaruAccentPink.copy(alpha = 0.2f), MaruPillShape)
                            .border(1.dp, MaruAccentPink.copy(alpha = 0.5f), MaruPillShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            track.pollingMethod.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold),
                            color = MaruAccentPink
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GlassMusicSearchItem(match: ItunesClient.ItunesSongMatch, onClick: () -> Unit) {
    GlassCard(
        border = MaruGlassBorderSoft,
        modifier = Modifier
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
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaruGlassSubtleBg)
            ) {
                if (!match.artworkUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = match.artworkUrl,
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
                        modifier = Modifier.size(20.dp).align(Alignment.Center)
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(match.trackName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = MaruTextStrong, maxLines = 1)
                Text(
                    "${match.artistName}${if (match.collectionName.isNotEmpty()) " • ${match.collectionName}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaruTextMuted,
                    maxLines = 1
                )
            }

            Icon(Icons.Default.ChevronRight, null, tint = MaruAccentPink, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun GlassProfileResultCard(
    profile: LastNotifApiClient.UserProfile,
    recentTracks: List<LastNotifApiClient.ScrobbleItem>,
    onOpen: () -> Unit
) {
    GlassCard(
        border = MaruAccentPink.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaruGlassSubtleBg)
                        .border(1.5.dp, MaruAccentPink, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (!profile.avatarUrl.isNullOrBlank()) {
                        AsyncImage(model = profile.avatarUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Icon(Icons.Default.Person, null, tint = MaruAccentPink, modifier = Modifier.size(24.dp))
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.username, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = MaruTextStrong)
                    Text("%,d scrobbles".format(profile.playcount), style = MaterialTheme.typography.labelSmall, color = MaruAccentPink)
                }

                Icon(Icons.Default.ChevronRight, null, tint = MaruAccentPink)
            }

            if (recentTracks.isNotEmpty()) {
                HorizontalDivider(color = MaruGlassBorderSoft)
                Text("RECENT ACTIVITY", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = MaruTextMuted)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    recentTracks.take(2).forEach { t ->
                        Text("• ${t.title} - ${t.artist}", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = MaruTextStrong, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun AutoArtworkImage(
    title: String,
    artist: String,
    initialUrl: String?,
    modifier: Modifier = Modifier
) {
    var resolvedUrl by remember(title, artist) { mutableStateOf(initialUrl) }

    LaunchedEffect(title, artist) {
        if (resolvedUrl.isNullOrBlank()) {
            withContext(Dispatchers.IO) {
                val match = ItunesClient.searchSong(title, artist)
                if (match?.artworkUrl != null) {
                    resolvedUrl = match.artworkUrl
                }
            }
        }
    }

    if (!resolvedUrl.isNullOrBlank()) {
        AsyncImage(
            model = resolvedUrl,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            error = painterResource(id = R.drawable.ic_maru_heart),
            placeholder = painterResource(id = R.drawable.ic_maru_heart)
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
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
                        val isChecked = currentSelection.contains(app.packageName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentSelection = if (isChecked) currentSelection - app.packageName else currentSelection + app.packageName
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (app.icon != null) {
                                Image(bitmap = app.icon, contentDescription = null, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                            } else {
                                Icon(Icons.Default.Apps, null, tint = MaruTextMuted, modifier = Modifier.size(36.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.appName, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaruTextStrong, maxLines = 1)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaruTextMuted, maxLines = 1)
                            }
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    currentSelection = if (checked) currentSelection + app.packageName else currentSelection - app.packageName
                                },
                                colors = CheckboxDefaults.colors(checkedColor = MaruAccentPink, checkmarkColor = Color.White)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAppsSelected(currentSelection) }) {
                Text("DONE", color = MaruAccentPink, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = MaruTextMuted) }
        }
    )
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
                    "MAudio needs Notification Access to detect current media playback from Spotify, Apple Music, YouTube Music, and other music players on your device.",
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
// 11. HELPER CLASSES FOR INSTALLED APPS
// -------------------------------------------------------------------------------------------------
data class AppInfo(
    val packageName: String,
    val appName: String,
    val isMedia: Boolean,
    val icon: androidx.compose.ui.graphics.ImageBitmap?
)

object AppUtils {
    fun getInstalledMediaApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val mediaPackages = setOf(
            "com.spotify.music",
            "com.apple.android.music",
            "com.google.android.apps.youtube.music",
            "com.aspiro.tidal",
            "com.amazon.mp3",
            "deezer.android.app",
            "com.soundcloud.android",
            "com.netease.cloudmusic",
            "com.tencent.qqmusic",
            "com.pandora.android",
            "org.videolan.vlc",
            "com.foobar2000.foobar2000",
            "com.maxmpz.audioplayer",
            "gonemad.gmmp"
        )

        return resolveInfos.map { resolveInfo ->
            val pkg = resolveInfo.activityInfo.packageName
            val name = resolveInfo.loadLabel(pm).toString()
            val drawable = try {
                resolveInfo.loadIcon(pm)
            } catch (_: Exception) {
                null
            }
            val bitmap = drawable?.let { d ->
                try {
                    val w = d.intrinsicWidth.coerceAtLeast(1)
                    val h = d.intrinsicHeight.coerceAtLeast(1)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(b)
                    d.setBounds(0, 0, canvas.width, canvas.height)
                    d.draw(canvas)
                    b.asImageBitmap()
                } catch (_: Exception) {
                    null
                }
            }
            val isMedia = mediaPackages.contains(pkg) || name.contains("music", ignoreCase = true) || name.contains("player", ignoreCase = true) || name.contains("radio", ignoreCase = true)
            AppInfo(pkg, name, isMedia, bitmap)
        }.sortedWith(compareByDescending<AppInfo> { it.isMedia }.thenBy { it.appName })
    }
}
