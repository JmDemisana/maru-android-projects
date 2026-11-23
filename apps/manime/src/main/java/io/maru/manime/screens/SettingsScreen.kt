package io.maru.manime.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.maru.manime.*

@Composable
fun SettingsScreen(
    user: AniListUser?,
    anilistUsername: String,
    isLoggedIn: Boolean,
    preferredAudio: String = "SUB",
    onSelectPreferredAudio: (String) -> Unit = {},
    preferredQuality: String = "1080p",
    onSelectPreferredQuality: (String) -> Unit = {},
    autoSkipIntro: Boolean = false,
    onToggleAutoSkipIntro: (Boolean) -> Unit = {},
    reportProgress: Boolean,
    onToggleReportProgress: (Boolean) -> Unit,
    rememberPosition: Boolean,
    onToggleRememberPosition: (Boolean) -> Unit,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // AniList Account Section
        item(key = "account_card") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    GlassSectionHeader(
                        title = "ANILIST ACCOUNT & SYNC",
                        icon = Icons.Default.AccountCircle,
                        color = CrunchyOrange
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (user?.avatarUrl != null) {
                            AsyncImage(
                                model = user.avatarUrl,
                                contentDescription = user.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = CrunchyOrange,
                                modifier = Modifier.size(54.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = user?.name ?: if (isLoggedIn) anilistUsername.ifEmpty { "AniList Connected" } else "Guest User",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaruTextStrong
                            )
                            Text(
                                text = if (isLoggedIn) "Watchlist & Episode Progress Synced" else "Sign in to sync your watchlist & episodes",
                                fontSize = 12.sp,
                                color = if (isLoggedIn) CrunchyTeal else MaruTextMuted
                            )
                        }
                    }

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    if (isLoggedIn) {
                        Button(
                            onClick = onLogoutClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x33E74C3C)),
                            border = BorderStroke(1.dp, MaruDanger),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaruDanger, modifier = Modifier.size(18.dp))
                                Text("LOG OUT OF ANILIST", color = MaruDanger, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
                            }
                        }
                    } else {
                        Button(
                            onClick = onLoginClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("CONNECT ANILIST ACCOUNT", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Stream & Audio Preferences
        item(key = "stream_prefs") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassSectionHeader(
                        title = "STREAMING & AUDIO PREFERENCES",
                        icon = Icons.Default.Tune,
                        color = CrunchyOrange
                    )

                    Text("Default Audio Language", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaruTextStrong)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("SUB" to "Japanese (Subtitled)", "DUB" to "English Dub").forEach { (key, label) ->
                            val selected = preferredAudio == key
                            Surface(
                                onClick = { onSelectPreferredAudio(key) },
                                shape = RoundedCornerShape(4.dp),
                                color = if (selected) CrunchyOrange else CrunchySurface,
                                border = BorderStroke(1.dp, if (selected) CrunchyOrange else CrunchyBorder),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(modifier = Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (selected) Color.Black else MaruTextStrong
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    Text("Preferred Video Quality", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaruTextStrong)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("1080p", "720p", "4K").forEach { q ->
                            val selected = preferredQuality == q
                            Surface(
                                onClick = { onSelectPreferredQuality(q) },
                                shape = RoundedCornerShape(4.dp),
                                color = if (selected) CrunchyOrange else CrunchySurface,
                                border = BorderStroke(1.dp, if (selected) CrunchyOrange else CrunchyBorder),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = q,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (selected) Color.Black else MaruTextStrong
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Playback & Tracker Settings
        item(key = "player_settings") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassSectionHeader(
                        title = "PLAYBACK & AUTOMATION",
                        icon = Icons.Default.PlayCircleOutline,
                        color = CrunchyOrange
                    )

                    GlassFeatureRow(
                        label = "Auto-Sync Watched Episodes to AniList",
                        checked = reportProgress,
                        onCheckedChange = onToggleReportProgress
                    )

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    GlassFeatureRow(
                        label = "Remember Episode Timestamp",
                        checked = rememberPosition,
                        onCheckedChange = onToggleRememberPosition
                    )

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    GlassFeatureRow(
                        label = "Automatically Skip Anime Intros (AniSkip)",
                        checked = autoSkipIntro,
                        onCheckedChange = onToggleAutoSkipIntro
                    )
                }
            }
        }

        // App Information Card
        item(key = "app_info") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GlassSectionHeader(
                        title = "ABOUT NANAMI ANIME",
                        icon = Icons.Default.Info,
                        color = CrunchyTeal
                    )
                    Text("Nanami Anime v2.0.0", fontSize = 13.sp, color = MaruTextStrong, fontWeight = FontWeight.Bold)
                    Text("Stream simulcasts, switch Sub & Dub audio, sync your AniList watchlist, and save episodes in app internal storage. Offline files are not encrypted.", fontSize = 12.sp, color = MaruTextMuted, lineHeight = 17.sp)
                }
            }
        }
    }
}
