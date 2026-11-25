package io.maru.manime.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.maru.manime.MaruTextMuted
import io.maru.manime.NanamiDiagnostics
import io.maru.manime.NanamiRelease
import io.maru.manime.NanamiUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

@Composable
internal fun NanamiSupportActions(hideNsfwContent: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var diagnosticJob by remember { mutableStateOf<Job?>(null) }
    var diagnosticText by remember { mutableStateOf<String?>(null) }
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
            enabled = diagnosticJob == null && !updateBusy
        ) {
            Text("Copy Log Dump to Clipboard")
        }
        diagnosticText?.let { Text(it, color = MaruTextMuted) }
        diagnosticJob?.let { job ->
            TextButton(onClick = { job.cancel() }) { Text("Cancel checks") }
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
            enabled = !updateBusy && diagnosticJob == null
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
                Text("Nanami checks its online services and tries to render the first frame of each sample. This can take several minutes and use data. Torrent checks require Wi-Fi or Ethernet. The report is copied locally and excludes account names and tokens.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiagnostics = false
                    diagnosticText = "Preparing checks…"
                    diagnosticJob = scope.launch {
                        try {
                            val report = NanamiDiagnostics.collect(context, includeAdult = !hideNsfwContent) {
                                diagnosticText = "Checking $it…"
                            }
                            val clip = ClipData.newPlainText("Nanami Anime diagnostics", report)
                            if (Build.VERSION.SDK_INT >= 33) {
                                clip.description.extras = PersistableBundle().apply {
                                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                                }
                            }
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                                ?: error("Clipboard unavailable")
                            clipboard.setPrimaryClip(clip)
                            diagnosticText = "Diagnostic report copied."
                        } catch (e: CancellationException) {
                            diagnosticText = "Diagnostic checks canceled."
                            throw e
                        } catch (_: Exception) {
                            diagnosticText = "Couldn't generate the diagnostic report. Try again."
                        } finally {
                            diagnosticJob = null
                        }
                    }
                }) { Text("Run and Copy") }
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
