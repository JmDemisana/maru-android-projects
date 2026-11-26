package io.maru.movies

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun MoviesSettings(
    simkl: Simkl,
    account: String?,
    activeAccount: SimklAccount?,
    savedAccounts: List<SimklAccount>,
    pendingSyncCount: Int,
    isSyncingQueue: Boolean,
    syncMessage: String?,
    onAccountChanged: () -> Unit,
    onOpenWebAuth: (Boolean) -> Unit,
    onSync: () -> Unit,
    hideMature: Boolean,
    onHideMature: (Boolean) -> Unit,
    doNotTrackMature: Boolean,
    onDoNotTrackMature: (Boolean) -> Unit,
    reportProgress: Boolean,
    onReportProgress: (Boolean) -> Unit,
    rememberPosition: Boolean,
    onRememberPosition: (Boolean) -> Unit,
    preferredQuality: String,
    onPreferredQuality: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val playerPrefs = remember(context) { MoviesPreferences.player(context) }
    val vault = MoviesDownloadVault
    val downloads by vault.items.collectAsState()

    var threshold by remember { mutableFloatStateOf(MoviesPreferences.completion(context).toFloat()) }
    var playbackSpeed by remember { mutableFloatStateOf(playerPrefs.getFloat("speed", 1f).coerceIn(0.5f, 2f)) }
    var autoplay by remember { mutableStateOf(playerPrefs.getBoolean("autoplay", true)) }
    var timeline by remember { mutableStateOf(playerPrefs.getBoolean("persistent_progress", false)) }
    var swipeGestures by remember { mutableStateOf(playerPrefs.getBoolean("swipe_gestures", true)) }
    var autoSkip by remember { mutableStateOf(playerPrefs.getBoolean("auto_skip_intro", false)) }
    var subtitleBg by remember { mutableStateOf(playerPrefs.getBoolean("subtitle_background", true)) }
    var subtitleSize by remember { mutableFloatStateOf(playerPrefs.getFloat("subtitle_size", 1f).coerceIn(0.75f, 1.5f)) }

    var cellular by remember { mutableStateOf(vault.allowCellular(context)) }
    var seed by remember { mutableStateOf(vault.seedOnWifi(context)) }
    var bandwidth by remember { mutableIntStateOf(vault.torrentSpeedKib(context)) }

    val completedDownloads = remember(downloads) { downloads.filter { it.state == MovieDownloadState.COMPLETE } }
    val savedBytes = remember(completedDownloads) { completedDownloads.sumOf { it.progress?.total ?: 0L } }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 720.dp)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Multi-Account SIMKL & Guest Mode Card (Exact parity with Nanami Anime!)
        item(key = "simkl_accounts_card") {
            NetflixCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    NetflixSectionHeader(
                        title = "SIMKL Accounts & Sync",
                        icon = Icons.Default.AccountCircle,
                        color = NetflixRed,
                        modifier = Modifier,
                    )

                    SimklAccountsSection(
                        simkl = simkl,
                        activeKey = account,
                        activeAccount = activeAccount,
                        savedAccounts = savedAccounts,
                        pendingSyncCount = pendingSyncCount,
                        isSyncingQueue = isSyncingQueue,
                        syncMessage = syncMessage,
                        watchedPercent = threshold.roundToInt(),
                        onOpenWebAuth = onOpenWebAuth,
                        onAccountChanged = onAccountChanged,
                        onSync = onSync,
                    )
                }
            }
        }

        // 2. Playback & Subtitles Card
        item(key = "playback_card") {
            NetflixCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NetflixSectionHeader(
                        title = "Playback & Subtitles",
                        icon = Icons.Default.PlayCircleOutline,
                        color = NetflixRed,
                        modifier = Modifier,
                    )

                    Text("Preferred Stream Quality", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = NetflixTextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("4K", "1080p", "720p").forEach { qual ->
                            NetflixChip(
                                label = qual,
                                selected = preferredQuality == qual,
                                onClick = { onPreferredQuality(qual) },
                            )
                        }
                    }

                    Text("Default Playback Speed", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = NetflixTextMuted)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { spd ->
                            val label = "${spd.toString().removeSuffix(".0")}x"
                            NetflixChip(
                                label = label,
                                selected = playbackSpeed == spd,
                                onClick = {
                                    playbackSpeed = spd
                                    playerPrefs.edit().putFloat("speed", spd).apply()
                                },
                            )
                        }
                    }

                    Text("Subtitle Size", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = NetflixTextMuted)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("Small" to 0.8f, "Normal" to 1f, "Large" to 1.25f, "XL" to 1.45f).forEach { (label, sz) ->
                            NetflixChip(
                                label = label,
                                selected = kotlin.math.abs(subtitleSize - sz) < 0.05f,
                                onClick = {
                                    subtitleSize = sz
                                    playerPrefs.edit().putFloat("subtitle_size", sz).apply()
                                },
                            )
                        }
                    }

                    HorizontalDivider(color = NetflixBorder)

                    NetflixFeatureRow(
                        label = "Black Background Behind Subtitles",
                        subtitle = "Improves readability over bright cinema scenes",
                        checked = subtitleBg,
                        onCheckedChange = { subtitleBg = it; playerPrefs.edit().putBoolean("subtitle_background", it).apply() },
                        modifier = Modifier,
                    )
                    NetflixFeatureRow(
                        label = "Autoplay Next Episode",
                        subtitle = "Starts an 8-second Up Next countdown when an episode finishes",
                        checked = autoplay,
                        onCheckedChange = { autoplay = it; playerPrefs.edit().putBoolean("autoplay", it).apply() },
                        modifier = Modifier,
                    )
                    NetflixFeatureRow(
                        label = "Auto-Skip Verified Intros",
                        subtitle = "Uses IntroDB chapter timestamps when available",
                        checked = autoSkip,
                        onCheckedChange = { autoSkip = it; playerPrefs.edit().putBoolean("auto_skip_intro", it).apply() },
                        modifier = Modifier,
                    )
                    NetflixFeatureRow(
                        label = "Vertical Brightness & Volume Gestures",
                        subtitle = "Swipe left side for brightness, right side for volume",
                        checked = swipeGestures,
                        onCheckedChange = { swipeGestures = it; playerPrefs.edit().putBoolean("swipe_gestures", it).apply() },
                        modifier = Modifier,
                    )
                    NetflixFeatureRow(
                        label = "Persistent Bottom Progress Line",
                        subtitle = "Shows a slim 2.5dp crimson timeline while controls are hidden",
                        checked = timeline,
                        onCheckedChange = { timeline = it; playerPrefs.edit().putBoolean("persistent_progress", it).apply() },
                        modifier = Modifier,
                    )
                }
            }
        }

        // 3. Watch Tracking & Maturity Card
        item(key = "tracking_maturity_card") {
            NetflixCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NetflixSectionHeader(
                        title = "Watch Tracking & Maturity",
                        icon = Icons.Default.TrackChanges,
                        color = NetflixRed,
                        modifier = Modifier,
                    )

                    NetflixFeatureRow(
                        label = "Track Watch Progress",
                        subtitle = "Records episode progress locally and queues SIMKL updates",
                        checked = reportProgress,
                        onCheckedChange = onReportProgress,
                        modifier = Modifier,
                    )
                    NetflixFeatureRow(
                        label = "Remember Resume Position",
                        subtitle = "Picks up movies and episodes right where you left off",
                        checked = rememberPosition,
                        onCheckedChange = onRememberPosition,
                        modifier = Modifier,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Mark Watched at ${threshold.roundToInt()}%",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(75, 80, 85, 90, 95).forEach { pct ->
                                NetflixChip(
                                    label = "$pct%",
                                    selected = threshold.roundToInt() == pct,
                                    onClick = {
                                        threshold = pct.toFloat()
                                        MoviesPreferences.setCompletion(context, pct)
                                    },
                                )
                            }
                        }
                        Slider(
                            value = threshold,
                            valueRange = 50f..100f,
                            steps = 9,
                            onValueChange = { threshold = it },
                            onValueChangeFinished = { MoviesPreferences.setCompletion(context, threshold.roundToInt()) },
                            colors = SliderDefaults.colors(
                                thumbColor = NetflixRed,
                                activeTrackColor = NetflixRed,
                                inactiveTrackColor = NetflixBorder,
                            ),
                        )
                    }

                    HorizontalDivider(color = NetflixBorder)

                    NetflixFeatureRow(
                        label = "Hide Mature Titles (R / TV-MA / 18+)",
                        subtitle = "Filters mature movies and series across Home, Search, and My List",
                        checked = hideMature,
                        onCheckedChange = onHideMature,
                        modifier = Modifier,
                    )
                    NetflixFeatureRow(
                        label = "Do Not Track Mature Titles",
                        subtitle = "Suppresses watch history and SIMKL sync on R / TV-MA / 18+ titles",
                        checked = doNotTrackMature,
                        onCheckedChange = onDoNotTrackMature,
                        modifier = Modifier,
                    )
                }
            }
        }

        // 4. Downloads & Private Offline Vault Card
        item(key = "downloads_vault_card") {
            NetflixCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NetflixSectionHeader(
                        title = "Private Offline Vault",
                        icon = Icons.Default.CloudDownload,
                        color = NetflixRed,
                        modifier = Modifier,
                    )
                    Text(
                        text = if (completedDownloads.isEmpty()) {
                            "0 titles saved offline • App-private encrypted storage (.nomedia)"
                        } else {
                            "${completedDownloads.size} saved offline • ${Formatter.formatFileSize(context, savedBytes)} used"
                        },
                        fontSize = 12.sp,
                        color = NetflixMatchGreen,
                    )
                    NetflixFeatureRow(
                        label = "Allow Downloads Over Cellular",
                        subtitle = "When off, downloads pause until Wi-Fi or Ethernet reconnects",
                        checked = cellular,
                        onCheckedChange = { cellular = it; vault.setAllowCellular(context, it) },
                        modifier = Modifier,
                    )
                    NetflixFeatureRow(
                        label = "Seed Completed Downloads on Wi-Fi",
                        subtitle = "Shares up to 30 min or 1.0x ratio strictly on Wi-Fi via zero-copy hardlink",
                        checked = seed,
                        onCheckedChange = { seed = it; vault.setSeedOnWifi(context, it) },
                        modifier = Modifier,
                    )
                    HorizontalDivider(color = NetflixBorder)
                    Text(
                        text = if (bandwidth == 0) "Download Speed Cap • Unlimited" else "Download Speed Cap • $bandwidth KiB/s",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = NetflixTextMuted,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(0 to "Unlimited", 1_024 to "1 MiB/s", 2_048 to "2 MiB/s", 5_120 to "5 MiB/s").forEach { (spd, label) ->
                            NetflixChip(
                                label = label,
                                selected = bandwidth == spd,
                                onClick = { bandwidth = spd; vault.setTorrentSpeedKib(context, spd) },
                            )
                        }
                    }
                }
            }
        }

        // 5. Video Sources & Stremio Addons Card
        item(key = "addons_card") {
            NetflixCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NetflixSectionHeader(
                        title = "Video Sources & Addons",
                        icon = Icons.Default.Extension,
                        color = NetflixRed,
                        modifier = Modifier,
                    )
                    MoviesAddonSettings()
                }
            }
        }

        // 6. About, Updater & Diagnostics Card
        item(key = "about_card") {
            NetflixCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NetflixSectionHeader(
                        title = "About Nanami Movies & Diagnostics",
                        icon = Icons.Default.Info,
                        color = NetflixRed,
                        modifier = Modifier,
                    )
                    MoviesAbout()
                }
            }
        }
    }
}

