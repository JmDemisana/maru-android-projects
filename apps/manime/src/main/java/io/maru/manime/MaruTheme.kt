package io.maru.manime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

val MaruAccentPink = Color(0xFFE85D9F)
val MaruAccentBlue = Color(0xFF60E2FF)
val MaruAccentGreen = Color(0xFF4ADE80)
val MaruAccentPurple = Color(0xFFB388FF)
val MaruAccentYellow = Color(0xFFFBBF24)
val MaruDanger = Color(0xFFFF5252)

val MaruBgBase = Color(0xFF07050C)
val MaruTextStrong = Color(0xFFFFFFFF)
val MaruTextMuted = Color(0x99FFFFFF)

val MaruCosmicMid = Color(0xFF140E24)
val MaruGlassSubtleBg = Color(0x18FFFFFF)
val MaruGlassCardBg = Color(0x1EFFFFFF)
val MaruGlassBorderSoft = Color(0x24FFFFFF)

val MaruInputShape = RoundedCornerShape(12.dp)
val MaruCardShape = RoundedCornerShape(14.dp)
val MaruPillShape = RoundedCornerShape(50)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = Color(0x18FFFFFF),
        border = BorderStroke(1.dp, MaruGlassBorderSoft),
        content = content
    )
}

@Composable
fun GlassSectionHeader(
    title: String,
    icon: ImageVector,
    color: Color = MaruAccentPink,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.5.sp,
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
                    lineHeight = 14.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Ep ${media.progress} / ${media.episodes ?: "?"}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaruAccentPink
                )
            }
        }
    }
}
