package io.maru.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun MovieDetails(
    preview: MovieTitle,
    catalog: MoviesCatalog,
    library: MovieLibrary,
    saved: Boolean,
    onSave: () -> Unit,
    onBack: () -> Unit,
    onPlay: (MovieTitle, MovieEpisode?) -> Unit,
    modifier: Modifier = Modifier,
    onDownload: ((MovieTitle, List<MovieEpisode?>) -> Unit)? = null,
    onWatched: ((MovieTitle, MovieEpisode?, Boolean) -> Unit)? = null,
    isDownloaded: (MovieTitle, MovieEpisode?) -> Boolean = { _, _ -> false },
    onOpenRelated: ((MovieTitle) -> Unit)? = null,
    hideMature: Boolean = false,
    history: List<WatchProgress>? = null,
    rating: Int = 0,
    onRate: ((MovieTitle, Int) -> Unit)? = null,
) {
    var details by remember(preview.ref) { mutableStateOf<Result<MovieTitle?>?>(null) }
    var retry by remember(preview.ref) { mutableIntStateOf(0) }
    var chosenSeason by rememberSaveable(preview.ref.key) { mutableStateOf<Int?>(null) }
    var downloadDialog by remember { mutableStateOf(false) }
    var ratingDialog by remember(preview.ref) { mutableStateOf(false) }
    var expandedOverview by rememberSaveable(preview.ref.key) { mutableStateOf(false) }
    var detailsTab by rememberSaveable(preview.ref.key) { mutableStateOf(DetailsTab.EPISODES) }
    var related by remember(preview.ref) { mutableStateOf<List<MovieTitle>?>(null) }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(preview.ref, retry) {
        details = null
        details = attempt { catalog.details(preview.kind, preview.imdbId) }
    }
    val verified = details?.getOrNull()
    val title = verified ?: preview
    val blocked = hideMature && title.isMature
    val seasons = title.episodes.map(MovieEpisode::season).distinct().sorted()
    val titleHistory = history?.filter { it.title == title.ref }
    fun progressFor(episode: MovieEpisode?): WatchProgress? = if (titleHistory == null) library.progress(title.ref, episode)
        else titleHistory.firstOrNull { it.season == episode?.season && it.episode == episode?.episode }
    val resume = if (titleHistory == null) library.recent().firstOrNull { it.title == title.ref }
        else titleHistory.filter(WatchProgress::canResume).maxByOrNull(WatchProgress::updated)
    val resumeEpisode = title.episodes.firstOrNull { it.season == resume?.season && it.episode == resume?.episode && it.available }
    val firstEpisode = resumeEpisode ?: title.episodes.firstOrNull {
        it.season > 0 && it.available && progressFor(it)?.completed != true
    } ?: title.episodes.firstOrNull { it.season > 0 && it.available } ?: title.episodes.firstOrNull { it.available }
    val activeSeason = chosenSeason ?: firstEpisode?.season ?: seasons.firstOrNull()
    val episodes = title.episodes.filter { it.season == activeSeason }
    val canPlay = verified != null && !blocked && (title.kind == MovieKind.MOVIE && title.available || firstEpisode != null)
    val downloadableEpisodes = episodes.filter { it.available && !isDownloaded(title, it) &&
        progressFor(it)?.completed != true }

    LaunchedEffect(verified, detailsTab, onOpenRelated != null) {
        if (verified == null || onOpenRelated == null || detailsTab != DetailsTab.RELATED || related != null) return@LaunchedEffect
        related = attempt { catalog.popular(title.kind, genre = title.genres.firstOrNull()).filterNot { it.ref == title.ref }.take(20) }
            .getOrDefault(emptyList())
    }

    if (downloadDialog && onDownload != null) DownloadEpisodesDialog(
        available = downloadableEpisodes.size,
        close = { downloadDialog = false },
        enqueue = { count ->
            onDownload(title, downloadableEpisodes.take(count))
            downloadDialog = false
        },
    )
    if (ratingDialog && onRate != null) {
        var score by remember { mutableIntStateOf(rating.takeIf { it in 1..10 } ?: 7) }
        AlertDialog(onDismissRequest = { ratingDialog = false }, title = { Text("Rate ${title.title}") }, text = {
            Column {
                Text("$score / 10", style = MaterialTheme.typography.titleMedium)
                Slider(value = score.toFloat(), onValueChange = { score = it.toInt() }, valueRange = 1f..10f, steps = 8)
            }
        }, confirmButton = {
            TextButton(onClick = { onRate(title, score); ratingDialog = false }) { Text("Save rating") }
        }, dismissButton = {
            TextButton(onClick = { if (rating > 0) onRate(title, 0); ratingDialog = false }) {
                Text(if (rating > 0) "Clear rating" else "Cancel")
            }
        })
    }

    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(13.dp), contentPadding = PaddingValues(bottom = 22.dp)) {
        item {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(if (maxWidth >= 600.dp) 347.dp else 275.dp)) {
                    if (!blocked) Artwork(title.backdrop ?: title.poster, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxWidth().fillMaxHeight(0.7f).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, MoviesBackground))))
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp).background(Color(0xBE151219), TileShape),
                    ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = PagePadding), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text(title.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title.subtitle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    title.rating?.takeIf { it > 0 }?.let { score -> DetailBadge("IMDb " + java.lang.String.format(java.util.Locale.ROOT, "%.1f", score)) }
                    if (seasons.any { it > 0 }) {
                        val count = seasons.count { it > 0 }
                        DetailBadge("$count " + if (count == 1) "season" else "seasons")
                    }
                }
                if (title.isMature && !blocked) DetailBadge("Mature content")
                if (blocked) {
                    Text("Hidden by your maturity setting.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Column
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onPlay(title, firstEpisode) },
                        enabled = canPlay,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = WhiteButton, contentColor = Color(0xFF100D15)),
                        shape = TileShape,
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(5.dp))
                        Text(when {
                            resume != null -> "Resume"
                            firstEpisode != null -> "Play S${firstEpisode.season}:E${firstEpisode.episode}"
                            else -> "Play"
                        }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = onSave, enabled = verified != null, shape = TileShape) {
                        Icon(if (saved) Icons.Default.Check else Icons.Default.Add, if (saved) "Remove from My List" else "Add to My List")
                        Text("My List")
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (verified != null && onDownload != null) {
                        if (title.kind == MovieKind.MOVIE) {
                            TextButton(onClick = { onDownload(title, listOf(null)) }, enabled = canPlay && !isDownloaded(title, null)) {
                                Icon(if (isDownloaded(title, null)) Icons.Default.Check else Icons.Outlined.Download, null)
                                Spacer(Modifier.width(5.dp))
                                Text(if (isDownloaded(title, null)) "Downloaded" else "Download")
                            }
                        } else {
                            TextButton(onClick = { downloadDialog = true }, enabled = downloadableEpisodes.isNotEmpty()) {
                                Icon(Icons.Outlined.Download, null)
                                Spacer(Modifier.width(5.dp))
                                Text("Download episodes")
                            }
                        }
                    }
                    if (verified != null && title.kind == MovieKind.MOVIE && onWatched != null) {
                        val watched = progressFor(null)?.completed == true
                        TextButton(onClick = { onWatched(title, null, !watched) }) {
                            Text(if (watched) "Mark unwatched" else "Mark watched")
                        }
                    }
                    if (verified != null && onRate != null) TextButton(onClick = { ratingDialog = true }) {
                        Icon(Icons.Outlined.StarOutline, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (rating > 0) "Your rating $rating/10" else "Rate")
                    }
                    title.trailerId?.let { trailer ->
                        TextButton(onClick = {
                            runCatching { uriHandler.openUri("https://www.youtube.com/watch?v=" + trailer) }
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Trailer")
                        }
                    }
                }
                title.overview?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = if (expandedOverview) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis)
                    if (it.length > 175) TextButton(onClick = { expandedOverview = !expandedOverview }) {
                        Text(if (expandedOverview) "Show less" else "Read more")
                    }
                }
                when {
                    details == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    details?.isFailure == true -> TextButton(onClick = { retry++ }) { Text("Couldn't load details. Retry") }
                    verified == null -> Text("This title is unavailable.")
                    title.kind == MovieKind.SERIES && seasons.isEmpty() -> Text("No episodes are listed for this show.")
                    title.kind == MovieKind.MOVIE && !title.available -> Text("Scheduled for " + title.releasedAt?.toString()?.take(10))
                }
            }
        }
        val activeTab = if (title.kind == MovieKind.MOVIE && detailsTab == DetailsTab.EPISODES) DetailsTab.CAST else detailsTab
        if (verified != null && !blocked) item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = PagePadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailsTab.entries.filter {
                    when (it) {
                        DetailsTab.EPISODES -> title.kind == MovieKind.SERIES
                        DetailsTab.CAST -> true
                        DetailsTab.RELATED -> onOpenRelated != null
                    }
                }.forEach { tab ->
                    FilterChip(activeTab == tab, { detailsTab = tab }, label = { Text(tab.label) })
                }
            }
        }
        if (!blocked && activeTab == DetailsTab.CAST && verified != null) item {
            Column(Modifier.padding(horizontal = PagePadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DetailCredits("Cast", title.cast)
                DetailCredits(if (title.directors.size == 1) "Director" else "Directors", title.directors)
                DetailCredits("Writers", title.writers)
                DetailCredits("Genres", title.genres)
                title.status?.let { DetailCredits("Series status", listOf(it)) }
            }
        }
        if (!blocked && activeTab == DetailsTab.RELATED && onOpenRelated != null) item {
            val options = related
            if (options == null) CircularProgressIndicator(Modifier.padding(PagePadding).size(24.dp))
            else if (options.isNotEmpty()) TitleShelf("More Like This", options.visible(hideMature), open = onOpenRelated)
            else Text("No similar titles available.", Modifier.padding(horizontal = PagePadding))
        }
        if (!blocked && activeTab == DetailsTab.EPISODES && seasons.isNotEmpty()) item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = PagePadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                seasons.forEach { season ->
                    FilterChip(selected = season == activeSeason, onClick = { chosenSeason = season },
                        label = { Text(if (season == 0) "Specials" else "Season $season") })
                }
            }
        }
        if (!blocked && activeTab == DetailsTab.EPISODES) items(episodes, key = MovieEpisode::id) { episode ->
            MovieEpisodeCard(title, episode, progressFor(episode), isDownloaded(title, episode),
                onPlay = { onPlay(title, episode) },
                onDownload = onDownload?.let { callback -> { callback(title, listOf(episode)) } },
                onWatched = onWatched?.let { callback -> { watched -> callback(title, episode, watched) } })
        }
    }
}

