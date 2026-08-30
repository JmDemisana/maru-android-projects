package com.maru.namispace.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maru.namispace.model.AffectionState
import com.maru.namispace.model.AffectionTier
import com.maru.namispace.ui.theme.*

@Composable
fun AffectionBar(
    state: AffectionState,
    modifier: Modifier = Modifier,
) {
    val maxDisplay = 100
    val progress = (state.level.toFloat() / maxDisplay).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(600),
        label = "affectionProgress",
    )
    val barColor by animateColorAsState(
        targetValue = when (state.tier) {
            AffectionTier.STRANGER -> NamiMuted
            AffectionTier.FRIEND -> NamiAccent.copy(alpha = 0.5f)
            AffectionTier.CLOSE_FRIEND -> NamiAccent
            AffectionTier.BEST_FRIEND -> MoodHappy
            AffectionTier.SENPAI -> NamiBlush
        },
        animationSpec = tween(600),
        label = "affectionColor",
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.tier.label,
                color = NamiText,
                fontSize = 12.sp,
            )
            Text(
                text = "${state.level}",
                color = barColor,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(NamiBorder),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor),
            )
        }
    }
}
