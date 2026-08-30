package io.maru.manime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// Shared Visual Constants
val MaruBgBase = Color(0xFF07050A)
val MaruCosmicTop = Color(0xFF140D26)
val MaruCosmicMid = Color(0xFF0E0A1A)
val MaruCosmicBot = Color(0xFF07050A)

val MaruGlassSubtleBg = Color(0x331E1433)
val MaruGlassCardBg = Color(0x331E1433)
val MaruGlassBorderSoft = Color(0x33B388FF)
val MaruAccentPink = Color(0xFFE85D9F)
val MaruAccentBlue = Color(0xFF60E2FF)
val MaruAccentPurple = Color(0xFFB388FF)
val MaruAccentGreen = Color(0xFF4ADE80)
val MaruAccentYellow = Color(0xFFFBBF24)
val MaruDanger = Color(0xFFFF5252)
val MaruTextStrong = Color(0xFFF3F0F7)
val MaruTextMuted = Color(0xFF9E95B0)

val MaruPillShape = RoundedCornerShape(24.dp)
val MaruInputShape = RoundedCornerShape(12.dp)
val MaruCardShape = RoundedCornerShape(16.dp)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
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
    color: Color = MaruAccentPink,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp
            ),
            color = color
        )
    }
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
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = MaruTextStrong
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MaruAccentPink,
                uncheckedThumbColor = MaruTextMuted,
                uncheckedTrackColor = MaruGlassSubtleBg,
                uncheckedBorderColor = MaruGlassBorderSoft
            )
        )
    }
}

@Composable
fun WatchingCard(
    media: AnimeMedia,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF140F22),
        border = BorderStroke(1.dp, MaruGlassBorderSoft),
        modifier = modifier
            .width(135.dp)
            .height(200.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                            listOf(Color.Transparent, Color(0x99000000), Color(0xF5000000))
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = media.title,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    lineHeight = 14.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                val isAiring = media.status?.equals("RELEASING", ignoreCase = true) == true
                val epText = if (isAiring && media.latestAiredEpisode != null) {
                    "Ep ${media.progress} / ${media.latestAiredEpisode} (Air)"
                } else {
                    "Ep ${media.progress} / ${media.episodes ?: "?"}"
                }
                Text(
                    text = epText,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isAiring) MaruAccentBlue else MaruAccentPink
                )
            }
        }
    }
}

@Composable
fun PosterCard(
    media: AnimeMedia,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF140F22),
        border = BorderStroke(1.dp, MaruGlassBorderSoft),
        modifier = modifier
            .fillMaxWidth()
            .height(230.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
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
                            listOf(Color.Transparent, Color(0x99000000), Color(0xF5000000))
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = media.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    lineHeight = 15.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isAiring = media.status?.equals("RELEASING", ignoreCase = true) == true
                    val epLabel = if (isAiring && media.latestAiredEpisode != null) {
                        "Ep ${media.latestAiredEpisode} rel"
                    } else {
                        "${media.episodes ?: "?"} eps"
                    }
                    Text(
                        text = epLabel,
                        fontSize = 9.5.sp,
                        color = if (isAiring) MaruAccentBlue else MaruTextMuted,
                        fontWeight = if (isAiring) FontWeight.Bold else FontWeight.Normal
                    )
                    if (media.averageScore != null && media.averageScore > 0) {
                        Text(
                            text = "â˜… ${media.averageScore}%",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaruAccentYellow
                        )
                    }
                }
            }
        }
    }
}
