package io.maru.manime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// Crunchyroll Dark & Vibrant Orange Palette
val CrunchyOrange = Color(0xFFF47521)
val CrunchyOrangeBright = Color(0xFFFF8838)
val CrunchyTeal = Color(0xFF2ABDBB)
val CrunchyGold = Color(0xFFFAB818)
val CrunchyBgBlack = Color(0xFF0B0C0F)
val CrunchySurface = Color(0xFF141519)
val CrunchyCardBg = Color(0xFF1B1D24)
val CrunchyBorder = Color(0xFF2B2E38)

// Backwards-compatible aliases so all existing screens adopt the Crunchyroll look seamlessly
val MaruBgBase = CrunchyBgBlack
val MaruCosmicTop = Color(0xFF16181F)
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
val MaruTextStrong = Color(0xFFF4F5F7)
val MaruTextMuted = Color(0xFFA0A4B0)

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
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .background(color)
        )
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.5.sp,
                letterSpacing = 1.sp
            ),
            color = MaruTextStrong
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
                checkedThumbColor = Color.Black,
                checkedTrackColor = CrunchyOrange,
                uncheckedThumbColor = MaruTextMuted,
                uncheckedTrackColor = CrunchySurface,
                uncheckedBorderColor = CrunchyBorder
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
    val hasDub = media.externalLinks.any { it.isEnglishDub }
    val subOrDubLabel = if (hasDub) "Sub | Dub" else "Subtitled"
    val totalEps = media.latestAiredEpisode ?: media.episodes ?: 12
    val progressFrac = if (totalEps > 0) (media.progress.toFloat() / totalEps.toFloat()).coerceIn(0.04f, 1f) else 0.1f

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = CrunchyCardBg,
        border = BorderStroke(1.dp, CrunchyBorder),
        modifier = modifier
            .width(150.dp)
            .height(225.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
                                listOf(Color.Transparent, Color(0x44000000), Color(0xDD0B0C0F))
                            )
                        )
                )

                // Crunchyroll Play Badge on Bottom Right of Cover
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

                // Episode tag on Bottom Left
                Surface(
                    shape = RoundedCornerShape(2.dp),
                    color = Color(0xCC0B0C0F),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                ) {
                    val nextEp = (media.progress + 1).coerceAtMost(totalEps.coerceAtLeast(1))
                    Text(
                        text = if (media.progress > 0) "S1 E$nextEp" else "S1 E1",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Crunchyroll Orange Progress Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0xFF2E323D))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressFrac)
                        .fillMaxHeight()
                        .background(CrunchyOrange)
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CrunchySurface)
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            ) {
                Text(
                    text = media.title,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$subOrDubLabel • Ep ${media.progress}/${media.episodes ?: "?"}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = CrunchyTeal
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
    val hasDub = media.externalLinks.any { it.isEnglishDub }
    val subOrDubLabel = if (hasDub) "Sub | Dub" else "Subtitled"

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = CrunchyCardBg,
        border = BorderStroke(1.dp, CrunchyBorder),
        modifier = modifier
            .fillMaxWidth()
            .height(242.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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
                                listOf(Color.Transparent, Color(0x33000000), Color(0xCC0B0C0F))
                            )
                        )
                )

                val isAiring = media.status?.equals("RELEASING", ignoreCase = true) == true
                if (isAiring) {
                    Surface(
                        color = CrunchyOrange,
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = "SIMULCAST",
                            fontSize = 8.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.Black,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }

                if (media.averageScore != null && media.averageScore > 0) {
                    Surface(
                        color = Color(0xCC0B0C0F),
                        shape = RoundedCornerShape(2.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                tint = CrunchyGold,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = "${media.averageScore}%",
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = CrunchyGold
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CrunchySurface)
                    .padding(horizontal = 8.dp, vertical = 7.dp)
            ) {
                Text(
                    text = media.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subOrDubLabel,
                    fontSize = 10.sp,
                    color = MaruTextMuted,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
