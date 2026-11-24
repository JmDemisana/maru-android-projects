package io.maru.manime.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.maru.manime.*

@Composable
fun SettingsScreen(
    user: AniListUser?,
    anilistUsername: String,
    isLoggedIn: Boolean,
    savedAccounts: List<SavedAniListAccount> = emptyList(),
    hideNsfwContent: Boolean = true,
    canShowNsfwContent: Boolean = false,
    onToggleHideNsfwContent: (Boolean) -> Unit = {},
    doNotTrackNsfw: Boolean = false,
    onToggleDoNotTrackNsfw: (Boolean) -> Unit = {},
    nsfwAnilistAccountId: Int? = null,
    onSelectNsfwAnilistAccount: (Int?) -> Unit = {},
    pendingSyncCount: Int = 0,
    isSyncingQueue: Boolean = false,
    onSyncNow: () -> Unit = {},
    offlineCount: Int = 0,
    offlineTotalMb: Long = 0L,
    allowCellularDownload: Boolean = false,
    onToggleAllowCellularDownload: (Boolean) -> Unit = {},
    seedDownloadOnWifi: Boolean = false,
    onToggleSeedDownloadOnWifi: (Boolean) -> Unit = {},
    preferredAudio: String = "SUB",
    onSelectPreferredAudio: (String) -> Unit = {},
    preferredQuality: String = "1080p",
    onSelectPreferredQuality: (String) -> Unit = {},
    playbackSpeed: Float = 1.0f,
    onSelectPlaybackSpeed: (Float) -> Unit = {},
    autoSkipIntro: Boolean = false,
    onToggleAutoSkipIntro: (Boolean) -> Unit = {},
    swipeGesturesEnabled: Boolean = true,
    onToggleSwipeGestures: (Boolean) -> Unit = {},
    persistentDurationLine: Boolean = false,
    onTogglePersistentDurationLine: (Boolean) -> Unit = {},
    subtitleBlackFill: Boolean = true,
    onToggleSubtitleBlackFill: (Boolean) -> Unit = {},
    subtitleSize: String = "MEDIUM",
    onSelectSubtitleSize: (String) -> Unit = {},
    reportProgress: Boolean,
    onToggleReportProgress: (Boolean) -> Unit,
    trackWatchedPercent: Int = 80,
    onSelectTrackWatchedPercent: (Int) -> Unit = {},
    rememberPosition: Boolean,
    onToggleRememberPosition: (Boolean) -> Unit,
    onLoginClick: () -> Unit,
    onAddAccountClick: () -> Unit = onLoginClick,
    onSwitchAccount: (SavedAniListAccount) -> Unit = {},
    onRemoveAccount: (SavedAniListAccount) -> Unit = {},
    onGuestModeClick: () -> Unit = {},
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
        // 1. Multi-Account AniList & Guest Mode Section
        item(key = "account_card") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    GlassSectionHeader(
                        title = "AniList Accounts & Sync",
                        icon = Icons.Default.AccountCircle,
                        color = CrunchyOrange
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (user?.avatarUrl != null && isLoggedIn) {
                            CrossfadeImage(
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
                                tint = if (isLoggedIn) CrunchyOrange else MaruTextMuted,
                                modifier = Modifier.size(54.dp)
                            )
                        }

                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = if (isLoggedIn) {
                                    user?.name ?: anilistUsername.ifEmpty { "AniList Connected" }
                                } else {
                                    "Local Guest Mode"
                                },
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaruTextStrong
                            )
                            Text(
                                text = if (isLoggedIn) {
                                    "Syncing watchlist & watched episodes at ${trackWatchedPercent}%"
                                } else {
                                    "Watchlists, progress & downloads work locally without signing in."
                                },
                                fontSize = 12.sp,
                                color = if (isLoggedIn) CrunchyTeal else MaruTextMuted,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    // Pending Offline Sync Queue Banner
                    if (pendingSyncCount > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF141820),
                            border = BorderStroke(1.dp, CrunchyTeal.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isSyncingQueue) "Syncing $pendingSyncCount queued updates…" else "$pendingSyncCount offline updates queued",
                                        color = CrunchyTeal,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.5.sp
                                    )
                                    Text(
                                        text = "Automatically syncs to AniList as soon as internet returns.",
                                        color = MaruTextMuted,
                                        fontSize = 11.sp
                                    )
                                }
                                if (!isSyncingQueue && isLoggedIn) {
                                    TextButton(onClick = onSyncNow) {
                                        Text("Sync Now", color = CrunchyOrange, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                            }
                        }
                    }

                    // Saved Multi-Account Switcher List
                    if (savedAccounts.isNotEmpty()) {
                        HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)
                        Text(
                            text = if (savedAccounts.size > 1) "SWITCH ACCOUNT" else "SAVED ACCOUNT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = CrunchyOrange,
                            letterSpacing = 0.8.sp
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            savedAccounts.forEach { acc ->
                                val isCurrent = isLoggedIn && acc.username.equals(anilistUsername, ignoreCase = true)
                                Surface(
                                    onClick = { if (!isCurrent) onSwitchAccount(acc) },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isCurrent) CrunchyOrange.copy(alpha = 0.16f) else CrunchySurface,
                                    border = BorderStroke(1.dp, if (isCurrent) CrunchyOrange else CrunchyBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        if (acc.avatarUrl != null) {
                                            CrossfadeImage(
                                                model = acc.avatarUrl,
                                                contentDescription = acc.username,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.Person,
                                                contentDescription = null,
                                                tint = CrunchyOrange,
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "@${acc.username}",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                            Text(
                                                text = if (isCurrent) "Active account" else "Tap to switch",
                                                fontSize = 11.sp,
                                                color = if (isCurrent) CrunchyOrange else MaruTextMuted
                                            )
                                        }

                                        if (isCurrent) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = CrunchyOrange
                                            ) {
                                                Text(
                                                    text = "ACTIVE",
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.Black,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                )
                                            }
                                        }

                                        IconButton(onClick = { onRemoveAccount(acc) }) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Remove @${acc.username}",
                                                tint = MaruTextMuted,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = if (isLoggedIn || savedAccounts.isNotEmpty()) onAddAccountClick else onLoginClick,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CrunchyOrange, contentColor = Color.Black),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (savedAccounts.isNotEmpty()) "Add Account" else "Connect AniList",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.5.sp
                            )
                        }

                        if (isLoggedIn) {
                            OutlinedButton(
                                onClick = onGuestModeClick,
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, CrunchyBorder),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Guest Mode",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp
                                )
                            }
                        }
                    }

                    if (isLoggedIn) {
                        TextButton(
                            onClick = onLogoutClick,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaruDanger, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Remove Active Account", color = MaruDanger, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        }
                    }
                }
            }
        }

        item(key = "nsfw_content") {
            NsfwContentSettings(
                isLoggedIn = isLoggedIn,
                savedAccounts = savedAccounts,
                hideNsfwContent = hideNsfwContent,
                canShowNsfwContent = canShowNsfwContent,
                onToggleHideNsfwContent = onToggleHideNsfwContent,
                doNotTrackNsfw = doNotTrackNsfw,
                onToggleDoNotTrackNsfw = onToggleDoNotTrackNsfw,
                nsfwAnilistAccountId = nsfwAnilistAccountId,
                onSelectNsfwAnilistAccount = onSelectNsfwAnilistAccount
            )
        }

        // 2. Stream & Audio Preferences
        item(key = "stream_prefs") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    GlassSectionHeader(
                        title = "Streaming Preferences",
                        icon = Icons.Default.Tune,
                        color = CrunchyOrange
                    )

                    Text("Default audio language", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaruTextStrong)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SettingChoice("Japanese (Sub)", preferredAudio == "SUB", Modifier.weight(1f)) {
                            onSelectPreferredAudio("SUB")
                        }
                        SettingChoice("English Dub", preferredAudio == "DUB", Modifier.weight(1f)) {
                            onSelectPreferredAudio("DUB")
                        }
                    }

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    Text("Preferred video quality", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaruTextStrong)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
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

        // 3. Playback, Gestures & Scrobbling Settings
        item(key = "player_settings") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GlassSectionHeader(
                        title = "Playback & Gestures",
                        icon = Icons.Default.PlayCircleOutline,
                        color = CrunchyOrange
                    )

                    GlassFeatureRow(
                        label = "Swipe up/down for brightness & volume",
                        subtitle = "Left side adjusts screen brightness, right side adjusts volume",
                        checked = swipeGesturesEnabled,
                        onCheckedChange = onToggleSwipeGestures
                    )

                    GlassFeatureRow(
                        label = "Persistent bottom progress bar",
                        subtitle = "Shows a slim elapsed ratio line at the bottom like YouTube when controls are hidden",
                        checked = persistentDurationLine,
                        onCheckedChange = onTogglePersistentDurationLine
                    )

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    GlassFeatureRow(
                        label = "Black fill behind subtitles",
                        subtitle = "Crunchyroll-style dark background box behind subtitle lines",
                        checked = subtitleBlackFill,
                        onCheckedChange = onToggleSubtitleBlackFill
                    )

                    Text("Subtitle size", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaruTextStrong)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "SMALL" to "Small",
                            "MEDIUM" to "Normal",
                            "LARGE" to "Large",
                            "EXTRA_LARGE" to "XL"
                        ).forEach { (key, label) ->
                            SettingChoice(label, subtitleSize == key, Modifier.weight(1f)) {
                                onSelectSubtitleSize(key)
                            }
                        }
                    }

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    Text("Playback speed", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaruTextStrong)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                            SettingChoice("${spd}×", playbackSpeed == spd, Modifier.weight(1f)) {
                                onSelectPlaybackSpeed(spd)
                            }
                        }
                    }

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    GlassFeatureRow(
                        label = "Mark watched at ${trackWatchedPercent}% & queue offline sync",
                        subtitle = "Marks watched locally even offline and syncs to AniList once online",
                        checked = reportProgress,
                        onCheckedChange = onToggleReportProgress
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Track watched at percentage",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaruTextStrong
                            )
                            Text(
                                text = "${trackWatchedPercent}%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = CrunchyOrange
                            )
                        }
                        Slider(
                            value = trackWatchedPercent.coerceIn(10, 100).toFloat(),
                            onValueChange = { raw ->
                                onSelectTrackWatchedPercent(raw.toInt().coerceIn(10, 100))
                            },
                            valueRange = 10f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = CrunchyOrange,
                                activeTrackColor = CrunchyOrange,
                                inactiveTrackColor = CrunchyBorder
                            )
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(50, 75, 80, 90, 95).forEach { preset ->
                                SettingChoice("${preset}%", trackWatchedPercent == preset, Modifier.weight(1f)) {
                                    onSelectTrackWatchedPercent(preset)
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    GlassFeatureRow(
                        label = "Remember playback position",
                        subtitle = "Resume episodes right where you stopped",
                        checked = rememberPosition,
                        onCheckedChange = onToggleRememberPosition
                    )

                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)

                    GlassFeatureRow(
                        label = "Skip intros automatically",
                        subtitle = "Jump past openings automatically using AniSkip timestamps",
                        checked = autoSkipIntro,
                        onCheckedChange = onToggleAutoSkipIntro
                    )
                }
            }
        }

        // 4. Private Offline Vault & App Information
        item(key = "app_info") {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GlassSectionHeader(
                        title = "Private Offline Vault & About",
                        icon = Icons.Default.Lock,
                        color = CrunchyTeal
                    )
                    Text(
                        text = if (offlineCount > 0) {
                            "$offlineCount downloaded episode${if (offlineCount == 1) "" else "s"} • $offlineTotalMb MB used"
                        } else {
                            "0 downloaded episodes stored"
                        },
                        fontSize = 13.5.sp,
                        color = CrunchyTeal,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Downloads are sealed inside Nanami Anime's private internal vault (.nanami + .nomedia) so they never clutter your phone's gallery or external video players.",
                        fontSize = 12.sp,
                        color = MaruTextMuted,
                        lineHeight = 17.sp
                    )
                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)
                    GlassFeatureRow(
                        label = "Allow Download with Cellular",
                        subtitle = "Allow offline episode downloads over mobile data when Wi-Fi is unavailable",
                        checked = allowCellularDownload,
                        onCheckedChange = onToggleAllowCellularDownload
                    )
                    GlassFeatureRow(
                        label = "Seed My Download on WiFi",
                        subtitle = "Shares completed torrent pieces over Wi-Fi (using zero extra storage or mobile data) to keep rare and older anime alive and fast for everyone",
                        checked = seedDownloadOnWifi,
                        onCheckedChange = onToggleSeedDownloadOnWifi
                    )
                    HorizontalDivider(color = CrunchyBorder, thickness = 1.dp)
                    Text(
                        text = "Nanami Anime $versionName".trimEnd(),
                        fontSize = 13.sp,
                        color = MaruTextStrong,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun NsfwContentSettings(
    isLoggedIn: Boolean,
    savedAccounts: List<SavedAniListAccount>,
    hideNsfwContent: Boolean,
    canShowNsfwContent: Boolean,
    onToggleHideNsfwContent: (Boolean) -> Unit,
    doNotTrackNsfw: Boolean,
    onToggleDoNotTrackNsfw: (Boolean) -> Unit,
    nsfwAnilistAccountId: Int?,
    onSelectNsfwAnilistAccount: (Int?) -> Unit
) {
    val hideContent = hideNsfwContent || !canShowNsfwContent
    val trackingAccounts = savedAccounts.filter { it.id > 0 }
    val selectedAccount = trackingAccounts.firstOrNull { it.id == nsfwAnilistAccountId }
    val canSelectTrackingAccount = isLoggedIn && trackingAccounts.isNotEmpty() && !doNotTrackNsfw
    var accountMenuExpanded by remember(canSelectTrackingAccount) { mutableStateOf(false) }

    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GlassSectionHeader(
                title = "NSFW Content",
                icon = Icons.Default.VisibilityOff,
                color = CrunchyOrange
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = hideContent,
                        enabled = canShowNsfwContent,
                        role = Role.Switch,
                        onValueChange = onToggleHideNsfwContent
                    )
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Hide NSFW content", color = MaruTextStrong, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        text = when {
                            !isLoggedIn -> "NSFW titles stay hidden in Guest Mode."
                            !canShowNsfwContent -> "Enable Adult Content in your active AniList account to change this setting."
                            else -> "Hide adult titles from discovery and playback."
                        },
                        color = MaruTextMuted,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    )
                }
                Switch(
                    checked = hideContent,
                    enabled = canShowNsfwContent,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = CrunchyOrange)
                )
            }

            HorizontalDivider(color = CrunchyBorder)

            GlassFeatureRow(
                label = "Do not track NSFW anime",
                subtitle = "Never record watch progress or list updates for explicit 18+ titles locally or on AniList.",
                checked = doNotTrackNsfw,
                onCheckedChange = onToggleDoNotTrackNsfw
            )

            HorizontalDivider(color = CrunchyBorder)
            Text("NSFW tracking account", color = MaruTextStrong, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                text = if (doNotTrackNsfw) {
                    "NSFW tracking is turned off while 'Do not track NSFW anime' is enabled."
                } else {
                    "Choose which saved AniList account receives NSFW list updates and watch progress."
                },
                color = MaruTextMuted,
                fontSize = 11.5.sp,
                lineHeight = 15.sp
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { accountMenuExpanded = true },
                    enabled = canSelectTrackingAccount,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = when {
                            doNotTrackNsfw -> "Do not track NSFW anime"
                            selectedAccount != null -> "@${selectedAccount.username}"
                            else -> "Use active account"
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = accountMenuExpanded, onDismissRequest = { accountMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Use active account") },
                        modifier = Modifier.semantics { selected = selectedAccount == null },
                        onClick = {
                            accountMenuExpanded = false
                            onSelectNsfwAnilistAccount(null)
                        }
                    )
                    trackingAccounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text("@${account.username}") },
                            modifier = Modifier.semantics { selected = selectedAccount == account },
                            onClick = {
                                accountMenuExpanded = false
                                onSelectNsfwAnilistAccount(account.id)
                            }
                        )
                    }
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
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
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
