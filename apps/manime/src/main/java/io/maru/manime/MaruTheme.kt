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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

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
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.ExtraBold,
            fontSize = 19.sp,
            letterSpacing = 0.2.sp
        ),
        color = Color.White,
        modifier = modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun GlassFeatureRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaruTextStrong
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
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
 * Authentic Crunchyroll Continue Watching Card with iOS Spring Press Physics
 */
@Composable
fun WatchingCard(
    media: AnimeMedia,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "WatchingCardSpring"
    )

    val subOrDubLabel = if (media.hasEnglishDub) "Dub | Sub" else "Subtitled"
    val totalEps = media.latestAiredEpisode ?: 0
    val progressFrac = if (totalEps > 0) (media.progress.toFloat() / totalEps.toFloat()).coerceIn(0.06f, 1f) else 0.1f

    Column(
        modifier = modifier
            .width(152.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(CrunchyCardBg)
        ) {
            AsyncImage(
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
                            listOf(Color.Transparent, Color(0x22000000), Color(0xBB000000))
                        )
                    )
            )

            // Play Icon Badge on Bottom Right
            Surface(
                shape = CircleShape,
                color = CrunchyOrange,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(28.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Continue",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Next Episode Badge on Bottom Left
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = Color(0xD9000000),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                val nextEp = (media.progress + 1).coerceAtMost(totalEps.coerceAtLeast(1))
                Text(
                    text = if (media.progress > 0) "E$nextEp" else "E1",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // Crunchyroll Orange Progress Line right under the 2:3 cover
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$subOrDubLabel • Ep ${media.progress}/${totalEps}",
                fontSize = 11.5.sp,
                color = MaruTextMuted,
                fontWeight = FontWeight.Normal
            )
            Icon(
                Icons.Default.MoreVert,
                contentDescription = null,
                tint = MaruTextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Authentic Crunchyroll 2:3 Poster Card matching the user's Crunchyroll reference screenshots:
 * Borderless 2:3 key art image on top, clean Title + "Dub | Sub" (or custom subtitle like "4 Episodes") + 3-dot icon below on pure black background.
 */
@Composable
fun PosterCard(
    media: AnimeMedia,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleOverride: String? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "PosterCardSpring"
    )

    val subOrDubLabel = subtitleOverride ?: if (media.hasEnglishDub) "Dub | Sub" else "Subtitled"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(CrunchyCardBg)
        ) {
            AsyncImage(
                model = media.coverUrl,
                contentDescription = media.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = media.title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(3.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = subOrDubLabel,
                fontSize = 12.sp,
                color = MaruTextMuted,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                Icons.Default.MoreVert,
                contentDescription = null,
                tint = MaruTextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
