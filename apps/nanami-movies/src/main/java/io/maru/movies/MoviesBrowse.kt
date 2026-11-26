package io.maru.movies

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LocalMovies
import androidx.compose.material.icons.outlined.Tv
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant

internal enum class BrowsePage(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    MOVIES("Movies", Icons.Outlined.LocalMovies),
    SERIES("TV Shows", Icons.Outlined.Tv),
    LIST("My List", Icons.Outlined.BookmarkBorder),
    SEARCH("Search", Icons.Default.Search),
}

internal val CardBackground = Color(0xFF25212C)
internal val WhiteButton = Color(0xFFF3ECF3)
internal val TileShape = RoundedCornerShape(8.dp)
internal val PagePadding = 20.dp
private val genres = listOf("Action", "Animation", "Comedy", "Documentary", "Drama", "Family", "Fantasy",
    "Horror", "Mystery", "Romance", "Sci-Fi", "Thriller")
private val navPages = BrowsePage.entries.filterNot { it == BrowsePage.SEARCH }

private data class Shelf(val label: String, val kind: MovieKind, val feed: MovieFeed = MovieFeed.POPULAR, val genre: String? = null)

private fun Shelf.encode(): String = listOf(label, kind.name, feed.name, genre.orEmpty()).joinToString("|")

