package io.maru.movies

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun MoviesDownloads(
    open: (MovieTitle, MovieEpisode?, File) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val vault = MoviesDownloadVault
    val downloads by vault.items.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var delete by remember { mutableStateOf<MovieDownload?>(null) }

    LaunchedEffect(vault) { vault.load(context.applicationContext) }

    fun change(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                snackbar.showSnackbar((error as? MovieDownloadException)?.message ?: "Couldn't update the offline vault.")
            }
        }
    }

    val hideMature = MoviesPreferences.hideMature(context)
    val visible = if (hideMature) downloads.filterNot { it.title.isMature } else downloads
    val unfinished = visible.filter { it.state != MovieDownloadState.COMPLETE }
    val saved = visible.filter { it.state == MovieDownloadState.COMPLETE }.asReversed()
    val storedBytes = saved.sumOf { it.progress?.total ?: 0L }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Downloads", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                        if (saved.isNotEmpty()) {
                            Text("${saved.size} saved · ${Formatter.formatFileSize(context, storedBytes)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (visible.isEmpty()) item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("No downloads yet", style = MaterialTheme.typography.titleLarge)
                    Text("Download a movie or episode to watch offline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (unfinished.isNotEmpty()) item { DownloadSection("Queue") }
            items(unfinished, key = MovieDownload::key) { item ->
                DownloadRow(
                    item = item,
                    onPlay = null,
                    onStop = { change { vault.cancel(context, item.key) } },
                    onRetry = { change { vault.retry(context, item) } },
                    onDelete = { delete = item },
                )
            }
            if (saved.isNotEmpty()) item { DownloadSection("Saved offline") }
            items(saved, key = MovieDownload::key) { item ->
                DownloadRow(
                    item = item,
                    onPlay = {
                        val file = vault.completedFile(context, item)
                        if (file != null) {
                            val episodes = saved.filter { it.title.imdbId == item.title.imdbId }
                                .mapNotNull(MovieDownload::episode)
                                .sortedWith(compareBy(MovieEpisode::season, MovieEpisode::episode))
                            open(item.title.copy(episodes = episodes), item.episode, file)
                        } else change {
                            vault.markMissing(context, item)
                            snackbar.showSnackbar("The saved file is incomplete. Tap Retry to download again.")
                        }
                    },
                    onStop = {},
                    onRetry = {},
                    onDelete = { delete = item },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    delete?.let { item ->
        AlertDialog(
            onDismissRequest = { delete = null },
            title = { Text(if (item.state == MovieDownloadState.COMPLETE) "Delete download?" else "Remove from Downloads?") },
            text = { Text(item.episode?.let { "${item.title.title} · ${item.label}" } ?: item.title.title) },
            confirmButton = { TextButton(onClick = {
                delete = null
                change { vault.delete(context, item) }
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { delete = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun DownloadSection(label: String) {
    Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun DownloadRow(
    item: MovieDownload,
    onPlay: (() -> Unit)?,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    val fraction = item.progress?.fraction
    val stateLabel = when (item.state) {
        MovieDownloadState.COMPLETE -> item.progress?.total?.let { Formatter.formatFileSize(context, it) }.orEmpty()
        MovieDownloadState.QUEUED -> "Queued"
        MovieDownloadState.DOWNLOADING -> item.message ?: "Downloading…"
        MovieDownloadState.FAILED -> item.message ?: "Download failed"
        MovieDownloadState.CANCELLED -> "Canceled"
    }
    val container = Modifier.fillMaxWidth().clip(shape).background(colors.surfaceVariant.copy(alpha = 0.52f))
    Column(if (onPlay != null) container.clickable(onClick = onPlay) else container) {
        Row(
            Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(89.dp).aspectRatio(16f / 9f).clip(shape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = item.episode?.thumbnail ?: item.title.backdrop ?: item.title.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (onPlay != null) Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(28.dp), tint = androidx.compose.ui.graphics.Color.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.title.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (item.episode != null) Text(item.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(stateLabel, color = if (item.state == MovieDownloadState.FAILED) colors.error else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall)
            }
            when {
                item.isActive -> IconButton(onClick = onStop) { Icon(Icons.Outlined.Close, "Cancel download") }
                item.state == MovieDownloadState.COMPLETE -> IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Delete download") }
                else -> {
                    IconButton(onClick = onRetry, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.Refresh, "Retry download") }
                    IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.DeleteOutline, "Remove queued download") }
                }
            }
        }
        if (item.state == MovieDownloadState.DOWNLOADING) {
            if (fraction == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}
