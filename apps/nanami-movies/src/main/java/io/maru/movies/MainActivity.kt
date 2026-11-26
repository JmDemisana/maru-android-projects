package io.maru.movies

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

internal val MoviesBackground = Color(0xFF100F14)
internal val MoviesAccent = Color(0xFFFF766E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isInMultiWindowMode && resources.configuration.smallestScreenWidthDp in 1..599) {
            // Samsung can keep the last landscape rotation after an unspecified orientation is restored.
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
            MaterialTheme(colorScheme = darkColorScheme(
                primary = MoviesAccent,
                onPrimary = Color(0xFF260908),
                background = MoviesBackground,
                surface = MoviesBackground,
                surfaceVariant = Color(0xFF272330),
                onSurface = Color(0xFFF9F5FB),
                onSurfaceVariant = Color(0xFFCAC3D1),
            )) { MoviesApp(this, catalog, library, simkl) }
        }
    }
}

private data class PlaybackRequest(val title: MovieTitle, val episode: MovieEpisode?, val position: Long, val offlineFile: File?)

@Composable
private fun MoviesApp(activity: MainActivity, catalog: MoviesCatalog, library: MovieLibrary, simkl: Simkl) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val browseState = rememberSaveableStateHolder()
    val vault = MoviesDownloadVault
    val downloads by vault.items.collectAsState()
    var tab by rememberSaveable { mutableStateOf(BrowsePage.HOME) }
    var selected by remember { mutableStateOf<MovieTitle?>(null) }
    var playback by remember { mutableStateOf<PlaybackRequest?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var downloadsOpen by rememberSaveable { mutableStateOf(false) }
    var hideMature by remember { mutableStateOf(MoviesPreferences.hideMature(activity)) }
    var saved by remember { mutableStateOf(library.saved()) }
    var savedOrder by remember { mutableStateOf(library.savedOrder()) }
    var recent by remember { mutableStateOf(library.recent()) }
    var history by remember { mutableStateOf(library.history()) }
    var remote by remember { mutableStateOf(emptySet<TitleRef>()) }
    var remoteHistory by remember { mutableStateOf(emptyList<WatchProgress>()) }
    var ratings by remember { mutableStateOf(library.ratings()) }
    var tracking by remember { mutableStateOf(library.tracking()) }
    var account by remember { mutableStateOf(simkl.connectionKey.takeIf { simkl.isConnected }) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    val syncSignal = remember { Channel<Unit>(Channel.CONFLATED) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var prompted by rememberSaveable { mutableStateOf(false) }

    fun refreshProgress() {
        recent = library.recent()
        history = library.history()
    }

    LaunchedEffect(vault) { vault.load(activity.applicationContext) }
    LaunchedEffect(playback) { if (playback == null) refreshProgress() }
    DisposableEffect(activity) {
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) syncSignal.trySend(Unit)
        }
        activity.lifecycle.addObserver(watcher)
        onDispose { activity.lifecycle.removeObserver(watcher) }
    }

    LaunchedEffect(account) {
        remote = emptySet()
        remoteHistory = emptyList()
        syncMessage = null
        val key = account ?: run { ratings = library.ratings(); tracking = library.tracking(); return@LaunchedEffect }
        ratings = library.pendingRatings(key).associate { it.title to it.score }
        tracking = library.pendingStatuses(key).associate { it.title to it.status }
        while (isActive) {
            var retry = false
            try {
                val list = library.pendingList(key)
                val watch = library.pendingWatch(key)
                syncMessage = "Syncing…"
                for (pending in list) {
                    val ref = pending.title
                    try { simkl.setWatchlisted(ref.imdbId, ref.kind == MovieKind.SERIES, pending.listed, key) }
                    catch (_: SimklTitleUnavailable) { /* The device library keeps this title. */ }
                    library.finishList(key, pending)
                }
                for (pending in watch) {
                    val entry = pending.progress
                    try { simkl.markWatched(entry.title.imdbId, entry.season, entry.episode, pending.watched, key) }
                    catch (_: SimklTitleUnavailable) { /* Some titles are absent from SIMKL. */ }
                    library.finishWatch(key, pending)
                }
                for (rating in library.pendingRatings(key)) {
                    try { simkl.rate(rating.title, rating.score, key) }
                    catch (_: SimklTitleUnavailable) { /* SIMKL has no entry for the IMDb title. */ }
                    library.finishRating(key, rating)
                }
                for (status in library.pendingStatuses(key)) {
                    try { simkl.track(status.title, status.status, key) }
                    catch (_: SimklTitleUnavailable) { /* The provider could not match this title. */ }
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
                syncMessage = "My List and watch history synced."
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                account = simkl.connectionKey.takeIf { simkl.isConnected }
                val pending = library.pendingList(key).size + library.pendingWatch(key).size + library.pendingRatings(key).size +
                    library.pendingStatuses(key).size
                syncMessage = if (pending > 0) "$pending changes saved to sync when connected."
                    else "Couldn't refresh SIMKL."
                retry = pending > 0
            }
            if (retry) withTimeoutOrNull(30_000) { syncSignal.receive() } else syncSignal.receive()
        }
    }

    fun startPlayback(title: MovieTitle, episode: MovieEpisode?, file: File? = null) {
        val position = library.progress(title.ref, episode)?.takeIf { it.canResume }?.position ?: 0L
        playback = PlaybackRequest(title, episode, position, file ?: vault.completedFile(activity, title, episode))
    }

    fun toggleSaved(title: MovieTitle) {
        val ref = title.ref
        val adding = ref !in saved && ref !in remote
        saved = if (adding) saved + ref else saved - ref
        library.save(saved)
        savedOrder = library.savedOrder()
        account?.let { key ->
            if (!adding) remote = remote - ref
            library.queueList(key, ref, adding)
            syncSignal.trySend(Unit)
        }
        if (account == null && (adding && ref !in tracking || !adding && tracking[ref] == MovieWatchStatus.PLAN)) {
            val status = MovieWatchStatus.PLAN.takeIf { adding }
            library.track(ref, status, null)
            tracking = library.tracking()
        }
    }

    fun markWatched(title: MovieTitle, episode: MovieEpisode?, watched: Boolean) {
        val batch = if (title.kind == MovieKind.SERIES && episode == null) title.episodes else listOf(episode)
        for (entry in batch) {
            val progress = library.markWatched(title.ref, entry, watched)
            if (!watched) remoteHistory = remoteHistory.filterNot { it.key == progress.key }
            account?.let { if (library.queueWatch(it, progress, watched)) syncSignal.trySend(Unit) }
        }
        refreshProgress()
    }

    fun rate(title: MovieTitle, score: Int) {
        library.rate(title.ref, score, account)
        ratings = ratings + (title.ref to score)
        if (account != null) syncSignal.trySend(Unit)
    }

    fun track(title: MovieTitle, status: MovieWatchStatus) {
        val key = account
        val ref = title.ref
        library.track(ref, status, key)
        tracking = tracking + (ref to status)
        if (key == null) {
            saved = if (status == MovieWatchStatus.PLAN) saved + ref else saved - ref
            library.save(saved)
            savedOrder = library.savedOrder()
        } else {
            remote = if (status == MovieWatchStatus.PLAN) remote + ref else remote - ref
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
                snackbar.showSnackbar(if (count == 0) "Already in Downloads." else if (count == 1) "Added to Downloads." else "$count episodes added to Downloads.")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                snackbar.showSnackbar(error.message ?: "Couldn't queue the download.")
            }
        }
    }

    BackHandler(playback != null || selected != null || settings || downloadsOpen || tab != BrowsePage.HOME) {
        when {
            playback != null -> playback = null
            selected != null -> selected = null
            settings -> settings = false
            downloadsOpen -> downloadsOpen = false
            else -> tab = BrowsePage.HOME
        }
    }

    Scaffold(containerColor = MoviesBackground, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        val request = playback
        val title = selected
        val combinedHistory = remember(history, remoteHistory) {
            (history + remoteHistory).distinctBy(WatchProgress::key).sortedByDescending(WatchProgress::updated)
        }
        when {
            request != null -> {
                val episodes = request.title.episodes.filter { it.available && (it.season > 0 || request.episode?.season == 0) }
                val index = episodes.indexOfFirst { it.id == request.episode?.id }
                val next = episodes.getOrNull(index + 1).takeIf { index >= 0 }
                val previous = episodes.getOrNull(index - 1)
                MoviesPlayback(
                    title = request.title, episode = request.episode, startPosition = request.position, offlineFile = request.offlineFile,
                    onProgress = { position, duration ->
                        val progress = library.record(request.title.ref, request.episode, position, duration, MoviesPreferences.completion(activity))
                        if (progress?.completed == true) account?.let { if (library.queueWatch(it, progress, true)) syncSignal.trySend(Unit) }
                    },
                    onBack = { playback = null },
                    onNextEpisode = next?.let { episode -> { startPlayback(request.title, episode) } },
                    onPreviousEpisode = previous?.let { episode -> { startPlayback(request.title, episode) } },
                )
            }
            title != null -> MovieDetails(
                preview = title, catalog = catalog, library = library,
                saved = title.ref in saved || title.ref in remote, onSave = { toggleSaved(title) },
                onBack = { selected = null }, onPlay = { item, episode -> startPlayback(item, episode) },
                modifier = Modifier.padding(insets), hideMature = hideMature, onDownload = ::queueDownloads,
                onWatched = ::markWatched, onOpenRelated = { selected = it }, history = combinedHistory,
                rating = ratings[title.ref] ?: 0, onRate = ::rate, watchStatus = tracking[title.ref], onWatchStatus = ::track,
                isDownloaded = { item, episode -> downloads.isNotEmpty() && vault.completedFile(activity, item, episode) != null },
            )
            settings -> MoviesSettings(
                simkl, account, onAccountChanged = { account = simkl.connectionKey.takeIf { simkl.isConnected } },
                syncMessage = syncMessage, onSync = { syncSignal.trySend(Unit) }, hideMature = hideMature,
                onHideMature = { hideMature = it; MoviesPreferences.setHideMature(activity, it) },
                onBack = { settings = false }, modifier = Modifier.padding(insets),
            )
            downloadsOpen -> MoviesDownloads(
                open = { item, episode, file -> startPlayback(item, episode, file) }, modifier = Modifier.padding(insets),
                onBack = { downloadsOpen = false },
            )
            else -> browseState.SaveableStateProvider("browse") {
                MoviesBrowse(
                    page = tab, catalog = catalog, saved = saved + remote, recent = recent,
                    onPage = { tab = it }, open = { selected = it },
                    resume = { item, entry ->
                        selected = item
                        val episode = item.episodes.firstOrNull { it.season == entry.season && it.episode == entry.episode && it.available }
                        if (item.kind == MovieKind.MOVIE || episode != null) startPlayback(item, episode)
                    },
                    openSettings = { settings = true }, modifier = Modifier.padding(insets),
                    openDownloads = { downloadsOpen = true }, toggleSaved = ::toggleSaved, history = combinedHistory,
                    savedOrder = savedOrder, hideMature = hideMature, tracking = tracking,
                    playFeatured = { item, episode -> selected = item; startPlayback(item, episode) },
                )
            }
        }
    }
}
