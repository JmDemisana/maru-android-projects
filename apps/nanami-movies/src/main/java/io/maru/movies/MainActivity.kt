package io.maru.movies

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal val MoviesBackground = NetflixBlack
internal val MoviesAccent = NetflixRed

internal enum class MoviesNavTab(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    LIST("My List", Icons.Default.BookmarkBorder),
    SEARCH("Search", Icons.Default.Search),
    ACCOUNT("Account", Icons.Default.PersonOutline),
}

private sealed interface MoviesRoute {
    data class Tab(val tab: MoviesNavTab) : MoviesRoute
    data class Detail(val title: MovieTitle, val id: Int) : MoviesRoute

    val stateKey: String
        get() = when (this) {
            is Tab -> tab.name
            is Detail -> "detail_$id"
        }

    val depth: Int
        get() = when (this) {
            is Tab -> 0
            is Detail -> 1 + id
        }
}

internal fun openCinemaCustomTab(context: Context, url: String) {
    val uri = Uri.parse(url)
    runCatching {
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(android.graphics.Color.BLACK)
            .setNavigationBarColor(android.graphics.Color.BLACK)
            .build()
        val intent = CustomTabsIntent.Builder()
            .setDefaultColorSchemeParams(colors)
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
        intent.launchUrl(context, uri)
    }.onFailure {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

class MainActivity : ComponentActivity() {
    internal val incomingAuthUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingAuthUri.value = intent?.data
        if (!isInMultiWindowMode && resources.configuration.smallestScreenWidthDp in 1..599) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val catalog = MoviesCatalog()
        val library = MovieLibrary(applicationContext)
        val simkl = Simkl(applicationContext)
        setContent {
            MaterialTheme(colorScheme = NanamiMoviesColorScheme) {
                MoviesApp(this, catalog, library, simkl)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != null) {
            incomingAuthUri.value = intent.data
        }
    }
}

private data class PlaybackRequest(
    val title: MovieTitle,
    val episode: MovieEpisode?,
    val position: Long,
    val offlineFile: File?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoviesApp(
    activity: MainActivity,
    catalog: MoviesCatalog,
    library: MovieLibrary,
    simkl: Simkl,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val routeStateHolder = rememberSaveableStateHolder()
    val vault = MoviesDownloadVault
    val downloads by vault.items.collectAsState()
    val authCallbackUri by activity.incomingAuthUri.collectAsState()

    var selectedTab by rememberSaveable { mutableStateOf(MoviesNavTab.HOME) }
    var detailStack by remember { mutableStateOf<List<MoviesRoute.Detail>>(emptyList()) }
    var nextRouteId by rememberSaveable { mutableIntStateOf(0) }
    val currentRoute: MoviesRoute = detailStack.lastOrNull() ?: MoviesRoute.Tab(selectedTab)

    var playback by remember { mutableStateOf<PlaybackRequest?>(null) }
    var lastPlayback by remember { mutableStateOf<PlaybackRequest?>(null) }
    if (playback != null) lastPlayback = playback

    var showSimklAuthDialog by remember { mutableStateOf(false) }
    var completingSimklCallback by remember { mutableStateOf(false) }
    var showTopAccountMenu by remember { mutableStateOf(false) }

    var hideMature by remember { mutableStateOf(MoviesPreferences.hideMature(activity)) }
    var doNotTrackMature by remember { mutableStateOf(MoviesPreferences.doNotTrackMature(activity)) }
    var reportProgress by remember { mutableStateOf(MoviesPreferences.reportProgress(activity)) }
    var rememberPosition by remember { mutableStateOf(MoviesPreferences.rememberPosition(activity)) }
    var preferredQuality by remember { mutableStateOf(MoviesPreferences.preferredQuality(activity)) }

    var saved by remember { mutableStateOf(library.saved()) }
    var savedOrder by remember { mutableStateOf(library.savedOrder()) }
    var recent by remember { mutableStateOf(library.recent()) }
    var history by remember { mutableStateOf(library.history()) }
    var remote by remember { mutableStateOf(emptySet<TitleRef>()) }
    var remoteHistory by remember { mutableStateOf(emptyList<WatchProgress>()) }
    var ratings by remember { mutableStateOf(library.ratings()) }
    var tracking by remember { mutableStateOf(library.tracking()) }

    var accountKey by remember { mutableStateOf(simkl.connectionKey.takeIf { simkl.isConnected }) }
    var activeSimklAccount by remember(accountKey) { mutableStateOf(simkl.activeAccount()) }
    var savedSimklAccounts by remember(accountKey) { mutableStateOf(simkl.accounts()) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var pendingSyncCount by remember { mutableIntStateOf(0) }
    var isSyncingQueue by remember { mutableStateOf(false) }

    val syncSignal = remember { Channel<Unit>(Channel.CONFLATED) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var prompted by rememberSaveable { mutableStateOf(false) }

    fun refreshAccountModels() {
        accountKey = simkl.connectionKey.takeIf { simkl.isConnected }
        activeSimklAccount = simkl.activeAccount()
        savedSimklAccounts = simkl.accounts()
    }

    fun computePendingCount(key: String?): Int {
        if (key == null) return 0
        return library.pendingList(key).size +
            library.pendingWatch(key).size +
            library.pendingRatings(key).size +
            library.pendingStatuses(key).size
    }

    fun refreshProgress() {
        recent = library.recent()
        history = library.history()
        pendingSyncCount = computePendingCount(accountKey)
    }

    fun openDetail(title: MovieTitle) {
        if (hideMature && title.isMature) return
        if (detailStack.lastOrNull()?.title?.ref == title.ref) return
        detailStack = detailStack + MoviesRoute.Detail(title, nextRouteId++)
    }

    fun closeDetail(route: MoviesRoute.Detail) {
        if (detailStack.lastOrNull() == route) {
            routeStateHolder.removeState(route.stateKey)
            detailStack = detailStack.dropLast(1)
        }
    }

    fun openSimklWebAuth(@Suppress("UNUSED_PARAMETER") clearCookies: Boolean = false) {
        showSimklAuthDialog = true
        scope.launch {
            runCatching {
                val session = simkl.createPkceSession(forAppDeepLink = true)
                openCinemaCustomTab(activity, session.authorizeUrl)
            }
        }
    }

    // Intercept returning `nanamimovies://simkl-auth?code=...&state=...` callback automatically!
    LaunchedEffect(authCallbackUri) {
        val uri = authCallbackUri ?: return@LaunchedEffect
        if (!simkl.isAppAuthCallback(uri)) return@LaunchedEffect
        activity.incomingAuthUri.value = null
        completingSimklCallback = true
        try {
            val account = simkl.completeAppCallback(uri)
            refreshAccountModels()
            showSimklAuthDialog = false
            syncSignal.trySend(Unit)
            Toast.makeText(activity, "Connected to SIMKL as ${account.label}", Toast.LENGTH_SHORT).show()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(activity, e.message ?: "Couldn't complete SIMKL sign-in.", Toast.LENGTH_LONG).show()
        } finally {
            completingSimklCallback = false
        }
    }

    LaunchedEffect(vault) { vault.load(activity.applicationContext) }
    LaunchedEffect(playback) { if (playback == null) refreshProgress() }

    LaunchedEffect(hideMature, detailStack) {
        if (hideMature) {
            val hidden = detailStack.filter { it.title.isMature }
            if (hidden.isNotEmpty()) {
                hidden.forEach { routeStateHolder.removeState(it.stateKey) }
                detailStack = detailStack - hidden.toSet()
            }
        }
    }

    DisposableEffect(activity) {
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) syncSignal.trySend(Unit)
        }
        activity.lifecycle.addObserver(watcher)
        onDispose { activity.lifecycle.removeObserver(watcher) }
    }

    LaunchedEffect(accountKey) {
        remote = emptySet()
        remoteHistory = emptyList()
        syncMessage = null
        activeSimklAccount = simkl.activeAccount()
        savedSimklAccounts = simkl.accounts()
        val key = accountKey ?: run {
            ratings = library.ratings()
            tracking = library.tracking()
            pendingSyncCount = 0
            isSyncingQueue = false
            return@LaunchedEffect
        }
        if (activeSimklAccount?.username.isNullOrBlank()) {
            runCatching { simkl.refreshViewerProfile() }.onSuccess {
                activeSimklAccount = simkl.activeAccount()
                savedSimklAccounts = simkl.accounts()
            }
        }
        ratings = library.pendingRatings(key).associate { it.title to it.score }
        tracking = library.pendingStatuses(key).associate { it.title to it.status }
        pendingSyncCount = computePendingCount(key)

        while (isActive) {
            var retry = false
            try {
                isSyncingQueue = true
                val list = library.pendingList(key)
                val watch = library.pendingWatch(key)
                syncMessage = "Syncing with SIMKL…"
                for (pending in list) {
                    val ref = pending.title
                    try { simkl.setWatchlisted(ref.imdbId, ref.kind == MovieKind.SERIES, pending.listed, key) }
                    catch (_: SimklTitleUnavailable) { }
                    library.finishList(key, pending)
                }
                for (pending in watch) {
                    val entry = pending.progress
                    try { simkl.markWatched(entry.title.imdbId, entry.season, entry.episode, pending.watched, key) }
                    catch (_: SimklTitleUnavailable) { }
                    library.finishWatch(key, pending)
                }
                for (rating in library.pendingRatings(key)) {
                    try { simkl.rate(rating.title, rating.score, key) }
                    catch (_: SimklTitleUnavailable) { }
                    library.finishRating(key, rating)
                }
                for (status in library.pendingStatuses(key)) {
                    try { simkl.track(status.title, status.status, key) }
                    catch (_: SimklTitleUnavailable) { }
                    library.finishStatus(key, status)
                }
                val snapshot = simkl.librarySnapshot()
                val listFromSimkl = snapshot.planned.toMutableSet()
                for (pending in library.pendingList(key)) {
                    if (pending.listed) listFromSimkl.add(pending.title) else listFromSimkl.remove(pending.title)
                }
                val pendingStatuses = library.pendingStatuses(key)
                for (pending in pendingStatuses) {
                    if (pending.status == MovieWatchStatus.PLAN) listFromSimkl.add(pending.title) else listFromSimkl.remove(pending.title)
                }
                remote = listFromSimkl
                val pendingDeletes = library.pendingWatch(key).filterNot { it.watched }.mapTo(mutableSetOf()) { it.progress.key }
                remoteHistory = snapshot.completed.filterNot { it.key in pendingDeletes }
                ratings = snapshot.ratings + library.pendingRatings(key).associate { it.title to it.score }
                tracking = snapshot.statuses + pendingStatuses.associate { it.title to it.status }
                pendingSyncCount = computePendingCount(key)
                syncMessage = "My List & watch history synced"
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                refreshAccountModels()
                val pending = computePendingCount(key)
                pendingSyncCount = pending
                syncMessage = if (pending > 0) "$pending changes queued to sync when online"
                else "Couldn't refresh SIMKL right now"
                retry = pending > 0
            } finally {
                isSyncingQueue = false
            }
            if (retry) withTimeoutOrNull(30_000) { syncSignal.receive() } else syncSignal.receive()
        }
    }

    fun startPlayback(title: MovieTitle, episode: MovieEpisode?, file: File? = null) {
        val position = if (rememberPosition) {
            library.progress(title.ref, episode)?.takeIf { it.canResume }?.position ?: 0L
        } else 0L
        playback = PlaybackRequest(title, episode, position, file ?: vault.completedFile(activity, title, episode))
    }

    fun toggleSaved(title: MovieTitle) {
        if (doNotTrackMature && title.isMature) return
        val ref = title.ref
        val adding = ref !in saved && ref !in remote
        saved = if (adding) saved + ref else saved - ref
        library.save(saved)
        savedOrder = library.savedOrder()
        accountKey?.let { key ->
            if (!adding) remote = remote - ref
            library.queueList(key, ref, adding)
            pendingSyncCount = computePendingCount(key)
            syncSignal.trySend(Unit)
        }
        if (accountKey == null && (adding && ref !in tracking || !adding && tracking[ref] == MovieWatchStatus.PLAN)) {
            val status = MovieWatchStatus.PLAN.takeIf { adding }
            library.track(ref, status, null)
            tracking = library.tracking()
        }
    }

    fun markWatched(title: MovieTitle, episode: MovieEpisode?, watched: Boolean) {
        if (doNotTrackMature && title.isMature) return
        val batch = if (title.kind == MovieKind.SERIES && episode == null) title.episodes else listOf(episode)
        for (entry in batch) {
            val progress = library.markWatched(title.ref, entry, watched)
            if (!watched) remoteHistory = remoteHistory.filterNot { it.key == progress.key }
            accountKey?.let { key ->
                if (library.queueWatch(key, progress, watched)) {
                    pendingSyncCount = computePendingCount(key)
                    syncSignal.trySend(Unit)
                }
            }
        }
        refreshProgress()
    }

    fun rate(title: MovieTitle, score: Int) {
        if (doNotTrackMature && title.isMature) return
        library.rate(title.ref, score, accountKey)
        ratings = ratings + (title.ref to score)
        if (accountKey != null) {
            pendingSyncCount = computePendingCount(accountKey)
            syncSignal.trySend(Unit)
        }
    }

    fun track(title: MovieTitle, status: MovieWatchStatus) {
        if (doNotTrackMature && title.isMature) return
        val key = accountKey
        val ref = title.ref
        library.track(ref, status, key)
        tracking = tracking + (ref to status)
        if (key == null) {
            saved = if (status == MovieWatchStatus.PLAN) saved + ref else saved - ref
            library.save(saved)
            savedOrder = library.savedOrder()
        } else {
            remote = if (status == MovieWatchStatus.PLAN) remote + ref else remote - ref
            pendingSyncCount = computePendingCount(key)
            syncSignal.trySend(Unit)
        }
    }

    fun queueDownloads(title: MovieTitle, episodes: List<MovieEpisode?>) {
        if (Build.VERSION.SDK_INT >= 33 && !prompted && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            prompted = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        scope.launch {
            try {
                val count = vault.enqueue(activity, title, episodes)
                val msg = when (count) {
                    0 -> "Already in Downloads."
                    1 -> "Added to Downloads."
                    else -> "$count episodes added to Downloads."
                }
                snackbar.showSnackbar(msg)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                snackbar.showSnackbar(error.message ?: "Couldn't start the download.")
            }
        }
    }

    BackHandler(playback != null || detailStack.isNotEmpty() || selectedTab != MoviesNavTab.HOME) {
        when {
            playback != null -> playback = null
            detailStack.isNotEmpty() -> closeDetail(detailStack.last())
            else -> selectedTab = MoviesNavTab.HOME
        }
    }

    val showNavigationChrome = currentRoute is MoviesRoute.Tab && playback == null
    val combinedHistory = remember(history, remoteHistory) {
        (history + remoteHistory).distinctBy(WatchProgress::key).sortedByDescending(WatchProgress::updated)
    }

    Box(modifier = Modifier.fillMaxSize().background(NetflixBlack)) {
        Scaffold(
            containerColor = NetflixBlack,
            snackbarHost = { SnackbarHost(snackbar) },
            contentWindowInsets = if (showNavigationChrome) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
            topBar = {
                if (showNavigationChrome) {
                    TopAppBar(
                        title = {
                            if (selectedTab == MoviesNavTab.HOME) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_nanami_movies_logo),
                                        contentDescription = "Nanami Movies",
                                        modifier = Modifier.size(34.dp),
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "nanami",
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 19.sp,
                                            color = NetflixRed,
                                        )
                                        Text(
                                            text = "movies",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 19.sp,
                                            color = Color.White,
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = selectedTab.title,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 21.sp,
                                    color = Color.White,
                                )
                            }
                        },
                        actions = {
                            if (selectedTab == MoviesNavTab.HOME) {
                                IconButton(onClick = { selectedTab = MoviesNavTab.SEARCH }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                                }
                            }

                            if (savedSimklAccounts.isNotEmpty()) {
                                Box {
                                    Surface(
                                        onClick = { showTopAccountMenu = true },
                                        shape = RoundedCornerShape(18.dp),
                                        color = NetflixCardBg,
                                        border = BorderStroke(1.dp, if (accountKey != null) NetflixRed else NetflixBorder),
                                        modifier = Modifier.padding(end = 8.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (activeSimklAccount?.avatarUrl != null && accountKey != null) {
                                                CrossfadeArtwork(
                                                    model = activeSimklAccount?.avatarUrl,
                                                    contentDescription = activeSimklAccount?.label,
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .clip(CircleShape),
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.PersonOutline,
                                                    contentDescription = null,
                                                    tint = NetflixRed,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                            Text(
                                                text = if (accountKey != null) {
                                                    activeSimklAccount?.username ?: activeSimklAccount?.label?.removePrefix("@") ?: "SIMKL"
                                                } else "Guest",
                                                color = Color.White,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Bold,
                                            )
                                            if (savedSimklAccounts.size > 1) {
                                                Icon(
                                                    Icons.Default.ArrowDropDown,
                                                    contentDescription = null,
                                                    tint = NetflixTextMuted,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    }

                                    DropdownMenu(
                                        expanded = showTopAccountMenu,
                                        onDismissRequest = { showTopAccountMenu = false },
                                        containerColor = NetflixSurface,
                                    ) {
                                        savedSimklAccounts.forEach { acc ->
                                            val isCurrent = accountKey == acc.id
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = if (isCurrent) "${acc.label} (Active)" else acc.label,
                                                        color = if (isCurrent) NetflixRed else Color.White,
                                                        fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                                    )
                                                },
                                                onClick = {
                                                    showTopAccountMenu = false
                                                    if (!isCurrent) {
                                                        scope.launch {
                                                            runCatching { simkl.selectAccount(acc.id) }
                                                            refreshAccountModels()
                                                        }
                                                    }
                                                },
                                            )
                                        }
                                        HorizontalDivider(color = NetflixBorder)
                                        DropdownMenuItem(
                                            text = { Text("+ Add SIMKL Account", color = NetflixRed, fontWeight = FontWeight.Bold) },
                                            onClick = {
                                                showTopAccountMenu = false
                                                openSimklWebAuth(clearCookies = true)
                                            },
                                        )
                                        if (accountKey != null) {
                                            DropdownMenuItem(
                                                text = { Text("Switch to Guest Mode", color = NetflixTextMuted) },
                                                onClick = {
                                                    showTopAccountMenu = false
                                                    scope.launch {
                                                        runCatching { simkl.selectAccount(null) }
                                                        refreshAccountModels()
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            } else if (selectedTab == MoviesNavTab.HOME) {
                                Surface(
                                    onClick = { openSimklWebAuth(false) },
                                    shape = RoundedCornerShape(18.dp),
                                    color = NetflixCardBg,
                                    border = BorderStroke(1.dp, NetflixBorder),
                                    modifier = Modifier.padding(end = 10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.PersonOutline,
                                            contentDescription = null,
                                            tint = NetflixRed,
                                            modifier = Modifier.size(16.dp),
                                        )
                                        Text(
                                            text = "Sign In",
                                            color = Color.White,
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = NetflixBlack,
                            titleContentColor = Color.White,
                        ),
                    )
                }
            },
            bottomBar = {
                if (showNavigationChrome) {
                    Column {
                        HorizontalDivider(color = Color(0xFF1A1A22), thickness = 1.dp)
                        NavigationBar(
                            containerColor = Color(0xFF0A0A0D),
                            contentColor = Color.White,
                            tonalElevation = 0.dp,
                        ) {
                            MoviesNavTab.entries.forEach { navItem ->
                                val isSelected = selectedTab == navItem
                                NavigationBarItem(
                                    selected = isSelected,
                                    onClick = { selectedTab = navItem },
                                    icon = {
                                        Icon(
                                            imageVector = navItem.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(23.dp),
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = navItem.title,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = NetflixRed,
                                        selectedTextColor = NetflixRed,
                                        indicatorColor = NetflixRed.copy(alpha = 0.16f),
                                        unselectedIconColor = Color(0xFFD5D7DF),
                                        unselectedTextColor = Color(0xFFD5D7DF),
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .consumeWindowInsets(paddingValues),
            ) {
                AnimatedContent(
                    targetState = currentRoute,
                    transitionSpec = {
                        when {
                            targetState.depth > initialState.depth -> {
                                (slideInHorizontally(tween(240)) { it / 5 } + fadeIn(tween(190))) togetherWith
                                    (slideOutHorizontally(tween(210)) { -it / 10 } + fadeOut(tween(150)))
                            }
                            targetState.depth < initialState.depth -> {
                                (slideInHorizontally(tween(220)) { -it / 10 } + fadeIn(tween(170))) togetherWith
                                    (slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(150)))
                            }
                            else -> {
                                (fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.985f)) togetherWith
                                    fadeOut(tween(130))
                            }
                        }.using(SizeTransform(clip = false))
                    },
                    label = "movies_route_transition",
                ) { route ->
                    when (route) {
                        is MoviesRoute.Detail -> routeStateHolder.SaveableStateProvider(route.stateKey) {
                            MovieDetails(
                                preview = route.title,
                                catalog = catalog,
                                library = library,
                                saved = route.title.ref in saved || route.title.ref in remote,
                                onSave = { toggleSaved(route.title) },
                                onBack = { closeDetail(route) },
                                onPlay = { item, episode -> startPlayback(item, episode) },
                                hideMature = hideMature,
                                onDownload = ::queueDownloads,
                                onWatched = ::markWatched,
                                onOpenRelated = { if (route == currentRoute) openDetail(it) },
                                history = combinedHistory,
                                rating = ratings[route.title.ref] ?: 0,
                                onRate = ::rate,
                                watchStatus = tracking[route.title.ref],
                                onWatchStatus = ::track,
                                isDownloaded = { item, episode -> downloads.isNotEmpty() && vault.completedFile(activity, item, episode) != null },
                            )
                        }
                        is MoviesRoute.Tab -> routeStateHolder.SaveableStateProvider(route.stateKey) {
                            when (route.tab) {
                                MoviesNavTab.HOME -> MoviesHomeScreen(
                                    catalog = catalog,
                                    saved = saved + remote,
                                    recent = recent,
                                    ratings = ratings,
                                    history = combinedHistory,
                                    hideMature = hideMature,
                                    open = ::openDetail,
                                    resume = { item, entry ->
                                        val episode = item.episodes.firstOrNull { it.season == entry.season && it.episode == entry.episode && it.available }
                                        if (item.kind == MovieKind.MOVIE || episode != null) startPlayback(item, episode)
                                        else openDetail(item)
                                    },
                                    playFeatured = { item, episode -> startPlayback(item, episode) },
                                    toggleSaved = ::toggleSaved,
                                )
                                MoviesNavTab.LIST -> MoviesMyListScreen(
                                    saved = saved + remote,
                                    catalog = catalog,
                                    recent = recent,
                                    history = combinedHistory,
                                    savedOrder = savedOrder,
                                    ratings = ratings,
                                    tracking = tracking,
                                    hideMature = hideMature,
                                    isLoggedIn = accountKey != null,
                                    pendingSyncCount = pendingSyncCount,
                                    isSyncingQueue = isSyncingQueue,
                                    onConnectClick = { openSimklWebAuth(clearCookies = false) },
                                    open = ::openDetail,
                                    resume = { item, entry ->
                                        val episode = item.episodes.firstOrNull { it.season == entry.season && it.episode == entry.episode && it.available }
                                        if (item.kind == MovieKind.MOVIE || episode != null) startPlayback(item, episode)
                                        else openDetail(item)
                                    },
                                    playOffline = { item, episode, file -> startPlayback(item, episode, file) },
                                )
                                MoviesNavTab.SEARCH -> MoviesSearchScreen(
                                    catalog = catalog,
                                    saved = saved + remote,
                                    recent = recent,
                                    ratings = ratings,
                                    isLoggedIn = accountKey != null,
                                    username = activeSimklAccount?.username ?: "Guest",
                                    hideMature = hideMature,
                                    onConnectClick = { openSimklWebAuth(clearCookies = false) },
                                    open = ::openDetail,
                                )
                                MoviesNavTab.ACCOUNT -> MoviesSettings(
                                    simkl = simkl,
                                    account = accountKey,
                                    activeAccount = activeSimklAccount,
                                    savedAccounts = savedSimklAccounts,
                                    pendingSyncCount = pendingSyncCount,
                                    isSyncingQueue = isSyncingQueue,
                                    syncMessage = syncMessage,
                                    onAccountChanged = ::refreshAccountModels,
                                    onOpenWebAuth = ::openSimklWebAuth,
                                    onSync = { syncSignal.trySend(Unit) },
                                    hideMature = hideMature,
                                    onHideMature = { hideMature = it; MoviesPreferences.setHideMature(activity, it) },
                                    doNotTrackMature = doNotTrackMature,
                                    onDoNotTrackMature = { doNotTrackMature = it; MoviesPreferences.setDoNotTrackMature(activity, it) },
                                    reportProgress = reportProgress,
                                    onReportProgress = { reportProgress = it; MoviesPreferences.setReportProgress(activity, it) },
                                    rememberPosition = rememberPosition,
                                    onRememberPosition = { rememberPosition = it; MoviesPreferences.setRememberPosition(activity, it) },
                                    preferredQuality = preferredQuality,
                                    onPreferredQuality = { preferredQuality = it; MoviesPreferences.setPreferredQuality(activity, it) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Smooth Fullscreen Player Overlay (preserves underlying scroll state & animates cleanly!)
        AnimatedVisibility(
            visible = playback != null,
            enter = fadeIn(tween(220)) + scaleIn(tween(240), initialScale = 0.96f),
            exit = fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.97f),
            label = "fullscreen_playback_transition",
        ) {
            val request = playback ?: lastPlayback
            if (request != null) {
                val episodes = request.title.episodes.filter { it.available && (it.season > 0 || request.episode?.season == 0) }
                val index = episodes.indexOfFirst { it.id == request.episode?.id }
                val next = episodes.getOrNull(index + 1).takeIf { index >= 0 }
                val previous = episodes.getOrNull(index - 1)
                MoviesPlayback(
                    title = request.title,
                    episode = request.episode,
                    startPosition = request.position,
                    offlineFile = request.offlineFile,
                    preferredQuality = preferredQuality,
                    onProgress = { position, duration ->
                        if (!reportProgress || (doNotTrackMature && request.title.isMature)) return@MoviesPlayback
                        val progress = library.record(request.title.ref, request.episode, position, duration, MoviesPreferences.completion(activity))
                        if (progress?.completed == true) {
                            accountKey?.let { key ->
                                if (library.queueWatch(key, progress, true)) {
                                    pendingSyncCount = computePendingCount(key)
                                    syncSignal.trySend(Unit)
                                }
                            }
                        }
                    },
                    onBack = { playback = null },
                    onNextEpisode = next?.let { episode -> { startPlayback(request.title, episode) } },
                    onPreviousEpisode = previous?.let { episode -> { startPlayback(request.title, episode) } },
                )
            }
        }

        if (showSimklAuthDialog || completingSimklCallback) {
            SimklConnectDialog(
                simkl = simkl,
                completingCallback = completingSimklCallback,
                onReopenBrowser = { openSimklWebAuth(false) },
                onConnected = {
                    refreshAccountModels()
                    showSimklAuthDialog = false
                    syncSignal.trySend(Unit)
                },
                onClose = {
                    if (!completingSimklCallback) showSimklAuthDialog = false
                },
            )
        }
    }
}

@Composable
private fun SimklConnectDialog(
    simkl: Simkl,
    completingCallback: Boolean,
    onReopenBrowser: () -> Unit,
    onConnected: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deviceSignIn by remember { mutableStateOf<SimklSignIn?>(null) }
    var loadingCode by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(deviceSignIn) {
        val active = deviceSignIn ?: return@LaunchedEffect
        try {
            simkl.awaitSignIn(active)
            val label = simkl.activeAccount()?.label ?: "SIMKL"
            Toast.makeText(context, "Connected to SIMKL as $label", Toast.LENGTH_SHORT).show()
            onConnected()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            codeError = e.message ?: "Sign-in code expired. Try again."
            deviceSignIn = null
        }
    }

    Dialog(onDismissRequest = onClose) {
        NetflixCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            background = NetflixSurface,
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (completingCallback) {
                    CircularProgressIndicator(color = NetflixRed, strokeWidth = 3.dp)
                    Text(
                        text = "Linking your SIMKL account…",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                } else if (deviceSignIn != null) {
                    val active = deviceSignIn!!
                    Text(
                        text = "Enter Code on SIMKL",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = "Approve this code in your browser and Nanami Movies will connect automatically:",
                        color = NetflixTextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = NetflixBlack,
                        border = BorderStroke(1.dp, NetflixRed),
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("SIMKL Code", active.userCode))
                            Toast.makeText(context, "Copied code ${active.userCode}", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text(
                            text = active.userCode,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 26.sp,
                            letterSpacing = 2.5.sp,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        )
                    }
                    Button(
                        onClick = { openCinemaCustomTab(context, active.verificationUrl) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NetflixRed, contentColor = Color.White),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("Open simkl.com/pin", fontWeight = FontWeight.ExtraBold)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(color = NetflixRed, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Text("Waiting for approval…", color = NetflixTextMuted, fontSize = 12.sp)
                    }
                    TextButton(onClick = onClose) {
                        Text("Cancel", color = NetflixTextMuted)
                    }
                } else {
                    Icon(
                        Icons.Default.AccountCircle,
                        contentDescription = null,
                        tint = NetflixRed,
                        modifier = Modifier.size(42.dp),
                    )
                    Text(
                        text = "Approve in Browser",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                    )
                    Text(
                        text = "Tap Approve on the SIMKL page that opened and you will return right back to Nanami Movies automatically.",
                        color = NetflixTextMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                    )
                    codeError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                    Button(
                        onClick = onReopenBrowser,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NetflixRed, contentColor = Color.White),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("Reopen SIMKL Sign-In", fontWeight = FontWeight.ExtraBold)
                    }
                    OutlinedButton(
                        enabled = !loadingCode,
                        onClick = {
                            scope.launch {
                                loadingCode = true
                                codeError = null
                                try {
                                    val started = simkl.beginSignIn()
                                    deviceSignIn = started
                                    openCinemaCustomTab(context, started.verificationUrl)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    codeError = e.message ?: "Couldn't start code sign-in."
                                } finally {
                                    loadingCode = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, NetflixBorder),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text(
                            text = if (loadingCode) "Generating code…" else "Use 8-Character Code Instead",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                        )
                    }
                    TextButton(onClick = onClose) {
                        Text("Cancel", color = NetflixTextMuted)
                    }
                }
            }
        }
    }
}
