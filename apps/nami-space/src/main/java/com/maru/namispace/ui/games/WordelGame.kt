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
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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

private const val WORD_LENGTH = 5
private const val MAX_GUESSES = 6

private val CURATED_ANSWERS = listOf(
    "AUDIO", "BRAIN", "CLOUD", "DREAM", "FLUTE", "GREEN", "HEART", "LIGHT",
    "MAGIC", "MUSIC", "NIGHT", "OCEAN", "PEACE", "PIANO", "PLUSH", "RADIO",
    "RHYTH", "RIVER", "SEVEN", "SHINE", "SMILE", "SOUND", "SPACE", "STARS",
    "SWEET", "TOAST", "TRACK", "VOCAL", "WATER", "WINDY", "WORLD", "YOUTH",
    "ANIME", "APPLE", "BEACH", "BLOOM", "BLUSH", "CHARM", "CHESS", "CLEAN",
    "DAILY", "DANCE", "FAVOR", "FRESH", "GLOWS", "GRACE", "HONEY", "IMAGE",
    "LUCKY", "MANGA", "MARCH", "MATCH", "NOBLE", "PANDA", "PAPER", "PETAL",
    "POETR", "PROUD", "QUIET", "RELAX", "ROYAL", "SCENE", "SHARE", "SHARP",
    "SLEEP", "SPARK", "STORY", "SUNNY", "TASTE", "THEME", "TIGER", "TOUCH",
    "TRAIN", "TRUST", "UNITY", "VALUE", "VIBES", "VIZIO", "VOICE", "WHITE"
)

private enum class LetterState {
    UNKNOWN,
    ABSENT,
    PRESENT,
    CORRECT,
}

