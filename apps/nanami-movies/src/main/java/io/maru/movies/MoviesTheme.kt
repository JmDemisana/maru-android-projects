package io.maru.movies

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import io.maru.manime.extensions.StremioAddonClient
import io.maru.manime.tv.nanamiFocusHighlight
import java.util.Locale

// Authentic Netflix Cinema OLED Pitch Black & Tudum Crimson Palette
internal val NetflixBlack = Color(0xFF000000)
internal val NetflixSurface = Color(0xFF101014)
internal val NetflixCardBg = Color(0xFF17171C)
internal val NetflixElevated = Color(0xFF222229)
internal val NetflixBorder = Color(0xFF282832)
internal val NetflixRed = Color(0xFFE50914)
internal val NetflixRedBright = Color(0xFFF40612)
internal val NetflixMatchGreen = Color(0xFF46D369)
internal val NetflixGold = Color(0xFFF5C518)
internal val NetflixWhite = Color(0xFFFFFFFF)
internal val NetflixTextStrong = Color(0xFFFFFFFF)
internal val NetflixTextMuted = Color(0xFFA3A6B3)

internal val NetflixPillShape = RoundedCornerShape(4.dp)
internal val NetflixCardShape = RoundedCornerShape(6.dp)

internal val NanamiMoviesColorScheme = darkColorScheme(
    primary = NetflixRed,
    onPrimary = Color.White,
    secondary = NetflixMatchGreen,
    background = NetflixBlack,
    surface = NetflixSurface,
    surfaceVariant = NetflixCardBg,
    onSurface = Color.White,
    onSurfaceVariant = NetflixTextMuted,
    outline = NetflixBorder,
    error = Color(0xFFFF6B6B),
)

internal data class MovieContentAdvisory(
    val ratingBadge: String,
    val warnings: List<String>,
    val isMature: Boolean,
) {
    val warningsText: String get() = warnings.joinToString(", ")
}

internal fun deriveMovieAdvisory(title: MovieTitle): MovieContentAdvisory {
    val cert = title.certification?.trim()?.uppercase(Locale.ROOT)
    val badge = when {
        !cert.isNullOrBlank() -> cert
        title.explicitlyAdult -> "18+"
        title.genres.any { it.equals("Horror", true) || it.equals("Crime", true) } -> "16+"
        title.genres.any { it.equals("Family", true) || it.equals("Kids", true) } -> "PG"
        else -> "13+"
    }
    val warnings = buildList {
        val genreSet = title.genres.map { it.lowercase(Locale.ROOT) }.toSet()
        if ("horror" in genreSet) add("Frightening Scenes")
        if ("crime" in genreSet || "war" in genreSet || "thriller" in genreSet) add("Violence")
        else if ("action" in genreSet) add("Action Violence")
        if ("mystery" in genreSet || "thriller" in genreSet) add("Intense Themes")
        if ("romance" in genreSet && title.isMature) add("Mature Themes")
        if (title.isMature && isEmpty()) add("Mature Themes, Language")
    }.distinct().take(3)
    return MovieContentAdvisory(
        ratingBadge = badge,
        warnings = warnings,
        isMature = title.isMature || badge in setOf("18+", "R", "NC-17", "TV-MA"),
    )
}

internal fun Modifier.pressableClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.962f,
    role: Role? = Role.Button,
    onClick: () -> Unit,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "movies_press_scale",
    )
    this
        .nanamiFocusHighlight(NetflixRed, cornerRadius = 6.dp, focusedScale = 1.03f)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            role = role,
            onClick = onClick,
        )
}

@Composable
internal fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "netflix_shimmer")
    val translate by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1150, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_x",
    )
    return Brush.linearGradient(
        colors = listOf(
            Color(0xFF15151A),
            Color(0xFF23232C),
            Color(0xFF15151A),
        ),
        start = Offset(translate, translate * 0.4f),
        end = Offset(translate + 280f, (translate + 280f) * 0.4f),
    )
}

/**
 * Poster & backdrop image loader with smooth crossfade and shimmer skeleton while loading.
 * Do NOT use this for transparent clear-logos; use [ClearLogoImage] instead!
 */