@Composable
private fun SimklAccountsSection(
    simkl: Simkl,
    activeKey: String?,
    activeAccount: SimklAccount?,
    savedAccounts: List<SimklAccount>,
    pendingSyncCount: Int,
    isSyncingQueue: Boolean,
    syncMessage: String?,
    watchedPercent: Int,
    onOpenWebAuth: (Boolean) -> Unit,
    onAccountChanged: () -> Unit,
    onSync: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isLoggedIn = activeKey != null

    var showPinDialog by remember { mutableStateOf(false) }
    var showClientDialog by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<SimklAccount?>(null) }
    var pinCode by remember { mutableStateOf<String?>(null) }
    var pinClientDraft by remember { mutableStateOf(simkl.clientId) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pinJob by remember { mutableStateOf<Job?>(null) }
    val busy = pinJob != null

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (activeAccount?.avatarUrl != null && isLoggedIn) {
            CrossfadeArtwork(
                model = activeAccount.avatarUrl,
                contentDescription = activeAccount.label,
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape),
            )
        } else {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = if (isLoggedIn) NetflixRed else NetflixTextMuted,
                modifier = Modifier.size(54.dp),
            )
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = if (isLoggedIn) (activeAccount?.label ?: "SIMKL Connected") else "Local Guest Mode",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = if (isLoggedIn) {
                    syncMessage ?: "Syncing watchlist & watched titles at $watchedPercent%"
                } else {
                    "My List, watch progress & offline downloads work locally without signing in."
                },
                fontSize = 12.sp,
                color = if (isLoggedIn) NetflixMatchGreen else NetflixTextMuted,
                lineHeight = 16.sp,
            )
        }
    }

    if (pendingSyncCount > 0) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0xFF14181E),
            border = BorderStroke(1.dp, NetflixMatchGreen.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isSyncingQueue) "Syncing $pendingSyncCount queued updates…" else "$pendingSyncCount offline updates queued",
                        color = NetflixMatchGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp,
                    )
                    Text(
                        text = "Automatically syncs to SIMKL when online.",
                        color = NetflixTextMuted,
                        fontSize = 11.sp,
                    )
                }
                if (!isSyncingQueue && isLoggedIn) {
                    TextButton(onClick = onSync) {
                        Text("Sync Now", color = NetflixRed, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
    }

    if (savedAccounts.isNotEmpty()) {
        HorizontalDivider(color = NetflixBorder)
        Text(
            text = if (savedAccounts.size > 1) "SWITCH ACCOUNT" else "SAVED ACCOUNT",
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = NetflixRed,
            letterSpacing = 0.8.sp,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            savedAccounts.forEach { acc ->
                val isCurrent = isLoggedIn && acc.id == activeKey
                Surface(
                    onClick = {
                        if (!isCurrent) {
                            scope.launch {
                                runCatching { simkl.selectAccount(acc.id) }
                                onAccountChanged()
                            }
                        }
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = if (isCurrent) NetflixRed.copy(alpha = 0.16f) else NetflixSurface,
                    border = BorderStroke(1.dp, if (isCurrent) NetflixRed else NetflixBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (acc.avatarUrl != null) {
                            CrossfadeArtwork(
                                model = acc.avatarUrl,
                                contentDescription = acc.label,
                                modifier = Modifier.size(36.dp).clip(CircleShape),
                            )
                        } else {
                            Icon(Icons.Default.Person, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(36.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(acc.label, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text(
                                text = if (isCurrent) "Active account" else "Tap to switch",
                                fontSize = 11.sp,
                                color = if (isCurrent) NetflixRed else NetflixTextMuted,
                            )
                        }
                        IconButton(onClick = { removeTarget = acc }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Remove account", tint = NetflixTextMuted)
                        }
                    }
                }
            }
        }
    }

    // Primary Action Row: 1-Tap WebView Connect (just like Nanami Anime!) + Guest Mode / Sync / PIN
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { onOpenWebAuth(savedAccounts.isNotEmpty()) },
            colors = ButtonDefaults.buttonColors(containerColor = NetflixRed, contentColor = Color.White),
            shape = RoundedCornerShape(6.dp),
        ) {
            Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (savedAccounts.isEmpty()) "Connect SIMKL" else "+ Add Account",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
            )
        }

        OutlinedButton(
            onClick = {
                pinClientDraft = simkl.clientId
                errorText = null
                pinCode = null
                showPinDialog = true
            },
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, NetflixBorder),
        ) {
            Text("Use TV / PIN Code", color = Color.White, fontSize = 12.5.sp)
        }

        if (isLoggedIn) {
            OutlinedButton(
                onClick = onSync,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, NetflixBorder),
            ) {
                Text("Sync Now", color = Color.White, fontSize = 12.5.sp)
            }
            TextButton(onClick = {
                scope.launch {
                    runCatching { simkl.selectAccount(null) }
                    onAccountChanged()
                }
            }) {
                Text("Guest Mode", color = NetflixTextMuted, fontSize = 12.5.sp)
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = {
            pinClientDraft = simkl.clientId
            showClientDialog = true
        }) {
            Text(
                text = if (simkl.clientId.isNotBlank()) "SIMKL Client ID Configured • Edit" else "Configure SIMKL Client ID",
                color = NetflixTextMuted,
                fontSize = 11.5.sp,
            )
        }
    }

    removeTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            containerColor = NetflixSurface,
            title = { Text("Remove ${target.label}?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("You can reconnect this SIMKL account anytime.", color = NetflixTextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget = null
                    scope.launch {
                        runCatching { simkl.removeAccount(target.id) }
                        onAccountChanged()
                    }
                }) { Text("Remove", color = NetflixRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel", color = Color.White) }
            },
        )
    }

    if (showClientDialog) {
        AlertDialog(
            onDismissRequest = { showClientDialog = false },
            containerColor = NetflixSurface,
            title = { Text("SIMKL AUTH V2 Client ID", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Redirect URI: https://maruchan.pages.dev/simkl-integration\nLeave blank to auto-discover from maruchan.pages.dev.",
                        color = NetflixTextMuted,
                        fontSize = 12.sp,
                    )
                    OutlinedTextField(
                        value = pinClientDraft,
                        onValueChange = { pinClientDraft = it.trim() },
                        label = { Text("Client ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NetflixRed,
                            unfocusedBorderColor = NetflixBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    simkl.setCustomClientId(pinClientDraft)
                    showClientDialog = false
                }) { Text("Save", color = NetflixRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showClientDialog = false }) { Text("Cancel", color = Color.White) }
            },
        )
    }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showPinDialog = false },
            containerColor = NetflixSurface,
            title = { Text("Connect SIMKL via PIN", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (simkl.clientId.isBlank()) {
                        OutlinedTextField(
                            value = pinClientDraft,
                            onValueChange = { pinClientDraft = it.trim() },
                            singleLine = true,
                            enabled = !busy,
                            label = { Text("SIMKL AUTH V2 Client ID") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NetflixRed,
                                unfocusedBorderColor = NetflixBorder,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                            ),
                        )
                    }
                    pinCode?.let { code ->
                        Text("Enter code at simkl.com/pin:", color = NetflixTextMuted, fontSize = 12.sp)
                        Text(code, color = NetflixGold, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                    }
                    errorText?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            dismissButton = {
                TextButton(onClick = { pinJob?.cancel(); pinJob = null; showPinDialog = false }) {
                    Text("Cancel", color = Color.White)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pinJob = scope.launch {
                            errorText = null
                            try {
                                val login = simkl.beginSignIn(pinClientDraft)
                                pinCode = login.userCode
                                openMoviesLink(context, login.verificationUrl)
                                simkl.awaitSignIn(login)
                                onAccountChanged()
                                showPinDialog = false
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                errorText = e.message ?: "SIMKL sign-in failed."
                            } finally {
                                pinCode = null
                                pinJob = null
                            }
                        }
                    },
                ) { Text(if (busy) "Waiting…" else "Start PIN Login", color = NetflixRed, fontWeight = FontWeight.Bold) }
            },
        )
    }
}

