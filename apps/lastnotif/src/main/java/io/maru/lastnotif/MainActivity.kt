package io.maru.lastnotif

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LastPage
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

enum class NavigationScreen(val title: String, val subtitle: String, val icon: ImageVector) {
    COMMON("Common Settings", "Layout & Sync Alerts", Icons.Default.Tune),
    SCROBBLING("Last.fm Scrobbler", "Accounts & Filters", Icons.Default.MusicNote),
    DISCOVERY("Music Discovery", "Personalized & Apple Music", Icons.Default.AutoAwesome),
    RECEIVER("Remote Receiver", "Cross-device Sync", Icons.Default.Wifi),
    LOCAL("Local Monitor", "Media Controller", Icons.Default.Smartphone)
}

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
    val lyricsEnabled by prefs.lyricsEnabled.collectAsStateWithLifecycle(initialValue = false)
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
        lyricsEnabled = lyricsEnabled,
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
        onLyricsToggle = { scope.launch { prefs.setLyricsEnabled(it) } },
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
    lyricsEnabled: Boolean,
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
    onLyricsToggle: (Boolean) -> Unit,
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
    var previousScreen by remember { mutableStateOf(NavigationScreen.COMMON) }
    val allApps = rememberAppList(context)
    var showManualCredsDialog by remember { mutableStateOf(false) }

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
        if (selectedScreen == NavigationScreen.DISCOVERY) {
            refreshRecommendations(selectedCategory, reset = true)
        }
    }

    var hasNotifPerm by remember {
        mutableStateOf(LastNotifMediaMonitor.hasPostNotificationsPermission(context))
    }
    var hasMediaAccess by remember {
        mutableStateOf(LastNotifMediaMonitor.isNotificationAccessGranted(context))
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotifPerm = granted
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPerm) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    // Cached background gradients
    val topBarGradient = remember {
        Brush.verticalGradient(listOf(MaruGlassPanelBg, MaruCosmicTop.copy(alpha = 0.92f)))
    }
    val backgroundGradient = remember {
        Brush.radialGradient(
            colors = listOf(MaruCosmicTop, MaruCosmicMid, MaruCosmicBot),
            radius = 1800f
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            GlassNavigationDrawer(
                currentScreen = selectedScreen,
                username = lastfmUsername,
                serviceRunning = serviceRunning,
                onSelectScreen = { screen ->
                    previousScreen = selectedScreen
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
                                    "LAST NOTIF",
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

                    // --- HERO LIVE NOTIFICATION PREVIEW ---
                    Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 10.dp)) {
                        NotificationPreviewBanner(
                            liveTrack = liveTrack,
                            lastAlertTitle = lastAlertTitle,
                            lastAlertSub = lastAlertSub,
                            lastAlertSource = lastAlertSource,
                            mainFmt = mainFmt,
                            subFmt = subFmt,
                            lyricsEnabled = lyricsEnabled
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
            ) {
                Crossfade(
                    targetState = selectedScreen,
                    animationSpec = tween(150),
                    label = "ScreenCrossfade",
                    modifier = Modifier.padding(innerPadding)
                ) { targetScreen ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // --- Permission Warning Chips ---
                        AnimatedVisibility(
                            visible = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPerm) || !hasMediaAccess,
                            enter = expandVertically(spring()) + fadeIn(),
                            exit = shrinkVertically(spring()) + fadeOut()
                        ) {
                            GlassCard(
                                border = MaruDanger.copy(alpha = 0.5f),
                                glowColor = MaruDanger.copy(alpha = 0.12f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Warning, null, tint = MaruDanger, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "PERMISSIONS REQUIRED",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaruDanger,
                                        fontWeight = FontWeight.Bold
                                    )

                                    Spacer(Modifier.weight(1f))

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPerm) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .background(MaruAccentYellow.copy(alpha = 0.15f), MaruPillShape)
                                                .border(BorderStroke(1.dp, MaruAccentYellow.copy(alpha = 0.5f)), MaruPillShape)
                                                .clickable { notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("NOTIF PERM", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaruAccentYellow, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                    }

                                    if (!hasMediaAccess) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .background(MaruDanger.copy(alpha = 0.15f), MaruPillShape)
                                                .border(BorderStroke(1.dp, MaruDanger.copy(alpha = 0.5f)), MaruPillShape)
                                                .clickable {
                                                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                                    hasMediaAccess = LastNotifMediaMonitor.isNotificationAccessGranted(context)
                                                }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text("MEDIA ACCESS", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaruDanger, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        when (targetScreen) {
                            NavigationScreen.COMMON -> {
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
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            platforms.forEach { platform ->
                                                val isSelected = preferredPlatform == platform
                                                val displayName = when (platform) {
                                                    "Apple Music" -> "Apple"
                                                    "YouTube Music" -> "YT Music"
                                                    else -> platform
                                                }
                                                Surface(
                                                    onClick = { onPreferredPlatformChange(platform) },
                                                    shape = MaruPillShape,
                                                    color = if (isSelected) MaruAccentPink.copy(alpha = 0.22f) else MaruGlassSubtleBg,
                                                    border = BorderStroke(1.dp, if (isSelected) MaruAccentPink else MaruGlassBorderSoft),
                                                    modifier = Modifier.weight(1f).height(36.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        Text(
                                                            displayName,
                                                            style = MaterialTheme.typography.labelSmall.copy(
                                                                fontSize = 10.sp,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                            ),
                                                            color = if (isSelected) MaruAccentPink else MaruTextMuted
                                                        )
                                                    }
                                                }
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

                            NavigationScreen.SCROBBLING -> {
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
                                                    onClick = { showManualCredsDialog = true },
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

                            NavigationScreen.DISCOVERY -> {
                                // Category Tab Bar: All Recommendations | Artists | Albums | Tracks
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
                                    LastFmRecommendationsEngine.RecCategory.values().forEach { cat ->
                                        val selected = selectedCategory == cat
                                        Tab(
                                            selected = selected,
                                            onClick = {
                                                if (selectedCategory != cat) {
                                                    selectedCategory = cat
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

                                Spacer(Modifier.height(4.dp))

                                // Header row with Category Title and Actions
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
                                            "RECOMMENDED FOR YOU",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaruAccentPink,
                                                letterSpacing = 0.8.sp
                                            )
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        IconButton(
                                            onClick = { isGridView = !isGridView },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                if (isGridView) Icons.Default.List else Icons.Default.GridView,
                                                contentDescription = "Toggle View",
                                                tint = MaruAccentBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = { refreshRecommendations(selectedCategory, reset = true) },
                                            enabled = !isRecommendationsLoading && !isLoadingMore,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = "Refresh",
                                                tint = if (isRecommendationsLoading) MaruTextMuted else MaruAccentPink,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                if (isRecommendationsLoading) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(240.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(36.dp))
                                            Text(
                                                "Fetching recommendations for $lastfmUsername...",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaruTextMuted
                                            )
                                        }
                                    }
                                } else if (recommendations.isEmpty()) {
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
                                                "Play some songs or tap below to fetch personalized recommendations based on your listening taste!",
                                                style = MaterialTheme.typography.bodySmall.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                                                color = MaruTextMuted
                                            )
                                            Spacer(Modifier.height(4.dp))
                                            GlassButton(onClick = { refreshRecommendations(selectedCategory, reset = true) }) {
                                                Text("FETCH RECOMMENDATIONS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaruAccentPink)
                                            }
                                        }
                                    }
                                } else {
                                    if (isGridView) {
                                        val chunked = recommendations.chunked(2)
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            chunked.forEach { rowItems ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    rowItems.forEach { item ->
                                                        GlassRecommendationGridCard(
                                                            item = item,
                                                            onClick = {
                                                                ItunesClient.openInPreferredPlayer(
                                                                    context,
                                                                    preferredPlatform,
                                                                    item.title,
                                                                    item.artist,
                                                                    item.itunesMatch?.appleMusicUrl
                                                                )
                                                            },
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    }
                                                    if (rowItems.size == 1) {
                                                        Spacer(Modifier.weight(1f))
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            recommendations.forEach { item ->
                                                GlassRecommendationCard(
                                                    item = item,
                                                    onClick = {
                                                        ItunesClient.openInPreferredPlayer(
                                                            context,
                                                            preferredPlatform,
                                                            item.title,
                                                            item.artist,
                                                            item.itunesMatch?.appleMusicUrl
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    // Infinite scroll / Load more button
                                    Spacer(Modifier.height(4.dp))
                                    GlassButton(
                                        onClick = { refreshRecommendations(selectedCategory, reset = false) },
                                        borderColor = MaruAccentPink.copy(alpha = 0.4f),
                                        background = MaruGlassSubtleBg
                                    ) {
                                        if (isLoadingMore) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                CircularProgressIndicator(color = MaruAccentPink, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                Text("DISCOVERING MORE...", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruTextMuted)
                                            }
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(Icons.Default.ExpandMore, null, tint = MaruAccentPink, modifier = Modifier.size(16.dp))
                                                Text("LOAD MORE RECOMMENDATIONS", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = MaruAccentPink)
                                            }
                                        }
                                    }
                                }
                            }

                            NavigationScreen.RECEIVER -> {
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

                                        GlassSectionHeader("RECEIVER OPTIONS", Icons.Default.Sync)
                                        GlassCard {
                                            Column {
                                                GlassFeatureRow(label = "Synced Lyrics", checked = lyricsEnabled, onCheckedChange = onLyricsToggle)
                                            }
                                        }

                                        Text(
                                            "Receiver mode polls the specified Last.fm user's current track and pushes updates to your notification shade & connected smart band.",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = MaruTextMuted,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                    }
                                }
                            }

                            NavigationScreen.LOCAL -> {
                                GlassMasterTile(
                                    title = "LOCAL MONITOR",
                                    description = "Watch local media sessions directly from device media controllers.",
                                    isEnabled = localEnabled,
                                    onToggle = onToggleLocal
                                )

                                AnimatedVisibility(
                                    visible = localEnabled,
                                    enter = expandVertically(spring()) + fadeIn(),
                                    exit = shrinkVertically(spring()) + fadeOut()
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        GlassSectionHeader("WATCHER APPS FILTER", Icons.Default.Apps)
                                        GlassAppSelectionTile(allApps, localApps, onLocalAppsChange)

                                        Text(
                                            "Filter which media player applications LastNotif should listen to for playback notifications and scrobbling.",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = MaruTextMuted,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    if (showManualCredsDialog) {
        ManualCredentialsDialog(
            currentUsername = lastfmUsername,
            currentSessionKey = sessionKey,
            onDismiss = { showManualCredsDialog = false },
            onSave = { u, k ->
                onUsernameChange(u)
                onSessionKeyChange(k)
                showManualCredsDialog = false
            }
        )
    }
}

@Composable
fun NotificationPreviewBanner(
    liveTrack: LastNotifPollerService.ActiveTrackState?,
    lastAlertTitle: String,
    lastAlertSub: String,
    lastAlertSource: String,
    mainFmt: String,
    subFmt: String,
    lyricsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val displayTitle: String
    val displaySub: String
    val displayBadge: String
    val isPlaying: Boolean
    val lyric: String

    when {
        liveTrack != null && liveTrack.pollingMethod == "Local" && (liveTrack.title.isNotEmpty() || liveTrack.artist.isNotEmpty()) -> {
            displayTitle = LastNotifNotificationManager.applyFormat(mainFmt, liveTrack.title, liveTrack.artist, liveTrack.album, "Local")
            displaySub = LastNotifNotificationManager.applyFormat(subFmt, liveTrack.title, liveTrack.artist, liveTrack.album, "Local")
            displayBadge = "LOCAL PLAYBACK"
            isPlaying = liveTrack.isPlaying
            lyric = if (lyricsEnabled && liveTrack.lyricLine.isNotEmpty()) liveTrack.lyricLine else ""
        }
        liveTrack != null && liveTrack.pollingMethod == "Receiver" && (liveTrack.title.isNotEmpty() || liveTrack.artist.isNotEmpty()) -> {
            displayTitle = LastNotifNotificationManager.applyFormat(mainFmt, liveTrack.title, liveTrack.artist, liveTrack.album, "Receiver")
            displaySub = LastNotifNotificationManager.applyFormat(subFmt, liveTrack.title, liveTrack.artist, liveTrack.album, "Receiver")
            displayBadge = "LAST.FM SCROBBLING"
            isPlaying = true
            lyric = if (lyricsEnabled && liveTrack.lyricLine.isNotEmpty()) liveTrack.lyricLine else ""
        }
        lastAlertTitle.isNotEmpty() || lastAlertSub.isNotEmpty() -> {
            displayTitle = lastAlertTitle
            displaySub = lastAlertSub
            displayBadge = "LAST ALERT • ${lastAlertSource.uppercase()}"
            isPlaying = false
            lyric = ""
        }
        else -> {
            val sampleTitle = "Tell Your World"
            val sampleArtist = "kz (livetune)"
            val sampleAlbum = "Tell Your World EP"
            displayTitle = LastNotifNotificationManager.applyFormat(mainFmt, sampleTitle, sampleArtist, sampleAlbum, "Preview")
            displaySub = LastNotifNotificationManager.applyFormat(subFmt, sampleTitle, sampleArtist, sampleAlbum, "Preview")
            displayBadge = "LIVE MIRROR PREVIEW"
            isPlaying = false
            lyric = if (lyricsEnabled) "君に伝えたい音が 一粒の光になって" else ""
        }
    }

    GlassCard(
        modifier = modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
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

            AnimatedVisibility(
                visible = lyric.isNotEmpty(),
                enter = expandVertically(spring()) + fadeIn(),
                exit = shrinkVertically(spring()) + fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .background(MaruGlassSubtleBg, MaruInputShape)
                        .border(BorderStroke(1.dp, MaruAccentPink.copy(alpha = 0.35f)), MaruInputShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        "♪  $lyric",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
                        color = MaruAccentPink,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Brand & User
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
                        "LAST NOTIF",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaruTextStrong,
                            letterSpacing = 1.2.sp
                        )
                    )
                    Text(
                        if (username.isNotEmpty()) "Logged in as $username" else "Guest Mode",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaruTextMuted
                    )
                }
            }

            HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)

            Text(
                "NAVIGATION",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = MaruAccentPink,
                    letterSpacing = 1.sp,
                    fontSize = 10.sp
                )
            )

            // Screen Items
            NavigationScreen.entries.forEach { screen ->
                val isSelected = currentScreen == screen
                Surface(
                    onClick = { onSelectScreen(screen) },
                    shape = MaruInputShape,
                    color = if (isSelected) MaruAccentPink.copy(alpha = 0.16f) else Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaruAccentPink.copy(alpha = 0.6f) else Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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

            Spacer(Modifier.weight(1f))

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
        }
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

    GlassCard(
        border = MaruGlassBorderSoft,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaruGlassSubtleBg)
            ) {
                if (item.itunesMatch?.artworkUrl != null) {
                    AsyncImage(
                        model = item.itunesMatch.artworkUrl,
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaruAccentPink,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Category badge overlay
                if (categoryBadge != null) {
                    Box(
                        modifier = Modifier
                            .padding(6.dp)
                            .align(Alignment.TopStart)
                            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                            .border(BorderStroke(1.dp, MaruGlassBorderSoft), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        Text(
                            categoryBadge,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                            color = when (item.category) {
                                LastFmRecommendationsEngine.RecCategory.ARTISTS -> MaruAccentBlue
                                LastFmRecommendationsEngine.RecCategory.ALBUMS -> MaruAccentYellow
                                else -> MaruAccentPink
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                color = MaruTextStrong,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )

            Text(
                text = item.artist,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaruTextMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )

            if (item.reason.isNotEmpty()) {
                Text(
                    text = item.reason,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, color = MaruAccentPink),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun GlassRecommendationCard(
    item: LastFmRecommendationsEngine.RecommendedTrackItem,
    onClick: () -> Unit
) {
    val categoryBadge = when (item.category) {
        LastFmRecommendationsEngine.RecCategory.ARTISTS -> "ARTIST"
        LastFmRecommendationsEngine.RecCategory.ALBUMS -> "ALBUM"
        LastFmRecommendationsEngine.RecCategory.TRACKS -> "TRACK"
        else -> null
    }

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
            // Album Artwork with High-Res Coil AsyncImage
            if (item.itunesMatch?.artworkUrl != null) {
                AsyncImage(
                    model = item.itunesMatch.artworkUrl,
                    contentDescription = item.title,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaruGlassSubtleBg),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(MaruGlassSubtleBg, RoundedCornerShape(8.dp))
                        .border(BorderStroke(1.dp, MaruGlassBorderSoft), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, null, tint = MaruAccentPink, modifier = Modifier.size(24.dp))
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold, fontSize = 13.5.sp),
                    color = MaruTextStrong,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    if (item.album.isNotEmpty()) "${item.artist} • ${item.album}" else item.artist,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                    color = MaruTextMuted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (categoryBadge != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .background(
                                    when (item.category) {
                                        LastFmRecommendationsEngine.RecCategory.ARTISTS -> MaruAccentBlue.copy(alpha = 0.18f)
                                        LastFmRecommendationsEngine.RecCategory.ALBUMS -> MaruAccentYellow.copy(alpha = 0.18f)
                                        else -> MaruAccentPink.copy(alpha = 0.18f)
                                    },
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        when (item.category) {
                                            LastFmRecommendationsEngine.RecCategory.ARTISTS -> MaruAccentBlue.copy(alpha = 0.4f)
                                            LastFmRecommendationsEngine.RecCategory.ALBUMS -> MaruAccentYellow.copy(alpha = 0.4f)
                                            else -> MaruAccentPink.copy(alpha = 0.4f)
                                        }
                                    ),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        ) {
                            Text(
                                categoryBadge,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, fontWeight = FontWeight.Bold),
                                color = when (item.category) {
                                    LastFmRecommendationsEngine.RecCategory.ARTISTS -> MaruAccentBlue
                                    LastFmRecommendationsEngine.RecCategory.ALBUMS -> MaruAccentYellow
                                    else -> MaruAccentPink
                                }
                            )
                        }
                        if (item.reason.isNotEmpty()) {
                            Text(
                                item.reason,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, color = MaruAccentPink),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                } else if (item.reason.isNotEmpty()) {
                    Text(
                        item.reason,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, color = MaruAccentPink),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    border: Color = MaruGlassBorderSoft,
    glowColor: Color? = null,
    background: Color = MaruGlassCardBg,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (glowColor != null) Modifier.border(
                    BorderStroke(1.dp, glowColor),
                    MaruCardShape
                ) else Modifier
            ),
        shape = MaruCardShape,
        color = background,
        border = BorderStroke(1.dp, border)
    ) {
        Column(content = content)
    }
}

@Composable
fun GlassMasterTile(title: String, description: String, isEnabled: Boolean, onToggle: (Boolean) -> Unit) {
    GlassCard(
        border = if (isEnabled) MaruAccentGreen.copy(alpha = 0.4f) else MaruGlassBorderSoft,
        glowColor = if (isEnabled) MaruAccentGreen.copy(alpha = 0.15f) else null
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                    color = if (isEnabled) MaruAccentGreen else MaruTextMuted
                )
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaruAccentGreen,
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = MaruGlassBorderSoft
                    )
                )
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaruTextMuted)
        }
    }
}

@Composable
fun GlassAppSelectionTile(allApps: List<AppInfo>, selectedApps: Set<String>, onSelectionChange: (Set<String>) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    GlassCard {
        Row(
            modifier = Modifier.clickable { showDialog = true }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (selectedApps.isEmpty()) "ALL MEDIA APPS (UNFILTERED)" else "${selectedApps.size} APPS FILTERED",
                style = MaterialTheme.typography.bodySmall,
                color = MaruTextStrong
            )
            Icon(Icons.Default.ChevronRight, null, tint = MaruTextMuted)
        }
    }

    if (showDialog) {
        AppPickerDialog(
            allApps = allApps,
            selectedApps = selectedApps,
            onDismiss = { showDialog = false },
            onAppsSelected = { onSelectionChange(it); showDialog = false }
        )
    }
}

@Composable
fun GlassFeatureRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaruTextStrong)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaruAccentPink,
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = MaruGlassBorderSoft
            )
        )
    }
}

@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = MaruAccentBlue)
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall, color = MaruTextMuted.copy(alpha = 0.4f)) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaruInputShape,
            textStyle = MaterialTheme.typography.bodySmall,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MaruTextStrong, unfocusedTextColor = MaruTextStrong,
                focusedContainerColor = MaruGlassSubtleBg, unfocusedContainerColor = MaruGlassSubtleBg,
                focusedBorderColor = MaruAccentPink, unfocusedBorderColor = MaruGlassBorderSoft,
                cursorColor = MaruAccentPink
            ),
            singleLine = true
        )
    }
}

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    borderColor: Color = MaruGlassBorderSoft,
    background: Color = MaruGlassCardBg,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(46.dp),
        shape = MaruInputShape,
        color = background,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun GlassSectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp, start = 4.dp)) {
        Icon(icon, null, Modifier.size(13.dp), tint = MaruAccentBlue)
        Spacer(Modifier.width(8.dp))
        Text(text = title, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, color = MaruAccentBlue, letterSpacing = 1.sp))
    }
}

