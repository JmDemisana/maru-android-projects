package io.maru.movies

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.LocalMovies
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal enum class BrowsePage(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    MOVIES("Movies", Icons.Outlined.LocalMovies),
    SERIES("TV Shows", Icons.Outlined.Tv),
    LIST("My List", Icons.Outlined.BookmarkBorder),
    SEARCH("Search", Icons.Default.Search),
}

private val CardBackground = Color(0xFF25212C)
private val WhiteButton = Color(0xFFF3ECF3)
private val TileShape = RoundedCornerShape(8.dp)
private val PagePadding = 20.dp

private data class Shelf(val label: String, val titles: List<MovieTitle>)

@Composable
internal fun MoviesBrowse(
    page: BrowsePage,
    catalog: MoviesCatalog,
    saved: Set<TitleRef>,
    recent: List<WatchProgress>,
    onPage: (BrowsePage) -> Unit,
    open: (MovieTitle) -> Unit,
    resume: (MovieTitle, WatchProgress) -> Unit,
    openSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = PagePadding, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("NANAMI", color = MoviesAccent, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
            Text(" MOVIES", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onPage(if (page == BrowsePage.SEARCH) BrowsePage.HOME else BrowsePage.SEARCH) }) {
                Icon(Icons.Default.Search, if (page == BrowsePage.SEARCH) "Close search" else "Search")
            }
            IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings") }
        }

        if (page == BrowsePage.SEARCH) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = PagePadding, vertical = 8.dp),
                placeholder = { Text("Search movies and TV shows") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                BrowsePage.HOME, BrowsePage.MOVIES, BrowsePage.SERIES -> BrowseShelves(page, catalog, recent, open, resume)
                BrowsePage.LIST -> ResultsPage(saved, catalog, open)
                BrowsePage.SEARCH -> SearchResults(query.trim(), catalog, open)
            }
        }

        NavigationBar(containerColor = Color(0xFF18151E), windowInsets = androidx.compose.foundation.layout.WindowInsets(0)) {
            BrowsePage.entries.filter { it != BrowsePage.SEARCH }.forEach { item ->
                NavigationBarItem(
                    selected = page == item,
                    onClick = { onPage(item) },
                    icon = { Icon(item.icon, null) },
                    label = { Text(item.label, maxLines = 1) },
                )
            }
        }
    }
}

@Composable
private fun BrowseShelves(
    page: BrowsePage,
    catalog: MoviesCatalog,
    recent: List<WatchProgress>,
    open: (MovieTitle) -> Unit,
    resume: (MovieTitle, WatchProgress) -> Unit,
) {
    val singleKind = when (page) {
        BrowsePage.MOVIES -> MovieKind.MOVIE
        BrowsePage.SERIES -> MovieKind.SERIES
        else -> null
    }
    var shelves by remember(page) { mutableStateOf<List<Shelf>?>(null) }
    var continueTitles by remember { mutableStateOf<List<MovieTitle>>(emptyList()) }
    var reload by remember(page) { mutableIntStateOf(0) }
    LaunchedEffect(page, reload) {
        shelves = null
        val kinds = singleKind?.let(::listOf) ?: MovieKind.entries
        val labels = kinds.flatMap { kind ->
            val movie = kind == MovieKind.MOVIE
            listOf(Triple(if (movie) "Popular movies" else "Popular TV shows", kind, null),
                Triple(if (movie) "Animated movies" else "Cartoons & animation", kind, "Animation"))
        }
        shelves = labels.map { (label, kind, genre) ->
            async { Shelf(label, attempt { catalog.popular(kind, genre = genre) }.getOrDefault(emptyList())) }
        }.map { it.await() }.filter { it.titles.isNotEmpty() }
    }
    LaunchedEffect(page, recent) {
        continueTitles = if (page == BrowsePage.HOME) catalog.resolve(recent.map(WatchProgress::title)) else emptyList()
    }
    val current = shelves
    if (current == null) {
        Loading()
        return
    }
    if (current.isEmpty() && continueTitles.isEmpty()) {
        EmptyState("Couldn't load the catalog.") { reload++ }
        return
    }
    val feature = current.firstOrNull()?.titles?.firstOrNull { it.backdrop != null }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        if (feature != null) item { FeatureCard(feature) { open(feature) } }
        if (page == BrowsePage.HOME && continueTitles.isNotEmpty()) {
            item {
                val progress = recent.associateBy(WatchProgress::title)
                TitleShelf("Continue watching", continueTitles, progress) { title ->
                    progress[title.ref]?.let { resume(title, it) }
                }
            }
        }
        items(current, key = Shelf::label) { shelf -> TitleShelf(shelf.label, shelf.titles, open = open) }
    }
}