private fun String.decodeShelf(): Shelf? {
    val parts = split('|')
    if (parts.size != 4) return null
    val kind = MovieKind.entries.firstOrNull { it.name == parts[1] } ?: return null
    val feed = MovieFeed.entries.firstOrNull { it.name == parts[2] } ?: return null
    return Shelf(parts[0], kind, feed, parts[3].takeIf(String::isNotBlank))
}

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
    playFeatured: ((MovieTitle, MovieEpisode?) -> Unit)? = null,
    toggleSaved: ((MovieTitle) -> Unit)? = null,
    openDownloads: (() -> Unit)? = null,
    history: List<WatchProgress> = recent,
    hideMature: Boolean = false,
    savedOrder: Map<TitleRef, Long> = emptyMap(),
    tracking: Map<TitleRef, MovieWatchStatus> = emptyMap(),
) {
    var query by rememberSaveable { mutableStateOf("") }
    var collectionKey by rememberSaveable(page) { mutableStateOf<String?>(null) }
    val collection = collectionKey?.decodeShelf()
    BackHandler(collection != null) { collectionKey = null }
    fun go(page: BrowsePage) {
        collectionKey = null
        onPage(page)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) NavigationRail(containerColor = Color(0xFF18151E),
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0)) {
                navPages.forEach { item ->
                    NavigationRailItem(page == item, { go(item) }, { Icon(item.icon, null) }, label = { Text(item.label) })
                }
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = PagePadding, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val name = collection?.label
                    if (name == null) {
                        Text("NANAMI", color = MoviesAccent, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        Text(" MOVIES", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                    } else {
                        IconButton(onClick = { collectionKey = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to browse") }
                        Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(onClick = { go(if (page == BrowsePage.SEARCH) BrowsePage.HOME else BrowsePage.SEARCH) }) {
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
                    val section = collection
                    if (section != null) MovieCollection(section, catalog, hideMature, open)
                    else when (page) {
                        BrowsePage.HOME, BrowsePage.MOVIES, BrowsePage.SERIES -> BrowseShelves(page, catalog, recent, open, resume,
                            saved, playFeatured, toggleSaved, hideMature, wide) { collectionKey = it.encode() }
                        BrowsePage.LIST -> ResultsPage(saved, catalog, open, openDownloads, recent, history, savedOrder,
                            hideMature, resume, tracking)
                        BrowsePage.SEARCH -> SearchResults(query.trim(), catalog, open, hideMature)
                    }
                }

                if (!wide) NavigationBar(containerColor = Color(0xFF18151E), windowInsets = androidx.compose.foundation.layout.WindowInsets(0)) {
                    navPages.forEach { item ->
                        NavigationBarItem(
                            selected = page == item,
                            onClick = { go(item) },
                            icon = { Icon(item.icon, null) },
                            label = { Text(item.label, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFF36242C), selectedIconColor = MoviesAccent),
                        )
                    }
                }
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
    saved: Set<TitleRef>,
    playFeatured: ((MovieTitle, MovieEpisode?) -> Unit)?,
    toggleSaved: ((MovieTitle) -> Unit)?,
    hideMature: Boolean,
    wide: Boolean,
    seeAll: (Shelf) -> Unit,
) {
    val singleKind = when (page) {
        BrowsePage.MOVIES -> MovieKind.MOVIE
        BrowsePage.SERIES -> MovieKind.SERIES
        else -> null
    }
    val shelves = remember(page) {
        val kinds = singleKind?.let(::listOf) ?: MovieKind.entries
        buildList {
            for (kind in kinds) add(Shelf(if (kind == MovieKind.MOVIE) "Popular Movies" else "Popular TV Shows", kind))
            for (kind in kinds) add(Shelf(if (kind == MovieKind.MOVIE) "New Movies" else "New TV Shows", kind, MovieFeed.NEW))
            for (kind in kinds) add(Shelf(if (kind == MovieKind.MOVIE) "Animated Movies" else "Cartoons & Animation", kind, genre = "Animation"))
            for (kind in kinds) add(Shelf(if (kind == MovieKind.MOVIE) "Top-Rated Movies" else "Top-Rated TV Shows", kind, MovieFeed.TOP_RATED))
        }
    }
    val loaded = remember(page) { mutableStateMapOf<Shelf, List<MovieTitle>>() }
    var continueTitles by remember { mutableStateOf<List<MovieTitle>>(emptyList()) }
    var reload by remember(page) { mutableIntStateOf(0) }
    var starting by remember { mutableStateOf(false) }
    var shuffling by remember { mutableStateOf(false) }
    var shuffleMenu by remember { mutableStateOf(false) }
    var shuffleMessage by remember(page) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(shelves, reload) {
        loaded.clear()
        shelves.chunked(2).forEach { pair ->
            pair.map { spec ->
                async {
                    loaded[spec] = attempt { catalog.discover(spec.kind, spec.feed, genre = spec.genre).titles }
                        .getOrDefault(emptyList())
                }
            }.awaitAll()
        }
    }
    LaunchedEffect(page, recent) {
        continueTitles = if (page == BrowsePage.HOME) catalog.resolve(recent.map(WatchProgress::title)) else emptyList()
    }
    val current = shelves.mapNotNull { shelf ->
        loaded[shelf].orEmpty().visible(hideMature).takeIf { it.isNotEmpty() }?.let { shelf to it }
    }
    val stillLoading = loaded.size < shelves.size
    if (!stillLoading && current.isEmpty() && continueTitles.isEmpty()) {
        EmptyState("Couldn't load the catalog.") { reload++ }
        return
    }
    val featured = current.firstOrNull()?.second?.firstOrNull { it.backdrop != null && it.available }
    val pool = current.flatMap { it.second }.distinctBy(MovieTitle::ref).filter(MovieTitle::available)
    fun pickRandom(english: Boolean) {
        shuffleMenu = false
        val candidates = pool.shuffled()
        if (!english) {
            candidates.firstOrNull()?.let(open)
            return
        }
        scope.launch {
            shuffling = true
            shuffleMessage = null
            try {
                val match = candidates.take(9).chunked(3).firstNotNullOfOrNull { batch ->
                    batch.map { candidate ->
                        async { candidate.takeIf { attempt { catalog.hasListedEnglishAudio(it) }.getOrDefault(false) } }
                    }.awaitAll().firstOrNull { it != null }
                }
                if (match != null) open(match)
                else shuffleMessage = "Couldn't confirm an English-audio source in this selection."
            } finally {
                shuffling = false
            }
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(top = 7.dp, bottom = 24.dp)) {
        if (featured != null) item(key = "featured") {
            FeatureCard(featured, wide, featured.ref in saved, starting, { open(featured) },
                toggleSaved?.let { toggle -> { toggle(featured) } },
                playFeatured?.let { play ->
                    {
                        scope.launch {
                            starting = true
                            try {
                                val full = attempt { catalog.details(featured.kind, featured.imdbId) }.getOrNull()
                                val first = full?.episodes?.firstOrNull { it.season > 0 && it.available }
                                if (full == null || full.kind == MovieKind.SERIES && first == null) open(featured)
                                else play(full, first)
                            } finally {
                                starting = false
                            }
                        }
                        Unit
                    }
                })
        }
        val continuing = continueTitles.visible(hideMature)
        if (page == BrowsePage.HOME && continuing.isNotEmpty()) {
            item {
                val progress = recent.associateBy(WatchProgress::title)
                TitleShelf("Continue Watching", continuing, progress) { title ->
                    progress[title.ref]?.let { resume(title, it) }
                }
            }
        }
        if (pool.isNotEmpty()) item(key = "browse-tools") {
            Column {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = PagePadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        OutlinedButton(onClick = { shuffleMenu = true }, enabled = !shuffling, shape = TileShape) {
                            if (shuffling) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Surprise Me")
                        }
                        DropdownMenu(shuffleMenu, { shuffleMenu = false }) {
                            DropdownMenuItem(text = { Text("Random title") }, onClick = { pickRandom(false) })
                            DropdownMenuItem(text = { Text("Random with English audio") }, onClick = { pickRandom(true) })
                        }
                    }
                    if (singleKind != null) genres.forEach { name ->
                        FilterChip(false, { seeAll(Shelf(name, singleKind, genre = name)) }, label = { Text(name) })
                    }
                }
                shuffleMessage?.let { Text(it, Modifier.padding(horizontal = PagePadding), style = MaterialTheme.typography.bodySmall) }
            }
        }
        items(current, key = { it.first.label }) { (shelf, titles) ->
            TitleShelf(shelf.label, titles.take(22), more = { seeAll(shelf) }, open = open)
        }
        if (stillLoading) item(key = "loading") {
            Box(Modifier.fillMaxWidth().height(if (current.isEmpty()) 320.dp else 58.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(26.dp))
            }
        }
    }
}

@Composable
private fun SearchResults(query: String, catalog: MoviesCatalog, open: (MovieTitle) -> Unit, hideMature: Boolean) {
    var result by remember(query) { mutableStateOf<Result<List<MovieTitle>>?>(null) }
    var retry by remember(query) { mutableIntStateOf(0) }
    var kind by rememberSaveable { mutableStateOf<MovieKind?>(null) }
    var sorting by rememberSaveable { mutableStateOf(PosterSort.ADDED) }
    LaunchedEffect(query, retry) {
        result = null
        if (query.length >= 2) {
            delay(290)
            result = attempt { catalog.search(query) }
        } else {
            result = attempt { catalog.popular(MovieKind.MOVIE).take(12) + catalog.popular(MovieKind.SERIES).take(12) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ListFilters(kind, { kind = it }, sorting, { sorting = it }, if (query.length >= 2) "Relevance" else "Popular")
        when {
            result == null -> Loading()
            result?.isFailure == true -> EmptyState("Search is temporarily unavailable.") { retry++ }
            else -> {
                val visible = result?.getOrNull().orEmpty().visible(hideMature).filter { kind == null || it.kind == kind }
                ResultItems(sorting.sort(visible, emptyMap()), "No matching movies or TV shows.", open)
            }
        }
    }
}

private enum class LibraryTab(val label: String) {
    SAVED("My List"), CONTINUE("Continue Watching"), WATCHED("Watched"), STATUS("Watch Status"), UPCOMING("Upcoming"),
}

@Composable
private fun ResultsPage(
    saved: Set<TitleRef>,
    catalog: MoviesCatalog,
    open: (MovieTitle) -> Unit,
    openDownloads: (() -> Unit)?,
    recent: List<WatchProgress>,
    history: List<WatchProgress>,
    savedOrder: Map<TitleRef, Long>,
    hideMature: Boolean,
    resume: (MovieTitle, WatchProgress) -> Unit,
    tracking: Map<TitleRef, MovieWatchStatus>,
) {
    var tab by rememberSaveable { mutableStateOf(LibraryTab.SAVED) }
    var kind by rememberSaveable { mutableStateOf<MovieKind?>(null) }
    var sorting by rememberSaveable { mutableStateOf(PosterSort.ADDED) }
    var watchStatus by rememberSaveable { mutableStateOf(MovieWatchStatus.WATCHING) }
    val watched = remember(history) { history.filter(WatchProgress::completed).distinctBy(WatchProgress::title) }
    val planned = saved + tracking.filterValues { it == MovieWatchStatus.PLAN }.keys
    val refs = when (tab) {
        LibraryTab.SAVED -> planned.toList()
        LibraryTab.CONTINUE -> recent.map(WatchProgress::title)
        LibraryTab.WATCHED -> (watched.map(WatchProgress::title) +
            tracking.filterValues { it == MovieWatchStatus.COMPLETED }.keys).distinct()
        LibraryTab.STATUS -> if (watchStatus == MovieWatchStatus.PLAN) planned.toList()
            else tracking.filterValues { it == watchStatus }.keys.toList()
        LibraryTab.UPCOMING -> (planned + recent.map(WatchProgress::title) +
            tracking.filterValues { it == MovieWatchStatus.WATCHING }.keys).toList()
    }
    val order = when (tab) {
        LibraryTab.SAVED, LibraryTab.STATUS -> savedOrder
        LibraryTab.CONTINUE -> recent.associate { it.title to it.updated }
        LibraryTab.WATCHED -> watched.associate { it.title to it.updated }
        LibraryTab.UPCOMING -> emptyMap()
    }
    var titles by remember(refs) { mutableStateOf<List<MovieTitle>?>(null) }
    LaunchedEffect(refs) { titles = catalog.resolve(refs) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = PagePadding),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (openDownloads != null) OutlinedButton(onClick = openDownloads, shape = TileShape) {
                Icon(Icons.Outlined.Download, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("Downloads")
            }
            LibraryTab.entries.forEach { item ->
                FilterChip(tab == item, { tab = item }, label = { Text(item.label) })
            }
        }
        if (tab == LibraryTab.STATUS) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = PagePadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MovieWatchStatus.entries.forEach { status ->
                    FilterChip(watchStatus == status, { watchStatus = status }, label = { Text(status.label) })
                }
            }
        }
        if (tab != LibraryTab.UPCOMING) ListFilters(kind, { kind = it }, sorting, { sorting = it },
            if (tab == LibraryTab.SAVED || tab == LibraryTab.STATUS) "Recently added" else "Recently watched")
        if (titles == null && refs.isNotEmpty()) Loading()
        else if (tab == LibraryTab.UPCOMING) UpcomingReleases(titles.orEmpty().visible(hideMature), open)
        else ResultItems(sorting.sort(titles.orEmpty().visible(hideMature).filter { kind == null || it.kind == kind }, order),
            when (tab) {
                LibraryTab.SAVED -> "Save a movie or show to find it here."
                LibraryTab.CONTINUE -> "Pick up a movie or episode you’ve started."
                LibraryTab.WATCHED -> "Completed movies and episodes will appear here."
                LibraryTab.STATUS -> "Change a title’s watch status from its details."
                LibraryTab.UPCOMING -> "No upcoming releases listed."
            }, open = { title ->
            val savedProgress = recent.firstOrNull { it.title == title.ref }
            if (tab == LibraryTab.CONTINUE && savedProgress != null) resume(title, savedProgress)
            else open(title)
        })
    }
}

@Composable
private fun UpcomingReleases(titles: List<MovieTitle>, open: (MovieTitle) -> Unit) {
    val upcoming = remember(titles) {
        titles.mapNotNull { title ->
            val episode = title.episodes.filterNot(MovieEpisode::available).minByOrNull { it.releasedAt ?: java.time.Instant.MAX }
            val whenReleased = if (title.kind == MovieKind.MOVIE && !title.available) title.releasedAt else episode?.releasedAt
            whenReleased?.let { Triple(title, episode, it) }
        }.sortedBy { it.third }
    }
    if (upcoming.isEmpty()) {
        EmptyState("Upcoming episodes and movies from My List will appear here.")
        return
    }
    val date = remember { java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .withZone(java.time.ZoneId.systemDefault()) }
    LazyColumn(contentPadding = PaddingValues(PagePadding), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        items(upcoming, key = { it.first.ref.key }) { (title, episode, released) ->
            Row(Modifier.fillMaxWidth().clip(TileShape).clickable { open(title) },
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(episode?.thumbnail ?: title.backdrop ?: title.poster, Modifier.width(110.dp).aspectRatio(16f / 9f).clip(TileShape))
                Column(Modifier.weight(1f)) {
                    Text(title.title, style = MaterialTheme.typography.titleMedium)
                    if (episode != null) Text("S${episode.season} E${episode.episode} · ${episode.title}", maxLines = 2)
                    Text(date.format(released), color = MoviesAccent, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private enum class PosterSort(val label: String) {
    ADDED("Recently added"), RELEASE("Newest release"), RATING_RELEASE("IMDb rating, newest release"),
    RATING_ADDED("IMDb rating, recently added"), RATING_TITLE("IMDb rating, title A–Z"), TITLE("Title A–Z");

    fun sort(titles: List<MovieTitle>, order: Map<TitleRef, Long>): List<MovieTitle> {
        val added = compareByDescending<MovieTitle> { order[it.ref] ?: 0 }
        val date = compareByDescending<MovieTitle> { it.year?.take(4)?.toIntOrNull() ?: 0 }
        val rating = compareByDescending<MovieTitle> { it.rating ?: -1.0 }
        val name = compareBy<MovieTitle> { it.title.lowercase() }
        return titles.sortedWith(when (this) {
            ADDED -> added
            RELEASE -> date.then(added)
            RATING_RELEASE -> rating.then(date)
            RATING_ADDED -> rating.then(added)
            RATING_TITLE -> rating.then(name)
            TITLE -> name
        })
    }
}

@Composable
private fun ListFilters(kind: MovieKind?, onKind: (MovieKind?) -> Unit, sort: PosterSort, onSort: (PosterSort) -> Unit, default: String) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = PagePadding), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(kind == null, { onKind(null) }, label = { Text("All") })
            FilterChip(kind == MovieKind.MOVIE, { onKind(MovieKind.MOVIE) }, label = { Text("Movies") })
            FilterChip(kind == MovieKind.SERIES, { onKind(MovieKind.SERIES) }, label = { Text("TV Shows") })
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort titles") }
            DropdownMenu(menu, { menu = false }) {
                PosterSort.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(if (option == PosterSort.ADDED) default else option.label) },
                        onClick = { menu = false; onSort(option) },
                        trailingIcon = if (option == sort) { { Icon(Icons.Default.Check, null) } } else null)
                }
            }
        }
    }
}

@Composable
private fun MovieCollection(shelf: Shelf, catalog: MoviesCatalog, hideMature: Boolean, open: (MovieTitle) -> Unit) {
    var titles by remember(shelf) { mutableStateOf(emptyList<MovieTitle>()) }
    var offset by remember(shelf) { mutableStateOf<Int?>(0) }
    var request by remember(shelf) { mutableIntStateOf(0) }
    var loading by remember(shelf) { mutableStateOf(true) }
    var error by remember(shelf) { mutableStateOf(false) }
    LaunchedEffect(shelf, request) {
        val start = offset ?: return@LaunchedEffect
        loading = true
        error = false
        attempt { catalog.discover(shelf.kind, shelf.feed, start, shelf.genre) }
            .onSuccess { next ->
                titles = (titles + next.titles).distinctBy(MovieTitle::ref)
                offset = next.nextOffset
            }
            .onFailure { error = true }
        loading = false
    }
    val visible = titles.visible(hideMature)
    when {
        loading && visible.isEmpty() -> Loading()
        error && visible.isEmpty() -> EmptyState("Couldn't load this collection.") { request++ }
        else -> ResultItems(visible, "No titles in this collection.", open, footer = if (offset != null) {
            {
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                else TextButton(onClick = { request++ }) { Text(if (error) "Try again" else "Load more") }
            }
        } else null)
    }
}

@Composable
private fun ResultItems(titles: List<MovieTitle>, empty: String, open: (MovieTitle) -> Unit, footer: (@Composable () -> Unit)? = null) {
    if (titles.isEmpty() && footer == null) {
        EmptyState(empty)
        return
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(105.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = PagePadding, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        items(titles, key = { it.ref.key }) { title ->
            Column(Modifier.fillMaxWidth().clip(TileShape).clickable { open(title) }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Artwork(title.poster, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(TileShape))
                Text(title.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text(title.subtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (footer != null) item(span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { footer() }
        }
    }
}

@Composable
private fun FeatureCard(
    title: MovieTitle,
    wide: Boolean,
    saved: Boolean,
    starting: Boolean,
    onOpen: () -> Unit,
    toggleSaved: (() -> Unit)?,
    onPlay: (() -> Unit)?,
) {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = PagePadding).height(if (wide) 406.dp else 412.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen),
    ) {
        Artwork(title.backdrop ?: title.poster, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Transparent, 0.25f to Color(0x13100F14), 0.6f to Color(0x89100F14),
            1f to Color(0xFA100F14))))
        Column(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp, vertical = 19.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title.title, color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(title.subtitle(), color = Color(0xFFF1EAF1), style = MaterialTheme.typography.labelLarge)
            title.overview?.let { Text(it, color = Color(0xFFF1EAF1), style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onPlay != null) Button(onPlay, enabled = !starting, shape = TileShape,
                    colors = ButtonDefaults.buttonColors(containerColor = WhiteButton, contentColor = Color(0xFF100D15))) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text(if (starting) "Opening…" else "Play", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onOpen, shape = TileShape) { Text("Details", color = Color.White) }
                if (toggleSaved != null) IconButton(onClick = toggleSaved) {
                    Icon(if (saved) Icons.Default.Check else Icons.Default.Add,
                        if (saved) "Remove from My List" else "Add to My List", tint = Color.White)
                }
            }
        }
    }
}

@Composable
internal fun TitleShelf(
    label: String,
    titles: List<MovieTitle>,
    progress: Map<TitleRef, WatchProgress> = emptyMap(),
    more: (() -> Unit)? = null,
    open: (MovieTitle) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = PagePadding), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (more != null) TextButton(onClick = more) { Text("See all") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = PagePadding), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(titles, key = { it.ref.key }) { title ->
                val item = progress[title.ref]
                Column(
                    Modifier.width(if (item == null) 118.dp else 180.dp).clip(TileShape).clickable { open(title) },
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    val art = if (item == null) title.poster else title.backdrop ?: title.poster
                    Box(Modifier.fillMaxWidth().aspectRatio(if (item == null) 2f / 3f else 16f / 9f).clip(TileShape)) {
                        Artwork(art, Modifier.fillMaxSize())
                        if (item != null) Icon(Icons.Default.PlayArrow, null,
                            Modifier.align(Alignment.Center).size(38.dp).background(Color(0xB5000000), RoundedCornerShape(50))
                                .padding(6.dp), tint = Color.White)
                    }
                    if (item != null) LinearProgressIndicator(
                        progress = { (item.position.toFloat() / item.duration).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(title.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    if (item?.season != null && item.episode != null) {
                        Text("S${item.season} E${item.episode}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun Artwork(url: String?, modifier: Modifier) {
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

internal val MovieTitle.available: Boolean get() = releasedAt?.isAfter(Instant.now()) != true
internal val MovieEpisode.available: Boolean get() = releasedAt?.isAfter(Instant.now()) != true
internal fun List<MovieTitle>.visible(hideMature: Boolean): List<MovieTitle> = if (hideMature) filterNot(MovieTitle::isMature) else this
internal fun MovieTitle.subtitle(): String = listOfNotNull(year, certification, runtime, genres.firstOrNull()).joinToString("  ·  ")

private suspend fun MoviesCatalog.resolve(refs: List<TitleRef>): List<MovieTitle> = kotlinx.coroutines.coroutineScope {
    val limit = Semaphore(6)
    refs.map { ref -> async { limit.withPermit { attempt { details(ref.kind, ref.imdbId) }.getOrNull() } } }
        .mapNotNull { it.await() }
}

internal suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: Exception) {
    if (error is CancellationException) throw error
    Result.failure(error)
}
