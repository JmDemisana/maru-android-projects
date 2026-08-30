package com.maru.namispace.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.maru.namispace.R
import com.maru.namispace.ai.NamiLocalChatEngine
import com.maru.namispace.engine.CompanionActivity
import com.maru.namispace.engine.DialoguePool
import com.maru.namispace.engine.GameManager
import com.maru.namispace.model.AffectionTier
import com.maru.namispace.model.NamiMood
import com.maru.namispace.model.StoryChapter
import com.maru.namispace.ui.components.*
import com.maru.namispace.ui.games.CupCupperCuppersGame
import com.maru.namispace.ui.games.DaelOrNoDaelGame
import com.maru.namispace.ui.games.WordelGame
import com.maru.namispace.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

enum class ActiveOverlay {
    NONE,
    CARE,
    GAMES,
    STORIES,
    SHOP,
    SETTINGS,
    OUTING,
}

@Composable
fun HomeScreen(
    gameManager: GameManager,
) {
    val session by gameManager.state.collectAsState()
    val char = session.character
    val haptics = LocalHapticFeedback.current

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val localEngine = remember { NamiLocalChatEngine(context) }
    var talkInput by remember { mutableStateOf("") }
    var isThinking by remember { mutableStateOf(false) }
    var thinkingMillis by remember { mutableLongStateOf(0L) }
    var lastLatencyMillis by remember { mutableLongStateOf(0L) }

    var activeOverlay by remember { mutableStateOf(ActiveOverlay.NONE) }
    var activeStoryChapter by remember { mutableStateOf<StoryChapter?>(null) }
    var activeGame by remember { mutableStateOf(SelectedGame.NONE) }
    var particles by remember { mutableStateOf(listOf<Particle>()) }
    var showGoodbyeDialog by remember { mutableStateOf(false) }

    // Relationship Tier calculation
    val tier = gameManager.getAffectionTier(char.affection)

    // Smooth Thinking Timer (Does NOT mutate dialogue in loop)
    LaunchedEffect(isThinking) {
        if (isThinking) {
            val startTime = System.currentTimeMillis()
            while (isThinking) {
                thinkingMillis = System.currentTimeMillis() - startTime
                delay(100)
            }
        }
    }

    fun sendTalkMessage(text: String) {
        if (text.isBlank() || isThinking) return
        talkInput = ""
        isThinking = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        gameManager.setMood(NamiMood.THINKING)
        gameManager.updateDialogue("Nanami is thinking...")

        scope.launch {
            val startTime = System.currentTimeMillis()
            val response = localEngine.generateChatReply(
                userMessage = text,
                gameManager = gameManager,
                conversationHistory = emptyList()
            )
            lastLatencyMillis = System.currentTimeMillis() - startTime

            isThinking = false
            gameManager.setMood(response.mood)
            gameManager.updateDialogue(response.text)
            if (response.suggestedChips.isNotEmpty()) {
                gameManager.setSuggestedReplies(response.suggestedChips)
            }
            gameManager.addChatAffection()
            particles = particles + createHeartParticles(count = 3)
        }
    }

    // Passive Stat tick every 90s (NO auto dialogue advance)
    LaunchedEffect(Unit) {
        while (true) {
            delay(90_000)
            gameManager.tickStats()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NamiDeep),
    ) {
        // 1. Room Background Layer (Visual Novel Interior)
        Image(
            painter = painterResource(R.drawable.background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.35f
                    scaleY = 1.35f
                    transformOrigin = TransformOrigin(0.5f, 0.15f)
                },
            contentScale = ContentScale.Crop,
        )

        // Atmospheric subtle dark vignette
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            NamiDeep.copy(alpha = 0.35f),
                            Color.Transparent,
                            NamiDeep.copy(alpha = 0.65f),
                        ),
                    ),
                ),
        )

        // 2. Persistent Character Layer: Up-Close VN Perspective (Upper Chest to Head)
        if (activeStoryChapter == null && activeGame == SelectedGame.NONE) {
            NamiCharacter(
                mood = char.effectiveMood,
                onTap = {
                    if (activeOverlay == ActiveOverlay.NONE && activeStoryChapter == null && activeGame == SelectedGame.NONE) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        gameManager.interactWithNami(scope)
                        particles = particles + createHeartParticles(count = 3)
                    }
                },
                upClose = true,
                scale = 1.16f,
                yOffsetDp = 220f,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.TopCenter)
                    .zIndex(1f),
            )
        }

        // Floating particle overlay
        ParticleOverlay(
            particles = particles,
            onParticleFinished = { p -> particles = particles.filter { it.id != p.id } },
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f),
        )

        // 3. Top Tamagotchi / Dating Sim HUD Header
        if (activeStoryChapter == null && activeGame == SelectedGame.NONE) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 12.dp, top = 16.dp, end = 12.dp, bottom = 4.dp)
                    .zIndex(3f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Row 1: Relationship Tier & Stats + Goodbye
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Left Pill: Relationship Tier & Mood
                    Surface(
                        onClick = { activeOverlay = ActiveOverlay.SETTINGS },
                        shape = RoundedCornerShape(16.dp),
                        color = NamiPanel.copy(alpha = 0.90f),
                        border = BorderStroke(1.dp, NamiBorder.copy(alpha = 0.9f)),
                        tonalElevation = 4.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "♡", color = NamiBlush, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(4.dp))
                            Text(text = tier.label, color = NamiText, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(5.dp))
                            Text(text = "• ${char.effectiveMood.label}", color = NamiAccent, fontSize = 11.sp)
                        }
                    }

                    // Right Pill: Coins, Hunger, Energy & Goodbye
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = NamiPanel.copy(alpha = 0.90f),
                        border = BorderStroke(1.dp, NamiBorder.copy(alpha = 0.9f)),
                        tonalElevation = 4.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Coins & Care shortcut
                            Row(
                                modifier = Modifier.clickable { activeOverlay = ActiveOverlay.CARE },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = "★", fontSize = 11.5.sp, color = MoodHappy)
                                    Spacer(Modifier.width(2.dp))
                                    Text(text = "${session.currency.coins}", color = MoodHappy, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                }
                                MiniStat(icon = "🍽", value = char.hunger)
                                MiniStat(icon = "⚡", value = char.energy)
                            }

                            // Goodbye Icon Button
                            Surface(
                                onClick = { showGoodbyeDialog = true },
                                shape = CircleShape,
                                color = NamiBlush.copy(alpha = 0.25f),
                                modifier = Modifier.size(24.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(text = "👋", fontSize = 11.5.sp)
                                }
                            }
                        }
                    }
                }

                // Row 2: Location Mode Switcher & Autonomous Activity Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Location Mode Switcher
                    Surface(
                        onClick = {
                            val nextMode = when (session.locationMode) {
                                com.maru.namispace.model.LocationMode.HOME -> com.maru.namispace.model.LocationMode.SCHOOL_WORK
                                com.maru.namispace.model.LocationMode.SCHOOL_WORK -> com.maru.namispace.model.LocationMode.OUTING
                                com.maru.namispace.model.LocationMode.OUTING -> com.maru.namispace.model.LocationMode.HOME
                            }
                            gameManager.setLocationMode(nextMode)
                            if (nextMode == com.maru.namispace.model.LocationMode.OUTING) {
                                activeOverlay = ActiveOverlay.OUTING
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = when (session.locationMode) {
                            com.maru.namispace.model.LocationMode.SCHOOL_WORK -> Color(0xFF1E293B).copy(alpha = 0.95f)
                            com.maru.namispace.model.LocationMode.OUTING -> NamiRibbon.copy(alpha = 0.4f)
                            else -> NamiPanel.copy(alpha = 0.85f)
                        },
                        border = BorderStroke(1.dp, if (session.locationMode == com.maru.namispace.model.LocationMode.OUTING) NamiAccent else NamiBorder.copy(alpha = 0.7f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = session.locationMode.icon, fontSize = 11.5.sp)
                            Spacer(Modifier.width(4.dp))
                            Text(text = session.locationMode.label, color = NamiText, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // Autonomous Activity Badge
                    Surface(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            gameManager.pickNewActivity()
                            particles = particles + createHeartParticles(2)
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = NamiPanel.copy(alpha = 0.85f),
                        border = BorderStroke(1.dp, NamiBorder.copy(alpha = 0.7f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(text = "${session.currentActivity.icon} ${session.currentActivity.label}", color = NamiAccent, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // 4. Bottom Visual Novel Dialogue & Action Dock Area
        if (activeStoryChapter == null && activeGame == SelectedGame.NONE) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .zIndex(3f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val latencyBadge = if (lastLatencyMillis > 0 && !isThinking) {
                    "⚡ ${String.format(Locale.US, "%.1fs", lastLatencyMillis / 1000f)}"
                } else null

                if (isThinking) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = NamiPanel.copy(alpha = 0.85f),
                            border = BorderStroke(1.dp, NamiAccent.copy(alpha = 0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = NamiAccent,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "Thinking... (${String.format(java.util.Locale.US, "%.1fs", thinkingMillis / 1000f)})",
                                    color = NamiText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                } else {
                    val dialogueDisplay = session.currentDialogue.ifBlank { char.effectiveMood.greeting }
                    DialogueBox(
                        speakerName = "Nanami",
                        text = dialogueDisplay,
                        textColor = NamiText,
                        latencyText = latencyBadge,
                        onAdvance = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            gameManager.refreshMoodDialogue()
                            particles = particles + createHeartParticles(2)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Persistent Suggested Quick Chips & Input Bar (Hidden when thinking to avoid duplicate thinking labels)
                if (!isThinking) {
                    val chips = session.suggestedReplies.filter { it.trim().length >= 3 }.ifEmpty {
                        listOf("🎵 Favorite song?", "🥑 Want a snack?", "💤 Let's rest", "what is a qubit?")
                    }
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(chips) { chip ->
                            Surface(
                                onClick = { sendTalkMessage(chip) },
                                shape = RoundedCornerShape(16.dp),
                                color = NamiPanel.copy(alpha = 0.88f),
                                border = BorderStroke(1.dp, NamiBorder.copy(alpha = 0.7f)),
                            ) {
                                Text(
                                    text = chip,
                                    color = NamiText,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }

                    // Integrated "Talk to Nami" Bar
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = NamiPanel.copy(alpha = 0.94f),
                        border = BorderStroke(1.dp, NamiBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .imePadding(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextField(
                                value = talkInput,
                                onValueChange = { talkInput = it },
                                placeholder = {
                                    Text(
                                        text = "Talk to Nami...",
                                        color = NamiMuted,
                                        fontSize = 12.5.sp,
                                    )
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedTextColor = NamiText,
                                    unfocusedTextColor = NamiText,
                                    cursorColor = MoodHappy,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent,
                                ),
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 38.dp),
                                singleLine = true,
                            )

                            IconButton(
                                onClick = { sendTalkMessage(talkInput) },
                                enabled = talkInput.isNotBlank(),
                                colors = IconButtonDefaults.iconButtonColors(
                                    contentColor = MoodHappy,
                                    disabledContentColor = NamiMuted.copy(alpha = 0.3f),
                                ),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                // Translucent Quick Action Dock
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = NamiPanel.copy(alpha = 0.90f),
                    border = BorderStroke(1.dp, NamiBorder.copy(alpha = 0.85f)),
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DockButton(
                            icon = Icons.Filled.Favorite,
                            label = "Care",
                            badge = if (char.hunger < 30 || char.energy < 30) "!" else null,
                            onClick = { activeOverlay = ActiveOverlay.CARE },
                        )
                        DockButton(
                            icon = Icons.Filled.Gamepad,
                            label = "Arcade",
                            badge = "★",
                            onClick = { activeOverlay = ActiveOverlay.GAMES },
                        )
                        DockButton(
                            icon = Icons.AutoMirrored.Filled.MenuBook,
                            label = "Stories",
                            badge = null,
                            onClick = { activeOverlay = ActiveOverlay.STORIES },
                        )
                        DockButton(
                            icon = Icons.Filled.ShoppingCart,
                            label = "Shop",
                            badge = null,
                            onClick = { activeOverlay = ActiveOverlay.SHOP },
                        )
                        DockButton(
                            icon = Icons.Filled.Settings,
                            label = "Status",
                            badge = null,
                            onClick = { activeOverlay = ActiveOverlay.SETTINGS },
                        )
                    }
                }
            }
        }

        // 5. Active Story Player Mode
        if (activeStoryChapter != null) {
            StoryPlayer(
                chapter = activeStoryChapter!!,
                onComplete = { bonus, text, mood ->
                    gameManager.completeStory(activeStoryChapter!!.id, text, mood)
                    activeStoryChapter = null
                    particles = particles + createHeartParticles(5)
                },
                onExit = { activeStoryChapter = null },
            )
        }

        // 6. Active Mini-Game Mode (Cup-Cupper-Cuppers, Wordel, Dael)
        when (activeGame) {
            SelectedGame.CUP_CUPPER -> {
                CupCupperCuppersGame(
                    gameManager = gameManager,
                    onExit = { activeGame = SelectedGame.NONE },
                )
            }
            SelectedGame.WORDEL -> {
                WordelGame(
                    gameManager = gameManager,
                    onExit = { activeGame = SelectedGame.NONE },
                )
            }
            SelectedGame.DAEL -> {
                DaelOrNoDaelGame(
                    gameManager = gameManager,
                    onExit = { activeGame = SelectedGame.NONE },
                )
            }
            SelectedGame.NONE -> {}
        }

        // 7. Translucent Overlays
        CareOverlay(
            gameManager = gameManager,
            visible = activeOverlay == ActiveOverlay.CARE,
            onDismiss = { activeOverlay = ActiveOverlay.NONE },
            onOpenShop = { activeOverlay = ActiveOverlay.SHOP },
            onActivityTriggered = {
                particles = particles + createHeartParticles(4)
            },
        )

        ArcadeOverlay(
            visible = activeOverlay == ActiveOverlay.GAMES,
            onDismiss = { activeOverlay = ActiveOverlay.NONE },
            onLaunchGame = { game ->
                activeOverlay = ActiveOverlay.NONE
                activeGame = game
            },
        )

        StoryOverlay(
            gameManager = gameManager,
            visible = activeOverlay == ActiveOverlay.STORIES,
            onDismiss = { activeOverlay = ActiveOverlay.NONE },
            onPlayChapter = { chapter ->
                activeOverlay = ActiveOverlay.NONE
                activeStoryChapter = chapter
            },
        )

        ShopOverlay(
            gameManager = gameManager,
            visible = activeOverlay == ActiveOverlay.SHOP,
            onDismiss = { activeOverlay = ActiveOverlay.NONE },
        )

        SettingsOverlay(
            gameManager = gameManager,
            visible = activeOverlay == ActiveOverlay.SETTINGS,
            onDismiss = { activeOverlay = ActiveOverlay.NONE },
        )

        if (activeOverlay == ActiveOverlay.OUTING) {
            OutingOverlay(
                gameManager = gameManager,
                onClose = { activeOverlay = ActiveOverlay.NONE },
            )
        }

        // Goodbye Confirmation Dialog
        if (showGoodbyeDialog) {
            AlertDialog(
                onDismissRequest = { showGoodbyeDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "👋", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(text = "Say Goodbye to Nanami?", color = NamiText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                text = {
                    Text(
                        text = "Tell Nanami you're leaving so she knows you didn't just ditch her! She'll greet you happily when you come back. ♡",
                        color = NamiText.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            gameManager.sayGoodbye()
                            showGoodbyeDialog = false
                            particles = particles + createHeartParticles(6)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NamiAccent, contentColor = NamiDeep),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("See You Later, Nami! ♡", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGoodbyeDialog = false }) {
                        Text("Stay Longer", color = NamiMuted)
                    }
                },
                containerColor = NamiPanel,
                shape = RoundedCornerShape(18.dp),
            )
        }
    }
}

@Composable
private fun QuickTopicChip(
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = NamiPanel.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, NamiAccent.copy(alpha = 0.4f)),
    ) {
        Text(
            text = label,
            color = NamiText,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** Rich Care & Tamagotchi Activity Menu */
@Composable
private fun CareOverlay(
    gameManager: GameManager,
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenShop: () -> Unit,
    onActivityTriggered: () -> Unit = {},
) {
    val session by gameManager.state.collectAsState()
    val char = session.character

    OverlaySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Care & Activities",
        subtitle = "Spend quality time and take care of Nanami",
        maxHeightFraction = 0.82f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Live Stats Panel
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = NamiAccent.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, NamiAccent.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "✦ NANAMI'S CURRENT STATE",
                        color = NamiAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                    StatBar(type = StatType.HUNGER, value = char.hunger)
                    StatBar(type = StatType.ENERGY, value = char.energy)
                    StatBar(type = StatType.AFFECTION, value = char.affection)
                }
            }

            // 1. Pantry & Feed Nanami Section
            val ownedFood = session.inventory.filter { it.value > 0 }.mapNotNull { (id, count) ->
                com.maru.namispace.model.ShopCatalog.getItem(id)?.copy(ownedCount = count)
            }

            Text(
                text = "Pantry & Snacks (${ownedFood.sumOf { it.ownedCount }} items)",
                color = NamiText,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )

            if (ownedFood.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(ownedFood) { item ->
                        Surface(
                            onClick = {
                                gameManager.consumeItem(item.id)
                                onActivityTriggered()
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = NamiPanel,
                            border = BorderStroke(1.dp, NamiAccent.copy(alpha = 0.5f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (item.drawableRes != null) {
                                    androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(id = item.drawableRes),
                                        contentDescription = item.name,
                                        modifier = Modifier.size(36.dp),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    )
                                } else {
                                    Text(
                                        text = when (item.id) {
                                            "avocado_toast" -> "🥑"
                                            "bento_box" -> "🍱"
                                            "four_leaf_clover" -> "🍀"
                                            "photo_album" -> "📖"
                                            else -> "🍓"
                                        },
                                        fontSize = 24.sp,
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = item.name,
                                            color = NamiText,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "x${item.ownedCount}",
                                            color = NamiAccent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                        )
                                    }
                                    val statEffects = buildList {
                                        if (item.hungerRestore > 0) add("+${item.hungerRestore} 🍽")
                                        if (item.energyRestore > 0) add("+${item.energyRestore} ⚡")
                                        if (item.affectionBonus > 0) add("+${item.affectionBonus} ♡")
                                    }.joinToString("  ")
                                    Text(
                                        text = statEffects.ifBlank { "+1 ♡" },
                                        color = NamiMuted,
                                        fontSize = 10.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Surface(
                    onClick = {
                        onDismiss()
                        onOpenShop()
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = NamiPanel.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, NamiBorder.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(
                                text = "Pantry is empty!",
                                color = NamiText,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = "Buy snacks in the Shop to feed Nanami",
                                color = NamiMuted,
                                fontSize = 11.sp,
                            )
                        }
                        Button(
                            onClick = {
                                onDismiss()
                                onOpenShop()
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NamiAccent, contentColor = NamiDeep),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("Go to Shop 🛍", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // 2. Companion Activities
            Text(
                text = "Companion Activities",
                color = NamiText,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )

            // Grid of activities
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                items(CompanionActivity.entries) { activity ->
                    ActivityCard(
                        activity = activity,
                        onClick = {
                            gameManager.spendTime(activity)
                            onActivityTriggered()
                            onDismiss()
                        },
                    )
                }
            }

            // Rest Button (always visible, glows amber when tired)
            val energyLow = char.energy < 50
            Surface(
                onClick = {
                    gameManager.tellNamiToRest()
                    onActivityTriggered()
                    onDismiss()
                },
                shape = RoundedCornerShape(14.dp),
                color = if (energyLow) Color(0xFFFFB347).copy(alpha = 0.20f) else NamiPanel.copy(alpha = 0.80f),
                border = BorderStroke(
                    width = if (energyLow) 1.5.dp else 1.dp,
                    color = if (energyLow) Color(0xFFFFB347).copy(alpha = 0.85f) else NamiBorder.copy(alpha = 0.6f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💤", fontSize = 20.sp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Tell Nami to Rest",
                                color = if (energyLow) Color(0xFFFFB347) else NamiText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (energyLow) "She looks tired... let her nap ⚡" else "Restore energy with a short nap",
                                color = NamiMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    Text(
                        text = "+35 ⚡",
                        color = if (energyLow) Color(0xFFFFB347) else NamiAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(
    activity: CompanionActivity,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = NamiPanel.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, NamiBorder),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = activity.icon, fontSize = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                text = activity.label,
                color = NamiText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            val statReward = when {
                activity.hungerRestore > 0 -> "+${activity.hungerRestore} 🍽"
                activity.energyRestore > 0 -> "+${activity.energyRestore} ⚡"
                else -> "+${activity.affectionGain} ♡"
            }
            Text(
                text = statReward,
                color = if (activity.affectionGain > 0) NamiBlush else NamiAccent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    badge: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = NamiAccent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = label,
                    color = NamiText,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (badge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = 2.dp)
                        .size(13.dp)
                        .clip(RoundedCornerShape(6.5.dp))
                        .background(if (badge == "★") MoodHappy else NamiBlush),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = badge,
                        color = NamiDeep,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniStat(icon: String, value: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(text = icon, fontSize = 10.5.sp)
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(3.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(NamiBorder),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth((value / 100f).coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when {
                            value < 25 -> Color(0xFFEF5350)
                            value < 50 -> Color(0xFFFFB74D)
                            else -> NamiAccent
                        }
                    ),
            )
        }
    }
}
