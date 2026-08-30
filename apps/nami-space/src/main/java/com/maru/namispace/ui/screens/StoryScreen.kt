package com.maru.namispace.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.maru.namispace.engine.GameManager
import com.maru.namispace.engine.StoryManager
import com.maru.namispace.model.NamiMood
import com.maru.namispace.model.StoryChapter
import com.maru.namispace.ui.components.NamiCharacter
import com.maru.namispace.ui.components.OverlaySheet
import com.maru.namispace.ui.theme.*

@Composable
fun StoryOverlay(
    gameManager: GameManager,
    visible: Boolean,
    onDismiss: () -> Unit,
    onPlayChapter: (StoryChapter) -> Unit,
) {
    val session by gameManager.state.collectAsState()
    val stories = remember { StoryManager() }
    val chapters = stories.getChapters()

    OverlaySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Memories & Stories",
        subtitle = "${session.completedStories.size}/${chapters.size} moments unlocked",
        maxHeightFraction = 0.82f,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = NamiAccent.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, NamiAccent.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "✦ Your Journey with Nanami",
                            color = NamiText,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Spend time together and chat about topics to unlock special memory scenes.",
                            color = NamiMuted,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            items(chapters, key = { it.id }) { item ->
                val unlocked = stories.canUnlock(item, session.character.affection, session.topicsDiscussed)
                val completed = item.id in session.completedStories
                StoryCard(
                    chapter = item,
                    unlocked = unlocked,
                    completed = completed,
                    discussed = session.topicsDiscussed,
                    onClick = {
                        onDismiss()
                        onPlayChapter(item)
                    },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun StoryCard(
    chapter: StoryChapter,
    unlocked: Boolean,
    completed: Boolean,
    discussed: Set<String>,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (unlocked) NamiPanel.copy(alpha = 0.88f) else NamiPanel.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, if (completed) MoodHappy.copy(alpha = 0.4f) else if (unlocked) NamiBorder else NamiBorder.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = unlocked, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (completed) MoodHappy.copy(alpha = 0.18f)
                        else if (unlocked) NamiAccent.copy(alpha = 0.16f)
                        else NamiBorder.copy(alpha = 0.5f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        completed -> Icons.Filled.Star
                        unlocked -> Icons.Filled.PlayArrow
                        else -> Icons.Filled.Lock
                    },
                    contentDescription = null,
                    tint = when {
                        completed -> MoodHappy
                        unlocked -> NamiAccent
                        else -> NamiMuted.copy(alpha = 0.5f)
                    },
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    color = if (unlocked) NamiText else NamiMuted,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = chapter.description,
                    color = NamiMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                )
                if (!unlocked) {
                    val missing = chapter.requiredTopics.filterNot { it in discussed }
                    val reqText = "Need ${chapter.requiredAffection} affection" +
                            if (missing.isNotEmpty()) " • Discuss: ${missing.joinToString()}" else ""
                    Text(
                        text = reqText,
                        color = NamiBlush,
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (completed) {
                Text(
                    text = "Revisit",
                    color = NamiAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Full visual novel story player running seamlessly on the stage */
@Composable
fun StoryPlayer(
    chapter: StoryChapter,
    onComplete: (Int, String, NamiMood) -> Unit,
    onExit: () -> Unit,
) {
    var currentNodeId by remember { mutableStateOf(chapter.nodes.first().id) }
    val node = chapter.nodes.firstOrNull { it.id == currentNodeId }

    if (node == null) {
        onExit()
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f),
    ) {
        // Character Staging
        if (node.speaker != "narrator") {
            NamiCharacter(
                mood = node.mood,
                upClose = true,
                scale = 1.58f,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.TopCenter),
            )
        }

        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Surface(
                onClick = onExit,
                shape = RoundedCornerShape(12.dp),
                color = NamiPanel.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, NamiBorder),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Exit Memory",
                        tint = NamiText,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Exit", color = NamiText, fontSize = 12.sp)
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = NamiPanel.copy(alpha = 0.85f),
                border = BorderStroke(1.dp, NamiBorder),
            ) {
                Text(
                    text = chapter.title,
                    color = NamiAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Bottom Visual Novel Dialogue & Choice Area
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Dialogue text
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = NamiPanel.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, NamiBorder),
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = if (node.speaker == "narrator") "✦ MEMORY" else "✦ NANAMI",
                        color = if (node.speaker == "narrator") NamiMuted else NamiAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = node.text,
                        color = NamiText,
                        fontSize = 14.5.sp,
                        lineHeight = 22.sp,
                    )
                }
            }

            // Choices or Advance Button
            if (node.choices.isNotEmpty()) {
                node.choices.forEach { choice ->
                    Surface(
                        onClick = {
                            onComplete(choice.affectionBonus, choice.text, node.mood)
                            currentNodeId = choice.nextNodeId
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = NamiAccent.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, NamiAccent.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = choice.text,
                            color = NamiText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            } else {
                Surface(
                    onClick = {
                        val nextId = node.nextNodeId
                        if (nextId != null) {
                            currentNodeId = nextId
                        } else {
                            onComplete(5, node.text, node.mood)
                            onExit()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = NamiAccent,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (node.nextNodeId == null) "Finish Memory (+5 ★)" else "Continue ▶",
                        color = NamiDeep,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}
