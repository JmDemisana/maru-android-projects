package io.maru.movies

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import io.maru.manime.extensions.StremioAddonClient
import io.maru.manime.extensions.StremioStreamResult
import io.maru.manime.readResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MoviesDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val vault = MoviesDownloadVault
    private lateinit var notifications: NotificationManager
    private var worker: Job? = null
    private var seedingJob: Job? = null
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Offline downloads", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == STOP_SEEDING) {
            seedingJob?.cancel()
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (intent?.action == CANCEL) {
            val key = intent.getStringExtra(EXTRA_KEY)
            scope.launch {
                try {
                    if (key != null) vault.cancel(applicationContext, key)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Could not cancel the download", error)
                } finally {
                    stopIfIdle()
                }
            }
            return START_NOT_STICKY
        }
        if (worker?.isActive != true) {
            startForeground(NOTIFICATION_ID, notification(null, "Preparing download queue…"))
            worker = scope.launch {
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val item = vault.takeNext(applicationContext) ?: break
                        transfer(item)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Unable to read the download queue", error)
                } finally {
                    if (seedingJob?.isActive == true) {
                        notifications.notify(NOTIFICATION_ID, notification(null, "Seeding on Wi-Fi", isSeeding = true))
                    } else stopSelfResult(latestStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        worker?.cancel()
        seedingJob?.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun transfer(item: MovieDownload) = supervisorScope {
        val context = applicationContext
        val task = async(Dispatchers.IO) {
            fetchDownload(context, item, ::offerSeed, report = { message, progress ->
                vault.progress(item, message, progress)
                if (vault.isCurrent(item)) notifications.notify(NOTIFICATION_ID, notification(item, message, progress))
            })
        }
        val cancellation = launch {
            vault.items.first { !vault.isCurrent(item) }
            // A successful move also changes state. Leave that transfer to return normally.
            if (vault.items.value.none { it.key == item.key && it.state == MovieDownloadState.COMPLETE }) task.cancel()
        }
        try {
            task.await()
        } catch (error: CancellationException) {
            withContext(NonCancellable) { safelyFail(item, "Download interrupted. Tap Retry to start again.") }
            currentCoroutineContext().ensureActive()
        } catch (error: Exception) {
            Log.w(TAG, "Download failed for ${item.key}", error)
            safelyFail(item, (error as? MovieDownloadException)?.message ?: "Couldn't save the video. Tap Retry to try again.")
        } catch (error: LinkageError) {
            Log.w(TAG, "Download engine unavailable", error)
            safelyFail(item, "Offline downloads are unavailable on this device.")
        } finally {
            cancellation.cancel()
        }
    }

    private suspend fun offerSeed(torrent: MovieTorrentTransfer, savedFile: File, staging: File): Boolean =
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            val context = applicationContext
            if (!scope.isActive || seedingJob?.isActive == true || !maySeed(context) || !torrent.file.isFile) {
                return@withContext false
            }
            val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    withContext(Dispatchers.IO) { torrent.seed { savedFile.isFile && maySeed(context) } }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Could not seed downloaded video", error)
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        torrent.stop()
                        staging.deleteRecursively()
                    }
                }
            }
            seedingJob = job
            job.invokeOnCompletion {
                scope.launch {
                    if (seedingJob === job) seedingJob = null
                    stopIfIdle()
                }
            }
            true
        }

    private fun stopIfIdle() {
        if (worker?.isActive != true && seedingJob?.isActive != true) stopSelfResult(latestStartId)
    }

    private suspend fun safelyFail(item: MovieDownload, message: String) {
        try {
            vault.fail(applicationContext, item, message)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w(TAG, "Unable to record a failed download", error)
        }
    }

    private fun notification(item: MovieDownload?, message: String, progress: MovieDownloadProgress? = null, isSeeding: Boolean = false): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val launch = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val percent = progress?.fraction?.times(100)?.roundToInt()?.coerceIn(0, 99)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item?.title?.title ?: "Nanami Movies")
            .setContentText(message)
            .setContentIntent(PendingIntent.getActivity(this, 0, launch, flags))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(if (isSeeding) 0 else 100, percent ?: 0, percent == null && !isSeeding)
            .apply {
                if (item != null) {
                    val cancel = Intent(this@MoviesDownloadService, MoviesDownloadService::class.java)
                        .setAction(CANCEL).putExtra(EXTRA_KEY, item.key)
                    addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
                        PendingIntent.getService(this@MoviesDownloadService, item.key.hashCode(), cancel, flags))
                }
                if (isSeeding) {
                    val stop = Intent(this@MoviesDownloadService, MoviesDownloadService::class.java).setAction(STOP_SEEDING)
                    addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop seeding",
                        PendingIntent.getService(this@MoviesDownloadService, 0, stop, flags))
                }
            }.build()
    }

    private companion object {
        const val TAG = "MoviesDownloadService"
        const val CHANNEL_ID = "movies_offline_downloads"
        const val NOTIFICATION_ID = 7052
        const val CANCEL = "io.maru.movies.action.CANCEL_DOWNLOAD"
        const val STOP_SEEDING = "io.maru.movies.action.STOP_SEEDING"
        const val EXTRA_KEY = "download_key"
    }
}