@Composable
internal fun CrossfadeArtwork(
    model: Any?,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    var loaded by remember(model) { mutableStateOf(false) }
    val shimmer = rememberShimmerBrush()

    Box(modifier = modifier.background(if (loaded) NetflixCardBg else Color.Transparent)) {
        if (!loaded) {
            Box(modifier = Modifier.fillMaxSize().background(shimmer))
        }
        if (model != null) {
            val request = remember(model, context) {
                ImageRequest.Builder(context)
                    .data(model)
                    .addHeader("User-Agent", StremioAddonClient.BROWSER_UA)
                    .crossfade(260)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = contentScale,
                onState = { state ->
                    if (state is AsyncImagePainter.State.Success) loaded = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Transparent clear-logo renderer for movie/series title logos (`title.logo`).
 * Strictly 100% transparent background (zero dark bar behind logos!) and falls back
 * gracefully to clean typography if the logo is absent or fails to load.
 */
@Composable
internal fun ClearLogoImage(
    logoUrl: String?,
    titleFallback: String,
    modifier: Modifier = Modifier,
    fallbackFontSize: Int = 26,
) {
    val context = LocalContext.current
    var loadFailed by remember(logoUrl) { mutableStateOf(logoUrl.isNullOrBlank()) }
    var loaded by remember(logoUrl) { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (loaded) 1f else 0f,
        animationSpec = tween(240),
        label = "logo_alpha",
    )

    if (logoUrl.isNullOrBlank() || loadFailed) {
        Text(
            text = titleFallback,
            fontSize = fallbackFontSize.sp,
            fontWeight = FontWeight.Black,
            color = Color.White,
            lineHeight = (fallbackFontSize + 5).sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        val request = remember(logoUrl, context) {
            ImageRequest.Builder(context)
                .data(logoUrl)
                .addHeader("User-Agent", StremioAddonClient.BROWSER_UA)
                .crossfade(220)
                .build()
        }
        Box(modifier = modifier, contentAlignment = Alignment.BottomStart) {
            if (!loaded) {
                Text(
                    text = titleFallback,
                    fontSize = fallbackFontSize.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White.copy(alpha = 0.85f),
                    lineHeight = (fallbackFontSize + 5).sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AsyncImage(
                model = request,
                contentDescription = titleFallback,
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomStart,
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> loaded = true
                        is AsyncImagePainter.State.Error -> loadFailed = true
                        else -> Unit
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha },
            )
        }
    }
}

@Composable
internal fun NetflixCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = NetflixCardShape,
    border: Color = NetflixBorder,
    background: Color = NetflixCardBg,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = background,
        border = BorderStroke(1.dp, border),
        content = content,
    )
}

@Composable
internal fun NetflixSectionHeader(
    title: String,
    icon: ImageVector? = null,
    color: Color = NetflixRed,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                letterSpacing = 0.2.sp,
            ),
            color = Color.White,
        )
    }
}

@Composable
internal fun NetflixFeatureRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = NetflixTextStrong,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = NetflixTextMuted,
                    lineHeight = 15.sp,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NetflixRed,
                uncheckedThumbColor = NetflixTextMuted,
                uncheckedTrackColor = NetflixSurface,
                uncheckedBorderColor = NetflixBorder,
            ),
        )
    }
}

