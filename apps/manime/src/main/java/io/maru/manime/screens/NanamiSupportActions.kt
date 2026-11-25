package io.maru.manime.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.maru.manime.CrunchyBorder
import io.maru.manime.CrunchyTeal
import io.maru.manime.MaruTextMuted
import io.maru.manime.NanamiRelease
import io.maru.manime.NanamiUpdater
import io.maru.manime.torrent.TorrentStreamService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun NanamiSupportActions(hideNsfwContent: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostics by TorrentStreamService.diagnosticState.collectAsStateWithLifecycle()
    var clipboardStatus by remember(diagnostics.report) { mutableStateOf<String?>(null) }
    var confirmDiagnostics by remember { mutableStateOf(false) }

    var updateBusy by remember { mutableStateOf(false) }
    var updateText by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<NanamiRelease?>(null) }
    var downloadedApk by remember { mutableStateOf<File?>(null) }

    fun install(apk: File) {
        try {
            updateText = if (NanamiUpdater.openInstaller(context, apk)) {
                "Finish installing in Android's package installer."
            } else {
                "Allow installs from Nanami Anime in Android settings, then tap Install Update."
            }
        } catch (_: Exception) {
            updateText = "Couldn't open the Android installer. Check again to download a fresh update."
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = { confirmDiagnostics = true },
            enabled = !diagnostics.isRunning && !updateBusy
        ) {
            Text("Generate Log Dump")
        }
        Text(
            "This dump will continue even if the app is on another page, another anime, or minimized.",
            style = MaterialTheme.typography.bodySmall,
            color = MaruTextMuted
        )
        if (diagnostics.isRunning) {
            val progress = diagnostics.progress
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = CrunchyTeal, trackColor = CrunchyBorder)
            } else {
                LinearProgressIndicator(
                    progress = { progress.completed.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                    color = CrunchyTeal,
                    trackColor = CrunchyBorder
                )
            }
            Text(
                if (progress == null) "Preparing checks…"
                else "${progress.completed} of ${progress.total} checks complete · ${progress.label}",
                style = MaterialTheme.typography.bodySmall,
                color = MaruTextMuted
            )
            TextButton(onClick = { TorrentStreamService.cancelLogDump(context) }) { Text("Cancel checks") }
        } else {
            (clipboardStatus ?: diagnostics.status)?.let { Text(it, color = MaruTextMuted) }
        }
        OutlinedButton(
            onClick = {
                val report = diagnostics.report ?: return@OutlinedButton
                val clip = ClipData.newPlainText("Nanami Anime diagnostics", report)
                if (Build.VERSION.SDK_INT >= 33) {
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                clipboardStatus = try {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                        ?: error("Clipboard unavailable")
                    clipboard.setPrimaryClip(clip)
                    "Log dump copied."
                } catch (_: Exception) {
                    "Couldn't copy the log dump. Try again."
                }
            },
            enabled = diagnostics.report != null && !diagnostics.isRunning
        ) {
            Text("Copy Generated Log Dump to Clipboard")
        }

        OutlinedButton(
            onClick = {
                val apk = downloadedApk?.takeIf(File::isFile)
                if (apk != null) {
                    install(apk)
                } else {
                    downloadedApk = null
                    updateBusy = true
                    updateText = "Checking stable releases…"
                    scope.launch {
                        try {
                            val release = NanamiUpdater.latestStable()
                            updateText = when {
                                release == null -> "No in-app update has been published yet."
                                release.code <= NanamiUpdater.installedCode(context) -> "Nanami Anime is up to date."
                                else -> {
                                    availableUpdate = release
                                    "Nanami Anime ${release.version} is available."
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            updateText = "Couldn't check for updates. Try again."
                        } finally {
                            updateBusy = false
                        }
                    }
                }
            },
            enabled = !updateBusy && !diagnostics.isRunning
        ) {
            Text(if (downloadedApk?.isFile == true) "Install Update" else "Check for Updates")
        }
        updateText?.let { Text(it, color = MaruTextMuted) }
    }

    if (confirmDiagnostics) {
        AlertDialog(
            onDismissRequest = { confirmDiagnostics = false },
            title = { Text("Run diagnostic checks?") },
            text = {
                Text("Nanami checks its online services and tries to render the first frame of each sample. This can take several minutes and use data. Torrent checks require Wi-Fi or Ethernet. The report stays on your device and excludes account names and tokens.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiagnostics = false
                    clipboardStatus = null
                    TorrentStreamService.generateLogDump(context, includeAdult = !hideNsfwContent)
                }) { Text("Generate") }
            },
            dismissButton = { TextButton(onClick = { confirmDiagnostics = false }) { Text("Cancel") } }
        )
    }

    availableUpdate?.let { release ->
        AlertDialog(
            onDismissRequest = { availableUpdate = null },
            title = { Text("Nanami Anime ${release.version}") },
            text = { Text("Download the signed update and open Android's installer?") },
            confirmButton = {
                TextButton(onClick = {
                    availableUpdate = null
                    updateBusy = true
                    updateText = "Downloading and verifying update…"
                    scope.launch {
                        try {
                            val apk = NanamiUpdater.download(context, release)
                            downloadedApk = apk
                            install(apk)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            updateText = if (e is IllegalStateException) {
                                e.message ?: "Couldn't verify the update."
                            } else {
                                "Couldn't download the update. Try again."
                            }
                        } finally {
                            updateBusy = false
                        }
                    }
                }) { Text("Download Update") }
            },
            dismissButton = { TextButton(onClick = { availableUpdate = null }) { Text("Later") } }
        )
    }
}
