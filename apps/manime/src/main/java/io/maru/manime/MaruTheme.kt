package io.maru.manime

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

// Authentic Crunchyroll OLED Pitch Black & Vibrant Orange Palette
val CrunchyOrange = Color(0xFFF47521)
val CrunchyOrangeBright = Color(0xFFFF8838)
val CrunchyTeal = Color(0xFF2ABDBB)
val CrunchyGold = Color(0xFFFAB818)
val CrunchyBgBlack = Color(0xFF000000)
val CrunchySurface = Color(0xFF0E0F12)
val CrunchyCardBg = Color(0xFF16181D)
val CrunchyBorder = Color(0xFF23252C)

// Backwards-compatible aliases
val MaruBgBase = CrunchyBgBlack
val MaruCosmicTop = Color(0xFF101216)
val MaruCosmicMid = CrunchySurface
val MaruCosmicBot = CrunchyBgBlack

val MaruGlassSubtleBg = CrunchyCardBg
val MaruGlassCardBg = CrunchyCardBg
val MaruGlassBorderSoft = CrunchyBorder
val MaruAccentPink = CrunchyOrange
val MaruAccentBlue = CrunchyTeal
val MaruAccentPurple = CrunchyOrangeBright
val MaruAccentGreen = Color(0xFF2ECC71)
val MaruAccentYellow = CrunchyGold
val MaruDanger = Color(0xFFE74C3C)
val MaruTextStrong = Color(0xFFFFFFFF)
val MaruTextMuted = Color(0xFF9EA2AE)

val MaruPillShape = RoundedCornerShape(4.dp)
val MaruInputShape = RoundedCornerShape(4.dp)
val MaruCardShape = RoundedCornerShape(6.dp)

/**
 * Smooth tactile spring press animation for cards, posters, and interactive surfaces.
 */
fun Modifier.pressableClick(
    enabled: Boolean = true,
    pressedScale: Float = 0.965f,
    role: Role? = Role.Button,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "press_scale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null,
            role = role,
            onClick = onClick
        )
}

@Composable
fun CrossfadeImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val request = remember(model, context) {
        ImageRequest.Builder(context)
            .data(model)
            .crossfade(220)
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier
    )
}

/**
 * Authentic Crunchyroll Age Rating & Content Advisory row, e.g.:
 * `[18+] Violence, Drugs` or `[16+] Violence, Sexual Themes`
 */
@Composable
fun CrunchyAdvisoryRow(
    advisory: ContentAdvisory,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val badgeBg = when (advisory.ageRating) {
        "18+" -> Color(0xFF3A1D22)
        "16+" -> Color(0xFF2B2E38)
        else -> Color(0xFF23262F)
    }
    val badgeBorder = when (advisory.ageRating) {
        "18+" -> Color(0xFFE05260)
        "16+" -> Color(0xFF494E5F)
        else -> CrunchyBorder
    }
    val badgeTextColor = if (advisory.ageRating == "18+") Color(0xFFFFB3BA) else Color(0xFFE6E8F0)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = badgeBg,
            border = BorderStroke(1.dp, badgeBorder)
        ) {
            Text(
                text = advisory.ageRating,
                fontSize = if (compact) 10.sp else 11.sp,
                fontWeight = FontWeight.ExtraBold,
                color = badgeTextColor,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        if (advisory.warnings.isNotEmpty()) {
            Text(
                text = advisory.warningsText,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFFB8BCC8),
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(6.dp),
    border: Color = MaruGlassBorderSoft,
    background: Color = MaruGlassSubtleBg,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = background,
        border = BorderStroke(1.dp, border)
    ) {
        content()
    }
}

@Composable
fun GlassSectionHeader(
    title: String,
    icon: ImageVector? = null,
    color: Color = CrunchyOrange,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp,
                letterSpacing = 0.2.sp
            ),
            color = Color.White
        )
    }
}

@Composable
fun GlassFeatureRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaruTextStrong
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = MaruTextMuted,
                    lineHeight = 15.sp
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = CrunchyOrange,
                uncheckedThumbColor = MaruTextMuted,
                uncheckedTrackColor = CrunchySurface,
                uncheckedBorderColor = CrunchyBorder
            )
        )
    }
}

/**
 * Continue watching card (wraps full anime titles cleanly without ellipses!)
 */
@Composable
fun WatchingCard(
    media: AnimeMedia,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val knownDubs by KnownDubRegistry.dubbedIds.collectAsState()
    val isDubbed = media.hasEnglishDub || media.mediaId in knownDubs
    val subOrDubLabel = if (isDubbed) "Dub | Sub" else "Subtitled"
    val totalEps = media.latestAiredEpisode ?: media.episodes ?: 0
    val rawFrac = if (totalEps > 0) (media.progress.toFloat() / totalEps).coerceIn(0f, 1f) else 0f
    val progressFrac by animateFloatAsState(
        targetValue = rawFrac,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "watch_progress"
    )

    Column(
        modifier = modifier
            .width(152.dp)
            .pressableClick(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp))
                .background(CrunchyCardBg)
        ) {
            CrossfadeImage(
                model = media.coverUrl,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0x22000000), Color(0xCC000000))
                        )
                    )
            )

            Surface(
                shape = CircleShape,
                color = CrunchyOrange,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(30.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Continue",
                        tint = Color.Black,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(3.dp),
                color = Color(0xE0000000),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                val nextEp = (media.progress + 1).coerceAtMost(if (totalEps > 0) totalEps else Int.MAX_VALUE)
                Text(
                    text = if (media.progress > 0) "NEXT E$nextEp" else "START E1",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color(0xFF282A32))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressFrac)
                    .fillMaxHeight()
                    .background(CrunchyOrange)
            )
        }

        Spacer(modifier = Modifier.height(7.dp))

        Text(
            text = media.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = if (totalEps > 0) "$subOrDubLabel • Ep ${media.progress}/$totalEps" else if (media.progress > 0) "$subOrDubLabel • Ep ${media.progress}" else subOrDubLabel,
            fontSize = 11.5.sp,
            color = MaruTextMuted,
            lineHeight = 15.sp
        )
    }
}

/**
 * Authentic Crunchyroll 2:3 Poster Card with wrapped titles (zero ellipses!) and tactile spring press.
 */
@Composable
fun PosterCard(
    media: AnimeMedia,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleOverride: String? = null
) {
    val knownDubs by KnownDubRegistry.dubbedIds.collectAsState()
    val isDubbed = media.hasEnglishDub || media.mediaId in knownDubs
    val subOrDubLabel = subtitleOverride ?: if (isDubbed) "Dub | Sub" else "Subtitled"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pressableClick(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(4.dp))
                .background(CrunchyCardBg)
        ) {
            CrossfadeImage(
                model = media.coverUrl,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = media.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            lineHeight = 17.sp
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = subOrDubLabel,
            fontSize = 11.5.sp,
            color = if (isDubbed && subtitleOverride == null) CrunchyTeal else MaruTextMuted,
            lineHeight = 15.sp
        )
    }
}
