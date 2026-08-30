package com.maru.namispace.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maru.namispace.ui.components.OverlaySheet
import com.maru.namispace.ui.theme.*

enum class SelectedGame {
    NONE,
    CUP_CUPPER,
    WORDEL,
    DAEL,
}

@Composable
fun ArcadeOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onLaunchGame: (SelectedGame) -> Unit,
) {
    OverlaySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Nami Arcade",
        subtitle = "Play games to earn NamiCoins and bond with Nanami",
        maxHeightFraction = 0.80f,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                GameCard(
                    title = "Cup-Cupper-Cuppers",
                    icon = "🎯",
                    rewardBadge = "1–2 ★ / win",
                    description = "A fast-paced shell memory game! Follow the 3 shuffled cups and pick the Rock-Paper-Scissors item that beats Nanami's cup.",
                    buttonLabel = "Play Cups ▶",
                    accentColor = NamiAccent,
                    onPlay = {
                        onDismiss()
                        onLaunchGame(SelectedGame.CUP_CUPPER)
                    },
                )
            }

            item {
                GameCard(
                    title = "Wordel",
                    icon = "🔤",
                    rewardBadge = "2–4 ★ / win",
                    description = "The classic 5-letter word puzzle! Guess the secret word in 6 tries with color-coded hints and an on-screen keyboard.",
                    buttonLabel = "Play Wordel ▶",
                    accentColor = MoodHappy,
                    onPlay = {
                        onDismiss()
                        onLaunchGame(SelectedGame.WORDEL)
                    },
                )
            }

            item {
                GameCard(
                    title = "Dael or No Dael",
                    icon = "💼",
                    rewardBadge = "Up to 50 ★",
                    description = "High-stakes briefcase game! Pick your lucky case, eliminate board values, and decide whether to accept the Banker's offer or risk it all!",
                    buttonLabel = "Play Dael ▶",
                    accentColor = NamiRibbon,
                    onPlay = {
                        onDismiss()
                        onLaunchGame(SelectedGame.DAEL)
                    },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun GameCard(
    title: String,
    icon: String,
    rewardBadge: String,
    description: String,
    buttonLabel: String,
    accentColor: Color,
    onPlay: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = NamiPanel.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, NamiBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(accentColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = icon, fontSize = 20.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = title,
                        color = NamiText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MoodHappy.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, MoodHappy.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = rewardBadge,
                        color = MoodHappy,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            Text(
                text = description,
                color = NamiMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            Button(
                onClick = onPlay,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = NamiDeep),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
            ) {
                Text(text = buttonLabel, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
