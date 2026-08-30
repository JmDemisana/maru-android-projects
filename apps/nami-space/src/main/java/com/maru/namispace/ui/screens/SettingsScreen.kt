package com.maru.namispace.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maru.namispace.engine.GameManager
import com.maru.namispace.model.AffectionState
import com.maru.namispace.model.AffectionTier
import com.maru.namispace.ui.components.AffectionBar
import com.maru.namispace.ui.components.OverlaySheet
import com.maru.namispace.ui.theme.*

@Composable
fun SettingsOverlay(
    gameManager: GameManager,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val session by gameManager.state.collectAsState()
    val tier = when {
        session.character.affection >= 50 -> AffectionTier.SENPAI
        session.character.affection >= 30 -> AffectionTier.BEST_FRIEND
        session.character.affection >= 15 -> AffectionTier.CLOSE_FRIEND
        session.character.affection >= 5 -> AffectionTier.FRIEND
        else -> AffectionTier.STRANGER
    }

    OverlaySheet(
        visible = visible,
        onDismiss = onDismiss,
        title = "Status & Journal",
        subtitle = "Bond progression, topics, and settings",
        maxHeightFraction = 0.85f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Relationship & Affection Progress
            SettingsSection(title = "RELATIONSHIP TIER") {
                AffectionBar(
                    state = AffectionState(level = session.character.affection),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Current Bond: ${tier.label}",
                        color = NamiText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Stories Unlocked: ${session.completedStories.size}",
                        color = NamiAccent,
                        fontSize = 12.sp,
                    )
                }
            }

            // Audio & Preferences
            SettingsSection(title = "PREFERENCES") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (session.bgmMuted) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = NamiAccent,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Background Music", color = NamiText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                if (session.bgmMuted) "Muted" else "Playing Mochi's Theme",
                                color = NamiMuted,
                                fontSize = 11.sp,
                            )
                        }
                    }
                    Switch(
                        checked = !session.bgmMuted,
                        onCheckedChange = { gameManager.toggleBgmMute() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NamiDeep,
                            checkedTrackColor = NamiAccent,
                            uncheckedThumbColor = NamiMuted,
                            uncheckedTrackColor = NamiBorder,
                        ),
                    )
                }
            }

            // Discovered Topics Journal
            SettingsSection(title = "DISCOVERED TOPICS (${session.topicsDiscussed.size})") {
                if (session.topicsDiscussed.isEmpty()) {
                    Text(
                        text = "Chat with Nanami about Vocaloid, rain, Mochi, food, or school to record memory topics here!",
                        color = NamiMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        session.topicsDiscussed.chunked(3).forEach { chunk ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                chunk.forEach { topic ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = NamiAccent.copy(alpha = 0.15f),
                                        border = BorderStroke(1.dp, NamiAccent.copy(alpha = 0.35f)),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            text = "✦ $topic",
                                            color = NamiAccent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                                // Fill remaining space if chunk < 3
                                repeat(3 - chunk.size) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            // Character Profile
            SettingsSection(title = "CHARACTER PROFILE") {
                Text(
                    text = "Nanami Shiro (白 七海)",
                    color = NamiText,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Birthday: February 10  •  Height: 5'2\" (157cm)",
                    color = NamiAccent,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Her name means Seven Seas. She likes avocado ice cream, Vocaloid playlists (especially GUMI), rainy bus stops, Mochi the Eevee, and teasing Senpai.",
                    color = NamiMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }

            // About Nami Space
            SettingsSection(title = "ABOUT NAMI SPACE") {
                Text(
                    text = "Nami Space  v0.1.0-alpha",
                    color = NamiText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Visual Novel & Tamagotchi companion app with authentic anime character interaction.",
                    color = NamiMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Original character created by Maru. GPL-3.0 Licensed.",
                    color = NamiMuted.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = NamiPanel.copy(alpha = 0.85f),
        border = BorderStroke(1.dp, NamiBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
        ) {
            Text(
                text = title,
                color = NamiAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