@Composable
private fun MoviesAddonSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf(MoviesPreferences.addons(context)) }
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text("Torrentio", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            Text("Built-in primary movie & series torrent resolver", color = NetflixTextMuted, fontSize = 11.5.sp)
        }
        Surface(color = NetflixMatchGreen.copy(alpha = 0.16f), shape = RoundedCornerShape(4.dp)) {
            Text(
                "ACTIVE",
                color = NetflixMatchGreen,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }

    addons.forEach { url ->
        HorizontalDivider(color = NetflixBorder)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(Uri.parse(url).host ?: "Stremio Add-on", Modifier.weight(1f), color = Color.White, fontSize = 13.5.sp)
            TextButton(onClick = {
                MoviesPreferences.removeAddon(context, url)
                addons = MoviesPreferences.addons(context)
            }) { Text("Remove", color = NetflixRed) }
        }
    }

    OutlinedButton(
        onClick = { input = ""; message = null; editing = true },
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, NetflixBorder),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text("Add Stremio Manifest Source", color = Color.White, fontSize = 12.5.sp)
    }
    if (!editing) message?.let { Text(it, color = NetflixMatchGreen, fontSize = 12.sp) }

    if (editing) {
        AlertDialog(
            onDismissRequest = { if (!busy) editing = false },
            containerColor = NetflixSurface,
            title = { Text("Add Stremio Source", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("HTTPS Manifest URL") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NetflixRed,
                            unfocusedBorderColor = NetflixBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                        ),
                    )
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { editing = false }) { Text("Cancel", color = Color.White) }
            },
            confirmButton = {
                TextButton(
                    enabled = input.isNotBlank() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            message = null
                            try {
                                val name = MoviesPreferences.addAddon(context, input)
                                addons = MoviesPreferences.addons(context)
                                editing = false
                                message = "Added $name."
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                message = if (error is IllegalArgumentException) error.message else "Couldn't load the add-on manifest."
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "Checking…" else "Add Source", color = NetflixRed, fontWeight = FontWeight.Bold) }
            },
        )
    }
}

