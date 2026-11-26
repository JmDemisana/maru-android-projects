package io.maru.movies

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.time.Instant
import java.util.Locale

internal val CardBackground = NetflixCardBg
internal val WhiteButton = NetflixWhite
internal val TileShape = RoundedCornerShape(6.dp)
internal val PagePadding = 16.dp

private enum class HomeFilter(val label: String) {
    ALL("All"),
    SERIES("TV Shows"),
    MOVIES("Movies"),
    CARTOONS("Cartoons"),
}

private val browseCategories = listOf(
    "For You",
    "Trending Now",
    "New Releases",
    "Top Rated",
    "Cartoons & Animation",
    "Action",
    "Sci-Fi",
    "Comedy",
    "Thriller",
    "Horror",
    "Romance",
    "Drama",
    "Documentary",
    "Family",
)

private data class Shelf(
    val label: String,
    val kind: MovieKind,
    val feed: MovieFeed = MovieFeed.POPULAR,
    val genre: String? = null,
) {
    fun encode(): String = listOf(label, kind.name, feed.name, genre.orEmpty()).joinToString("|")
}

private fun String.decodeShelf(): Shelf? {
    val parts = split('|')
    if (parts.size != 4) return null
    val kind = MovieKind.entries.firstOrNull { it.name == parts[1] } ?: return null
    val feed = MovieFeed.entries.firstOrNull { it.name == parts[2] } ?: return null
    return Shelf(parts[0], kind, feed, parts[3].takeIf(String::isNotBlank))
}

internal enum class PosterSort(val displayName: String) {
    ADDED("Recently Added"),
    RELEASE("Newest Release"),
    RATING_RELEASE("Rating, Then Released"),
    RATING_ADDED("Rating, Then Added"),
    RATING_TITLE("Rating, Then A-Z"),
    TITLE("Alphabetical (A-Z)");

    fun sort(titles: List<MovieTitle>, order: Map<TitleRef, Long>, userRatings: Map<TitleRef, Int> = emptyMap()): List<MovieTitle> {
        val added = compareByDescending<MovieTitle> { order[it.ref] ?: 0L }
        val date = compareByDescending<MovieTitle> { it.year?.take(4)?.toIntOrNull() ?: 0 }
        val effectiveScore = compareByDescending<MovieTitle> { item ->
            userRatings[item.ref]?.takeIf { it > 0 }?.toDouble() ?: item.rating ?: -1.0
        }
        val name = compareBy<MovieTitle> { it.title.lowercase(Locale.ROOT) }
        return titles.sortedWith(
            when (this) {
                ADDED -> added.then(name)
                RELEASE -> date.then(added)
                RATING_RELEASE -> effectiveScore.then(date)
                RATING_ADDED -> effectiveScore.then(added)
                RATING_TITLE -> effectiveScore.then(name)
                TITLE -> name
            }
        )
    }
}