@Composable
fun ManualCredentialsDialog(
    currentUsername: String,
    currentSessionKey: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var username by remember { mutableStateOf(currentUsername) }
    var sessionKey by remember { mutableStateOf(currentSessionKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaruCardShape,
        containerColor = Color(0xFF140F22),
        title = {
            Text("MANUAL LAST.FM CREDENTIALS", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaruTextStrong)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "You can paste your Last.fm Username and Session Key directly without using the browser redirect flow.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaruTextMuted
                )
                GlassTextField(value = username, onValueChange = { username = it }, placeholder = "Last.fm Username")
                GlassTextField(value = sessionKey, onValueChange = { sessionKey = it }, placeholder = "Session Key (sk)")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(username.trim(), sessionKey.trim()) }) {
                Text("SAVE", color = MaruAccentPink, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = MaruTextMuted) }
        }
    )
}

@Composable
fun AppPickerDialog(allApps: List<AppInfo>, selectedApps: Set<String>, onDismiss: () -> Unit, onAppsSelected: (Set<String>) -> Unit) {
    var currentSelection by remember { mutableStateOf(selectedApps) }
    var searchQuery by remember { mutableStateOf("") }
    var showMediaOnly by remember { mutableStateOf(true) }

    val filteredApps = remember(allApps, searchQuery, showMediaOnly) {
        allApps.filter { app ->
            val matchesMedia = !showMediaOnly || app.isMedia
            val matchesSearch = searchQuery.isBlank() || app.name.contains(searchQuery, ignoreCase = true) || app.packageName.contains(searchQuery, ignoreCase = true)
            matchesMedia && matchesSearch
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaruCardShape,
        containerColor = Color(0xFF140F22),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SELECT MEDIA APPS", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = MaruTextStrong)
                
                GlassTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Search apps..."
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (showMediaOnly) MaruAccentPink.copy(alpha = 0.2f) else MaruGlassSubtleBg, MaruPillShape)
                            .border(BorderStroke(1.dp, if (showMediaOnly) MaruAccentPink else MaruGlassBorderSoft), MaruPillShape)
                            .clickable { showMediaOnly = true }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("MEDIA ONLY", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), fontWeight = FontWeight.Bold, color = if (showMediaOnly) MaruAccentPink else MaruTextMuted)
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
                            modifier = Modifier.fillMaxWidth().clickable {
                                currentSelection = if (currentSelection.contains(app.packageName)) currentSelection - app.packageName else currentSelection + app.packageName
                            }.padding(vertical = 8.dp),
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

            // Discover media browser & media session services
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
                "com.samsung.android.app.notes", "com.google.android.keep",
                "barum.life.wirelessearbudslatencytester", "com.serg.chuprin.tageditor"
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
            receiverUsername = "", localApps = emptySet(), notifySongUpdate = true, lyricsEnabled = true,
            mainFmt = "{song_name}", subFmt = "{artist}", intervalEnabled = true, intervalMinutes = 5,
            preferredPlatform = "Apple Music",
            lastAlertTitle = "Test Song", lastAlertSub = "Test Artist", lastAlertSource = "Local",
            serviceRunning = true, liveTrack = LastNotifPollerService.ActiveTrackState("Test Song", "Test Artist", "Test Album", true, "Sample lyric line", "Local"),
            onToggleScrobble = {}, onToggleReceiver = {}, onToggleLocal = {},
            onUsernameChange = {}, onSessionKeyChange = {}, onScrobbleAppsChange = {}, onScrobblePercentageChange = {},
            onReceiverUsernameChange = {}, onLocalAppsChange = {}, onToggleSongUpdate = {}, onLyricsToggle = {},
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
