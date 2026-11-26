package io.maru.movies

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.maru.manime.extensions.StremioAddonClient
import io.maru.manime.readResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

internal data class MoviesDiagnosticState(val current: String? = null, val checked: Int = 0, val total: Int = 7, val report: String? = null)

class MoviesDiagnosticsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker != null) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Diagnostics", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification("Preparing checks", 0, 7))
        worker = scope.launch {
            val context = applicationContext
            val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            val network = context.getSystemService(ConnectivityManager::class.java)
            suspend fun reachable(url: String) {
                val request = Request.Builder().url(url).header("User-Agent", StremioAddonClient.BROWSER_UA).build()
                http.newCall(request).readResponse { if (!it.isSuccessful) throw IOException("HTTP ${it.code}") }
            }
            val checks: List<Pair<String, suspend () -> Unit>> = listOf(
                "Internet connection" to { check(network.getNetworkCapabilities(network.activeNetwork)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) { "Offline" } },
                "Movie catalog" to { reachable("https://v3-cinemeta.strem.io/manifest.json") },
                "Anime classification" to { reachable("https://api.ani.zip/mappings?imdb_id=tt0245429") },
                "Torrentio" to { reachable("https://torrentio.strem.fun/manifest.json") },
                "SIMKL service" to { reachable("https://api.simkl.org/") },
                "Private storage" to { check(context.filesDir.canWrite()) { "Unavailable" } },
                "Android installer" to { context.packageManager.canRequestPackageInstalls(); Unit },
            )
            val report = StringBuilder("Nanami Movies ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            report.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            report.appendLine("Supported ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
            report.appendLine("SIMKL ${if (Simkl(context).isConnected) "connected" else "guest"}")
            report.appendLine()
            checks.forEachIndexed { index, (name, check) ->
                progress.value = MoviesDiagnosticState(name, index, checks.size)
                manager.notify(NOTIFICATION, notification(name, index, checks.size))
                val result = try {
                    check()
                    "OK"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.message?.take(120) ?: "Unavailable"
                }
                report.appendLine("$name: $result")
            }
            progress.value = MoviesDiagnosticState(checked = checks.size, total = checks.size, report = report.toString())
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (progress.value.current != null) progress.value = MoviesDiagnosticState()
        super.onDestroy()
    }

    private fun notification(text: String, current: Int, maximum: Int) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Nanami Movies diagnostics")
        .setContentText(text).setProgress(maximum, current, false).setOnlyAlertOnce(true).setOngoing(true).build()

    companion object {
        private const val CHANNEL = "movies_diagnostics"
        private const val NOTIFICATION = 8212
        private val progress = MutableStateFlow(MoviesDiagnosticState())
        internal val state = progress.asStateFlow()

        internal fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MoviesDiagnosticsService::class.java))
        }

        internal fun cancel(context: Context) {
            context.stopService(Intent(context, MoviesDiagnosticsService::class.java))
        }
    }
}
