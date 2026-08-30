package com.maru.namispace.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maru.namispace.ui.theme.*
import kotlinx.coroutines.delay

@Composable
fun DialogueBox(
    speakerName: String = "Nanami",
    text: String,
    textColor: Color = NamiText,
    latencyText: String? = null,
    onAdvance: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var displayedText by remember { mutableStateOf("") }
    var isTyping by remember { mutableStateOf(false) }

    // Pulsing indicator when text finishes typing
    val infiniteTransition = rememberInfiniteTransition(label = "indicatorPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    LaunchedEffect(text) {
        if (text.startsWith("Nami is thinking...") || text.startsWith("Thinking...")) {
            displayedText = text
            isTyping = false
            return@LaunchedEffect
        }
        isTyping = true
        displayedText = ""
        for (i in text.indices) {
            displayedText = text.substring(0, i + 1)
            delay(16)
        }
        isTyping = false
    }

    val clickableModifier = if (onAdvance != null) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            if (isTyping) {
                displayedText = text
                isTyping = false
            } else {
                onAdvance()
            }
        }
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier),
    ) {
        // Speaker tag tab & High-Contrast Latency Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                NamiAccent.copy(alpha = 0.35f),
                                NamiPanel.copy(alpha = 0.98f),
                            ),
                        ),
                    )
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(NamiAccent),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = speakerName,
                    color = NamiAccent,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
            }

            if (latencyText != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = NamiPanel.copy(alpha = 0.98f),
                    border = BorderStroke(1.dp, MoodHappy.copy(alpha = 0.85f)),
                    tonalElevation = 6.dp,
                ) {
                    Text(
                        text = latencyText,
                        color = MoodHappy,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
        }

        // Main VN Acrylic Text Panel
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp, max = 220.dp),
            shape = RoundedCornerShape(16.dp),
            color = NamiPanel.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, NamiBorder.copy(alpha = 0.90f)),
            tonalElevation = 8.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = displayedText,
                    color = textColor,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Flashing advance arrow indicator
                if (!isTyping && !text.startsWith("Nami is thinking...") && !text.startsWith("Thinking...")) {
                    Text(
                        text = "▶",
                        color = NamiAccent,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .graphicsLayer { alpha = pulseAlpha },
                    )
                }
            }
        }
    }
}
