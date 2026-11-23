package io.maru.manime.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val context = LocalContext.current
    val versionName = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 720.dp)
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
                        title = "AniList account",
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
                                text = if (isLoggedIn) "Connected to AniList" else "Connect to sync your watchlist and episode progress.",
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
                                Text("Log out of AniList", color = MaruDanger, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
                            }
                        }
                    } else {
                        Button(
                            onClick = onLoginClick,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("Connect AniList", color = Color.Black, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
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
                        title = "Streaming preferences",
                        icon = Icons.Default.Tune,
                        color = CrunchyOrange
                    )

                    Text("Default audio language", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaruTextStrong)
                    Row(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingChoice("Japanese with subtitles", preferredAudio == "SUB", Modifier.weight(1f)) {
                            onSelectPreferredAudio("SUB")
                        }
                        SettingChoice("English dub", preferredAudio == "DUB", Modifier.weight(1f)) {
                            onSelectPreferredAudio("DUB")
                        }
                    }

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    Text("Preferred video quality", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaruTextStrong)
                    Row(
                        modifier = Modifier.fillMaxWidth().selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("720p", "1080p", "4K").forEach { quality ->
                            SettingChoice(quality, preferredQuality == quality, Modifier.weight(1f)) {
                                onSelectPreferredQuality(quality)
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
                        title = "Playback",
                        icon = Icons.Default.PlayCircleOutline,
                        color = CrunchyOrange
                    )

                    GlassFeatureRow(
                        label = "Sync watched episodes to AniList",
                        checked = reportProgress,
                        onCheckedChange = onToggleReportProgress
                    )

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    GlassFeatureRow(
                        label = "Remember where I stopped",
                        checked = rememberPosition,
                        onCheckedChange = onToggleRememberPosition
                    )

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    GlassFeatureRow(
                        label = "Skip intros automatically",
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
                        title = "About",
                        icon = Icons.Default.Info,
                        color = CrunchyTeal
                    )
                    Text("Nanami Anime $versionName".trimEnd(), fontSize = 13.sp, color = MaruTextStrong, fontWeight = FontWeight.Bold)
                    Text("Downloaded episodes are stored inside the app.", fontSize = 12.sp, color = MaruTextMuted, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingChoice(label: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaruPillShape,
        color = if (isSelected) CrunchyOrange else CrunchySurface,
        border = BorderStroke(1.dp, if (isSelected) CrunchyOrange else CrunchyBorder),
        modifier = modifier.semantics { selected = isSelected }
    ) {
        Box(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) Color.Black else MaruTextStrong,
                textAlign = TextAlign.Center
            )
        }
    }
}