private val downloadHttp = OkHttpClient.Builder()
    .connectTimeout(12, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .followSslRedirects(false)
    .build()

private suspend fun fetchDownload(
    context: Context,
    item: MovieDownload,
    offerSeed: suspend (MovieTorrentTransfer, File, File) -> Boolean,
    report: (String, MovieDownloadProgress?) -> Unit,
) {
    requireDownloadNetwork(context)
    report("Finding sources…", null)
    val candidates = findMovieSources(item.title, item.episode, context)
        .filter { it.downloadUrl() != null || it.magnetUri() != null }
        .distinctBy { it.url ?: "${it.infoHash}:${it.fileIdx}" }
        .sortedWith(compareBy(::resolutionPreference).thenByDescending(::seederCount))
        .take(4)
    if (candidates.isEmpty()) throw MovieDownloadException("No source offers an offline video file for this title.")
    var failure = "Couldn't connect to the available download sources."
    for ((index, candidate) in candidates.withIndex()) {
        currentCoroutineContext().ensureActive()
        requireDownloadNetwork(context)
        val staging = MoviesDownloadVault.workDirectory(context)
        var torrent: MovieTorrentTransfer? = null
        var retainedForSeed = false
        try {
            val attempt = "Source ${index + 1} of ${candidates.size}"
            report("Connecting · $attempt", null)
            val progress: (MovieDownloadProgress) -> Unit = { stats ->
                requireDownloadNetwork(context)
                val speed = stats.speed.takeIf { it > 0 }?.let { "${Formatter.formatFileSize(context, it)}/s" }
                val percent = stats.fraction?.times(100)?.roundToInt()?.coerceIn(0, 99)?.let { "$it%" }
                val details = listOfNotNull(percent, speed).joinToString(" · ")
                report(details.ifBlank { "Starting download · $attempt" }, stats)
            }
            val direct = candidate.downloadUrl()
            val file = if (direct != null) {
                downloadMovieFile(staging, candidate, direct, progress)
            } else {
                val transfer = downloadMoviesTorrent(staging, candidate, item.episode, MoviesDownloadVault.torrentSpeedKib(context), progress)
                torrent = transfer
                transfer.file
            }
            currentCoroutineContext().ensureActive()
            val seeding = torrent?.takeIf { maySeed(context) }
            if (seeding == null) {
                torrent?.stop()
                torrent = null
            }
            val savedFile = MoviesDownloadVault.finish(context, item, file, preserveForSeed = seeding != null)
            if (seeding != null) {
                // Record the handoff before leaving the non-cancellable context.
                withContext(NonCancellable) { retainedForSeed = offerSeed(seeding, savedFile, staging) }
            }
            return
        } catch (error: CancellationException) {
            throw error
        } catch (error: DownloadNetworkException) {
            throw error
        } catch (error: DownloadStorageException) {
            failure = error.message.orEmpty()
        } catch (error: Exception) {
            failure = (error as? MovieDownloadException)?.message ?: "The available download sources failed."
        } finally {
            if (!retainedForSeed) {
                torrent?.stop()
                staging.deleteRecursively()
            }
        }
    }
    throw MovieDownloadException(failure)
}

private suspend fun downloadMovieFile(
    staging: File,
    candidate: StremioStreamResult,
    url: HttpUrl,
    report: (MovieDownloadProgress) -> Unit,
): File {
    val request = Request.Builder().url(url).header("User-Agent", StremioAddonClient.BROWSER_UA)
    candidate.headers.filterKeys { !it.equals("Range", true) }.forEach { (name, value) -> request.header(name, value) }
    return downloadHttp.newCall(request.build()).readResponse { response ->
        if (response.code != 200 || response.header("Content-Range") != null) {
            throw MovieDownloadException("The direct source didn't return a complete video.")
        }
        val body = response.body ?: throw MovieDownloadException("The direct source didn't return a video.")
        val mime = body.contentType()?.toString()?.substringBefore(';').orEmpty().lowercase()
        if (mime.startsWith("text/") || mime.endsWith("json") || mime.contains("mpegurl") || mime.contains("dash+xml")) {
            throw MovieDownloadException("This source can't be saved as an offline file.")
        }
        val size = body.contentLength()
        if (size > 0 && staging.usableSpace < size) throw DownloadStorageException()
        val pathExtension = url.pathSegments.lastOrNull()?.substringAfterLast('.', "")?.lowercase()
        val extension = when (mime) {
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            else -> pathExtension?.takeIf { it in directExtensions } ?: "mp4"
        }
        val file = File(staging, "video.$extension")
        var written = 0L
        var sampleTime = SystemClock.elapsedRealtime()
        var sampleBytes = 0L
        body.byteStream().use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    output.write(buffer, 0, count)
                    written += count
                    val now = SystemClock.elapsedRealtime()
                    val elapsed = now - sampleTime
                    if (elapsed >= 800) {
                        report(MovieDownloadProgress(written, size, (written - sampleBytes) * 1000L / elapsed))
                        sampleTime = now
                        sampleBytes = written
                    }
                }
                output.fd.sync()
            }
        }
        if (written == 0L || (size > 0 && written != size)) throw MovieDownloadException("The source ended before the full video downloaded.")
        file
    }
}

