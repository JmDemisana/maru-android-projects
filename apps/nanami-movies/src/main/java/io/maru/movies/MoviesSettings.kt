package io.maru.movies

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun MoviesSettings(
    simkl: Simkl, account: String?, onAccountChanged: () -> Unit, syncMessage: String?, onSync: () -> Unit,
    hideMature: Boolean, onHideMature: (Boolean) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(context) { MoviesPreferences.player(context) }
    val vault = MoviesDownloadVault
    var threshold by remember { mutableFloatStateOf(MoviesPreferences.completion(context).toFloat()) }
    var autoplay by remember { mutableStateOf(player.getBoolean("autoplay", true)) }
    var timeline by remember { mutableStateOf(player.getBoolean("persistent_progress", false)) }
    var autoSkip by remember { mutableStateOf(player.getBoolean("auto_skip_intro", false)) }
    var cellular by remember { mutableStateOf(vault.allowCellular(context)) }
    var seed by remember { mutableStateOf(vault.seedOnWifi(context)) }
    var bandwidth by remember { mutableIntStateOf(vault.torrentSpeedKib(context)) }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            SettingsSection("Account") {
                SimklAccounts(simkl, account, onAccountChanged, onSync, syncMessage)
            }
            SettingsSection("Playback") {
                SettingsToggle("Play the next episode automatically", autoplay) {
                    autoplay = it; player.edit().putBoolean("autoplay", it).apply()
                }
                SettingsToggle("Keep the progress bar visible", timeline) {
                    timeline = it; player.edit().putBoolean("persistent_progress", it).apply()
                }
                SettingsToggle("Skip verified intros automatically", autoSkip) {
                    autoSkip = it; player.edit().putBoolean("auto_skip_intro", it).apply()
                }
                HorizontalDivider()
                Text("Mark as watched at ${threshold.roundToInt()}%")
                Slider(value = threshold, valueRange = 50f..100f, steps = 9, onValueChange = { threshold = it },
                    onValueChangeFinished = { MoviesPreferences.setCompletion(context, threshold.roundToInt()) })
                SettingsToggle("Hide titles with mature ratings", hideMature, onHideMature)
            }
            SettingsSection("Downloads") {
                SettingsToggle("Allow cellular downloads", cellular) { cellular = it; vault.setAllowCellular(context, it) }
                SettingsToggle("Seed completed torrents on Wi-Fi", seed) { seed = it; vault.setSeedOnWifi(context, it) }
                HorizontalDivider()
                Text(if (bandwidth == 0) "Download speed · Unlimited" else "Download speed · $bandwidth KiB/s")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(0 to "Unlimited", 512 to "512 KiB/s", 2_048 to "2 MiB/s", 5_120 to "5 MiB/s").forEach { (speed, label) ->
                        FilterChip(selected = bandwidth == speed, onClick = { bandwidth = speed; vault.setTorrentSpeedKib(context, speed) },
                            label = { Text(label, maxLines = 1) })
                    }
                }
            }
            SettingsSection("Video sources") { MoviesAddonSettings() }
            SettingsSection("About Nanami Movies") { MoviesAbout() }
        }
    }
}

@Composable
private fun SettingsSection(title: String, body: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MoviesAccent)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { body() }
        }
    }
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = changed)
    }
}

