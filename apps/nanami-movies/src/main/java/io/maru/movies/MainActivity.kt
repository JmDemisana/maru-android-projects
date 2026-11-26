package io.maru.movies

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal val MoviesBackground = Color(0xFF100F14)
internal val MoviesAccent = Color(0xFFFF766E)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            )) {
                MoviesApp(this, catalog, library, simkl)
            }
        }
    }
}

private data class PlaybackRequest(val title: MovieTitle, val episode: MovieEpisode?, val position: Long)

@Composable
private fun MoviesApp(context: Context, catalog: MoviesCatalog, library: MovieLibrary, simkl: Simkl) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(BrowsePage.HOME) }
    var selected by remember { mutableStateOf<MovieTitle?>(null) }
    var playback by remember { mutableStateOf<PlaybackRequest?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var saved by remember { mutableStateOf(library.saved()) }
    var recent by remember { mutableStateOf(library.recent()) }
    var remote by remember { mutableStateOf(emptySet<TitleRef>()) }
    var connected by remember { mutableStateOf(simkl.isConnected) }
    var syncRevision by remember { mutableIntStateOf(0) }
    val pendingSave = remember { mutableSetOf<TitleRef>() }
    val pendingWatched = remember { mutableSetOf<String>() }

    LaunchedEffect(playback) {
        if (playback == null) recent = library.recent()
    }

    LaunchedEffect(connected, syncRevision) {
        if (!connected) {
            remote = emptySet()
            return@LaunchedEffect
        }
        try {
            remote = simkl.watchlist().map {
                TitleRef(if (it.isSeries) MovieKind.SERIES else MovieKind.MOVIE, it.imdbId)
            }.toSet()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            connected = simkl.isConnected
            snackbar.showSnackbar("Couldn't refresh your SIMKL list.")
        }
    }

    fun stopPlayback() {
        playback = null
    }

    fun startPlayback(title: MovieTitle, episode: MovieEpisode?) {
        val position = library.progress(title.ref, episode)?.takeIf { it.canResume }?.position ?: 0L
        playback = PlaybackRequest(title, episode, position)
    }

    fun toggleSaved(title: MovieTitle) {
        val ref = title.ref
        if (!pendingSave.add(ref)) return
        val adding = ref !in saved && ref !in remote
        saved = if (adding) saved + ref else saved - ref
        library.save(saved)
        scope.launch {
            try {
                if (connected) {
                    val listedRemotely = simkl.setWatchlisted(title.imdbId, title.kind == MovieKind.SERIES, adding)
                    remote = if (listedRemotely) remote + ref else remote - ref
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                connected = simkl.isConnected
                snackbar.showSnackbar("Couldn't update My List on SIMKL.")
            } finally {
                pendingSave.remove(ref)
            }
        }
    }

    BackHandler(playback != null || selected != null || tab != BrowsePage.HOME) {
        when {
            playback != null -> stopPlayback()
            selected != null -> selected = null
            else -> tab = BrowsePage.HOME
        }
    }

    Scaffold(
        containerColor = MoviesBackground,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        val request = playback
        val title = selected
        when {
            request != null -> MoviesPlayback(
                title = request.title,
                episode = request.episode,
                startPosition = request.position,
                onProgress = { position, duration ->
                    val progress = library.record(request.title.ref, request.episode, position, duration)
                    val syncKey = simkl.connectionKey?.let { connection -> progress?.let { "$connection/${it.key}" } }
                    if (connected && progress?.completed == true && syncKey != null &&
                        !library.sent(syncKey) && pendingWatched.add(syncKey)
                    ) {
                        scope.launch {
                            try {
                                simkl.markWatched(progress.title.imdbId, progress.season, progress.episode)
                                library.markSent(syncKey)
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                connected = simkl.isConnected
                                snackbar.showSnackbar("Couldn't send watch progress to SIMKL.")
                            } finally {
                                pendingWatched.remove(syncKey)
                            }
                        }
                    }
                },
                onBack = ::stopPlayback,
            )
            title != null -> MovieDetails(
                preview = title,
                catalog = catalog,
                library = library,
                saved = title.ref in saved || title.ref in remote,
                onSave = { toggleSaved(title) },
                onBack = { selected = null },
                onPlay = ::startPlayback,
                modifier = Modifier.padding(insets),
            )
            else -> MoviesBrowse(
                page = tab,
                catalog = catalog,
                saved = saved + remote,
                recent = recent,
                onPage = { tab = it },
                open = { selected = it },
                resume = { title, item ->
                    selected = title
                    val episode = title.episodes.firstOrNull { it.season == item.season && it.episode == item.episode }
                    if (title.kind == MovieKind.MOVIE || episode != null) startPlayback(title, episode)
                },
                openSettings = { settings = true },
                modifier = Modifier.padding(insets),
            )
        }
    }

    if (settings) SimklSettings(
        context = context,
        simkl = simkl,
        connected = connected,
        onConnected = { connected = it },
        onSync = { syncRevision++ },
        onClose = { settings = false },
    )
}

@Composable
private fun SimklSettings(
    context: Context,
    simkl: Simkl,
    connected: Boolean,
    onConnected: (Boolean) -> Unit,
    onSync: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf(simkl.clientId) }
    var code by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val busy = operation != null

    fun openInBrowser(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    fun close() {
        operation?.cancel()
        onClose()
    }

    AlertDialog(
        onDismissRequest = ::close,
        modifier = Modifier.imePadding(),
        title = { Text("SIMKL") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (connected) {
                    Text("Your SIMKL account is connected. Finished plays update your watch history.")
                    TextButton(onClick = onSync) { Text("Refresh My List") }
                    TextButton(enabled = !busy, onClick = {
                        operation = scope.launch {
                            try {
                                simkl.disconnect()
                                onConnected(false)
                            } finally {
                                operation = null
                            }
                        }
                    }) { Text("Disconnect") }
                } else {
                    Text("Connect to sync My List and watched movies and episodes.")
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it },
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("SIMKL V2 client ID") },
                    )
                    if (id.isBlank()) TextButton(onClick = {
                        runCatching { openInBrowser("https://simkl.com/settings/developer/new/") }
                            .onFailure { status = "Couldn't open the browser." }
                    }) { Text("Get a SIMKL client ID") }
                    Button(enabled = id.isNotBlank() && !busy, onClick = {
                        operation = scope.launch {
                            status = null
                            try {
                                simkl.setClientId(id.trim())
                                val signIn = simkl.beginSignIn()
                                code = signIn.userCode
                                openInBrowser(signIn.verificationUrl)
                                simkl.awaitSignIn(signIn)
                                onConnected(true)
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                status = error.message ?: "Couldn't connect to SIMKL."
                            } finally {
                                code = null
                                operation = null
                            }
                        }
                    }) { Text("Connect SIMKL") }
                    code?.let { Text("Approve the sign-in in your browser. Code $it") }
                }
                TextButton(onClick = {
                    runCatching { openInBrowser("https://simkl.com/") }
                        .onFailure { status = "Couldn't open the browser." }
                }) { Text("Open SIMKL") }
                status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = ::close) { Text("Done") } },
    )
}