private fun requireDownloadNetwork(context: Context) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        ?: throw DownloadNetworkException("Connect to the internet, then tap Retry.")
    if (!MoviesDownloadVault.allowCellular(context) && !capabilities.isWifiOrEthernet()) {
        throw DownloadNetworkException("Connect to Wi-Fi or allow cellular downloads, then tap Retry.")
    }
}

private fun maySeed(context: Context): Boolean {
    if (!MoviesDownloadVault.seedOnWifi(context)) return false
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.isWifiOrEthernet() == true
}

private fun NetworkCapabilities.isWifiOrEthernet(): Boolean =
    hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

private val directExtensions = setOf("mp4", "mkv", "webm", "m4v", "ts", "mov", "avi")

private fun StremioStreamResult.downloadUrl(): HttpUrl? = url?.toHttpUrlOrNull()?.takeIf { direct ->
    direct.isHttps && direct.pathSegments.lastOrNull()?.substringAfterLast('.')?.lowercase() !in setOf("m3u8", "mpd")
}

private fun resolutionPreference(result: StremioStreamResult): Int {
    if (result.downloadUrl() != null) return 0
    val label = "${result.name} ${result.title}"
    return when {
        label.contains("1080p", true) -> 1
        label.contains("720p", true) -> 2
        label.contains("2160p", true) || label.contains("4k", true) -> 4
        else -> 3
    }
}

private val seedPattern = Regex("👤\\s*(\\d+)")
private fun seederCount(result: StremioStreamResult): Int =
    seedPattern.find(result.title.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
