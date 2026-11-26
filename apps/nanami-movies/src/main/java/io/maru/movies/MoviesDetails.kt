package io.maru.movies

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private enum class DetailsTab(val label: String) {
    EPISODES("Episodes"),
    RELATED("More Like This"),
    CAST("Cast & Details"),
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
    onDownload: ((MovieTitle, List<MovieEpisode?>) -> Unit)? = null,
    onWatched: ((MovieTitle, MovieEpisode?, Boolean) -> Unit)? = null,
    isDownloaded: (MovieTitle, MovieEpisode?) -> Boolean = { _, _ -> false },
    onOpenRelated: ((MovieTitle) -> Unit)? = null,
    hideMature: Boolean = false,
    history: List<WatchProgress>? = null,
    rating: Int = 0,
    onRate: ((MovieTitle, Int) -> Unit)? = null,
    watchStatus: MovieWatchStatus? = null,
    onWatchStatus: ((MovieTitle, MovieWatchStatus) -> Unit)? = null,
) {
    var details by remember(preview.ref) { mutableStateOf<Result<MovieTitle?>?>(null) }
    var retry by remember(preview.ref) { mutableIntStateOf(0) }
    var chosenSeason by rememberSaveable(preview.ref.key) { mutableStateOf<Int?>(null) }
    var downloadDialog by remember { mutableStateOf(false) }
    var ratingDialog by remember(preview.ref) { mutableStateOf(false) }
    var statusMenu by remember(preview.ref) { mutableStateOf(false) }
    var expandedOverview by rememberSaveable(preview.ref.key) { mutableStateOf(false) }
    var detailsTab by rememberSaveable(preview.ref.key) {
        mutableStateOf(if (preview.kind == MovieKind.SERIES) DetailsTab.EPISODES else DetailsTab.RELATED)
    }
    var related by remember(preview.ref) { mutableStateOf<List<MovieTitle>?>(null) }
    var hasEnglishAudio by remember(preview.ref) { mutableStateOf<Boolean?>(null) }
    val uriHandler = LocalUriHandler.current

    LaunchedEffect(preview.ref, retry) {
        details = null
        details = attempt { catalog.details(preview.kind, preview.imdbId) }
    }

    val verified = details?.getOrNull()
    val title = verified ?: preview
    val blocked = hideMature && title.isMature
    val advisory = remember(title) { deriveMovieAdvisory(title) }

    LaunchedEffect(verified?.ref) {
        val target = verified ?: return@LaunchedEffect
        hasEnglishAudio = attempt { catalog.hasListedEnglishAudio(target) }.getOrNull()
    }

    val seasons = title.episodes.map(MovieEpisode::season).distinct().sorted()
    val titleHistory = history?.filter { it.title == title.ref }
    fun progressFor(episode: MovieEpisode?): WatchProgress? =
        if (titleHistory == null) library.progress(title.ref, episode)
        else titleHistory.firstOrNull { it.season == episode?.season && it.episode == episode?.episode }

    val resume = if (titleHistory == null) library.recent().firstOrNull { it.title == title.ref }
    else titleHistory.filter(WatchProgress::canResume).maxByOrNull(WatchProgress::updated)

    val resumeEpisode = title.episodes.firstOrNull {
        it.season == resume?.season && it.episode == resume?.episode && it.available
    }
    val firstEpisode = resumeEpisode ?: title.episodes.firstOrNull {
        it.season > 0 && it.available && progressFor(it)?.completed != true
    } ?: title.episodes.firstOrNull { it.season > 0 && it.available } ?: title.episodes.firstOrNull { it.available }

    val activeSeason = chosenSeason ?: firstEpisode?.season ?: seasons.firstOrNull()
    val episodes = title.episodes.filter { it.season == activeSeason }
    val canPlay = verified != null && !blocked && (title.kind == MovieKind.MOVIE && title.available || firstEpisode != null)
    val downloadableEpisodes = episodes.filter {
        it.available && !isDownloaded(title, it) && progressFor(it)?.completed != true
    }

    val activeTab = when {
        title.kind == MovieKind.MOVIE && detailsTab == DetailsTab.EPISODES ->
            if (onOpenRelated != null) DetailsTab.RELATED else DetailsTab.CAST
        else -> detailsTab
    }

    LaunchedEffect(verified, activeTab, onOpenRelated != null) {
        if (verified == null || onOpenRelated == null || activeTab != DetailsTab.RELATED || related != null) return@LaunchedEffect
        related = attempt {
            catalog.popular(title.kind, genre = title.genres.firstOrNull())
                .filterNot { it.ref == title.ref }
                .take(20)
        }.getOrDefault(emptyList())
    }

    if (downloadDialog && onDownload != null) {
        DownloadEpisodesDialog(
            available = downloadableEpisodes.size,
            close = { downloadDialog = false },
            enqueue = { count ->
                onDownload(title, downloadableEpisodes.take(count))
                downloadDialog = false
            },
        )
    }

    if (ratingDialog && onRate != null) {
        var score by remember { mutableIntStateOf(rating.takeIf { it in 1..10 } ?: 8) }
        AlertDialog(
            onDismissRequest = { ratingDialog = false },
            containerColor = NetflixSurface,
            title = { Text("Rate ${title.title}", color = Color.White, fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("★ $score / 10", color = NetflixGold, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Slider(
                        value = score.toFloat(),
                        onValueChange = { score = it.toInt() },
                        valueRange = 1f..10f,
                        steps = 8,
                        colors = SliderDefaults.colors(
                            thumbColor = NetflixRed,
                            activeTrackColor = NetflixRed,
                            inactiveTrackColor = NetflixBorder,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onRate(title, score); ratingDialog = false }) {
                    Text("Save Rating", color = NetflixRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { if (rating > 0) onRate(title, 0); ratingDialog = false }) {
                    Text(if (rating > 0) "Clear" else "Cancel", color = NetflixTextMuted)
                }
            },
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(NetflixBlack),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        // 1. Cinematic Edge-to-Edge Backdrop Header
        item(key = "detail_hero") {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val heroHeight = if (maxWidth >= 600.dp) 340.dp else 265.dp
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    if (!blocked) {
                        CrossfadeArtwork(
                            model = title.backdrop ?: title.poster,
                            contentDescription = title.title,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color(0x66000000),
                                    0.45f to Color(0x22000000),
                                    0.82f to Color(0xDE000000),
                                    1f to NetflixBlack,
                                ),
                            ),
                    )
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(start = 12.dp, top = 6.dp)
                            .background(Color(0xB8101014), CircleShape),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    if (!blocked && !title.logo.isNullOrBlank()) {
                        CrossfadeArtwork(
                            model = title.logo,
                            contentDescription = title.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = PagePadding, vertical = 10.dp)
                                .height(54.dp)
                                .widthIn(max = 260.dp)
                                .background(Color.Transparent),
                        )
                    }
                }
            }
        }

        // 2. Title, Metadata, Stacked Netflix Play + Download CTAs, Synopsis & Quick Actions
        item(key = "detail_meta") {
            Column(
                modifier = Modifier.padding(horizontal = PagePadding),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(color = NetflixRed, shape = RoundedCornerShape(2.dp)) {
                        Text(
                            text = "N",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        text = if (title.kind == MovieKind.SERIES) "SERIES" else "FILM",
                        color = NetflixTextMuted,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        letterSpacing = 1.4.sp,
                    )
                }

                Text(
                    text = title.title,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    lineHeight = 30.sp,
                )

                // Match %, Year, IMDb Score, Seasons, Audio Badge
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    title.rating?.takeIf { it > 0 }?.let { score ->
                        val match = (score * 10).toInt().coerceIn(75, 99)
                        Text(
                            text = "$match% Match",
                            color = NetflixMatchGreen,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp,
                        )
                        DetailBadge("★ " + String.format(Locale.US, "%.1f", score), accent = NetflixGold)
                    }
                    title.year?.let { DetailBadge(it) }
                    title.runtime?.takeIf { title.kind == MovieKind.MOVIE }?.let { DetailBadge(it) }
                    if (seasons.any { it > 0 }) {
                        val count = seasons.count { it > 0 }
                        DetailBadge("$count " + if (count == 1) "Season" else "Seasons")
                    }
                    if (hasEnglishAudio == true) {
                        DetailBadge("English Audio", accent = NetflixMatchGreen)
                    }
                    DetailBadge("HD")
                }

                NetflixAdvisoryRow(advisory = advisory)

                if (blocked) {
                    Text("Hidden by your maturity setting in Account preferences.", color = NetflixTextMuted)
                    return@Column
                }

                // Signature Netflix Stacked Full-Width Play + Download Buttons
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPlay(title, firstEpisode) },
                        enabled = canPlay,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NetflixWhite,
                            contentColor = Color.Black,
                            disabledContainerColor = NetflixCardBg,
                            disabledContentColor = NetflixTextMuted,
                        ),
                        shape = RoundedCornerShape(5.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                resume != null && resumeEpisode != null -> "Resume S${resumeEpisode.season}:E${resumeEpisode.episode}"
                                resume != null -> "Resume"
                                firstEpisode != null -> "Play S${firstEpisode.season}:E${firstEpisode.episode}"
                                else -> "Play"
                            },
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (verified != null && onDownload != null) {
                        val movieDownloaded = title.kind == MovieKind.MOVIE && isDownloaded(title, null)
                        val dlEnabled = if (title.kind == MovieKind.MOVIE) canPlay && !movieDownloaded else downloadableEpisodes.isNotEmpty()
                        Button(
                            onClick = {
                                if (title.kind == MovieKind.MOVIE) onDownload(title, listOf(null))
                                else downloadDialog = true
                            },
                            enabled = dlEnabled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NetflixElevated,
                                contentColor = Color.White,
                                disabledContainerColor = NetflixCardBg,
                                disabledContentColor = NetflixTextMuted,
                            ),
                            shape = RoundedCornerShape(5.dp),
                        ) {
                            Icon(
                                imageVector = if (movieDownloaded) Icons.Default.Check else Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    movieDownloaded -> "Downloaded Offline"
                                    title.kind == MovieKind.MOVIE -> "Download"
                                    else -> "Download Season ${activeSeason ?: 1}"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }

                // Resume Progress Bar if partially watched
                if (resume != null && resume.duration > 0L) {
                    val frac = (resume.position.toFloat() / resume.duration).coerceIn(0f, 1f)
                    val remMin = ((resume.duration - resume.position).coerceAtLeast(0L) / 60_000L)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                            color = NetflixRed,
                            trackColor = NetflixBorder,
                        )
                        Text("${remMin}m remaining", fontSize = 11.5.sp, color = NetflixTextMuted)
                    }
                }

                // Synopsis
                title.overview?.takeIf(String::isNotBlank)?.let { synopsis ->
                    Column(modifier = Modifier.animateContentSize()) {
                        Text(
                            text = synopsis,
                            fontSize = 13.5.sp,
                            color = Color(0xFFE6E8F0),
                            lineHeight = 20.sp,
                            maxLines = if (expandedOverview) Int.MAX_VALUE else 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { expandedOverview = !expandedOverview },
                        )
                        if (synopsis.length > 165) {
                            Text(
                                text = if (expandedOverview) "Less" else "More",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = NetflixTextMuted,
                                modifier = Modifier
                                    .padding(top = 3.dp)
                                    .clickable { expandedOverview = !expandedOverview },
                            )
                        }
                    }
                }

                // Compact Cast & Director Preview Line (Netflix Style)
                if (title.cast.isNotEmpty()) {
                    Text(
                        text = "Starring: " + title.cast.take(4).joinToString(", "),
                        fontSize = 12.sp,
                        color = NetflixTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (title.directors.isNotEmpty()) {
                    Text(
                        text = "Director: " + title.directors.take(2).joinToString(", "),
                        fontSize = 12.sp,
                        color = NetflixTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Netflix Action Icon Bar (+ My List | Status | Rate | Watched | Trailer)
                if (verified != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NetflixActionIcon(
                            icon = if (saved) Icons.Default.Check else Icons.Default.Add,
                            label = "My List",
                            tint = if (saved) NetflixRed else Color.White,
                            onClick = onSave,
                        )

                        if (onWatchStatus != null) {
                            Box {
                                NetflixActionIcon(
                                    icon = Icons.Default.BookmarkBorder,
                                    label = watchStatus?.label ?: "Status",
                                    tint = if (watchStatus != null) NetflixRed else Color.White,
                                    onClick = { statusMenu = true },
                                )
                                DropdownMenu(
                                    expanded = statusMenu,
                                    onDismissRequest = { statusMenu = false },
                                    containerColor = NetflixSurface,
                                ) {
                                    MovieWatchStatus.entries.forEach { status ->
                                        val isCurrent = status == watchStatus
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    status.label,
                                                    color = if (isCurrent) NetflixRed else Color.White,
                                                    fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                                                )
                                            },
                                            trailingIcon = if (isCurrent) {
                                                { Icon(Icons.Default.Check, null, tint = NetflixRed) }
                                            } else null,
                                            onClick = {
                                                statusMenu = false
                                                onWatchStatus(title, status)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        if (onRate != null) {
                            NetflixActionIcon(
                                icon = if (rating > 0) Icons.Default.Star else Icons.Outlined.StarOutline,
                                label = if (rating > 0) "Rated $rating/10" else "Rate",
                                tint = if (rating > 0) NetflixGold else Color.White,
                                onClick = { ratingDialog = true },
                            )
                        }

                        if (title.kind == MovieKind.MOVIE && onWatched != null) {
                            val watched = progressFor(null)?.completed == true
                            NetflixActionIcon(
                                icon = if (watched) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                label = if (watched) "Watched" else "Mark Seen",
                                tint = if (watched) NetflixMatchGreen else Color.White,
                                onClick = { onWatched(title, null, !watched) },
                            )
                        }

                        title.trailerId?.let { trailer ->
                            NetflixActionIcon(
                                icon = Icons.Outlined.SmartDisplay,
                                label = "Trailer",
                                onClick = { runCatching { uriHandler.openUri("https://www.youtube.com/watch?v=$trailer") } },
                            )
                        }
                    }
                }

                when {
                    details == null -> LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = NetflixRed,
                        trackColor = NetflixBorder,
                    )
                    details?.isFailure == true -> TextButton(onClick = { retry++ }) {
                        Text("Couldn't load full details. Tap to retry", color = NetflixRed)
                    }
                    verified == null -> Text("This title is currently unavailable.", color = NetflixTextMuted)
                    title.kind == MovieKind.SERIES && seasons.isEmpty() -> Text("No episodes are listed yet.", color = NetflixTextMuted)
                    title.kind == MovieKind.MOVIE && !title.available -> Text(
                        "Scheduled release: " + title.releasedAt?.toString()?.take(10).orEmpty(),
                        color = NetflixRed,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // 3. Signature Netflix Top-Red-Bar Section Tabs
        if (verified != null && !blocked) {
            item(key = "detail_tabs") {
                val visibleTabs = DetailsTab.entries.filter {
                    when (it) {
                        DetailsTab.EPISODES -> title.kind == MovieKind.SERIES
                        DetailsTab.RELATED -> onOpenRelated != null
                        DetailsTab.CAST -> true
                    }
                }
                Column {
                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = PagePadding)
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        visibleTabs.forEach { tab ->
                            val selected = activeTab == tab
                            val barColor by animateColorAsState(
                                targetValue = if (selected) NetflixRed else Color.Transparent,
                                animationSpec = tween(180),
                                label = "detail_tab_bar",
                            )
                            Column(
                                modifier = Modifier
                                    .width(IntrinsicSize.Max)
                                    .selectable(selected = selected, role = Role.Tab) { detailsTab = tab },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.5.dp)
                                        .background(barColor),
                                )
                                Text(
                                    text = tab.label,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                    color = if (selected) Color.White else NetflixTextMuted,
                                    modifier = Modifier.padding(vertical = 11.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4A. Episodes Tab
        if (!blocked && activeTab == DetailsTab.EPISODES && seasons.isNotEmpty()) {
            item(key = "season_selector") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = PagePadding),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    seasons.forEach { season ->
                        NetflixChip(
                            label = if (season == 0) "Specials" else "Season $season",
                            selected = season == activeSeason,
                            onClick = { chosenSeason = season },
                        )
                    }
                }
            }

            items(episodes, key = MovieEpisode::id) { episode ->
                MovieEpisodeCard(
                    title = title,
                    episode = episode,
                    progress = progressFor(episode),
                    downloaded = isDownloaded(title, episode),
                    onPlay = { onPlay(title, episode) },
                    onDownload = onDownload?.let { cb -> { cb(title, listOf(episode)) } },
                    onWatched = onWatched?.let { cb -> { w -> cb(title, episode, w) } },
                )
            }
        }

        // 4B. More Like This Grid
        if (!blocked && activeTab == DetailsTab.RELATED && onOpenRelated != null) {
            item(key = "related_shelf") {
                val options = related
                when {
                    options == null -> Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NetflixRed, modifier = Modifier.size(26.dp))
                    }
                    options.isNotEmpty() -> TitleShelf(
                        label = "More Like This",
                        titles = options.visible(hideMature),
                        open = onOpenRelated,
                    )
                    else -> Text("No similar titles available right now.", Modifier.padding(horizontal = PagePadding), color = NetflixTextMuted)
                }
            }
        }

        // 4C. Cast & Details Card
        if (!blocked && activeTab == DetailsTab.CAST && verified != null) {
            item(key = "cast_credits") {
                NetflixCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PagePadding),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        DetailCredits("Cast", title.cast)
                        DetailCredits(if (title.directors.size == 1) "Director" else "Directors", title.directors)
                        DetailCredits("Writers", title.writers)
                        DetailCredits("Genres", title.genres)
                        title.status?.let { DetailCredits("Status", listOf(it)) }
                        DetailCredits("Maturity Rating", listOf(listOf(advisory.ratingBadge, advisory.warningsText).filter(String::isNotBlank).joinToString(" • ")))
                    }
                }
            }
        }
    }
}

@Composable
private fun NetflixActionIcon(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .widthIn(min = 56.dp)
            .pressableClick(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(23.dp))
        Text(label, fontSize = 11.sp, color = NetflixTextMuted, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun DetailBadge(label: String, accent: Color? = null) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = NetflixCardBg,
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.55f) ?: NetflixBorder),
    ) {
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            color = accent ?: Color(0xFFE0E2EC),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp),
        )
    }
}