// ============================================================================
// 1. NETFLIX HOME SCREEN (Billboard Pager + Top 10 + Continue Watching + Lanes)
// ============================================================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoviesHomeScreen(
    catalog: MoviesCatalog,
    saved: Set<TitleRef>,
    recent: List<WatchProgress>,
    ratings: Map<TitleRef, Int>,
    history: List<WatchProgress>,
    hideMature: Boolean,
    open: (MovieTitle) -> Unit,
    resume: (MovieTitle, WatchProgress) -> Unit,
    playFeatured: (MovieTitle, MovieEpisode?) -> Unit,
    toggleSaved: (MovieTitle) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val posterWidth = if (LocalConfiguration.current.screenWidthDp < 360) 124.dp else 138.dp

    var filter by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
    var collectionKey by rememberSaveable { mutableStateOf<String?>(null) }
    val activeCollection = collectionKey?.decodeShelf()
    BackHandler(activeCollection != null) { collectionKey = null }

    if (activeCollection != null) {
        MovieCollectionScreen(
            shelf = activeCollection,
            catalog = catalog,
            ratings = ratings,
            history = history,
            hideMature = hideMature,
            onBack = { collectionKey = null },
            open = open,
        )
        return
    }

    val shelves = remember(filter) {
        when (filter) {
            HomeFilter.ALL -> listOf(
                Shelf("Trending Movies", MovieKind.MOVIE, MovieFeed.POPULAR),
                Shelf("Trending TV Shows", MovieKind.SERIES, MovieFeed.POPULAR),
                Shelf("New Movie Releases", MovieKind.MOVIE, MovieFeed.NEW),
                Shelf("New TV Series", MovieKind.SERIES, MovieFeed.NEW),
                Shelf("Cartoons & Western Animation", MovieKind.SERIES, MovieFeed.POPULAR, genre = "Animation"),
                Shelf("Animated Feature Films", MovieKind.MOVIE, MovieFeed.POPULAR, genre = "Animation"),
                Shelf("Critically Acclaimed Films", MovieKind.MOVIE, MovieFeed.TOP_RATED),
                Shelf("Top-Rated TV Shows", MovieKind.SERIES, MovieFeed.TOP_RATED),
            )
            HomeFilter.MOVIES -> listOf(
                Shelf("Popular Movies", MovieKind.MOVIE, MovieFeed.POPULAR),
                Shelf("New Releases", MovieKind.MOVIE, MovieFeed.NEW),
                Shelf("Action Blockbusters", MovieKind.MOVIE, MovieFeed.POPULAR, genre = "Action"),
                Shelf("Sci-Fi & Fantasy", MovieKind.MOVIE, MovieFeed.POPULAR, genre = "Sci-Fi"),
                Shelf("Animated Films", MovieKind.MOVIE, MovieFeed.POPULAR, genre = "Animation"),
                Shelf("Critically Acclaimed", MovieKind.MOVIE, MovieFeed.TOP_RATED),
            )
            HomeFilter.SERIES -> listOf(
                Shelf("Popular TV Shows", MovieKind.SERIES, MovieFeed.POPULAR),
                Shelf("New Episodes & Seasons", MovieKind.SERIES, MovieFeed.NEW),
                Shelf("Drama Series", MovieKind.SERIES, MovieFeed.POPULAR, genre = "Drama"),
                Shelf("Sci-Fi & Mystery", MovieKind.SERIES, MovieFeed.POPULAR, genre = "Sci-Fi"),
                Shelf("Cartoons & Animation", MovieKind.SERIES, MovieFeed.POPULAR, genre = "Animation"),
                Shelf("All-Time Top Series", MovieKind.SERIES, MovieFeed.TOP_RATED),
            )
            HomeFilter.CARTOONS -> listOf(
                Shelf("Popular Cartoons & Series", MovieKind.SERIES, MovieFeed.POPULAR, genre = "Animation"),
                Shelf("Animated Movies", MovieKind.MOVIE, MovieFeed.POPULAR, genre = "Animation"),
                Shelf("Family Favorites", MovieKind.MOVIE, MovieFeed.POPULAR, genre = "Family"),
                Shelf("Family TV Shows", MovieKind.SERIES, MovieFeed.POPULAR, genre = "Family"),
            )
        }
    }

    val loaded = remember(filter) { mutableStateMapOf<Shelf, List<MovieTitle>>() }
    var continueTitles by remember { mutableStateOf<List<MovieTitle>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var startingHeroId by remember { mutableStateOf<String?>(null) }
    var shuffling by remember { mutableStateOf(false) }
    var shuffleMenu by remember { mutableStateOf(false) }

    val watchedRefs = remember(history) {
        history.filter(WatchProgress::completed).mapTo(mutableSetOf()) { it.title }
    }

    LaunchedEffect(shelves, reload) {
        if (reload > 0) isRefreshing = true
        loaded.clear()
        try {
            shelves.chunked(2).forEach { pair ->
                pair.map { spec ->
                    async {
                        loaded[spec] = attempt { catalog.discover(spec.kind, spec.feed, genre = spec.genre).titles }
                            .getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
        } finally {
            isRefreshing = false
        }
    }

    LaunchedEffect(recent, reload) {
        continueTitles = if (recent.isNotEmpty()) catalog.resolve(recent.map(WatchProgress::title)) else emptyList()
    }

    val currentShelves = shelves.mapNotNull { shelf ->
        loaded[shelf].orEmpty().visible(hideMature).takeIf { it.isNotEmpty() }?.let { shelf to it }
    }
    val stillLoading = loaded.size < shelves.size
    val pool = remember(currentShelves) {
        currentShelves.flatMap { it.second }.distinctBy(MovieTitle::ref).filter(MovieTitle::available)
    }
    val spotlightItems = remember(currentShelves) {
        currentShelves.flatMap { it.second }
            .distinctBy(MovieTitle::ref)
            .filter { (!it.backdrop.isNullOrBlank() || !it.poster.isNullOrBlank()) && it.available }
            .take(5)
    }
    val topTenItems = remember(currentShelves) {
        currentShelves.firstOrNull()?.second
            ?.filter(MovieTitle::available)
            ?.take(10)
            .orEmpty()
    }

    fun pickRandom(englishOnly: Boolean) {
        shuffleMenu = false
        val candidates = pool.shuffled()
        if (!englishOnly) {
            candidates.firstOrNull()?.let(open)
            return
        }
        scope.launch {
            shuffling = true
            try {
                val match = candidates.take(9).chunked(3).firstNotNullOfOrNull { batch ->
                    batch.map { candidate ->
                        async { candidate.takeIf { attempt { catalog.hasListedEnglishAudio(it) }.getOrDefault(false) } }
                    }.awaitAll().firstOrNull { it != null }
                }
                if (match != null) open(match)
                else Toast.makeText(context, "Couldn't confirm English audio in this row; try another filter.", Toast.LENGTH_SHORT).show()
            } finally {
                shuffling = false
            }
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { reload++ },
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixBlack),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // 0. Netflix Top Filter Pills + Surprise Me
            item(key = "netflix_filter_bar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = PagePadding, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomeFilter.entries.forEach { item ->
                        NetflixChip(
                            label = item.label,
                            selected = filter == item,
                            onClick = { filter = item },
                        )
                    }
                    if (pool.isNotEmpty()) {
                        Box {
                            NetflixChip(
                                label = if (shuffling) "Picking…" else "Surprise Me",
                                selected = false,
                                leadingIcon = Icons.Default.Shuffle,
                                onClick = { if (!shuffling) shuffleMenu = true },
                            )
                            DropdownMenu(
                                expanded = shuffleMenu,
                                onDismissRequest = { shuffleMenu = false },
                                containerColor = NetflixSurface,
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Random Title", color = Color.White, fontWeight = FontWeight.SemiBold) },
                                    onClick = { pickRandom(false) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Random English Audio", color = NetflixMatchGreen, fontWeight = FontWeight.Bold) },
                                    onClick = { pickRandom(true) },
                                )
                            }
                        }
                    }
                }
            }

            // 1. Netflix Billboard Hero Carousel
            if (spotlightItems.isNotEmpty()) {
                item(key = "netflix_billboard_${filter.name}") {
                    val pagerState = rememberPagerState(pageCount = { spotlightItems.size })
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HorizontalPager(
                            state = pagerState,
                            contentPadding = PaddingValues(horizontal = PagePadding),
                            pageSpacing = 12.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(430.dp),
                        ) { page ->
                            val hero = spotlightItems[page]
                            val isHeroSaved = hero.ref in saved
                            val isStarting = startingHeroId == hero.ref.key
                            NetflixHeroBillboard(
                                title = hero,
                                saved = isHeroSaved,
                                starting = isStarting,
                                onOpen = { open(hero) },
                                onToggleSave = { toggleSaved(hero) },
                                onPlay = {
                                    if (isStarting) return@NetflixHeroBillboard
                                    scope.launch {
                                        startingHeroId = hero.ref.key
                                        try {
                                            val full = attempt { catalog.details(hero.kind, hero.imdbId) }.getOrNull()
                                            val first = full?.episodes?.firstOrNull { it.season > 0 && it.available }
                                            if (full == null || (full.kind == MovieKind.SERIES && first == null)) open(hero)
                                            else playFeatured(full, first)
                                        } finally {
                                            startingHeroId = null
                                        }
                                    }
                                },
                            )
                        }

                        if (spotlightItems.size > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                repeat(spotlightItems.size) { idx ->
                                    val active = pagerState.currentPage == idx
                                    val dotWidth by animateDpAsState(
                                        targetValue = if (active) 20.dp else 6.dp,
                                        animationSpec = tween(200),
                                        label = "hero_dot_w",
                                    )
                                    val dotColor by animateColorAsState(
                                        targetValue = if (active) NetflixRed else NetflixBorder,
                                        animationSpec = tween(200),
                                        label = "hero_dot_c",
                                    )
                                    Box(
                                        modifier = Modifier
                                            .padding(horizontal = 3.dp)
                                            .width(dotWidth)
                                            .height(5.dp)
                                            .background(dotColor, CircleShape),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Continue Watching 16:9 Lane (Works in Guest Mode & SIMKL Mode!)
            val continuing = continueTitles.visible(hideMature).filter { title ->
                when (filter) {
                    HomeFilter.MOVIES -> title.kind == MovieKind.MOVIE
                    HomeFilter.SERIES -> title.kind == MovieKind.SERIES
                    HomeFilter.CARTOONS -> title.genres.any { it.equals("Animation", true) }
                    HomeFilter.ALL -> true
                }
            }
            if (continuing.isNotEmpty()) {
                item(key = "lane_continue_watching") {
                    val progressMap = remember(recent) { recent.associateBy(WatchProgress::title) }
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Continue Watching",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = PagePadding),
                        )
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PagePadding),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(continuing, key = { "cont_${it.ref.key}" }) { title ->
                                val prog = progressMap[title.ref]
                                if (prog != null) {
                                    NetflixContinueCard(
                                        title = title,
                                        progress = prog,
                                        onClick = { resume(title, prog) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Netflix Top 10 Row with Iconic Oversized Rank Numbers
            if (topTenItems.size >= 5) {
                item(key = "lane_top_10_${filter.name}") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.padding(horizontal = PagePadding),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Surface(
                                color = NetflixRed,
                                shape = RoundedCornerShape(3.dp),
                            ) {
                                Text(
                                    text = "TOP 10",
                                    color = Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            Text(
                                text = when (filter) {
                                    HomeFilter.MOVIES -> "Movies Today"
                                    HomeFilter.SERIES -> "TV Shows Today"
                                    HomeFilter.CARTOONS -> "Cartoons Today"
                                    HomeFilter.ALL -> "Trending Today"
                                },
                                fontSize = 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PagePadding),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            itemsIndexed(topTenItems, key = { _, item -> "top10_${item.ref.key}" }) { index, item ->
                                NetflixPosterCard(
                                    title = item,
                                    userRating = ratings[item.ref] ?: 0,
                                    watched = item.ref in watchedRefs,
                                    rankNumber = index + 1,
                                    onClick = { open(item) },
                                    modifier = Modifier.width(156.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 4. All Curated Shelves
            items(currentShelves, key = { it.first.encode() }) { (shelf, titles) ->
                TitleShelf(
                    label = shelf.label,
                    titles = titles.take(22),
                    ratings = ratings,
                    watchedRefs = watchedRefs,
                    posterWidth = posterWidth,
                    more = { collectionKey = shelf.encode() },
                    open = open,
                )
            }

            if (stillLoading) {
                item(key = "home_loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (currentShelves.isEmpty()) 280.dp else 56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = NetflixRed, modifier = Modifier.size(28.dp))
                    }
                }
            } else if (currentShelves.isEmpty() && continuing.isEmpty()) {
                item(key = "home_empty") {
                    EmptyState("Couldn't load the catalog right now.") { reload++ }
                }
            }
        }
    }
}

@Composable
private fun NetflixHeroBillboard(
    title: MovieTitle,
    saved: Boolean,
    starting: Boolean,
    onOpen: () -> Unit,
    onToggleSave: () -> Unit,
    onPlay: () -> Unit,
) {
    val matchPercent = remember(title.rating, title.imdbId) {
        val base = ((title.rating ?: 7.8) * 10.0).toInt()
        base.coerceIn(78, 99)
    }

    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(10.dp),
        color = NetflixCardBg,
        border = BorderStroke(1.dp, NetflixBorder),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            CrossfadeArtwork(
                model = title.poster ?: title.backdrop,
                contentDescription = title.title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x22000000),
                            0.42f to Color(0x44000000),
                            0.72f to Color(0xCC060608),
                            1f to Color(0xFA000000),
                        ),
                    ),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!title.logo.isNullOrBlank()) {
                    CrossfadeArtwork(
                        model = title.logo,
                        contentDescription = title.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(64.dp)
                            .fillMaxWidth(0.82f)
                            .background(Color.Transparent),
                    )
                } else {
                    Text(
                        text = title.title,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 28.sp,
                    )
                }

                // Genre & Metadata Ribbon
                val metaParts = remember(title) {
                    buildList {
                        add("$matchPercent% Match")
                        title.year?.take(4)?.let(::add)
                        title.certification?.let(::add)
                        add(if (title.kind == MovieKind.SERIES) "Series" else (title.runtime ?: "Movie"))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    metaParts.forEachIndexed { idx, part ->
                        Text(
                            text = part,
                            fontSize = 12.sp,
                            fontWeight = if (idx == 0) FontWeight.ExtraBold else FontWeight.SemiBold,
                            color = if (idx == 0) NetflixMatchGreen else Color(0xFFE4E6EE),
                        )
                        if (idx < metaParts.lastIndex) {
                            Text("•", fontSize = 11.sp, color = NetflixTextMuted)
                        }
                    }
                }

                if (title.genres.isNotEmpty()) {
                    Text(
                        text = title.genres.take(3).joinToString("  •  "),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFD0D3DF),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onPlay,
                        enabled = !starting,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(5.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NetflixWhite,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = if (starting) "Opening…" else "Play",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                        )
                    }

                    OutlinedButton(
                        onClick = onToggleSave,
                        modifier = Modifier.weight(1f).height(42.dp),
                        shape = RoundedCornerShape(5.dp),
                        border = BorderStroke(1.dp, Color(0x66FFFFFF)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0x661C1C22),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(
                            if (saved) Icons.Default.Check else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "My List",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                        )
                    }

                    Surface(
                        onClick = onOpen,
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0x661C1C22),
                        border = BorderStroke(1.dp, Color(0x55FFFFFF)),
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Info, contentDescription = "Details", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 2. MY LIST SCREEN (Downloads-First + Series Grouping + 6 Sort Options + Random)
// ============================================================================
private enum class MoviesLibraryCategory(val label: String) {
    DOWNLOADS("Downloads"),
    WATCHING("Watching"),
    PLAN("Plan to Watch"),
    COMPLETED("Completed"),
    HOLD("On Hold"),
    DROPPED("Dropped"),
    UPCOMING("Upcoming"),
}

@Composable
internal fun MoviesMyListScreen(
    saved: Set<TitleRef>,
    catalog: MoviesCatalog,
    recent: List<WatchProgress>,
    history: List<WatchProgress>,
    savedOrder: Map<TitleRef, Long>,
    ratings: Map<TitleRef, Int>,
    tracking: Map<TitleRef, MovieWatchStatus>,
    hideMature: Boolean,
    isLoggedIn: Boolean,
    pendingSyncCount: Int,
    isSyncingQueue: Boolean,
    onConnectClick: () -> Unit,
    open: (MovieTitle) -> Unit,
    resume: (MovieTitle, WatchProgress) -> Unit,
    playOffline: (MovieTitle, MovieEpisode?, File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = MoviesDownloadVault
    val allDownloads by vault.items.collectAsState()
    val visibleDownloads = remember(allDownloads, hideMature) {
        if (hideMature) allDownloads.filterNot { it.title.isMature } else allDownloads
    }

    var selectedCategory by rememberSaveable { mutableStateOf(MoviesLibraryCategory.DOWNLOADS) }
    var selectedOfflineSeriesImdb by rememberSaveable { mutableStateOf<String?>(null) }
    var kindFilter by rememberSaveable { mutableStateOf<MovieKind?>(null) }
    var sortOption by rememberSaveable {
        val savedName = MoviesPreferences.myListsSortOption(context)
        mutableStateOf(runCatching { PosterSort.valueOf(savedName) }.getOrDefault(PosterSort.ADDED))
    }
    var pickingEnglishRandom by remember { mutableStateOf(false) }

    val watched = remember(history) { history.filter(WatchProgress::completed).distinctBy(WatchProgress::title) }
    val watchedRefs = remember(watched, tracking) {
        (watched.map(WatchProgress::title) + tracking.filterValues { it == MovieWatchStatus.COMPLETED }.keys).toSet()
    }
    val plannedRefs = remember(saved, tracking) {
        (saved + tracking.filterValues { it == MovieWatchStatus.PLAN }.keys).distinct()
    }
    val watchingRefs = remember(recent, tracking) {
        (recent.map(WatchProgress::title) + tracking.filterValues { it == MovieWatchStatus.WATCHING }.keys).distinct()
    }
    val holdRefs = remember(tracking) { tracking.filterValues { it == MovieWatchStatus.HOLD }.keys.toList() }
    val droppedRefs = remember(tracking) { tracking.filterValues { it == MovieWatchStatus.DROPPED }.keys.toList() }
    val upcomingRefs = remember(plannedRefs, watchingRefs) { (plannedRefs + watchingRefs).distinct() }

    fun countFor(cat: MoviesLibraryCategory): Int = when (cat) {
        MoviesLibraryCategory.DOWNLOADS -> visibleDownloads.size
        MoviesLibraryCategory.WATCHING -> watchingRefs.size
        MoviesLibraryCategory.PLAN -> plannedRefs.size
        MoviesLibraryCategory.COMPLETED -> watchedRefs.size
        MoviesLibraryCategory.HOLD -> holdRefs.size
        MoviesLibraryCategory.DROPPED -> droppedRefs.size
        MoviesLibraryCategory.UPCOMING -> 0
    }

    val activeRefs = when (selectedCategory) {
        MoviesLibraryCategory.DOWNLOADS -> emptyList()
        MoviesLibraryCategory.WATCHING -> watchingRefs
        MoviesLibraryCategory.PLAN -> plannedRefs
        MoviesLibraryCategory.COMPLETED -> watchedRefs.toList()
        MoviesLibraryCategory.HOLD -> holdRefs
        MoviesLibraryCategory.DROPPED -> droppedRefs
        MoviesLibraryCategory.UPCOMING -> upcomingRefs
    }

    val orderMap = when (selectedCategory) {
        MoviesLibraryCategory.WATCHING -> recent.associate { it.title to it.updated } + savedOrder
        MoviesLibraryCategory.COMPLETED -> watched.associate { it.title to it.updated }
        else -> savedOrder
    }

    var resolvedTitles by remember(activeRefs) { mutableStateOf<List<MovieTitle>?>(null) }
    LaunchedEffect(activeRefs) {
        resolvedTitles = if (activeRefs.isEmpty()) emptyList() else catalog.resolve(activeRefs)
    }

    BackHandler(selectedCategory == MoviesLibraryCategory.DOWNLOADS && selectedOfflineSeriesImdb != null) {
        selectedOfflineSeriesImdb = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixBlack),
    ) {
        // Single-line scrolling category strip with Downloads FIRST (100% parity with Nanami Anime!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(NetflixBlack)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MoviesLibraryCategory.entries.forEach { cat ->
                val isSelected = selectedCategory == cat
                val count = countFor(cat)
                val textColor by animateColorAsState(
                    targetValue = if (isSelected) Color.White else NetflixTextMuted,
                    animationSpec = tween(160),
                    label = "list_tab_text",
                )
                val barColor by animateColorAsState(
                    targetValue = if (isSelected) NetflixRed else Color.Transparent,
                    animationSpec = tween(180),
                    label = "list_tab_bar",
                )

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .width(IntrinsicSize.Max)
                        .selectable(selected = isSelected, role = Role.Tab) {
                            selectedCategory = cat
                            selectedOfflineSeriesImdb = null
                        }
                        .padding(top = 8.dp),
                ) {
                    Text(
                        text = if (count > 0) "${cat.label} ($count)" else cat.label,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = textColor,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(barColor, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
                    )
                }
            }
        }

        HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

        // Non-blocking Offline Sync Queue or Guest Mode Banner (identical to Nanami Anime!)
        if (pendingSyncCount > 0 && isLoggedIn) {
            Surface(color = Color(0xFF14181E), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (isSyncingQueue) Icons.Default.Sync else Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = NetflixMatchGreen,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (isSyncingQueue) {
                            "Syncing $pendingSyncCount offline update${if (pendingSyncCount == 1) "" else "s"} to SIMKL…"
                        } else {
                            "$pendingSyncCount offline update${if (pendingSyncCount == 1) "" else "s"} queued • Will sync automatically when online"
                        },
                        fontSize = 11.5.sp,
                        color = NetflixMatchGreen,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            HorizontalDivider(color = NetflixBorder, thickness = 1.dp)
        } else if (!isLoggedIn && selectedCategory != MoviesLibraryCategory.DOWNLOADS) {
            Surface(color = NetflixSurface, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Managing lists on this device. Connect SIMKL anytime for cloud sync.",
                        fontSize = 11.5.sp,
                        color = NetflixTextMuted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onConnectClick) {
                        Text("Connect", color = NetflixRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            HorizontalDivider(color = NetflixBorder, thickness = 1.dp)
        }

        AnimatedContent(
            targetState = selectedCategory to selectedOfflineSeriesImdb,
            transitionSpec = { fadeIn(tween(170)) togetherWith fadeOut(tween(120)) },
            label = "movies_my_list_body",
            modifier = Modifier.fillMaxSize(),
        ) { (activeCat, drilldownImdb) ->
            when {
                activeCat == MoviesLibraryCategory.DOWNLOADS -> {
                    MoviesDownloadsVaultTab(
                        downloads = visibleDownloads,
                        selectedSeriesImdb = drilldownImdb,
                        onSelectSeriesImdb = { selectedOfflineSeriesImdb = it },
                        onOpenTitle = open,
                        onPlayOffline = playOffline,
                    )
                }
                activeCat == MoviesLibraryCategory.UPCOMING -> {
                    if (resolvedTitles == null && activeRefs.isNotEmpty()) Loading()
                    else UpcomingReleases(resolvedTitles.orEmpty().visible(hideMature), open)
                }
                else -> {
                    val baseTitles = resolvedTitles.orEmpty().visible(hideMature)
                        .filter { kindFilter == null || it.kind == kindFilter }
                    val sortedList = remember(baseTitles, sortOption, orderMap, ratings) {
                        sortOption.sort(baseTitles, orderMap, ratings)
                    }

                    if (resolvedTitles == null && activeRefs.isNotEmpty()) {
                        Loading()
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Sort Dropdown + Filter Chips + 1-Tap Random & Random English Buttons (matching Nanami Anime!)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = PagePadding, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box {
                                    var showSortMenu by remember { mutableStateOf(false) }
                                    Surface(
                                        onClick = { showSortMenu = true },
                                        shape = RoundedCornerShape(6.dp),
                                        color = NetflixCardBg,
                                        border = BorderStroke(1.dp, NetflixBorder),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        ) {
                                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort", tint = NetflixRed, modifier = Modifier.size(16.dp))
                                            Text(sortOption.displayName, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = NetflixTextMuted, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = showSortMenu,
                                        onDismissRequest = { showSortMenu = false },
                                        containerColor = NetflixSurface,
                                    ) {
                                        PosterSort.entries.forEach { opt ->
                                            val chosen = sortOption == opt
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = opt.displayName,
                                                        fontWeight = if (chosen) FontWeight.ExtraBold else FontWeight.Medium,
                                                        color = if (chosen) NetflixRed else Color.White,
                                                        fontSize = 12.5.sp,
                                                    )
                                                },
                                                leadingIcon = {
                                                    if (chosen) Icon(Icons.Default.Check, null, tint = NetflixRed, modifier = Modifier.size(16.dp))
                                                    else Spacer(Modifier.size(16.dp))
                                                },
                                                onClick = {
                                                    sortOption = opt
                                                    MoviesPreferences.setMyListsSortOption(context, opt.name)
                                                    showSortMenu = false
                                                },
                                            )
                                        }
                                    }
                                }

                                NetflixChip("All", kindFilter == null) { kindFilter = null }
                                NetflixChip("Movies", kindFilter == MovieKind.MOVIE) { kindFilter = MovieKind.MOVIE }
                                NetflixChip("TV Shows", kindFilter == MovieKind.SERIES) { kindFilter = MovieKind.SERIES }

                                if (sortedList.isNotEmpty()) {
                                    NetflixChip(
                                        label = "Random",
                                        selected = false,
                                        leadingIcon = Icons.Default.Shuffle,
                                        onClick = { sortedList.randomOrNull()?.let(open) },
                                    )
                                    NetflixChip(
                                        label = if (pickingEnglishRandom) "Checking…" else "Random English",
                                        selected = false,
                                        accent = NetflixMatchGreen,
                                        leadingIcon = Icons.Default.Shuffle,
                                        onClick = {
                                            if (pickingEnglishRandom) return@NetflixChip
                                            scope.launch {
                                                pickingEnglishRandom = true
                                                try {
                                                    val found = sortedList.shuffled().take(8).chunked(4).firstNotNullOfOrNull { batch ->
                                                        batch.map { item ->
                                                            async { item.takeIf { attempt { catalog.hasListedEnglishAudio(it) }.getOrDefault(false) } }
                                                        }.awaitAll().firstOrNull { it != null }
                                                    }
                                                    if (found != null) open(found)
                                                    else Toast.makeText(context, "No confirmed English audio title found in this list.", Toast.LENGTH_SHORT).show()
                                                } finally {
                                                    pickingEnglishRandom = false
                                                }
                                            }
                                        },
                                    )
                                }
                            }

                            if (sortedList.isEmpty()) {
                                EmptyState(
                                    when (activeCat) {
                                        MoviesLibraryCategory.WATCHING -> "Movies and shows you are currently watching appear here."
                                        MoviesLibraryCategory.PLAN -> "Tap + My List on any movie or show to save it here."
                                        MoviesLibraryCategory.COMPLETED -> "Completed movies and series appear here."
                                        MoviesLibraryCategory.HOLD -> "No titles marked On Hold yet."
                                        MoviesLibraryCategory.DROPPED -> "No titles marked Dropped."
                                        else -> "Nothing in this list yet."
                                    }
                                )
                            } else {
                                PosterGrid(
                                    titles = sortedList,
                                    ratings = ratings,
                                    watchedRefs = watchedRefs,
                                    open = { item ->
                                        val prog = recent.firstOrNull { it.title == item.ref }
                                        if (activeCat == MoviesLibraryCategory.WATCHING && prog != null) resume(item, prog)
                                        else open(item)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoviesDownloadsVaultTab(
    downloads: List<MovieDownload>,
    selectedSeriesImdb: String?,
    onSelectSeriesImdb: (String?) -> Unit,
    onOpenTitle: (MovieTitle) -> Unit,
    onPlayOffline: (MovieTitle, MovieEpisode?, File) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vault = MoviesDownloadVault
    var deleteCandidate by remember { mutableStateOf<MovieDownload?>(null) }

    if (downloads.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(28.dp),
            ) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(44.dp))
                Text("No Downloads Yet", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Download any movie or TV episode to watch privately offline in Nanami Movies.",
                    color = NetflixTextMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
        return
    }

    val seriesDownloads = remember(downloads, selectedSeriesImdb) {
        downloads.filter { it.title.imdbId == selectedSeriesImdb }
            .sortedWith(compareBy({ it.episode?.season ?: 0 }, { it.episode?.episode ?: 0 }))
    }

    if (selectedSeriesImdb != null && seriesDownloads.isNotEmpty()) {
        val parentTitle = seriesDownloads.first().title
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onSelectSeriesImdb(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Text(
                    text = parentTitle.title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenTitle(parentTitle) },
                )
                TextButton(onClick = { onOpenTitle(parentTitle) }) {
                    Text("Details", color = NetflixRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = PagePadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(seriesDownloads, key = MovieDownload::key) { item ->
                    VaultDownloadCard(
                        item = item,
                        onPlay = {
                            val file = vault.completedFile(context, item)
                            if (file != null) {
                                val savedEps = seriesDownloads
                                    .filter { it.state == MovieDownloadState.COMPLETE }
                                    .mapNotNull(MovieDownload::episode)
                                onPlayOffline(item.title.copy(episodes = savedEps), item.episode, file)
                            } else {
                                scope.launch {
                                    vault.markMissing(context, item)
                                    Toast.makeText(context, "Saved file missing; tap Retry to download again.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onCancel = { scope.launch { runCatching { vault.cancel(context, item.key) } } },
                        onRetry = { scope.launch { runCatching { vault.retry(context, item) } } },
                        onDelete = { deleteCandidate = item },
                    )
                }
            }
        }
    } else {
        val grouped = remember(downloads) { downloads.groupBy { it.title.imdbId }.values.toList() }
        val totalBytes = remember(downloads) {
            downloads.filter { it.state == MovieDownloadState.COMPLETE }.sumOf { it.progress?.total ?: 0L }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (totalBytes > 0L) {
                Text(
                    text = "${downloads.count { it.state == MovieDownloadState.COMPLETE }} saved offline • ${Formatter.formatFileSize(context, totalBytes)}",
                    fontSize = 12.sp,
                    color = NetflixTextMuted,
                    modifier = Modifier.padding(horizontal = PagePadding, vertical = 8.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(136.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = PagePadding, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(grouped, key = { "dl_grp_${it.first().title.imdbId}" }) { group ->
                    val first = group.first()
                    val readyCount = group.count { it.state == MovieDownloadState.COMPLETE }
                    val activeCount = group.count { it.isActive }
                    val subLabel = when {
                        activeCount > 0 -> "$readyCount/${group.size} Ready • Downloading…"
                        first.title.kind == MovieKind.MOVIE && readyCount > 0 -> {
                            val bytes = first.progress?.total ?: 0L
                            "Saved • ${Formatter.formatFileSize(context, bytes)}"
                        }
                        group.size == 1 -> "1 Episode Saved"
                        else -> "${group.size} Episodes Saved"
                    }
                    NetflixPosterCard(
                        title = first.title,
                        subtitleOverride = subLabel,
                        onClick = {
                            if (first.title.kind == MovieKind.MOVIE && group.size == 1 && first.state == MovieDownloadState.COMPLETE) {
                                val file = vault.completedFile(context, first)
                                if (file != null) onPlayOffline(first.title, null, file)
                                else onSelectSeriesImdb(first.title.imdbId)
                            } else {
                                onSelectSeriesImdb(first.title.imdbId)
                            }
                        },
                    )
                }
            }
        }
    }

    deleteCandidate?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            containerColor = NetflixSurface,
            title = { Text(if (target.state == MovieDownloadState.COMPLETE) "Delete Download?" else "Remove From Queue?", color = Color.White) },
            text = { Text(target.episode?.let { "${target.title.title} • ${target.label}" } ?: target.title.title, color = NetflixTextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    scope.launch {
                        runCatching { vault.delete(context, target) }
                        if (seriesDownloads.size <= 1) onSelectSeriesImdb(null)
                    }
                }) { Text("Remove", color = NetflixRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text("Keep", color = Color.White) }
            },
        )
    }
}

@Composable
private fun VaultDownloadCard(
    item: MovieDownload,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val rawPct = item.progress?.fraction ?: 0f
    val animPct by animateFloatAsState(targetValue = rawPct, animationSpec = tween(250), label = "vault_pct")
    val isComplete = item.state == MovieDownloadState.COMPLETE

    NetflixCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = isComplete, onClick = onPlay),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(124.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NetflixSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    CrossfadeArtwork(
                        model = item.episode?.thumbnail ?: item.title.backdrop ?: item.title.poster,
                        contentDescription = item.title.title,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (isComplete) {
                        Surface(
                            color = Color(0xCC000000),
                            shape = CircleShape,
                            border = BorderStroke(1.dp, Color.White),
                            modifier = Modifier.size(34.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(21.dp))
                            }
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = if (item.episode != null) "S${item.episode.season}:E${item.episode.episode} • ${item.episode.title}" else item.title.title,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val statusText = when (item.state) {
                        MovieDownloadState.COMPLETE -> item.progress?.total?.let { "Saved • ${Formatter.formatFileSize(context, it)}" } ?: "Saved offline"
                        MovieDownloadState.QUEUED -> "Queued for download"
                        MovieDownloadState.DOWNLOADING -> item.message ?: "Downloading ${(rawPct * 100).toInt()}%"
                        MovieDownloadState.FAILED -> item.message ?: "Download failed"
                        MovieDownloadState.CANCELLED -> "Canceled"
                    }
                    Text(
                        text = statusText,
                        fontSize = 11.5.sp,
                        color = when (item.state) {
                            MovieDownloadState.COMPLETE -> NetflixMatchGreen
                            MovieDownloadState.FAILED -> Color(0xFFFF8A80)
                            else -> NetflixRed
                        },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        item.isActive -> IconButton(onClick = onCancel) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                        }
                        isComplete -> IconButton(onClick = onDelete) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = NetflixTextMuted)
                        }
                        else -> {
                            IconButton(onClick = onRetry) {
                                Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = NetflixRed)
                            }
                            IconButton(onClick = onDelete) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove", tint = NetflixTextMuted)
                            }
                        }
                    }
                }
            }

            if (item.state == MovieDownloadState.DOWNLOADING) {
                Spacer(Modifier.height(8.dp))
                if (item.progress?.fraction == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = NetflixRed,
                        trackColor = NetflixBorder,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { animPct },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = NetflixRed,
                        trackColor = NetflixBorder,
                    )
                }
            }
        }
    }
}

// ============================================================================
// 3. UNIFIED SEARCH & EXPLORE HUB (Identical UX to Nanami Anime SearchScreen!)
// ============================================================================
@Composable
internal fun MoviesSearchScreen(
    catalog: MoviesCatalog,
    saved: Set<TitleRef>,
    recent: List<WatchProgress>,
    ratings: Map<TitleRef, Int>,
    isLoggedIn: Boolean,
    username: String,
    hideMature: Boolean,
    onConnectClick: () -> Unit,
    open: (MovieTitle) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    var query by rememberSaveable { mutableStateOf("") }
    var kindFilter by rememberSaveable { mutableStateOf<MovieKind?>(null) }
    var selectedCategory by rememberSaveable { mutableStateOf("For You") }

    val term = query.trim()
    var searchSubmit by remember(term) { mutableIntStateOf(0) }
    var searchResults by remember(term) { mutableStateOf<List<MovieTitle>>(emptyList()) }
    var isSearching by remember(term) { mutableStateOf(term.length >= 2) }
    var searchFailed by remember(term) { mutableStateOf(false) }

    var browseItems by remember(selectedCategory, kindFilter) { mutableStateOf<List<MovieTitle>>(emptyList()) }
    var isBrowseLoading by remember(selectedCategory, kindFilter) { mutableStateOf(true) }
    var browseFailed by remember(selectedCategory, kindFilter) { mutableStateOf(false) }
    var browseRetry by remember { mutableIntStateOf(0) }

    fun dismissKeyboard() {
        focusManager.clearFocus()
        keyboard?.hide()
    }

    LaunchedEffect(term, searchSubmit) {
        if (term.length < 2) {
            isSearching = false
            searchFailed = false
            return@LaunchedEffect
        }
        isSearching = true
        searchFailed = false
        if (searchSubmit == 0) delay(280)
        attempt { catalog.search(term) }
            .onSuccess { searchResults = it.distinctBy(MovieTitle::ref) }
            .onFailure { searchFailed = true }
        isSearching = false
    }

    val seedRefs = remember(saved, recent) {
        (recent.map(WatchProgress::title) + saved).distinct().take(6)
    }

    LaunchedEffect(term.isEmpty(), selectedCategory, kindFilter, browseRetry, seedRefs) {
        if (term.isNotEmpty()) return@LaunchedEffect
        isBrowseLoading = true
        browseFailed = false
        val kinds = kindFilter?.let(::listOf) ?: MovieKind.entries
        attempt {
            when (selectedCategory) {
                "For You" -> {
                    val seedGenre = if (seedRefs.isNotEmpty()) {
                        catalog.resolve(seedRefs.take(3)).flatMap(MovieTitle::genres).firstOrNull()
                    } else null
                    kinds.map { k -> async { catalog.discover(k, MovieFeed.POPULAR, genre = seedGenre).titles.take(20) } }
                        .awaitAll().flatten()
                }
                "Trending Now" -> kinds.map { k -> async { catalog.discover(k, MovieFeed.POPULAR).titles.take(20) } }.awaitAll().flatten()
                "New Releases" -> kinds.map { k -> async { catalog.discover(k, MovieFeed.NEW).titles.take(20) } }.awaitAll().flatten()
                "Top Rated" -> kinds.map { k -> async { catalog.discover(k, MovieFeed.TOP_RATED).titles.take(20) } }.awaitAll().flatten()
                "Cartoons & Animation" -> kinds.map { k -> async { catalog.discover(k, MovieFeed.POPULAR, genre = "Animation").titles.take(20) } }.awaitAll().flatten()
                else -> kinds.map { k -> async { catalog.discover(k, MovieFeed.POPULAR, genre = selectedCategory).titles.take(20) } }.awaitAll().flatten()
            }.distinctBy(MovieTitle::ref)
        }.onSuccess { browseItems = it }
            .onFailure { browseFailed = true }
        isBrowseLoading = false
    }

    Box(modifier = Modifier.fillMaxSize().background(NetflixBlack), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 980.dp)
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 14.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text(
                        text = "Search movies, TV shows, cartoons…",
                        color = NetflixTextMuted,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = NetflixRed) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", tint = Color.White)
                        }
                    }
                },
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = NetflixSurface,
                    unfocusedContainerColor = NetflixSurface,
                    focusedBorderColor = NetflixRed,
                    unfocusedBorderColor = NetflixBorder,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = NetflixRed,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    dismissKeyboard()
                    if (term.length >= 2) searchSubmit++
                }),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NetflixChip("All", kindFilter == null) { kindFilter = null }
                NetflixChip("Movies", kindFilter == MovieKind.MOVIE) { kindFilter = MovieKind.MOVIE }
                NetflixChip("TV Shows", kindFilter == MovieKind.SERIES) { kindFilter = MovieKind.SERIES }
            }

            AnimatedContent(
                targetState = term.length < 2,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "movies_search_or_browse",
                modifier = Modifier.fillMaxSize(),
            ) { showingBrowseHub ->
                if (showingBrowseHub) {
                    val visibleBrowse = browseItems.visible(hideMature).filter { kindFilter == null || it.kind == kindFilter }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(134.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "search_categories_bar") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(browseCategories, key = { it }) { cat ->
                                    NetflixChip(
                                        label = cat,
                                        selected = cat == selectedCategory,
                                        onClick = { selectedCategory = cat },
                                    )
                                }
                            }
                        }

                        item(span = { GridItemSpan(maxLineSpan) }, key = "search_browse_title") {
                            val heading = when {
                                selectedCategory != "For You" -> selectedCategory
                                isLoggedIn -> "For You, @$username"
                                seedRefs.isNotEmpty() -> "Recommended From Your List"
                                else -> "Top Picks on Nanami Movies"
                            }
                            Text(
                                text = heading,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        if (!isLoggedIn && seedRefs.isEmpty() && selectedCategory == "For You") {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "search_guest_hint") {
                                NetflixCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = "Save titles to My List or connect SIMKL to personalize For You.",
                                            color = NetflixTextMuted,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = onConnectClick) {
                                            Text("Connect", color = NetflixRed, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        when {
                            isBrowseLoading -> item(span = { GridItemSpan(maxLineSpan) }, key = "browse_spin") {
                                Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = NetflixRed)
                                }
                            }
                            browseFailed -> item(span = { GridItemSpan(maxLineSpan) }, key = "browse_err") {
                                EmptyState("Couldn't load this category.") { browseRetry++ }
                            }
                            visibleBrowse.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "browse_none") {
                                EmptyState("No titles matched in this category.")
                            }
                            else -> items(visibleBrowse, key = { "br_${it.ref.key}" }) { item ->
                                NetflixPosterCard(
                                    title = item,
                                    userRating = ratings[item.ref] ?: 0,
                                    onClick = {
                                        dismissKeyboard()
                                        open(item)
                                    },
                                )
                            }
                        }
                    }
                } else {
                    val visibleSearch = searchResults.visible(hideMature).filter { kindFilter == null || it.kind == kindFilter }
                    when {
                        isSearching -> Loading()
                        searchFailed -> EmptyState("Couldn't search right now.") { searchSubmit++ }
                        visibleSearch.isEmpty() -> EmptyState("No movies or TV shows found for “$term”.")
                        else -> PosterGrid(
                            titles = visibleSearch,
                            ratings = ratings,
                            watchedRefs = emptySet(),
                            open = {
                                dismissKeyboard()
                                open(it)
                            },
                            contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 4. SHARED SHELVES, COLLECTION DRILLDOWN & UPCOMING
// ============================================================================
@Composable
private fun MovieCollectionScreen(
    shelf: Shelf,
    catalog: MoviesCatalog,
    ratings: Map<TitleRef, Int>,
    history: List<WatchProgress>,
    hideMature: Boolean,
    onBack: () -> Unit,
    open: (MovieTitle) -> Unit,
) {
    var titles by remember(shelf) { mutableStateOf(emptyList<MovieTitle>()) }
    var offset by remember(shelf) { mutableStateOf<Int?>(0) }
    var request by remember(shelf) { mutableIntStateOf(0) }
    var loading by remember(shelf) { mutableStateOf(true) }
    var error by remember(shelf) { mutableStateOf(false) }
    val watchedRefs = remember(history) { history.filter(WatchProgress::completed).mapTo(mutableSetOf()) { it.title } }

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
    Column(modifier = Modifier.fillMaxSize().background(NetflixBlack)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                text = shelf.label,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            loading && visible.isEmpty() -> Loading()
            error && visible.isEmpty() -> EmptyState("Couldn't load this collection.") { request++ }
            else -> PosterGrid(
                titles = visible,
                ratings = ratings,
                watchedRefs = watchedRefs,
                open = open,
                footer = if (offset != null) {
                    {
                        if (loading) CircularProgressIndicator(color = NetflixRed, modifier = Modifier.size(24.dp))
                        else TextButton(onClick = { request++ }) {
                            Text(if (error) "Try again" else "Load More", color = NetflixRed, fontWeight = FontWeight.Bold)
                        }
                    }
                } else null,
            )
        }
    }
}

@Composable
internal fun TitleShelf(
    label: String,
    titles: List<MovieTitle>,
    ratings: Map<TitleRef, Int> = emptyMap(),
    watchedRefs: Set<TitleRef> = emptySet(),
    posterWidth: androidx.compose.ui.unit.Dp = 138.dp,
    more: (() -> Unit)? = null,
    open: (MovieTitle) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PagePadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                fontSize = 18.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
            if (more != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = more)
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Explore All", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NetflixRed)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(16.dp))
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = PagePadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(titles, key = { it.ref.key }) { title ->
                NetflixPosterCard(
                    title = title,
                    userRating = ratings[title.ref] ?: 0,
                    watched = title.ref in watchedRefs,
                    onClick = { open(title) },
                    modifier = Modifier.width(posterWidth),
                )
            }
        }
    }
}

@Composable
private fun PosterGrid(
    titles: List<MovieTitle>,
    ratings: Map<TitleRef, Int>,
    watchedRefs: Set<TitleRef>,
    open: (MovieTitle) -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = PagePadding, vertical = 12.dp),
    footer: (@Composable () -> Unit)? = null,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(124.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(titles, key = { it.ref.key }) { title ->
            NetflixPosterCard(
                title = title,
                userRating = ratings[title.ref] ?: 0,
                watched = title.ref in watchedRefs,
                onClick = { open(title) },
            )
        }
        if (footer != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) { footer() }
            }
        }
    }
}

@Composable
private fun UpcomingReleases(titles: List<MovieTitle>, open: (MovieTitle) -> Unit) {
    val upcoming = remember(titles) {
        titles.mapNotNull { title ->
            val episode = title.episodes.filterNot(MovieEpisode::available).minByOrNull { it.releasedAt ?: Instant.MAX }
            val whenReleased = if (title.kind == MovieKind.MOVIE && !title.available) title.releasedAt else episode?.releasedAt
            whenReleased?.let { Triple(title, episode, it) }
        }.sortedBy { it.third }
    }
    if (upcoming.isEmpty()) {
        EmptyState("Upcoming episodes and movies from My List will appear here.")
        return
    }
    val date = remember {
        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
            .withZone(java.time.ZoneId.systemDefault())
    }
    LazyColumn(
        contentPadding = PaddingValues(PagePadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(upcoming, key = { it.first.ref.key }) { (title, episode, released) ->
            NetflixCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressableClick { open(title) },
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CrossfadeArtwork(
                        model = episode?.thumbnail ?: title.backdrop ?: title.poster,
                        contentDescription = title.title,
                        modifier = Modifier
                            .width(116.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (episode != null) {
                            Text(
                                "S${episode.season}:E${episode.episode} • ${episode.title}",
                                color = NetflixTextMuted,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = "Coming ${date.format(released)}",
                            color = NetflixRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.5.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun Artwork(url: String?, modifier: Modifier) {
    CrossfadeArtwork(model = url, modifier = modifier)
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = NetflixRed)
    }
}

@Composable
private fun EmptyState(message: String, retry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(PagePadding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(message, color = NetflixTextMuted, textAlign = TextAlign.Center)
            if (retry != null) {
                TextButton(onClick = retry) {
                    Text("Try again", color = NetflixRed, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

internal val MovieTitle.available: Boolean get() = releasedAt?.isAfter(Instant.now()) != true
internal val MovieEpisode.available: Boolean get() = releasedAt?.isAfter(Instant.now()) != true
internal fun List<MovieTitle>.visible(hideMature: Boolean): List<MovieTitle> = if (hideMature) filterNot(MovieTitle::isMature) else this
internal fun MovieTitle.subtitle(): String = listOfNotNull(year, certification, runtime, genres.firstOrNull()).joinToString("  •  ")

internal suspend fun MoviesCatalog.resolve(refs: List<TitleRef>): List<MovieTitle> = kotlinx.coroutines.coroutineScope {
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