@Composable
fun WordelGame(
    gameManager: GameManager,
    onExit: () -> Unit,
) {
    val session by gameManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    var targetWord by remember { mutableStateOf(CURATED_ANSWERS.random()) }
    var guesses by remember { mutableStateOf(listOf<String>()) }
    var currentInput by remember { mutableStateOf("") }
    var wager by remember { mutableIntStateOf(1) }
    var isBettingPhase by remember { mutableStateOf(true) }
    var gameFinished by remember { mutableStateOf(false) }
    var isWinner by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Place your entry bet to start Wordel!") }
    var shakeTrigger by remember { mutableIntStateOf(0) }

    fun startPuzzle() {
        val actualWager = wager.coerceAtMost(session.currency.coins)
        if (actualWager > 0 && !gameManager.spendCoins(actualWager)) {
            message = "Not enough coins for this ticket!"
            return
        }
        wager = actualWager
        targetWord = CURATED_ANSWERS.random()
        guesses = emptyList()
        currentInput = ""
        isBettingPhase = false
        gameFinished = false
        isWinner = false
        message = if (wager > 0) "Ticket: $wager ★. Guess the 5-letter secret word!" else "Practice Round. Guess the word!"
    }

    fun submitGuess() {
        if (currentInput.length != WORD_LENGTH || gameFinished) {
            shakeTrigger += 1
            message = "Word must be 5 letters, Senpai!"
            return
        }
        val guess = currentInput.uppercase()
        val newGuesses = guesses + guess
        guesses = newGuesses
        currentInput = ""

        if (guess == targetWord) {
            gameFinished = true
            isWinner = true
            if (wager > 0) {
                val multiplier = when (newGuesses.size) {
                    1, 2 -> 2.5
                    3, 4 -> 1.8
                    else -> 1.2
                }
                val totalPayout = (wager * multiplier).toInt().coerceAtLeast(wager + 1)
                val netProfit = totalPayout - wager
                message = "✦ Solved in ${newGuesses.size} tries! Payout: +$totalPayout ★ (+$netProfit profit)!"
                gameManager.earnCoins(totalPayout, "Solved Wordel Bet")
            } else {
                message = "✦ Solved in ${newGuesses.size} tries! Practice win!"
            }
            gameManager.setMood(NamiMood.HAPPY)
        } else if (newGuesses.size >= MAX_GUESSES) {
            gameFinished = true
            isWinner = false
            message = if (wager > 0) "Out of tries! Lost your $wager ★ bet. Word was: $targetWord" else "Game over! The secret word was: $targetWord"
            gameManager.setMood(NamiMood.TSUNDERE)
        } else {
            message = "Guesses left: ${MAX_GUESSES - newGuesses.size}"
        }
    }

    fun handleKeyPress(key: String) {
        if (gameFinished || isBettingPhase) return
        when (key) {
            "ENTER" -> submitGuess()
            "BKSP" -> {
                if (currentInput.isNotEmpty()) {
                    currentInput = currentInput.dropLast(1)
                }
            }
            else -> {
                if (currentInput.length < WORD_LENGTH) {
                    currentInput += key.uppercase()
                }
            }
        }
    }

    // Accurate duplicate-letter evaluation
    fun evaluateGuessLetters(guess: String, answer: String): List<LetterState> {
        val result = MutableList(WORD_LENGTH) { LetterState.ABSENT }
        val remainingLetters = answer.toMutableList()

        // 1. Correct passes
        for (i in 0 until WORD_LENGTH) {
            if (guess[i] == answer[i]) {
                result[i] = LetterState.CORRECT
                remainingLetters[i] = ' '
            }
        }

        // 2. Present passes
        for (i in 0 until WORD_LENGTH) {
            if (result[i] == LetterState.CORRECT) continue
            val char = guess[i]
            val remIdx = remainingLetters.indexOf(char)
            if (remIdx >= 0) {
                result[i] = LetterState.PRESENT
                remainingLetters[remIdx] = ' '
            }
        }

        return result
    }

    val keyStatusMap = remember(guesses, targetWord) {
        val map = mutableMapOf<Char, LetterState>()
        guesses.forEach { guess ->
            val evaluation = evaluateGuessLetters(guess, targetWord)
            guess.forEachIndexed { i, char ->
                val currentStatus = map[char] ?: LetterState.UNKNOWN
                val newStatus = evaluation[i]
                if (newStatus == LetterState.CORRECT || (newStatus == LetterState.PRESENT && currentStatus != LetterState.CORRECT)) {
                    map[char] = newStatus
                } else if (currentStatus == LetterState.UNKNOWN) {
                    map[char] = newStatus
                }
            }
        }
        map
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
                .padding(horizontal = 12.dp, vertical = 8.dp),
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
                    text = "Wordel",
                    color = NamiAccent,
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

            // Status message card
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NamiPanel.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, NamiBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = message,
                    color = if (isWinner) MoodHappy else NamiText,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            Spacer(Modifier.height(10.dp))

            if (isBettingPhase) {
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
                        Text("SELECT ENTRY TICKET", color = MoodHappy, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
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
                                    color = if (wager == amount) MoodHappy else if (enabled) NamiPanel.copy(alpha = 0.8f) else NamiBorder.copy(alpha = 0.3f),
                                    border = BorderStroke(1.dp, if (wager == amount) MoodHappy else NamiBorder),
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
                            text = "Solve in fewer guesses for higher multipliers (up to 2.5x)!",
                            color = NamiMuted,
                            fontSize = 11.5.sp,
                        )
                        Button(
                            onClick = { startPuzzle() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MoodHappy, contentColor = NamiDeep),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                        ) {
                            Text(if (wager > 0) "Buy Ticket (★$wager) & Solve ▶" else "Practice Round ▶", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                // Word Grid (6 rows x 5 letters)
                Column(
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    for (row in 0 until MAX_GUESSES) {
                        val guessWord = guesses.getOrNull(row)
                        val isCurrentRow = row == guesses.size && !gameFinished
                        val evaluation = if (guessWord != null) evaluateGuessLetters(guessWord, targetWord) else null

                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for (col in 0 until WORD_LENGTH) {
                                val letter = when {
                                    guessWord != null -> guessWord.getOrNull(col)?.toString() ?: ""
                                    isCurrentRow -> currentInput.getOrNull(col)?.toString() ?: ""
                                    else -> ""
                                }

                                val letterState = evaluation?.getOrNull(col) ?: LetterState.UNKNOWN

                                WordelCell(
                                    letter = letter,
                                    state = letterState,
                                    isRevealed = guessWord != null,
                                    colIndex = col,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            if (!isBettingPhase && !gameFinished) {
                WordelKeyboard(
                    keyStatusMap = keyStatusMap,
                    onKey = { handleKeyPress(it) },
                )
            } else if (gameFinished) {
                Button(
                    onClick = { isBettingPhase = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NamiAccent, contentColor = NamiDeep),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                ) {
                    Text("Play Next Wordel (Place Bet) 🔄", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun WordelCell(
    letter: String,
    state: LetterState,
    isRevealed: Boolean,
    colIndex: Int,
) {
    // Staggered Flip animation
    val flipAnim = remember { Animatable(if (isRevealed) 1f else 0f) }
    LaunchedEffect(isRevealed) {
        if (isRevealed) {
            delay(colIndex * 120L)
            flipAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(350, easing = FastOutSlowInEasing),
            )
        }
    }

    val rotation = if (isRevealed) (1f - flipAnim.value) * 180f else 0f
    val showEvaluated = flipAnim.value >= 0.5f || isRevealed

    val cellBg = when {
        !showEvaluated -> NamiPanel.copy(alpha = 0.85f)
        state == LetterState.CORRECT -> Color(0xFF44B46C)
        state == LetterState.PRESENT -> Color(0xFFDAB048)
        state == LetterState.ABSENT -> Color(0xFF384861)
        else -> NamiPanel.copy(alpha = 0.85f)
    }

    val cellBorder = when {
        !showEvaluated -> BorderStroke(1.5.dp, if (letter.isNotEmpty()) NamiAccent.copy(alpha = 0.6f) else NamiBorder)
        state == LetterState.CORRECT -> BorderStroke(1.5.dp, Color(0xFF91EFAF))
        state == LetterState.PRESENT -> BorderStroke(1.5.dp, Color(0xFFFFE59E))
        state == LetterState.ABSENT -> BorderStroke(1.dp, Color(0xFF99B0D4).copy(alpha = 0.3f))
        else -> BorderStroke(1.5.dp, if (letter.isNotEmpty()) NamiAccent.copy(alpha = 0.6f) else NamiBorder)
    }

    val textColor = when {
        !showEvaluated -> NamiText
        state == LetterState.CORRECT || state == LetterState.PRESENT -> Color(0xFF0F172A)
        else -> Color(0xFFF3F7FF)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = cellBg,
        border = cellBorder,
        modifier = Modifier
            .size(46.dp)
            .graphicsLayer {
                rotationX = rotation
            },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = letter,
                color = textColor,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun WordelKeyboard(
    keyStatusMap: Map<Char, LetterState>,
    onKey: (String) -> Unit,
) {
    val row1 = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
    val row2 = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
    val row3 = listOf("ENTER", "Z", "X", "C", "V", "B", "N", "M", "BKSP")

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row1.forEach { key ->
                KeyboardKey(label = key, state = keyStatusMap[key[0]] ?: LetterState.UNKNOWN, onClick = { onKey(key) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row2.forEach { key ->
                KeyboardKey(label = key, state = keyStatusMap[key[0]] ?: LetterState.UNKNOWN, onClick = { onKey(key) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row3.forEach { key ->
                val state = if (key.length == 1) keyStatusMap[key[0]] ?: LetterState.UNKNOWN else LetterState.UNKNOWN
                KeyboardKey(
                    label = key,
                    state = state,
                    isSpecial = key == "ENTER" || key == "BKSP",
                    onClick = { onKey(key) },
                )
            }
        }
    }
}

@Composable
private fun KeyboardKey(
    label: String,
    state: LetterState,
    isSpecial: Boolean = false,
    onClick: () -> Unit,
) {
    val keyBg = when (state) {
        LetterState.CORRECT -> Color(0xFF44B46C)
        LetterState.PRESENT -> Color(0xFFDAB048)
        LetterState.ABSENT -> Color(0xFF384861).copy(alpha = 0.6f)
        LetterState.UNKNOWN -> if (isSpecial) NamiAccent.copy(alpha = 0.25f) else NamiPanel
    }

    val keyText = when (state) {
        LetterState.CORRECT, LetterState.PRESENT -> Color(0xFF0F172A)
        LetterState.ABSENT -> NamiMuted.copy(alpha = 0.5f)
        LetterState.UNKNOWN -> if (isSpecial) NamiAccent else NamiText
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = keyBg,
        border = BorderStroke(1.dp, if (isSpecial) NamiAccent.copy(alpha = 0.4f) else NamiBorder),
        modifier = Modifier
            .height(42.dp)
            .width(if (isSpecial) 50.dp else 30.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (label == "BKSP") {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Backspace",
                    tint = keyText,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = if (label == "ENTER") "↵" else label,
                    color = keyText,
                    fontSize = if (label == "ENTER") 16.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