@Composable
private fun MoviesAbout() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostic by MoviesDiagnosticsService.state.collectAsState()
    var update by remember { mutableStateOf<MoviesUpdate?>(null) }
    var apk by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun install(file: File) {
        status = try {
            if (MoviesUpdater.install(context, file)) "Update opened in the Android installer."
            else "Allow installs from Nanami Movies, then return and tap Install Update."
        } catch (error: Exception) {
            error.message ?: "Couldn't open the installer."
        }
    }

    Text(
        text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
    )
    Text(
        text = "Netflix-inspired Cinema & Western Animation companion sharing the Nanami streaming engine.",
        color = NetflixTextMuted,
        fontSize = 12.sp,
    )

    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (update == null) {
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        status = "Checking GitHub Releases…"
                        try {
                            update = MoviesUpdater.available()
                            status = update?.let { "Update ${it.name} is ready to download." } ?: "Nanami Movies is up to date."
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            status = error.message ?: "Couldn't check for updates."
                        } finally {
                            busy = false
                        }
                    }
                },
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, NetflixBorder),
            ) {
                Text("Check for Updates", color = Color.White, fontSize = 12.5.sp)
            }
        }
        update?.let { release ->
            Button(
                enabled = !busy,
                onClick = {
                    val downloaded = apk
                    if (downloaded != null) install(downloaded)
                    else scope.launch {
                        busy = true
                        status = "Downloading & verifying SHA-256 + signer…"
                        try {
                            val file = MoviesUpdater.download(context, release)
                            apk = file
                            install(file)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            update = null
                            status = error.message ?: "Couldn't download the update."
                        } finally {
                            busy = false
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed, contentColor = Color.White),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(if (apk == null) "Download ${release.name}" else "Install Update", fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton(
            onClick = {
                runCatching { openMoviesLink(context, MoviesUpdater.releasePage) }
                    .onFailure { status = "Couldn't open GitHub Releases." }
            },
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, NetflixBorder),
        ) {
            Text("Release Notes", color = Color.White, fontSize = 12.5.sp)
        }
    }

    HorizontalDivider(color = NetflixBorder)

    Text(
        text = "Service & Device Diagnostics",
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
    )
    Text(
        text = "Runs 7 background service & storage checks (continues even if you switch apps) and prepares a copyable report.",
        color = NetflixTextMuted,
        fontSize = 11.5.sp,
    )

    val current = diagnostic.current
    if (current == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    runCatching { MoviesDiagnosticsService.start(context) }
                        .onFailure { status = "Couldn't start diagnostics." }
                },
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, NetflixBorder),
            ) {
                Text("Generate Log Dump", color = Color.White, fontSize = 12.5.sp)
            }
            diagnostic.report?.let { report ->
                Button(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Nanami Movies diagnostics", report))
                        status = "Diagnostics copied to clipboard."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NetflixRed, contentColor = Color.White),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text("Copy Log Dump", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                }
            }
        }
    } else {
        Text("Checking: $current (${diagnostic.checked}/${diagnostic.total})", color = NetflixRed, fontSize = 12.sp)
        LinearProgressIndicator(
            progress = { diagnostic.checked.toFloat() / diagnostic.total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            color = NetflixRed,
            trackColor = NetflixBorder,
        )
        TextButton(onClick = { MoviesDiagnosticsService.cancel(context) }) {
            Text("Cancel Diagnostics", color = NetflixTextMuted)
        }
    }

    status?.let { Text(it, color = NetflixMatchGreen, fontSize = 12.sp) }
}

private fun openMoviesLink(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
