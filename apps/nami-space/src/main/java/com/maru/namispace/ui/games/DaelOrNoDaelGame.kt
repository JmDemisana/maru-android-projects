package com.maru.namispace.ui.games

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.maru.namispace.engine.GameManager
import com.maru.namispace.model.NamiMood
import com.maru.namispace.ui.theme.*
import kotlin.math.roundToInt

private val BASE_POSITIVE = listOf(3, 4, 5, 6, 8, 10, 15, 25)
private val BASE_NEGATIVE = listOf(-1, -2, -2, -3, -4, -5, -8, -10)

private data class CaseItem(
    val id: Int,
    val amount: Int,
    var opened: Boolean = false,
)

private enum class DaelPhase {
    BETTING,
    PICK_PLAYER_CASE,
    OPEN_CASES,
    BANKER_OFFER,
    FINAL_DECISION,
    GAME_OVER,
}

@Composable
fun DaelOrNoDaelGame(
    gameManager: GameManager,
    onExit: () -> Unit,
) {
    val session by gameManager.state.collectAsState()
    var buyIn by remember { mutableIntStateOf(5) }
    var cases by remember { mutableStateOf(emptyList<CaseItem>()) }
    var playerCaseId by remember { mutableStateOf<Int?>(null) }
    var phase by remember { mutableStateOf(DaelPhase.BETTING) }
    var casesToOpenThisRound by remember { mutableIntStateOf(4) }
    var casesOpenedThisRound by remember { mutableIntStateOf(0) }
    var bankerOffer by remember { mutableIntStateOf(0) }
    var statusText by remember { mutableStateOf("Choose your buy-in stake to enter the high-stakes board!") }

    val remainingCases = cases.filter { !it.opened }

    fun calculateBankerOffer(): Int {
        val remaining = cases.filter { !it.opened && it.id != playerCaseId }
        if (remaining.isEmpty()) return 0
        val avg = remaining.map { it.amount }.average()
        val casesOpened = cases.count { it.opened }
        val factor = 0.65 + (0.05 * (casesOpened / 3)).coerceAtMost(0.80)
        return (avg * factor).roundToInt()
    }

    fun startDaelGame() {
        val actualBuyIn = buyIn.coerceAtMost(session.currency.coins)
        if (actualBuyIn > 0 && !gameManager.spendCoins(actualBuyIn)) {
            statusText = "Not enough coins for this buy-in!"
            return
        }
        buyIn = actualBuyIn
        val multiplier = if (buyIn == 0) 0.5 else buyIn / 5.0

        val scaledPos = BASE_POSITIVE.map { (it * multiplier).roundToInt().coerceAtLeast(1) }
        val scaledNeg = BASE_NEGATIVE.map { (it * multiplier).roundToInt().coerceAtMost(-1) }
        val allAmounts = (scaledPos + scaledNeg).shuffled()

        cases = allAmounts.mapIndexed { index, amount ->
            CaseItem(id = index + 1, amount = amount)
        }
        playerCaseId = null
        phase = DaelPhase.PICK_PLAYER_CASE
        statusText = if (buyIn > 0) "Buy-in: $buyIn ★. 8 of 16 cases are PENALTY traps! Pick your lucky case!" else "Practice Round. 8 cases are traps! Pick your case."
    }

    fun onCaseClick(clickedCase: CaseItem) {
        if (clickedCase.opened) return

        when (phase) {
            DaelPhase.PICK_PLAYER_CASE -> {
                playerCaseId = clickedCase.id
                phase = DaelPhase.OPEN_CASES
                casesToOpenThisRound = 4
                casesOpenedThisRound = 0
                statusText = "Open $casesToOpenThisRound briefcases to reveal board values."
                gameManager.setMood(NamiMood.HAPPY)
            }
            DaelPhase.OPEN_CASES -> {
                if (clickedCase.id == playerCaseId) return
                cases = cases.map { if (it.id == clickedCase.id) it.copy(opened = true) else it }
                casesOpenedThisRound += 1

                val unopenedCount = cases.count { !it.opened && it.id != playerCaseId }

                if (unopenedCount == 1) {
                    phase = DaelPhase.FINAL_DECISION
                    bankerOffer = calculateBankerOffer()
                    statusText = "Final Round! 1 case left. Take Banker's offer, swap, or keep?"
                    gameManager.setMood(NamiMood.SURPRISE)
                } else if (casesOpenedThisRound >= casesToOpenThisRound) {
                    bankerOffer = calculateBankerOffer()
                    phase = DaelPhase.BANKER_OFFER
                    statusText = "☎ Ring ring! The Banker is calling with an offer!"
                    gameManager.setMood(NamiMood.THINKING)
                } else {
                    statusText = "Open ${casesToOpenThisRound - casesOpenedThisRound} more briefcase(s)."
                }
            }
            else -> {}
        }
    }

    fun acceptOffer() {
        val profit = bankerOffer - buyIn
        phase = DaelPhase.GAME_OVER
        if (bankerOffer >= 0) {
            statusText = if (profit >= 0) "✦ DAEL! Accepted Banker's $bankerOffer ★ coins (+$profit profit)!"
            else "✦ DAEL! Accepted Banker's $bankerOffer ★ coins (Lost ${-profit} ★ on buy-in)!"
            gameManager.earnCoins(bankerOffer, "Banker Deal in Dael or No Dael")
            gameManager.setMood(if (profit >= 0) NamiMood.HAPPY else NamiMood.TSUNDERE)
        } else {
            statusText = "✕ DAEL gone wrong! Banker's poisoned offer costs ${-bankerOffer} ★!"
            gameManager.spendCoins(-bankerOffer)
            gameManager.setMood(NamiMood.TSUNDERE)
        }
    }

    fun rejectOffer() {
        phase = DaelPhase.OPEN_CASES
        casesOpenedThisRound = 0
        casesToOpenThisRound = (casesToOpenThisRound - 1).coerceAtLeast(1)
        statusText = "NO DAEL! Open $casesToOpenThisRound more briefcases."
        gameManager.setMood(NamiMood.TSUNDERE)
    }

    fun finishWithPlayerCase(swap: Boolean) {
        val playerCase = cases.first { it.id == playerCaseId }
        val otherCase = cases.first { !it.opened && it.id != playerCaseId }
        val chosenAmount = if (swap) otherCase.amount else playerCase.amount
        val otherAmount = if (swap) playerCase.amount else otherCase.amount

        phase = DaelPhase.GAME_OVER

        if (chosenAmount >= 0) {
            val profit = chosenAmount - buyIn
            statusText = if (profit >= 0) {
                "✦ Won $chosenAmount ★! (+$profit profit). Other case: ${if (otherAmount < 0) "⚠ $otherAmount" else "★ $otherAmount"}"
            } else {
                "Won $chosenAmount ★ (lost ${-profit} ★). Other case: ${if (otherAmount < 0) "⚠ $otherAmount" else "★ $otherAmount"}"
            }
            gameManager.earnCoins(chosenAmount, "Won Dael or No Dael")
            gameManager.setMood(if (profit >= 0) NamiMood.HAPPY else NamiMood.TSUNDERE)
        } else {
            statusText = "⚠ Penalty Case! Lost ${-chosenAmount} ★! The other case had: ${if (otherAmount < 0) "⚠ $otherAmount" else "★ $otherAmount"}"
            gameManager.spendCoins(-chosenAmount)
            gameManager.setMood(NamiMood.PANIC)
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
                .padding(horizontal = 10.dp, vertical = 8.dp),
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
                    text = "Dael or No Dael",
                    color = MoodHappy,
                    fontSize = 16.sp,
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

            Spacer(Modifier.height(6.dp))

            // Status Card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NamiPanel.copy(alpha = 0.9f),
                border = BorderStroke(1.dp, NamiBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = statusText,
                    color = NamiText,
                    fontSize = 12.5.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            if (phase == DaelPhase.BETTING) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = NamiPanel,
                    border = BorderStroke(1.dp, NamiBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("SELECT BUY-IN STAKE", color = MoodHappy, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            listOf(0, 2, 5, 10).forEach { amount ->
                                val enabled = amount <= session.currency.coins
                                Surface(
                                    onClick = { buyIn = amount },
                                    enabled = enabled,
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (buyIn == amount) MoodHappy else if (enabled) NamiPanel.copy(alpha = 0.8f) else NamiBorder.copy(alpha = 0.3f),
                                    border = BorderStroke(1.dp, if (buyIn == amount) MoodHappy else NamiBorder),
                                    modifier = Modifier.size(62.dp, 44.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = if (amount == 0) "Free" else "★$amount",
                                            color = if (buyIn == amount) NamiDeep else if (enabled) NamiText else NamiMuted.copy(alpha = 0.4f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = "8 positive jackpots vs 8 penalty traps scaled by your buy-in!",
                            color = NamiMuted,
                            fontSize = 11.5.sp,
                        )
                        Button(
                            onClick = { startDaelGame() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MoodHappy, contentColor = NamiDeep),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                        ) {
                            Text(if (buyIn > 0) "Buy-in (★$buyIn) & Play ▶" else "Practice Board ▶", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // TV-Style Board: Left Traps Ladder, Center Cases Grid, Right Jackpots Ladder
                val openedAmounts = cases.filter { it.opened }.map { it.amount }
                val allNegative = cases.filter { it.amount < 0 }.map { it.amount }.sorted()
                val allPositive = cases.filter { it.amount > 0 }.map { it.amount }.sorted()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left Ladder: 8 Penalty Values
                        Column(
                            modifier = Modifier
                                .width(56.dp)
                                .height(260.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            allNegative.forEach { valAmt ->
                                val isKnockedOut = openedAmounts.contains(valAmt)
                                LadderPill(value = valAmt, isNegative = true, isKnockedOut = isKnockedOut, modifier = Modifier.weight(1f))
                            }
                        }

                        // Center 4x4 Briefcase Grid
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(260.dp),
                        ) {
                            items(cases, key = { it.id }) { item ->
                                val isPlayerCase = item.id == playerCaseId
                                BriefcaseTile(
                                    item = item,
                                    isPlayerCase = isPlayerCase,
                                    isClickable = phase == DaelPhase.PICK_PLAYER_CASE || (phase == DaelPhase.OPEN_CASES && !isPlayerCase && !item.opened),
                                    onClick = { onCaseClick(item) },
                                )
                            }
                        }

                        // Right Ladder: 8 Jackpot Values
                        Column(
                            modifier = Modifier
                                .width(56.dp)
                                .height(260.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            allPositive.forEach { valAmt ->
                                val isKnockedOut = openedAmounts.contains(valAmt)
                                LadderPill(value = valAmt, isNegative = false, isKnockedOut = isKnockedOut, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Bottom Action Controls
            when (phase) {
                DaelPhase.BANKER_OFFER -> {
                    val offerIsNegative = bankerOffer < 0
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = NamiPanel,
                        border = BorderStroke(1.5.dp, if (offerIsNegative) NamiBlush else MoodHappy),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = if (offerIsNegative) "⚠ BANKER'S TRAP OFFER" else "BANKER'S OFFER",
                                color = if (offerIsNegative) NamiBlush else NamiMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (offerIsNegative) "⚠ $bankerOffer coins" else "★ $bankerOffer coins",
                                color = if (offerIsNegative) NamiBlush else MoodHappy,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { acceptOffer() },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = NamiAccent, contentColor = NamiDeep),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("DAEL! ✓", fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { rejectOffer() },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = NamiBlush, contentColor = Color.White),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("NO DAEL ✕", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                DaelPhase.FINAL_DECISION -> {
                    val offerIsNegative = bankerOffer < 0
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = NamiPanel,
                        border = BorderStroke(1.5.dp, if (offerIsNegative) NamiBlush else MoodHappy),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = if (offerIsNegative) "⚠ FINAL TRAP OFFER" else "FINAL DECISION",
                                color = if (offerIsNegative) NamiBlush else NamiMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = if (offerIsNegative) "⚠ $bankerOffer coins" else "Banker: ★ $bankerOffer coins",
                                color = if (offerIsNegative) NamiBlush else MoodHappy,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Button(
                                    onClick = { acceptOffer() },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (offerIsNegative) NamiBlush else MoodHappy,
                                        contentColor = if (offerIsNegative) Color.White else NamiDeep,
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Deal", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { finishWithPlayerCase(swap = true) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = NamiAccent, contentColor = NamiDeep),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Swap", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { finishWithPlayerCase(swap = false) },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = NamiRibbon, contentColor = NamiText),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Keep", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                DaelPhase.GAME_OVER -> {
                    Button(
                        onClick = { phase = DaelPhase.BETTING },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NamiAccent, contentColor = NamiDeep),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                    ) {
                        Text("Play Again (Place Stake) 🔄", fontWeight = FontWeight.Bold)
                    }
                }
                else -> {}
            }

            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun LadderPill(
    value: Int,
    isNegative: Boolean,
    isKnockedOut: Boolean,
    modifier: Modifier = Modifier,
) {
    val bgColor = when {
        isKnockedOut -> Color(0xFF1E293B).copy(alpha = 0.4f)
        isNegative -> NamiBlush.copy(alpha = 0.85f)
        else -> MoodHappy.copy(alpha = 0.85f)
    }

    val textColor = when {
        isKnockedOut -> NamiMuted.copy(alpha = 0.35f)
        else -> Color(0xFF0F172A)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (isNegative) "$value" else "★$value",
            color = textColor,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun BriefcaseTile(
    item: CaseItem,
    isPlayerCase: Boolean,
    isClickable: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = when {
        item.opened && item.amount < 0 -> NamiBlush.copy(alpha = 0.25f)
        item.opened -> NamiBorder.copy(alpha = 0.4f)
        isPlayerCase -> MoodHappy.copy(alpha = 0.35f)
        isClickable -> NamiAccent.copy(alpha = 0.15f)
        else -> NamiPanel
    }

    val borderColor = when {
        item.opened && item.amount < 0 -> NamiBlush
        item.opened -> NamiBorder.copy(alpha = 0.3f)
        isPlayerCase -> MoodHappy
        isClickable -> NamiAccent
        else -> NamiBorder
    }

    Surface(
        onClick = onClick,
        enabled = isClickable,
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(1.5.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(2.dp),
        ) {
            if (item.opened) {
                Text(if (item.amount < 0) "⚠" else "💼", fontSize = 12.sp)
                Text(
                    text = if (item.amount < 0) "$item.amount" else "★ ${item.amount}",
                    color = if (item.amount < 0) NamiBlush else NamiMuted,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Text(
                    text = if (isPlayerCase) "⭐ #${item.id}" else "💼 #${item.id}",
                    color = if (isPlayerCase) MoodHappy else NamiText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
