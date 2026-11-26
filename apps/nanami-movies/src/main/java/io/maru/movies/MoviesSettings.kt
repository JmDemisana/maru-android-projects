package io.maru.movies

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.Formatter
import android.widget.Toast
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val playerPrefs = remember(context) { MoviesPreferences.player(context) }
    val vault = MoviesDownloadVault
    val downloads by vault.items.collectAsState()

    var threshold by remember { mutableIntStateOf(MoviesPreferences.completion(context)) }
    var playbackSpeed by remember { mutableFloatStateOf(playerPrefs.getFloat("speed", 1f).coerceIn(0.5f, 2f)) }
    var autoplay by remember { mutableStateOf(playerPrefs.getBoolean("autoplay", true)) }
    var timeline by remember { mutableStateOf(playerPrefs.getBoolean("persistent_progress", false)) }
    var swipeGestures by remember { mutableStateOf(playerPrefs.getBoolean("swipe_gestures", true)) }
    var autoSkip by remember { mutableStateOf(playerPrefs.getBoolean("auto_skip_intro", false)) }
    var subtitleBg by remember { mutableStateOf(playerPrefs.getBoolean("subtitle_background", true)) }
    var subtitleSize by remember { mutableFloatStateOf(playerPrefs.getFloat("subtitle_size", 1f).coerceIn(0.75f, 1.5f)) }
    var cellular by remember { mutableStateOf(vault.allowCellular(context)) }

    val completedDownloads = remember(downloads) { downloads.filter { it.state == MovieDownloadState.COMPLETE } }
    val savedBytes = remember(completedDownloads) { completedDownloads.sumOf { it.progress?.total ?: 0L } }
    val isLoggedIn = account != null

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 720.dp)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. SIMKL Accounts & Sync (Exact parity with Nanami Anime's AniList card!)
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
                                text = if (isLoggedIn) {
                                    activeAccount?.username ?: activeAccount?.label?.removePrefix("@") ?: "SIMKL Connected"
                                } else {
                                    "Local Guest Mode"
                                },
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = NetflixTextStrong,
                            )
                            Text(
                                text = if (isLoggedIn) {
                                    syncMessage ?: "Syncing My List & watched progress at $threshold%"
                                } else {
                                    "My List, watch history & downloads work locally without signing in."
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
                                        text = "Syncs automatically to SIMKL as soon as you're online.",
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
                        HorizontalDivider(color = NetflixBorder, thickness = 1.dp)
                        Text(
                            text = if (savedAccounts.size > 1) "SWITCH ACCOUNT" else "SAVED ACCOUNT",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = NetflixRed,
                            letterSpacing = 0.8.sp,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            savedAccounts.forEach { acc ->
                                val isCurrent = isLoggedIn && acc.id == account
                                Surface(
                                    onClick = {
                                        if (!isCurrent) {
                                            scope.launch {
                                                runCatching { simkl.selectAccount(acc.id) }
                                                onAccountChanged()
                                                Toast.makeText(context, "Switched to ${acc.label}", Toast.LENGTH_SHORT).show()
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
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape),
                                            )
                                        } else {
                                            Icon(
                                                Icons.Default.Person,
                                                contentDescription = null,
                                                tint = NetflixRed,
                                                modifier = Modifier.size(36.dp),
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = acc.label,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                            )
                                            Text(
                                                text = if (isCurrent) "Active account" else "Tap to switch",
                                                fontSize = 11.sp,
                                                color = if (isCurrent) NetflixRed else NetflixTextMuted,
                                            )
                                        }
                                        if (isCurrent) {
                                            Surface(shape = RoundedCornerShape(4.dp), color = NetflixRed) {
                                                Text(
                                                    text = "ACTIVE",
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    runCatching { simkl.removeAccount(acc.id) }
                                                    onAccountChanged()
                                                    Toast.makeText(context, "Removed ${acc.label}", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                        ) {
                                            Icon(
                                                Icons.Default.DeleteOutline,
                                                contentDescription = "Remove ${acc.label}",
                                                tint = NetflixTextMuted,
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { onOpenWebAuth(savedAccounts.isNotEmpty()) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = NetflixRed, contentColor = Color.White),
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (savedAccounts.isNotEmpty()) "Add Account" else "Connect SIMKL",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.5.sp,
                            )
                        }

                        if (isLoggedIn) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching { simkl.selectAccount(null) }
                                        onAccountChanged()
                                        Toast.makeText(context, "Switched to Local Guest Mode", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                border = BorderStroke(1.dp, NetflixBorder),
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Text(
                                    text = "Guest Mode",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                )
                            }
                        }
                    }

                    if (isLoggedIn) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching { simkl.disconnect() }
                                    onAccountChanged()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Remove Active Account", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        }
                    }
                }
            }
        }

        // 2. Streaming & Playback (Exact layout & polish as Nanami Anime!)
        item(key = "streaming_playback_card") {
            NetflixCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    NetflixSectionHeader(
                        title = "Playback & Gestures",
                        icon = Icons.Default.PlayCircleOutline,
                        color = NetflixRed,
                        modifier = Modifier,
                    )

                    Text("Preferred video quality", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = NetflixTextStrong)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf("720p", "1080p", "4K").forEach { qual ->
                            NetflixSettingChoice(qual, preferredQuality == qual, Modifier.weight(1f)) {
                                onPreferredQuality(qual)
                            }
                        }
                    }

                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

                    NetflixFeatureRow(
                        label = "Swipe up/down for brightness & volume",
                        subtitle = "Left side adjusts screen brightness, right side adjusts volume",
                        checked = swipeGestures,
                        onCheckedChange = {
                            swipeGestures = it
                            playerPrefs.edit().putBoolean("swipe_gestures", it).apply()
                        },
                    )

                    NetflixFeatureRow(
                        label = "Persistent bottom progress bar",
                        subtitle = "Shows a slim elapsed line along the bottom when player controls hide",
                        checked = timeline,
                        onCheckedChange = {
                            timeline = it
                            playerPrefs.edit().putBoolean("persistent_progress", it).apply()
                        },
                    )

                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

                    NetflixFeatureRow(
                        label = "Black fill behind subtitles",
                        subtitle = "Dark background box behind subtitle lines for clear contrast",
                        checked = subtitleBg,
                        onCheckedChange = {
                            subtitleBg = it
                            playerPrefs.edit().putBoolean("subtitle_background", it).apply()
                        },
                    )

                    Text("Subtitle size", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = NetflixTextStrong)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            0.8f to "Small",
                            1f to "Normal",
                            1.25f to "Large",
                            1.45f to "XL",
                        ).forEach { (sz, label) ->
                            NetflixSettingChoice(label, kotlin.math.abs(subtitleSize - sz) < 0.05f, Modifier.weight(1f)) {
                                subtitleSize = sz
                                playerPrefs.edit().putFloat("subtitle_size", sz).apply()
                            }
                        }
                    }

                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

                    Text("Playback speed", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = NetflixTextStrong)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                            val label = "${spd.toString().removeSuffix(".0")}×"
                            NetflixSettingChoice(label, kotlin.math.abs(playbackSpeed - spd) < 0.02f, Modifier.weight(1f)) {
                                playbackSpeed = spd
                                playerPrefs.edit().putFloat("speed", spd).apply()
                            }
                        }
                    }

                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

                    NetflixFeatureRow(
                        label = "Autoplay next episode",
                        subtitle = "Starts the next episode automatically at the end of a TV episode",
                        checked = autoplay,
                        onCheckedChange = {
                            autoplay = it
                            playerPrefs.edit().putBoolean("autoplay", it).apply()
                        },
                    )

                    NetflixFeatureRow(
                        label = "Skip intros automatically",
                        subtitle = "Jumps past verified title openings automatically",
                        checked = autoSkip,
                        onCheckedChange = {
                            autoSkip = it
                            playerPrefs.edit().putBoolean("auto_skip_intro", it).apply()
                        },
                    )
                }
            }
        }

        // 3. Watch Tracking & Maturity
        item(key = "watch_tracking_card") {
            NetflixCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NetflixSectionHeader(
                        title = "Watch Tracking & Maturity",
                        icon = Icons.Default.TrackChanges,
                        color = NetflixRed,
                        modifier = Modifier,
                    )

                    NetflixFeatureRow(
                        label = "Mark watched at $threshold% & queue sync",
                        subtitle = "Marks movies and episodes watched locally and syncs to SIMKL when online",
                        checked = reportProgress,
                        onCheckedChange = onReportProgress,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Track watched at percentage",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = NetflixTextStrong,
                            )
                            Text(
                                text = "$threshold%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = NetflixRed,
                            )
                        }
                        Slider(
                            value = threshold.toFloat(),
                            onValueChange = { raw ->
                                threshold = raw.roundToInt().coerceIn(50, 100)
                                MoviesPreferences.setCompletion(context, threshold)
                            },
                            valueRange = 50f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = NetflixRed,
                                activeTrackColor = NetflixRed,
                                inactiveTrackColor = NetflixBorder,
                            ),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(75, 80, 85, 90, 95).forEach { preset ->
                                NetflixSettingChoice("$preset%", threshold == preset, Modifier.weight(1f)) {
                                    threshold = preset
                                    MoviesPreferences.setCompletion(context, preset)
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

                    NetflixFeatureRow(
                        label = "Remember playback position",
                        subtitle = "Resumes movies and episodes right where you left off",
                        checked = rememberPosition,
                        onCheckedChange = onRememberPosition,
                    )

                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

                    NetflixFeatureRow(
                        label = "Hide mature titles (R / TV-MA / 18+)",
                        subtitle = "Hides mature movies and shows across Home, Search, and My List",
                        checked = hideMature,
                        onCheckedChange = onHideMature,
                    )

                    NetflixFeatureRow(
                        label = "Do not track mature titles",
                        subtitle = "Skips saving watch history or SIMKL updates for R / TV-MA / 18+ titles",
                        checked = doNotTrackMature,
                        onCheckedChange = onDoNotTrackMature,
                    )
                }
            }
        }

        // 4. Offline Downloads & About (Clean, zero technical jargon!)
        item(key = "downloads_about_card") {
            NetflixCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NetflixSectionHeader(
                        title = "Downloads & About",
                        icon = Icons.Default.DownloadDone,
                        color = NetflixMatchGreen,
                        modifier = Modifier,
                    )

                    Text(
                        text = if (completedDownloads.isNotEmpty()) {
                            "${completedDownloads.size} downloaded ${if (completedDownloads.size == 1) "video" else "videos"} • ${Formatter.formatFileSize(context, savedBytes)} used"
                        } else {
                            "0 downloaded videos stored"
                        },
                        fontSize = 13.5.sp,
                        color = NetflixMatchGreen,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Downloads stay safely inside Nanami Movies so they never clutter your phone's photo gallery.",
                        fontSize = 12.sp,
                        color = NetflixTextMuted,
                        lineHeight = 17.sp,
                    )

                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

                    NetflixFeatureRow(
                        label = "Allow Download with Cellular",
                        subtitle = "Download movies and episodes over mobile data when Wi-Fi is unavailable",
                        checked = cellular,
                        onCheckedChange = {
                            cellular = it
                            vault.setAllowCellular(context, it)
                        },
                    )

                    HorizontalDivider(color = NetflixBorder, thickness = 1.dp)

                    Text(
                        text = "Nanami Movies ${BuildConfig.VERSION_NAME}",
                        fontSize = 13.sp,
                        color = NetflixTextStrong,
                        fontWeight = FontWeight.Bold,
                    )

                    MoviesSupportActions(context = context)
                }
            }
        }
    }
}

