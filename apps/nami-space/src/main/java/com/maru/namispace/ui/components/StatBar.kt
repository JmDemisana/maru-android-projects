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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maru.namispace.ui.theme.*

enum class StatType(val icon: String, val label: String) {
    HUNGER("🍽", "Hunger"),
    ENERGY("⚡", "Energy"),
    MOOD("♥", "Mood"),
    AFFECTION("♡", "Affection"),
}

@Composable
fun StatBar(
    type: StatType,
    value: Int,
    maxValue: Int = 100,
    modifier: Modifier = Modifier,
) {
    val progress = (value.toFloat() / maxValue).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(500),
        label = "${type.name}Progress",
    )

    val barColor by animateColorAsState(
        targetValue = when {
            progress < 0.2f -> Color(0xFFEF5350)
            progress < 0.5f -> Color(0xFFFFB74D)
            else -> NamiAccent
        },
        animationSpec = tween(500),
        label = "${type.name}Color",
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = type.icon, fontSize = 12.sp)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(NamiBorder),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor),
            )
        }
        Text(
            text = "$value",
            color = NamiMuted,
            fontSize = 10.sp,
            modifier = Modifier.width(24.dp),
        )
    }
}
