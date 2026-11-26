package io.maru.movies

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import io.maru.manime.extensions.StremioAddonClient
import io.maru.manime.extensions.StremioStreamResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private val moviesTorrentio = StremioAddonClient("https://torrentio.strem.fun")

@Composable
internal fun MoviesPlayback(
    title: MovieTitle,
    episode: MovieEpisode?,
    startPosition: Long,
    onProgress: (position: Long, duration: Long) -> Unit,
    onBack: () -> Unit
) {
    val streamId = episode?.let { "${title.imdbId}:${it.season}:${it.episode}" } ?: title.imdbId
    var sources by remember(streamId) { mutableStateOf<List<StremioStreamResult>?>(null) }
    var lookupError by remember(streamId) { mutableStateOf(false) }
    var lookupAttempt by remember(streamId) { mutableIntStateOf(0) }
    var playerAttempt by remember(streamId) { mutableIntStateOf(0) }
    var sourceIndex by remember(streamId) { mutableIntStateOf(0) }
    var showSources by remember(streamId) { mutableStateOf(false) }
    var position by remember(streamId) { mutableLongStateOf(startPosition) }

    BackHandler(onBack = onBack)
    LaunchedEffect(streamId, lookupAttempt) {
        sources = null
        lookupError = false
        try {
            sources = moviesTorrentio.getStreams(title.kind.addonType, streamId)
                .filter { it.httpUri() != null || it.magnetUri() != null }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            lookupError = true
        }
    }

    val currentSources = sources
    val selected = currentSources?.getOrNull(sourceIndex)
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(title.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                episode?.let {
                    Text("S${it.season} E${it.episode} · ${it.title}", style = MaterialTheme.typography.labelMedium,
                        color = Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!currentSources.isNullOrEmpty()) {
                TextButton(onClick = { showSources = true }) { Text("Sources") }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                lookupError -> PlaybackMessage("Could not reach Torrentio.") {
                    Button(onClick = { lookupAttempt++ }) { Text("Try again") }
                }
                currentSources == null -> PlaybackLoading("Looking for sources…")
                selected == null -> PlaybackMessage("Torrentio found no playable sources.")
                else -> key(streamId, playerAttempt, sourceIndex) {
                    MovieSourcePlayer(
                        source = selected,
                        episode = episode,
                        startPosition = position,
                        onProgress = { current, duration ->
                            position = current
                            onProgress(current, duration)
                        },
                        onRetry = { playerAttempt++ },
                        onChooseSource = { showSources = true }
                    )
                }
            }
        }
    }

    if (showSources && !currentSources.isNullOrEmpty()) {
        AlertDialog(
            onDismissRequest = { showSources = false },
            title = { Text("Video sources") },
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(currentSources) { index, source ->
                        TextButton(
                            onClick = {
                                sourceIndex = index
                                playerAttempt++
                                showSources = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(source.name?.replace('\n', ' ') ?: "Torrentio", style = MaterialTheme.typography.labelLarge)
                                source.title?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSources = false }) { Text("Close") } }
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MovieSourcePlayer(
    source: StremioStreamResult,
    episode: MovieEpisode?,
    startPosition: Long,
    onProgress: (Long, Long) -> Unit,
    onRetry: () -> Unit,
    onChooseSource: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    val reportProgress by rememberUpdatedState(onProgress)
    val resumePosition = remember { startPosition.coerceAtLeast(0) }
    var loading by remember { mutableStateOf("Connecting to source…") }
    var buffering by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    val player = remember {
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderers).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setHandleAudioBecomingNoisy(true)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_IDLE || playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED && player.duration > 0) {
                    reportProgress(player.duration, player.duration)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                failure = "This source could not be played."
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            if (player.duration > 0 && player.currentPosition > 0) {
                reportProgress(player.currentPosition, player.duration)
            }
            player.release()
        }
    }

    DisposableEffect(lifecycle, player, view) {
        val wasKeepingScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            view.keepScreenOn = wasKeepingScreenOn
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(2_000)
            if (player.duration > 0 && player.currentPosition > 0) {
                reportProgress(player.currentPosition, player.duration)
            }
        }
    }

    LaunchedEffect(player, failure) {
        if (failure != null) return@LaunchedEffect
        try {
            val direct = source.httpUri()
            if (direct != null) {
                val factory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(source.headers)
                val item = MediaItem.fromUri(direct)
                val media = DefaultMediaSourceFactory(context).setDataSourceFactory(factory).createMediaSource(item)
                player.setMediaSource(media, resumePosition)
                player.playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                player.prepare()
            } else {
                withMoviesTorrent(context.cacheDir, source, episode,
                    onBuffer = { withContext(Dispatchers.Main) { loading = "Buffering video…" } }
                ) { uri, dataSource ->
                    withContext(Dispatchers.Main) {
                        val media = ProgressiveMediaSource.Factory(dataSource).createMediaSource(MediaItem.fromUri(uri))
                        player.setMediaSource(media, resumePosition)
                        player.playWhenReady = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        player.prepare()
                        awaitCancellation()
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure = "This source timed out while connecting or buffering."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LinkageError) {
            failure = "The torrent engine could not start on this device."
        } catch (error: Exception) {
            Log.w("MoviesPlayback", "Could not prepare source", error)
            failure = "This source could not be played."
        }
    }

    val currentFailure = failure
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { PlayerView(it).apply { this.player = player } }
        )
        if (currentFailure != null) {
            PlaybackMessage(currentFailure) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRetry) { Text("Retry") }
                    Button(onClick = onChooseSource) { Text("Choose another source") }
                }
            }
        } else if (buffering) {
            PlaybackLoading(loading)
        }
    }
}

private fun StremioStreamResult.httpUri(): Uri? = url?.let(Uri::parse)?.takeIf { uri ->
    uri.scheme == "https" && !uri.host.isNullOrBlank()
}

@Composable
private fun PlaybackLoading(message: String) {
    PlaybackMessage(message) { CircularProgressIndicator() }
}

@Composable
private fun PlaybackMessage(message: String, actions: @Composable () -> Unit = {}) {
    Column(
        Modifier.background(Color.Black.copy(alpha = 0.75f)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(message, color = Color.White)
        actions()
    }
}