@Composable
private fun SimklAccounts(simkl: Simkl, active: String?, changed: () -> Unit, sync: () -> Unit, syncMessage: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var accounts by remember(active) { mutableStateOf(simkl.accounts()) }
    var showAdd by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var client by remember { mutableStateOf(simkl.clientId) }
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = job != null

    fun run(action: suspend () -> Unit) {
        if (job != null) return
        job = scope.launch {
            error = null
            try {
                action()
                accounts = simkl.accounts()
                changed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "SIMKL couldn't complete that action."
            } finally {
                code = null
                job = null
            }
        }
    }

    Text(if (active == null) "Guest Mode · Lists and viewing activity stay on this device." else syncMessage ?: "SIMKL account connected.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FilterChip(selected = active == null, enabled = !busy, onClick = { run { simkl.selectAccount(null) } }, label = { Text("Guest Mode") })
    accounts.forEach { account ->
        FilterChip(selected = active == account.id, enabled = !busy,
            onClick = { run { simkl.selectAccount(account.id) } }, label = { Text(account.label) })
    }
    Row {
        TextButton(enabled = !busy, onClick = { error = null; title = ""; client = simkl.clientId; showAdd = true }) {
            Text(if (accounts.isEmpty()) "Connect SIMKL" else "Add account")
        }
        if (active != null) TextButton(onClick = sync) { Text("Sync now") }
        if (active != null) TextButton(enabled = !busy, onClick = { showRemove = true }) { Text("Disconnect") }
    }
    if (!showAdd) error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (showRemove) AlertDialog(onDismissRequest = { showRemove = false }, title = { Text("Disconnect SIMKL?") },
        text = { Text("You can reconnect this account in Settings.") },
        dismissButton = { TextButton(onClick = { showRemove = false }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { showRemove = false; run { simkl.disconnect() } }) { Text("Disconnect") } })
    if (showAdd) AlertDialog(onDismissRequest = { if (!busy) showAdd = false }, title = { Text("Connect SIMKL") },
        text = {
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it.take(40) }, singleLine = true, enabled = !busy,
                    label = { Text("Account name (optional)") })
                OutlinedTextField(value = client, onValueChange = { client = it.trim() }, singleLine = true, enabled = !busy,
                    label = { Text("SIMKL V2 client ID") })
                if (client.isBlank()) TextButton(onClick = {
                    runCatching { openMoviesLink(context, "https://simkl.com/settings/developer/new/") }
                        .onFailure { error = "Couldn't open the browser." }
                }) { Text("Get a SIMKL client ID") }
                code?.let { Text("Approve your account in the browser. Code $it") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = { job?.cancel(); showAdd = false }) { Text("Cancel") } },
        confirmButton = {
            TextButton(enabled = client.isNotBlank() && !busy, onClick = {
                run {
                    val login = simkl.beginSignIn(client, title)
                    code = login.userCode
                    openMoviesLink(context, login.verificationUrl)
                    simkl.awaitSignIn(login)
                    showAdd = false
                }
            }) { Text("Sign in") }
        },
    )
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

    Text("Torrentio · Built in", style = MaterialTheme.typography.titleSmall)
    addons.forEach { url ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(Uri.parse(url).host ?: "Stremio add-on", Modifier.weight(1f))
            TextButton(onClick = { MoviesPreferences.removeAddon(context, url); addons = MoviesPreferences.addons(context) }) {
                Text("Remove")
            }
        }
    }
    TextButton(onClick = { input = ""; message = null; editing = true }) { Text("Add a Stremio source") }
    if (!editing) message?.let { Text(it) }
    if (editing) AlertDialog(onDismissRequest = { if (!busy) editing = false }, title = { Text("Add a Stremio source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Manifest URL") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { editing = false }) { Text("Cancel") } },
        confirmButton = {
            TextButton(enabled = input.isNotBlank() && !busy, onClick = {
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
                    } finally { busy = false }
                }
            }) { Text(if (busy) "Checking…" else "Add source") }
        },
    )
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
            else "Allow installs from Nanami Movies, then return and tap Install update."
        } catch (error: Exception) { error.message ?: "Couldn't open the installer." }
    }

    Text("${BuildConfig.VERSION_NAME} · Rotator", style = MaterialTheme.typography.titleSmall)
    if (update == null) TextButton(enabled = !busy, onClick = {
        scope.launch {
            busy = true
            status = "Checking for updates…"
            try {
                update = MoviesUpdater.available()
                status = update?.let { "Version ${it.name} is available." } ?: "You're up to date."
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status = error.message ?: "Couldn't check for updates."
            } finally { busy = false }
        }
    }) { Text("Check for updates") }
    update?.let { release ->
        Button(enabled = !busy, onClick = {
            val downloaded = apk
            if (downloaded != null) install(downloaded) else scope.launch {
                busy = true
                status = "Downloading and verifying the APK…"
                try {
                    val file = MoviesUpdater.download(context, release)
                    apk = file
                    install(file)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    update = null
                    status = error.message ?: "Couldn't download the update."
                } finally { busy = false }
            }
        }) { Text(if (apk == null) "Download update" else "Install update") }
    }
    TextButton(onClick = { runCatching { openMoviesLink(context, MoviesUpdater.releasePage) }
        .onFailure { status = "Couldn't open the release page." } }) { Text("Release page") }
    HorizontalDivider()
    val current = diagnostic.current
    if (current == null) TextButton(onClick = {
        runCatching { MoviesDiagnosticsService.start(context) }.onFailure { status = "Couldn't start diagnostics." }
    }) { Text("Generate log dump") }
    else {
        Text(current)
        LinearProgressIndicator(progress = { diagnostic.checked.toFloat() / diagnostic.total }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { MoviesDiagnosticsService.cancel(context) }) { Text("Cancel diagnostics") }
    }
    diagnostic.report?.takeIf { current == null }?.let { report ->
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Nanami Movies diagnostics", report))
            status = "Diagnostics copied."
        }) { Text("Copy generated log dump") }
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

private fun openMoviesLink(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