@Composable
private fun DetailCredits(label: String, names: List<String>) {
    if (names.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = NetflixRed)
        Text(names.joinToString(", "), fontSize = 13.5.sp, color = Color.White, lineHeight = 19.sp)
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

    NetflixCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PagePadding),
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .pressableClick(enabled = canPlay, onClick = onPlay),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .width(122.dp)
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(NetflixSurface),
                    ) {
                        CrossfadeArtwork(
                            model = episode.thumbnail ?: title.backdrop,
                            contentDescription = episode.title,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (canPlay) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xB8000000),
                                border = BorderStroke(1.dp, Color.White),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(32.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(19.dp))
                                }
                            }
                        }
                        if (progress?.canResume == true && progress.duration > 0L) {
                            LinearProgressIndicator(
                                progress = { (progress.position.toFloat() / progress.duration).coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(3.dp),
                                color = NetflixRed,
                                trackColor = Color(0x66FFFFFF),
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = "${episode.episode}. ${episode.title}",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val statusText = when {
                            !episode.available && !downloaded -> "Airs " + episode.releasedAt?.toString()?.take(10).orEmpty()
                            downloaded && watched -> "Downloaded • Watched"
                            downloaded -> "Downloaded Offline"
                            watched -> "Watched"
                            progress?.canResume == true -> "In Progress"
                            episode.rating != null && episode.rating > 0 -> String.format(Locale.US, "★ %.1f", episode.rating)
                            else -> null
                        }
                        statusText?.let {
                            Text(
                                text = it,
                                fontSize = 11.5.sp,
                                color = when {
                                    downloaded || watched -> NetflixMatchGreen
                                    progress?.canResume == true -> NetflixRed
                                    else -> NetflixTextMuted
                                },
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }

                // Direct 1-tap Download icon + More menu (just like Netflix & Nanami Anime!)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (downloaded) {
                        Icon(
                            Icons.Default.DownloadDone,
                            contentDescription = "Downloaded",
                            tint = NetflixMatchGreen,
                            modifier = Modifier.padding(8.dp).size(20.dp),
                        )
                    } else if (canDownload) {
                        IconButton(onClick = { onDownload?.invoke() }) {
                            Icon(Icons.Outlined.Download, contentDescription = "Download episode", tint = Color.White)
                        }
                    }
                    if (canMark) {
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Episode options", tint = NetflixTextMuted)
                            }
                            DropdownMenu(
                                expanded = menu,
                                onDismissRequest = { menu = false },
                                containerColor = NetflixSurface,
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (watched) "Mark Unwatched" else "Mark Watched", color = Color.White) },
                                    onClick = { menu = false; onWatched?.invoke(!watched) },
                                )
                            }
                        }
                    }
                }
            }

            episode.overview?.takeIf(String::isNotBlank)?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    color = NetflixTextMuted,
                    lineHeight = 17.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DownloadEpisodesDialog(available: Int, close: () -> Unit, enqueue: (Int) -> Unit) {
    var count by remember { mutableStateOf("") }
    val custom = count.toIntOrNull()?.takeIf { it in 1..available }
    AlertDialog(
        onDismissRequest = close,
        containerColor = NetflixSurface,
        title = { Text("Download Season Episodes", color = Color.White, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$available unwatched episodes ready to download", color = NetflixTextMuted, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (available > 5) {
                        NetflixChip("Next 5 Unwatched", selected = false, onClick = { enqueue(5) })
                    }
                    if (available > 0) {
                        NetflixChip("All Unwatched ($available)", selected = true, onClick = { enqueue(available) })
                    }
                }
                OutlinedTextField(
                    value = count,
                    onValueChange = { count = it.filter(Char::isDigit).take(4) },
                    label = { Text("Or enter custom episode count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = available > 0,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NetflixRed,
                        unfocusedBorderColor = NetflixBorder,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = custom != null, onClick = { custom?.let(enqueue) }) {
                Text("Queue Custom", color = if (custom != null) NetflixRed else NetflixTextMuted, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = close) { Text("Cancel", color = Color.White) }
        },
    )
}