@Composable
private fun NetflixSettingChoice(
    label: String,
    isSelected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = NetflixPillShape,
        color = if (isSelected) NetflixRed else NetflixSurface,
        border = BorderStroke(1.dp, if (isSelected) NetflixRed else NetflixBorder),
        modifier = modifier.semantics { selected = isSelected },
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MoviesSupportActions(context: Context) {
    val scope = rememberCoroutineScope()
    val diagState by MoviesDiagnosticsService.state.collectAsState()
    var checkingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<MoviesUpdate?>(null) }
    var downloadingUpdate by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                enabled = !checkingUpdate && !downloadingUpdate,
                onClick = {
                    scope.launch {
                        checkingUpdate = true
                        updateStatus = null
                        try {
                            val next = MoviesUpdater.available()
                            availableUpdate = next
                            updateStatus = if (next != null) {
                                "Update available: ${next.name}"
                            } else {
                                "You're on the latest version."
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            updateStatus = e.message ?: "Couldn't check for updates."
                        } finally {
                            checkingUpdate = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, NetflixBorder),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = if (checkingUpdate) "Checking…" else "Check for Updates",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            OutlinedButton(
                enabled = diagState.current == null,
                onClick = { MoviesDiagnosticsService.start(context) },
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, NetflixBorder),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = if (diagState.current != null) "Checking (${diagState.checked}/${diagState.total})…" else "Generate Log Dump",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        updateStatus?.let { msg ->
            Text(msg, fontSize = 12.sp, color = if (availableUpdate != null) NetflixMatchGreen else NetflixTextMuted)
        }

        availableUpdate?.let { next ->
            Button(
                enabled = !downloadingUpdate,
                onClick = {
                    scope.launch {
                        downloadingUpdate = true
                        try {
                            val apk = MoviesUpdater.download(context, next)
                            val launched = MoviesUpdater.install(context, apk)
                            if (!launched) {
                                updateStatus = "Allow app installs from this source, then tap Install Update again."
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            updateStatus = e.message ?: "Update download failed."
                        } finally {
                            downloadingUpdate = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed, contentColor = Color.White),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = if (downloadingUpdate) "Downloading & verifying update…" else "Install ${next.name}",
                    fontWeight = FontWeight.ExtraBold,
                )
            }
        }

        if (diagState.current != null) {
            LinearProgressIndicator(
                progress = { (diagState.checked.toFloat() / diagState.total.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = NetflixRed,
                trackColor = NetflixBorder,
            )
        }

        diagState.report?.let { report ->
            OutlinedButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Nanami Movies Diagnostics", report))
                    Toast.makeText(context, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, NetflixRed),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("Copy Generated Log Dump to Clipboard", color = NetflixRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