private enum class DetailsTab(val label: String) { EPISODES("Episodes"), CAST("Cast & Crew"), RELATED("More Like This") }

@Composable
private fun DetailBadge(label: String) {
    Text(label, Modifier.background(CardBackground, TileShape).padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DetailCredits(label: String, names: List<String>) {
    if (names.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(names.joinToString(", "), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MovieEpisodeCard(
    title: MovieTitle,
    episode: MovieEpisode,
    progress: WatchProgress?,
    downloaded: Boolean,
    onPlay: () -> Unit,
    onDownload: (() -> Unit)?,
    onWatched: ((Boolean) -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    val watched = progress?.completed == true
    val canPlay = episode.available || downloaded
    val canDownload = onDownload != null && !downloaded && episode.available
    val canMark = onWatched != null && canPlay
    Column(Modifier.fillMaxWidth().padding(horizontal = PagePadding).clip(TileShape)
        .background(CardBackground).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(Modifier.weight(1f).clip(TileShape).clickable(enabled = canPlay, onClick = onPlay),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box(Modifier.width(110.dp).aspectRatio(16f / 9f).clip(TileShape)) {
                    Artwork(episode.thumbnail ?: title.backdrop, Modifier.fillMaxSize())
                    if (canPlay) Icon(Icons.Default.PlayArrow, null, Modifier.align(Alignment.Center)
                        .size(29.dp).background(Color(0xB0000000), RoundedCornerShape(50)).padding(4.dp), tint = Color.White)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${episode.episode}. ${episode.title}", style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val status = when {
                        !episode.available && !downloaded -> "Airs " + episode.releasedAt?.toString()?.take(10)
                        downloaded && watched -> "Downloaded · Watched"
                        downloaded -> "Downloaded"
                        watched -> "Watched"
                        progress?.canResume == true -> "In progress"
                        else -> null
                    }
                    status?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (canDownload || canMark) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Episode options") }
                DropdownMenu(menu, { menu = false }) {
                    if (canDownload) {
                        DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; onDownload?.invoke() })
                    }
                    if (canMark) {
                        DropdownMenuItem(text = { Text(if (watched) "Mark unwatched" else "Mark watched") },
                            onClick = { menu = false; onWatched?.invoke(!watched) })
                    }
                }
            }
        }
        if (progress?.canResume == true) LinearProgressIndicator(
            progress = { (progress.position.toFloat() / progress.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
        )
        episode.overview?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DownloadEpisodesDialog(available: Int, close: () -> Unit, enqueue: (Int) -> Unit) {
    var count by remember { mutableStateOf("") }
    val custom = count.toIntOrNull()?.takeIf { it in 1..available }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Download this season") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text("$available unwatched episodes available")
                if (available > 5) TextButton(onClick = { enqueue(5) }) { Text("Next 5 unwatched") }
                if (available > 0) TextButton(onClick = { enqueue(available) }) { Text("All unwatched") }
                OutlinedTextField(value = count, onValueChange = { count = it.filter(Char::isDigit).take(5) },
                    label = { Text("Custom number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, enabled = available > 0)
            }
        },
        confirmButton = { TextButton(enabled = custom != null, onClick = { custom?.let(enqueue) }) { Text("Queue downloads") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
    )
}
