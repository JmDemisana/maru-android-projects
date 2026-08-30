package io.maru.manime.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
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
    reportProgress: Boolean,
    onToggleReportProgress: (Boolean) -> Unit,
    rememberPosition: Boolean,
    onToggleRememberPosition: (Boolean) -> Unit,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit
) {
    val context = LocalContext.current

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
                        title = "ANILIST ACCOUNT",
                        icon = Icons.Default.AccountCircle,
                        color = MaruAccentPurple
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
                                tint = if (isLoggedIn) MaruAccentPurple else MaruAccentPink,
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
                                text = if (isLoggedIn) "Authenticated & Synced" else "Sign in to sync your library across devices",
                                fontSize = 12.sp,
                                color = if (isLoggedIn) MaruAccentGreen else MaruTextMuted
                            )
                        }
                    }

                    HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)

                    if (isLoggedIn) {
                        Button(
                            onClick = onLogoutClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0x33FF5252)),
                            border = BorderStroke(1.dp, MaruDanger),
                            shape = MaruInputShape
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaruDanger, modifier = Modifier.size(18.dp))
                                Text("LOG OUT OF ANILIST", color = MaruDanger, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                            }
                        }
                    } else {
                        Button(
                            onClick = onLoginClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaruAccentPink),
                            shape = MaruInputShape
                        ) {
                            Text("LOGIN WITH ANILIST", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                        title = "PLAYBACK & TRACKING",
                        icon = Icons.Default.Settings,
                        color = MaruAccentPink
                    )

                    GlassFeatureRow(
                        label = "Sync Progress to AniList",
                        checked = reportProgress,
                        onCheckedChange = onToggleReportProgress
                    )

                    HorizontalDivider(color = MaruGlassBorderSoft, thickness = 1.dp)

                    GlassFeatureRow(
                        label = "Remember Playback Position",
                        checked = rememberPosition,
                        onCheckedChange = onToggleRememberPosition
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
                        title = "ABOUT MANIME",
                        icon = Icons.Default.Info,
                        color = MaruAccentBlue
                    )
                    Text("Version 1.0.0 (16KB Page-Aligned)", fontSize = 12.5.sp, color = MaruTextStrong, fontWeight = FontWeight.Medium)
                    Text("Native Material 3 AniList client powered by Jetpack Compose.", fontSize = 11.5.sp, color = MaruTextMuted)
                }
            }
        }
    }
}
