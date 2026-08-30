package com.maru.namispace.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maru.namispace.ai.NamiLocalChatEngine
import com.maru.namispace.engine.GameManager
import com.maru.namispace.engine.NamiTalkTopics
import com.maru.namispace.model.ChatMessage
import com.maru.namispace.model.NamiMood
import com.maru.namispace.ui.components.ChatBubble
import com.maru.namispace.ui.components.OverlaySheet
import com.maru.namispace.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ChatOverlay(
    gameManager: GameManager,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localEngine = remember { NamiLocalChatEngine(context) }

    var messages by remember {
        mutableStateOf(
            listOf(
                ChatMessage(
                    content = "I'm right here, Senpai. Talk to me about music, rhythm games, or whatever is on your mind!",
                    isFromUser = false,
                    suggestedReplies = NamiTalkTopics.starterPrompts,
                ),
            )
        )
    }

    var inputText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    val listState = rememberLazyListState()

    // Live elapsed timer while thinking
    LaunchedEffect(isLoading) {
        if (isLoading) {
            val startTime = System.currentTimeMillis()
            while (isLoading) {
                elapsedMillis = System.currentTimeMillis() - startTime
                delay(100)
            }
        } else {
            elapsedMillis = 0L
        }
    }

    // Auto-scroll on new message
    LaunchedEffect(messages.size, visible) {
        if (visible && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || isLoading) return

        val userMsg = ChatMessage(content = text.trim(), isFromUser = true)
        messages = messages + userMsg
        NamiTalkTopics.findIn(text).forEach(gameManager::recordTopic)
        inputText = ""
        isLoading = true
        gameManager.setMood(NamiMood.THINKING)

        scope.launch {
            val startTime = System.currentTimeMillis()
            val history = messages.dropLast(1).takeLast(6).map { it.content to it.isFromUser }
            
            val response = localEngine.generateChatReply(
                userMessage = text,
                gameManager = gameManager,
                conversationHistory = history
            )
            val durationMs = System.currentTimeMillis() - startTime

            isLoading = false
            messages = messages + ChatMessage(
                content = response.text,
                isFromUser = false,
                suggestedReplies = response.suggestedChips,
                durationMs = durationMs,
            )

            gameManager.addChatAffection()
            gameManager.setMood(response.mood)
            gameManager.updateDialogue(response.text)
        }
    }

    OverlaySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Chat with Nami",
        subtitle = "Always by your side 🍵",
        maxHeightFraction = 0.88f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent),
        ) {
            // Messages stream
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(message = message)
                }

                if (isLoading) {
                    item {
                        val seconds = String.format(Locale.US, "%.1fs", elapsedMillis / 1000f)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MoodHappy,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Nami is thinking... ($seconds)",
                                color = NamiMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            // Horizontal scrolling suggested reply chips
            val lastBotMessage = messages.lastOrNull { !it.isFromUser }
            val currentChips = lastBotMessage?.suggestedReplies ?: emptyList()
            if (currentChips.isNotEmpty() && !isLoading) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(currentChips) { chip ->
                        Surface(
                            onClick = { sendMessage(chip) },
                            shape = RoundedCornerShape(16.dp),
                            color = NamiDeep.copy(alpha = 0.85f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NamiBorder.copy(alpha = 0.6f)),
                        ) {
                            Text(
                                text = chip,
                                color = NamiText,
                                fontSize = 11.5.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            // Input Bar
            Surface(
                color = NamiPanel,
                border = androidx.compose.foundation.BorderStroke(1.dp, NamiBorder),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .navigationBarsPadding()
                        .imePadding(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                "Say something to Nami...",
                                color = NamiMuted,
                                fontSize = 13.sp,
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = NamiDeep.copy(alpha = 0.6f),
                            unfocusedContainerColor = NamiDeep.copy(alpha = 0.6f),
                            focusedTextColor = NamiText,
                            unfocusedTextColor = NamiText,
                            cursorColor = MoodHappy,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 42.dp),
                        singleLine = false,
                        maxLines = 3,
                    )

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = { sendMessage(inputText) },
                        enabled = inputText.isNotBlank() && !isLoading,
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MoodHappy,
                            disabledContentColor = NamiMuted.copy(alpha = 0.3f),
                        ),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}