@Composable
internal fun NetflixChip(
    label: String,
    selected: Boolean,
    accent: Color = NetflixRed,
    leadingIcon: ImageVector? = null,
    pill: Boolean = true,
    onClick: () -> Unit,
) {
    val selectedTextColor = if (accent == NetflixWhite || accent == NetflixGold || accent == NetflixMatchGreen) Color.Black else Color.White
    val shape = if (pill) CircleShape else RoundedCornerShape(6.dp)
    Surface(
        shape = shape,
        color = if (selected) accent else Color(0xFF191920),
        border = BorderStroke(1.dp, if (selected) accent else Color(0xFF32323E)),
        modifier = Modifier.pressableClick(pressedScale = 0.94f, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (leadingIcon != null) {
                Icon(
                    leadingIcon,
                    contentDescription = null,
                    tint = if (selected) selectedTextColor else Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                color = if (selected) selectedTextColor else Color(0xFFE6E8F0),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
internal fun NetflixAdvisoryRow(
    advisory: MovieContentAdvisory,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val badgeBg = if (advisory.isMature) Color(0xFF381317) else Color(0xFF23252E)
    val badgeBorder = if (advisory.isMature) NetflixRed.copy(alpha = 0.75f) else NetflixBorder
    val badgeText = if (advisory.isMature) Color(0xFFFFB8BE) else Color(0xFFEAECEF)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = badgeBg,
            border = BorderStroke(1.dp, badgeBorder),
        ) {
            Text(
                text = advisory.ratingBadge,
                fontSize = if (compact) 10.sp else 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = badgeText,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (advisory.warnings.isNotEmpty()) {
            Text(
                text = advisory.warningsText,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFBEC2CE),
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
internal fun NetflixContinueCard(
    title: MovieTitle,
    progress: WatchProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rawFrac = if (progress.duration > 0L) {
        (progress.position.toFloat() / progress.duration).coerceIn(0.04f, 1f)
    } else 0f
    val animFrac by animateFloatAsState(
        targetValue = rawFrac,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "continue_progress",
    )
    val remainingMin = ((progress.duration - progress.position).coerceAtLeast(0L) / 60_000L)

    Column(
        modifier = modifier
            .width(198.dp)
            .pressableClick(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                .background(NetflixCardBg),
        ) {
            CrossfadeArtwork(
                model = title.backdrop ?: title.poster,
                contentDescription = title.title,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0x28000000), Color(0xCC000000)),
                        ),
                    ),
            )

            Surface(
                shape = CircleShape,
                color = Color(0xCC000000),
                border = BorderStroke(1.5.dp, Color.White),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Resume",
                        tint = Color.White,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }

            if (progress.season != null && progress.episode != null) {
                Surface(
                    shape = RoundedCornerShape(3.dp),
                    color = Color(0xDF000000),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(7.dp),
                ) {
                    Text(
                        text = "S${progress.season}:E${progress.episode}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color(0xFF32323B)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animFrac)
                    .fillMaxHeight()
                    .background(NetflixRed),
            )
        }

        Surface(
            color = NetflixCardBg,
            shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        if (progress.season != null && progress.episode != null) {
                            append("Episode ${progress.episode}")
                            if (remainingMin > 0L) append(" • ${remainingMin}m left")
                        } else if (remainingMin > 0L) {
                            append("${remainingMin}m remaining")
                        } else {
                            append("Resume")
                        }
                    },
                    fontSize = 11.sp,
                    color = NetflixTextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun NetflixPosterCard(
    title: MovieTitle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    userRating: Int = 0,
    watched: Boolean = false,
    subtitleOverride: String? = null,
    rankNumber: Int? = null,
) {
    val subtitleText = remember(title, userRating, subtitleOverride) {
        if (subtitleOverride != null) {
            subtitleOverride
        } else {
            val parts = mutableListOf<String>()
            parts += if (title.kind == MovieKind.SERIES) "Series" else "Movie"
            if (userRating > 0) {
                parts += "★ $userRating/10"
            } else if (title.rating != null && title.rating > 0.0) {
                parts += String.format(Locale.US, "★ %.1f", title.rating)
            }
            title.year?.take(4)?.takeIf { it.isNotBlank() }?.let { parts += it }
            parts.joinToString(" • ")
        }
    }

    Column(
        modifier = modifier.pressableClick(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.BottomStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (rankNumber != null) 22.dp else 0.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(NetflixCardBg),
            ) {
                CrossfadeArtwork(
                    model = title.poster ?: title.backdrop,
                    contentDescription = title.title,
                    modifier = Modifier.fillMaxSize(),
                )

                if (rankNumber != null) {
                    Surface(
                        color = NetflixRed,
                        shape = RoundedCornerShape(bottomEnd = 5.dp),
                        modifier = Modifier.align(Alignment.TopStart),
                    ) {
                        Text(
                            text = "TOP 10",
                            color = Color.White,
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.4.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }

                if (watched) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xD8000000),
                        border = BorderStroke(1.dp, NetflixMatchGreen),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(20.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Watched",
                                tint = NetflixMatchGreen,
                                modifier = Modifier.size(13.dp),
                            )
                        }
                    }
                }
            }

            if (rankNumber != null) {
                Box(modifier = Modifier.offset(x = (-2).dp, y = 8.dp)) {
                    Text(
                        text = rankNumber.toString(),
                        style = TextStyle(
                            fontSize = 68.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF0A0A0D),
                        ),
                    )
                    Text(
                        text = rankNumber.toString(),
                        style = TextStyle(
                            fontSize = 68.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFD5D7E0),
                            drawStyle = Stroke(width = 4.5f, join = StrokeJoin.Round),
                        ),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(7.dp))

        Text(
            text = title.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = subtitleText,
            fontSize = 11.sp,
            color = if (userRating > 0) NetflixGold else NetflixTextMuted,
            lineHeight = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