@Composable
private fun SearchResults(query: String, catalog: MoviesCatalog, open: (MovieTitle) -> Unit) {
    var result by remember(query) { mutableStateOf<Result<List<MovieTitle>>?>(null) }
    var retry by remember(query) { mutableIntStateOf(0) }
    LaunchedEffect(query, retry) {
        if (query.length < 2) return@LaunchedEffect
        result = null
        delay(300)
        result = attempt { catalog.search(query) }
    }
    when {
        query.length < 2 -> EmptyState("Find a movie or TV show.")
        result == null -> Loading()
        result?.isFailure == true -> EmptyState("Search is temporarily unavailable.") { retry++ }
        else -> ResultItems(result?.getOrNull().orEmpty(), "No matching movies or TV shows.", open)
    }
}

@Composable
private fun ResultsPage(saved: Set<TitleRef>, catalog: MoviesCatalog, open: (MovieTitle) -> Unit) {
    var titles by remember(saved) { mutableStateOf<List<MovieTitle>?>(null) }
    LaunchedEffect(saved) { titles = catalog.resolve(saved.toList()) }
    if (titles == null && saved.isNotEmpty()) Loading()
    else ResultItems(titles.orEmpty(), "Your saved movies and shows will appear here.", open)
}

@Composable
private fun ResultItems(titles: List<MovieTitle>, empty: String, open: (MovieTitle) -> Unit) {
    if (titles.isEmpty()) {
        EmptyState(empty)
        return
    }
    LazyColumn(contentPadding = PaddingValues(PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(titles, key = { it.ref.key }) { title ->
            Row(
                Modifier.fillMaxWidth().clip(TileShape).clickable { open(title) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Artwork(title.poster, Modifier.width(58.dp).aspectRatio(2f / 3f).clip(TileShape))
                Column(Modifier.weight(1f)) {
                    Text(title.title, style = MaterialTheme.typography.titleMedium)
                    Text(title.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Default.PlayArrow, null, Modifier.padding(end = 8.dp))
            }
        }
    }
}

@Composable
private fun FeatureCard(title: MovieTitle, onOpen: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = PagePadding).height(310.dp).clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen),
    ) {
        Artwork(title.backdrop ?: title.poster, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xED120E17)), startY = 65f)))
        Column(
            Modifier.align(Alignment.BottomStart).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title.title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(title.subtitle(), color = Color(0xFFF1EAF1), style = MaterialTheme.typography.bodySmall)
            Text("View title", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TitleShelf(
    label: String,
    titles: List<MovieTitle>,
    progress: Map<TitleRef, WatchProgress> = emptyMap(),
    open: (MovieTitle) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(label, Modifier.padding(horizontal = PagePadding), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyRow(contentPadding = PaddingValues(horizontal = PagePadding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(titles, key = { it.ref.key }) { title ->
                val item = progress[title.ref]
                Column(
                    Modifier.width(if (item == null) 110.dp else 164.dp).clip(TileShape).clickable { open(title) },
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    val art = if (item == null) title.poster else title.backdrop ?: title.poster
                    Artwork(art, Modifier.fillMaxWidth().aspectRatio(if (item == null) 2f / 3f else 16f / 9f).clip(TileShape))
                    if (item != null) LinearProgressIndicator(
                        progress = { (item.position.toFloat() / item.duration).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(title.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

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
) {
    var details by remember(preview.ref) { mutableStateOf<Result<MovieTitle?>?>(null) }
    var retry by remember(preview.ref) { mutableIntStateOf(0) }
    var chosenSeason by rememberSaveable(preview.ref.key) { mutableStateOf<Int?>(null) }
    LaunchedEffect(preview.ref, retry) {
        details = null
        details = attempt { catalog.details(preview.kind, preview.imdbId) }
    }
    val verified = details?.getOrNull()
    val title = verified ?: preview
    val seasons = title.episodes.map(MovieEpisode::season).distinct().sorted()
    val resume = library.recent().firstOrNull { it.title == title.ref }
    val resumeEpisode = title.episodes.firstOrNull { it.season == resume?.season && it.episode == resume?.episode }
    val firstEpisode = resumeEpisode ?: title.episodes.firstOrNull { it.season > 0 } ?: title.episodes.firstOrNull()
    val activeSeason = chosenSeason ?: firstEpisode?.season
    val episodes = title.episodes.filter { it.season == activeSeason }
    val canPlay = verified != null && (title.kind == MovieKind.MOVIE || firstEpisode != null)

    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(13.dp), contentPadding = PaddingValues(bottom = 22.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(240.dp)) {
                Artwork(title.backdrop ?: title.poster, Modifier.fillMaxSize())
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.7f).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, MoviesBackground))))
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp).background(Color(0xBE151219), TileShape),
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }
        }
        item {
            Column(Modifier.padding(horizontal = PagePadding), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                Text(title.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                Text(title.subtitle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onPlay(title, firstEpisode) },
                        enabled = canPlay,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = WhiteButton, contentColor = Color(0xFF100D15)),
                        shape = TileShape,
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text(if (resume != null) "Resume" else if (firstEpisode != null) "Play episode" else "Play")
                    }
                    OutlinedButton(onClick = onSave, enabled = verified != null, shape = TileShape) {
                        Icon(if (saved) Icons.Default.Check else Icons.Default.Add, if (saved) "Remove from My List" else "Add to My List")
                        Text("My List")
                    }
                }
                title.overview?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                when {
                    details == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    details?.isFailure == true -> TextButton(onClick = { retry++ }) { Text("Couldn't load details. Retry") }
                    verified == null -> Text("This title is unavailable.")
                    title.kind == MovieKind.SERIES && seasons.isEmpty() -> Text("No episodes are listed for this show.")
                }
            }
        }
        if (seasons.isNotEmpty()) item {
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
        items(episodes, key = MovieEpisode::id) { episode ->
            Column(Modifier.padding(horizontal = PagePadding).clip(TileShape).clickable(enabled = verified != null) { onPlay(title, episode) }
                .padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Artwork(episode.thumbnail ?: title.backdrop, Modifier.width(108.dp).aspectRatio(16f / 9f).clip(TileShape))
                    Text("${episode.episode}. ${episode.title}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Icon(Icons.Default.PlayArrow, null)
                }
                episode.overview?.takeIf(String::isNotBlank)?.let {
                    Text(it, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Artwork(url: String?, modifier: Modifier) {
    Box(modifier.background(CardBackground)) {
        if (url != null) AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun EmptyState(message: String, retry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(PagePadding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (retry != null) TextButton(onClick = retry) { Text("Try again") }
        }
    }
}

private fun MovieTitle.subtitle(): String = listOfNotNull(year, runtime, genres.firstOrNull()).joinToString("  ·  ")

private suspend fun MoviesCatalog.resolve(refs: List<TitleRef>): List<MovieTitle> = kotlinx.coroutines.coroutineScope {
    val limit = Semaphore(6)
    refs.map { ref -> async { limit.withPermit { attempt { details(ref.kind, ref.imdbId) }.getOrNull() } } }
        .mapNotNull { it.await() }
}

private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Exception) {
    if (error is CancellationException) throw error
    Result.failure(error)
}
