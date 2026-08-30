package com.maru.namispace.ui.games

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.maru.namispace.engine.GameManager
import com.maru.namispace.model.NamiMood
import com.maru.namispace.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

private enum class CupPhase {
    BETTING,
    PREVIEW,
    SHUFFLING,
    PICKING,
    RESULT,
}

private data class CupItem(
    val id: String,
    val label: String,
    val icon: String,
    val beats: String,
)

private val CUP_ITEMS = listOf(
    CupItem("rock", "Rock", "🪨", "scissors"),
    CupItem("paper", "Paper", "📄", "rock"),
    CupItem("scissors", "Scissors", "✂️", "paper"),
)

@Composable
fun CupCupperCuppersGame(
    gameManager: GameManager,
    onExit: () -> Unit,
) {
    val session by gameManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(CupPhase.BETTING) }
    var wager by remember { mutableIntStateOf(1) }
    var slotItems by remember { mutableStateOf(CUP_ITEMS.shuffled()) }
    var computerSlot by remember { mutableIntStateOf(-1) }
    var selectedSlot by remember { mutableIntStateOf(-1) }
    var winStreak by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf("Place your bet and test your memory!") }

    fun confirmBetAndPreview() {
        val actualWager = wager.coerceAtMost(session.currency.coins)
        if (actualWager > 0 && !gameManager.spendCoins(actualWager)) {
            statusText = "Not enough coins for this bet!"
            return
        }
        wager = actualWager
        slotItems = CUP_ITEMS.shuffled()
        phase = CupPhase.PREVIEW
        statusText = if (wager > 0) "Bet: $wager ★. Memorize where each item is, then tap Shuffle!" else "Practice Round. Memorize items and tap Shuffle!"
    }

    fun startShuffle() {
        if (phase != CupPhase.PREVIEW) return
        phase = CupPhase.SHUFFLING
        statusText = "Tracking the cups..."
        selectedSlot = -1
        computerSlot = -1

        scope.launch {
            delay(400)
            for (step in 0 until 8) {
                val idx1 = Random.nextInt(3)
                var idx2 = Random.nextInt(3)
                while (idx2 == idx1) {
                    idx2 = Random.nextInt(3)
                }
                val mutable = slotItems.toMutableList()
                val temp = mutable[idx1]
                mutable[idx1] = mutable[idx2]
                mutable[idx2] = temp
                slotItems = mutable
                delay(320)
            }

            val comp = Random.nextInt(3)
            computerSlot = comp
            val compItem = slotItems[comp]
            statusText = "Nanami revealed ${compItem.icon} ${compItem.label}! Pick the cup that beats it!"
            phase = CupPhase.PICKING
            gameManager.setMood(NamiMood.SURPRISE)
        }
    }

    fun pickCup(slot: Int) {
        if (phase != CupPhase.PICKING || slot == computerSlot) return
        selectedSlot = slot
        val playerItem = slotItems[slot]
        val compItem = slotItems[computerSlot]

        val isWin = playerItem.beats == compItem.id
        phase = CupPhase.RESULT

        if (isWin) {
            winStreak += 1
            if (wager > 0) {
                val multiplier = if (winStreak >= 3) 2.5 else 2.0
                val totalPayout = (wager * multiplier).toInt().coerceAtLeast(wager + 1)
                val netProfit = totalPayout - wager
                statusText = "✦ VICTORY! ${playerItem.icon} beats ${compItem.icon}! Payout: +$totalPayout ★ (+$netProfit profit)!"
                gameManager.earnCoins(totalPayout, "Won Cup-Cupper Bet")
            } else {
                statusText = "✦ Practice Victory! ${playerItem.icon} beats ${compItem.icon}!"
            }
            gameManager.setMood(NamiMood.HAPPY)
        } else {
            winStreak = 0
            if (wager > 0) {
                statusText = "✕ Loss! ${playerItem.icon} didn't beat ${compItem.icon}. Lost your $wager ★ bet!"
            } else {
                statusText = "✕ Practice Round Failed! Better luck next time!"
            }
            gameManager.setMood(NamiMood.TSUNDERE)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiDeep.copy(alpha = 0.96f))
            .zIndex(30f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    onClick = onExit,
                    shape = RoundedCornerShape(12.dp),
                    color = NamiPanel,
                    border = BorderStroke(1.dp, NamiBorder),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Exit",
                            tint = NamiText,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Exit", color = NamiText, fontSize = 12.sp)
                    }
                }

                Text(
                    text = "Cup-Cupper-Cuppers",
                    color = NamiAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MoodHappy.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, MoodHappy.copy(alpha = 0.4f)),
                ) {
                    Text(
                        text = "★ ${session.currency.coins}",
                        color = MoodHappy,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Status Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = NamiPanel.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, NamiBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = statusText,
                    color = NamiText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }

            Spacer(Modifier.height(20.dp))

            if (phase == CupPhase.BETTING) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = NamiPanel,
                    border = BorderStroke(1.dp, NamiBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("SELECT YOUR WAGER", color = NamiAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            listOf(0, 1, 2, 5, 10).forEach { amount ->
                                val enabled = amount <= session.currency.coins
                                Surface(
                                    onClick = { wager = amount },
                                    enabled = enabled,
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (wager == amount) NamiAccent else if (enabled) NamiPanel.copy(alpha = 0.8f) else NamiBorder.copy(alpha = 0.3f),
                                    border = BorderStroke(1.dp, if (wager == amount) NamiAccent else NamiBorder),
                                    modifier = Modifier.size(54.dp, 42.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = if (amount == 0) "Free" else "★$amount",
                                            color = if (wager == amount) NamiDeep else if (enabled) NamiText else NamiMuted.copy(alpha = 0.4f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = if (wager > 0) "Win to double your bet (2x)!" else "Practice with zero risk (0 coins).",
                            color = NamiMuted,
                            fontSize = 11.5.sp,
                        )
                        Button(
                            onClick = { confirmBetAndPreview() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NamiAccent, contentColor = NamiDeep),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                        ) {
                            Text(if (wager > 0) "Bet ★$wager & Play ▶" else "Play Practice ▶", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // 3 Cups Stage with smooth elevation and badges
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    slotItems.forEachIndexed { slotIndex, item ->
                        val isComputer = slotIndex == computerSlot
                        val isSelected = slotIndex == selectedSlot
                        val isRevealed = phase == CupPhase.PREVIEW || isComputer || (phase == CupPhase.RESULT && isSelected)

                        CupView(
                            slotIndex = slotIndex,
                            item = item,
                            isRevealed = isRevealed,
                            isComputer = isComputer,
                            isSelected = isSelected,
                            isClickable = phase == CupPhase.PICKING && !isComputer,
                            onPick = { pickCup(slotIndex) },
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Action Controls
            when (phase) {
                CupPhase.PREVIEW -> {
                    Button(
                        onClick = { startShuffle() },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NamiAccent, contentColor = NamiDeep),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text("Start Shuffle ▶", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                CupPhase.SHUFFLING -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = NamiAccent.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, NamiAccent.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("Shuffling cups...", color = NamiAccent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                CupPhase.PICKING -> {
                    Text(
                        text = "Tap one of the two closed cups that beats Nanami's cup!",
                        color = NamiMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                CupPhase.RESULT -> {
                    Button(
                        onClick = { phase = CupPhase.BETTING },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NamiAccent, contentColor = NamiDeep),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text("Play Again (Place Bet) 🔄", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                else -> {}
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CupView(
    slotIndex: Int,
    item: CupItem,
    isRevealed: Boolean,
    isComputer: Boolean,
    isSelected: Boolean,
    isClickable: Boolean,
    onPick: () -> Unit,
) {
    val liftY by animateFloatAsState(
        targetValue = if (isRevealed) -42f else 0f,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "cupLift",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .clickable(enabled = isClickable, onClick = onPick),
    ) {
        Box(modifier = Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            if (isComputer) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = NamiAccent,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Text("NANAMI", color = NamiDeep, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
            } else if (isSelected) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MoodHappy,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Text("YOU", color = NamiDeep, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                }
            }
        }

        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier.size(86.dp),
        ) {
            Text(
                text = item.icon,
                fontSize = 38.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 10.dp),
                color = if (isComputer) NamiAccent.copy(alpha = 0.92f) else if (isSelected) MoodHappy.copy(alpha = 0.92f) else NamiPanel,
                border = BorderStroke(2.dp, if (isClickable) NamiAccent else NamiBorder),
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = liftY },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isRevealed) item.label else "✦",
                        color = if (isComputer || isSelected) NamiDeep else NamiAccent,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
