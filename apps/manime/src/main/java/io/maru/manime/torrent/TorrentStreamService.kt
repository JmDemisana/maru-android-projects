package io.maru.manime.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.maru.manime.MAnimePrefs
import io.maru.manime.extensions.StreamLink
import io.maru.manime.extensions.StremioAddonClient
import io.maru.manime.readResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.Priority
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.ErrorCode
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class ActiveStreamStatus(
    val requestId: String? = null,
    val isReady: Boolean = false,
    val isBuffering: Boolean = false,
    val statusMessage: String = "Preparing episode...",
    val progressPercent: Int = 0,
    val downloadSpeedKbps: Int = 0,
    val connectedPeers: Int = 0,
    val streamUrl: String? = null,
    val error: String? = null,
    val phase: String = "starting"
)

data class OfflineDownloadCandidate(
    val url: String,
    val isTorrent: Boolean,
    val fileIdx: Int? = null,
    val audioType: String = "SUB",
    val quality: String = "1080p",
    val headers: Map<String, String> = emptyMap(),
    val verifiedAdultEpisode: Boolean = false
)

internal fun selectDownloadCandidates(
    streams: List<StreamLink>,
    preferredAudio: String,
    maxCandidates: Int = 5,
    requireVerifiedAdultEpisode: Boolean = false
): List<OfflineDownloadCandidate> {
    fun isDownloadableHttp(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        return (url.startsWith("http://", true) || url.startsWith("https://", true)) &&
            !clean.endsWith(".m3u8") && !clean.endsWith(".mpd")
    }

    val eligibleStreams = if (requireVerifiedAdultEpisode) {
        streams.filter { it.verifiedAdultEpisode && !it.isTorrent }
    } else streams
    val matchingAudio = eligibleStreams.filter {
        it.audioType == preferredAudio || it.audioType == "DUAL_AUDIO"
    }
    val orderedPool = (matchingAudio + eligibleStreams).distinctBy { (it.infoHash ?: it.url) to it.fileIdx }

    return orderedPool.mapNotNull { s ->
        when {
            !s.isTorrent && isDownloadableHttp(s.url) -> OfflineDownloadCandidate(
                url = s.url,
                isTorrent = false,
                fileIdx = null,
                audioType = s.audioType,
                quality = s.quality,
                headers = s.headers,
                verifiedAdultEpisode = s.verifiedAdultEpisode
            )
            s.isTorrent && s.url.startsWith("magnet:", ignoreCase = true) && (s.seeders == null || s.seeders > 0) ->
                OfflineDownloadCandidate(
                    url = s.url,
                    isTorrent = true,
                    fileIdx = s.fileIdx,
                    audioType = s.audioType,
                    quality = s.quality
                )
            else -> null
        }
    }.take(maxCandidates)
}

internal fun encodeOfflineCandidates(candidates: List<OfflineDownloadCandidate>): String {
    val arr = JSONArray()
    for (c in candidates) {
        val headersObj = JSONObject()
        for ((k, v) in c.headers) headersObj.put(k, v)
        arr.put(JSONObject().apply {
            put("url", c.url)
            put("isTorrent", c.isTorrent)
            if (c.fileIdx != null) put("fileIdx", c.fileIdx)
            put("audioType", c.audioType)
            put("quality", c.quality)
            put("headers", headersObj)
            if (c.verifiedAdultEpisode) put("verifiedAdultEpisode", true)
        })
    }
    return arr.toString()
}

internal fun decodeOfflineCandidates(rawJson: String?): List<OfflineDownloadCandidate> {
    if (rawJson.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(rawJson)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("url", "").trim()
                if (url.isEmpty()) continue
                val headersObj = obj.optJSONObject("headers")
                val headers = buildMap {
                    if (headersObj != null) {
                        for (k in headersObj.keys()) {
                            val v = headersObj.optString(k, "").trim()
                            if (v.isNotEmpty()) put(k, v)
                        }
                    }
                }
                add(
                    OfflineDownloadCandidate(
                        url = url,
                        isTorrent = obj.optBoolean("isTorrent", url.startsWith("magnet:", true)),
                        fileIdx = if (obj.has("fileIdx") && !obj.isNull("fileIdx")) obj.optInt("fileIdx", -1).takeIf { it >= 0 } else null,
                        audioType = obj.optString("audioType", "SUB"),
                        quality = obj.optString("quality", "1080p"),
                        headers = headers,
                        verifiedAdultEpisode = obj.optBoolean("verifiedAdultEpisode")
                    )
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private class OfflineDownloadException(message: String) : Exception(message)

class TorrentStreamService : Service() {

    companion object {
        const val ACTION_START_STREAM = "io.maru.manime.action.START_STREAM"
        const val ACTION_DOWNLOAD_OFFLINE = "io.maru.manime.action.DOWNLOAD_OFFLINE"
        const val ACTION_SERVE_OFFLINE_CAST = "io.maru.manime.action.SERVE_OFFLINE_CAST"
        const val EXTRA_OFFLINE_FILE_PATH = "extra_offline_file_path"

        const val EXTRA_MAGNET = "extra_magnet"
        private const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_FILE_IDX = "extra_file_idx"
        const val EXTRA_MEDIA_ID = "extra_media_id"
        const val EXTRA_ANIME_TITLE = "extra_anime_title"
        const val EXTRA_EPISODE_NUM = "extra_episode_num"
        const val EXTRA_EPISODE_TITLE = "extra_episode_title"
        const val EXTRA_COVER_URL = "extra_cover_url"
        const val EXTRA_THUMB_URL = "extra_thumb_url"
        const val EXTRA_AUDIO_TYPE = "extra_audio_type"
        const val EXTRA_QUALITY = "extra_quality"
        const val EXTRA_CANDIDATES_JSON = "extra_candidates_json"
        private const val EXTRA_IS_ADULT_CONTENT = "extra_is_adult_content"

        const val NOTIFICATION_ID = 9002
        const val CHANNEL_ID = "nanami_anime_stream_channel"

        private val _streamStatus = MutableStateFlow(ActiveStreamStatus())
        val streamStatus: StateFlow<ActiveStreamStatus> = _streamStatus.asStateFlow()

        private val _offlineCastUrl = MutableStateFlow<String?>(null)
        val offlineCastUrl: StateFlow<String?> = _offlineCastUrl.asStateFlow()

        fun findLanIpv4Address(): String? {
            return try {
                val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                interfaces
                    .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("rmnet") }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            } catch (_: Exception) {
                null
            }
        }

        fun startOfflineCastServer(context: Context, file: File) {
            _offlineCastUrl.value = null
            val intent = Intent(context, TorrentStreamService::class.java).apply {
                action = ACTION_SERVE_OFFLINE_CAST
                putExtra(EXTRA_OFFLINE_FILE_PATH, file.absolutePath)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        val offlineEpisodes = OfflineVault.episodes
        private val offlineJobs = ConcurrentHashMap<Pair<Int, Int>, Job>()
        private val seedingJobs = ConcurrentHashMap<Pair<Int, Int>, Job>()
        // Accessed under OfflineVault.lock to preserve request order across service jobs.
        private var offlineDownloadTail = CompletableDeferred(Unit)
        private val wifiSeedSemaphore = Semaphore(2)

        fun isWifiOrEthernetActive(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            } catch (_: Exception) {
                false
            }
        }

        fun isCellularNetworkActive(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                val onWifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                !onWifiOrEthernet && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            } catch (_: Exception) {
                false
            }
        }

        fun canDownloadWithNetworkPolicy(context: Context, allowCellular: Boolean): Boolean =
            allowCellular || !isCellularNetworkActive(context)

        suspend fun loadOfflineVault(context: Context) = withContext(Dispatchers.IO) {
            OfflineVault.load(context)
        }

        internal suspend fun applyOfflineAdultRatings(context: Context, ratings: Map<Int, Boolean>) =
            withContext(Dispatchers.IO) { OfflineVault.applyAdultRatings(context, ratings) }

        suspend fun getCompletedOfflineEpisode(context: Context, mediaId: Int, episodeNum: Int) =
            withContext(Dispatchers.IO) { OfflineVault.getCompleted(context, mediaId, episodeNum) }

        suspend fun deleteOfflineEpisode(context: Context, mediaId: Int, episodeNum: Int): Boolean =
            withContext(Dispatchers.IO) {
                synchronized(OfflineVault.lock) {
                    offlineJobs[mediaId to episodeNum]?.cancel()
                    seedingJobs.remove(mediaId to episodeNum)?.cancel()
                    OfflineVault.delete(context, mediaId, episodeNum)
                }
            }

        fun startStream(
            context: Context,
            magnetUri: String,
            fileIdx: Int? = null,
            animeTitle: String = "Anime",
            episodeNum: Int = 1
        ): String {
            val requestId = UUID.randomUUID().toString()
            _streamStatus.value = ActiveStreamStatus(
                requestId = requestId,
                isReady = false,
                isBuffering = true,
                statusMessage = "Connecting to Episode $episodeNum...",
                progressPercent = 0
            )
            val intent = Intent(context, TorrentStreamService::class.java).apply {
                action = ACTION_START_STREAM
                putExtra(EXTRA_REQUEST_ID, requestId)
                putExtra(EXTRA_MAGNET, magnetUri)
                putExtra(EXTRA_FILE_IDX, fileIdx ?: -1)
                putExtra(EXTRA_ANIME_TITLE, animeTitle)
                putExtra(EXTRA_EPISODE_NUM, episodeNum)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w("TorrentStreamService", "Could not start playback service", e)
                _streamStatus.update { current ->
                    if (current.requestId != requestId) current else ActiveStreamStatus(
                        requestId = requestId, phase = "service_start",
                        error = "Couldn't start the torrent engine. Try reopening the player.",
                        statusMessage = "Playback failed"
                    )
                }
            }
            return requestId
        }

        fun startOfflineDownload(
            context: Context,
            magnetUri: String,
            fileIdx: Int? = null,
            mediaId: Int,
            animeTitle: String,
            episodeNum: Int,
            episodeTitle: String,
            coverUrl: String?,
            thumbnailUrl: String?,
            audioType: String,
            quality: String,
            candidates: List<OfflineDownloadCandidate> = emptyList(),
            isAdultContent: Boolean? = null
        ) {
            val effectiveCandidates = candidates.ifEmpty {
                listOf(
                    OfflineDownloadCandidate(
                        url = magnetUri,
                        isTorrent = magnetUri.startsWith("magnet:", ignoreCase = true),
                        fileIdx = fileIdx,
                        audioType = audioType,
                        quality = quality
                    )
                )
            }
            val encodedCandidates = encodeOfflineCandidates(effectiveCandidates)
            val primary = effectiveCandidates.first()
            val intent = Intent(context, TorrentStreamService::class.java).apply {
                action = ACTION_DOWNLOAD_OFFLINE
                putExtra(EXTRA_MAGNET, primary.url)
                putExtra(EXTRA_FILE_IDX, primary.fileIdx ?: -1)
                putExtra(EXTRA_MEDIA_ID, mediaId)
                putExtra(EXTRA_ANIME_TITLE, animeTitle)
                putExtra(EXTRA_EPISODE_NUM, episodeNum)
                putExtra(EXTRA_EPISODE_TITLE, episodeTitle)
                putExtra(EXTRA_COVER_URL, coverUrl ?: "")
                putExtra(EXTRA_THUMB_URL, thumbnailUrl ?: "")
                putExtra(EXTRA_AUDIO_TYPE, primary.audioType)
                putExtra(EXTRA_QUALITY, primary.quality)
                putExtra(EXTRA_CANDIDATES_JSON, encodedCandidates)
                isAdultContent?.let { putExtra(EXTRA_IS_ADULT_CONTENT, it) }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w("TorrentStreamService", "Could not start offline download service", e)
                val finalFile = OfflineVault.episodeFile(context, mediaId, episodeNum)
                OfflineVault.update(
                    context,
                    OfflineEpisodeEntry(
                        mediaId = mediaId,
                        animeTitle = animeTitle,
                        episodeNumber = episodeNum,
                        episodeTitle = episodeTitle,
                        coverUrl = coverUrl,
                        thumbnailUrl = thumbnailUrl,
                        audioType = primary.audioType,
                        quality = primary.quality,
                        vaultFilePath = finalFile.absolutePath,
                        sizeBytes = 0,
                        downloadedBytes = 0,
                        progressPercent = 0,
                        isCompleted = false,
                        addedAt = System.currentTimeMillis(),
                        failure = "Background download service was blocked. Open the app and tap Retry.",
                        retryCandidatesJson = encodedCandidates,
                        isAdultContent = isAdultContent
                    )
                )
            }
        }

        fun retryOfflineDownload(context: Context, entry: OfflineEpisodeEntry): Boolean {
            val candidates = decodeOfflineCandidates(entry.retryCandidatesJson)
            if (candidates.isEmpty()) return false
            val first = candidates.first()
            startOfflineDownload(
                context = context,
                magnetUri = first.url,
                fileIdx = first.fileIdx,
                mediaId = entry.mediaId,
                animeTitle = entry.animeTitle,
                episodeNum = entry.episodeNumber,
                episodeTitle = entry.episodeTitle,
                coverUrl = entry.coverUrl,
                thumbnailUrl = entry.thumbnailUrl,
                audioType = entry.audioType,
                quality = entry.quality,
                candidates = candidates,
                isAdultContent = entry.isAdultContent
            )
            return true
        }

        fun stopStream(requestId: String) {
            _streamStatus.update { current ->
                if (current.requestId == requestId) ActiveStreamStatus() else current
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val socketLock = Any()
    private var serverSocket: ServerSocket? = null
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
    private var streamJob: Job? = null
    private var streamRequestId: String? = null
    private var latestStartId = 0
    private data class StreamSession(
        val id: String, val file: File, val size: Long, val handle: TorrentHandle,
        val fileOffset: Long, val pieceLength: Int, val pieceCount: Int,
        val demand: PieceDemand = PieceDemand()
    )
    private val activeSession = AtomicReference<StreamSession?>()
    private val activeOfflineCast = AtomicReference<Pair<String, File>?>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Nanami Anime Playback Engine"))
        serviceScope.launch(Dispatchers.Main.immediate) {
            streamStatus.collect { status ->
                if (streamRequestId != null && streamRequestId != status.requestId) {
                    streamRequestId = null
                    streamJob?.cancel()
                    activeSession.set(null)
                    closeClientSockets()
                    stopIfIdle()
                }
            }
        }
    }

    private fun stopIfIdle() {
        val status = _streamStatus.value
        val noDownloads = offlineJobs.values.none { !it.isCompleted }
        val noSeeds = seedingJobs.values.none { !it.isCompleted }
        if ((status.requestId == null || status.error != null) && noDownloads && noSeeds) {
            stopSelfResult(latestStartId)
        }
    }

    private fun closeClientSockets() {
        clientSockets.forEach { socket -> runCatching { socket.close() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_START_STREAM -> {
                val magnet = intent.getStringExtra(EXTRA_MAGNET)
                val fileIdx = intent.getIntExtra(EXTRA_FILE_IDX, -1).takeIf { it >= 0 }
                val epNum = intent.getIntExtra(EXTRA_EPISODE_NUM, 1)
                val title = intent.getStringExtra(EXTRA_ANIME_TITLE) ?: "Anime"
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                if (!magnet.isNullOrEmpty() && requestId != null && requestId == _streamStatus.value.requestId) {
                    // A queued intent must not revive a stream already replaced/stopped by the UI.
                    streamRequestId = requestId
                    launchLiveStream(magnet, fileIdx, title, epNum, requestId)
                } else {
                    stopIfIdle()
                }
            }
            ACTION_DOWNLOAD_OFFLINE -> {
                val magnet = intent.getStringExtra(EXTRA_MAGNET) ?: return START_NOT_STICKY
                val fileIdx = intent.getIntExtra(EXTRA_FILE_IDX, -1).takeIf { it >= 0 }
                val mediaId = intent.getIntExtra(EXTRA_MEDIA_ID, 0)
                val animeTitle = intent.getStringExtra(EXTRA_ANIME_TITLE) ?: "Anime"
                val epNum = intent.getIntExtra(EXTRA_EPISODE_NUM, 1)
                val epTitle = intent.getStringExtra(EXTRA_EPISODE_TITLE) ?: "Episode $epNum"
                val coverUrl = intent.getStringExtra(EXTRA_COVER_URL)?.takeIf { it.isNotBlank() }
                val thumbUrl = intent.getStringExtra(EXTRA_THUMB_URL)?.takeIf { it.isNotBlank() }
                val audioType = intent.getStringExtra(EXTRA_AUDIO_TYPE) ?: "SUB"
                val quality = intent.getStringExtra(EXTRA_QUALITY) ?: "1080p"
                val candidatesJson = intent.getStringExtra(EXTRA_CANDIDATES_JSON)
                val isAdultContent = if (intent.hasExtra(EXTRA_IS_ADULT_CONTENT)) {
                    intent.getBooleanExtra(EXTRA_IS_ADULT_CONTENT, false)
                } else null

                launchOfflineDownload(
                    magnetUri = magnet,
                    preferredFileIdx = fileIdx,
                    mediaId = mediaId,
                    animeTitle = animeTitle,
                    episodeNum = epNum,
                    episodeTitle = epTitle,
                    coverUrl = coverUrl,
                    thumbnailUrl = thumbUrl,
                    audioType = audioType,
                    quality = quality,
                    candidatesJson = candidatesJson,
                    isAdultContent = isAdultContent
                )
            }
            ACTION_SERVE_OFFLINE_CAST -> {
                val rawPath = intent.getStringExtra(EXTRA_OFFLINE_FILE_PATH)
                val file = rawPath?.let(::File)
                val vaultDir = OfflineVault.preparedDirectory(applicationContext)
                if (file != null && file.isFile && file.canonicalPath.startsWith(vaultDir.canonicalPath + File.separator)) {
                    serviceScope.launch {
                        runCatching {
                            val token = UUID.randomUUID().toString()
                            val port = localHttpServerPort()
                            activeOfflineCast.set(token to file)
                            val lanIp = findLanIpv4Address()
                            if (lanIp != null) {
                                _offlineCastUrl.value = "http://$lanIp:$port/$token/offline.mp4"
                            }
                        }
                    }
                } else {
                    stopIfIdle()
                }
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pickVideoFileIndex(tInfo: TorrentInfo, preferredIdx: Int?, episodeNum: Int): Int {
        val files = tInfo.files()
        val videos = (0 until files.numFiles()).filter { index ->
            videoExtension(files.fileName(index)) != null && files.fileSize(index) > 0
        }
        require(videos.isNotEmpty()) { "Torrent has no supported video files" }
        if (preferredIdx != null && preferredIdx in videos) return preferredIdx
        if (videos.size == 1) return videos.single()

        val namedEpisodes = videos.mapNotNull { index ->
            torrentEpisodeNumber(files.fileName(index))?.let { index to it }
        }
        return if (namedEpisodes.isEmpty()) {
            videos.maxBy { files.fileSize(it) }
        } else {
            requireNotNull(namedEpisodes.filter { it.second == episodeNum }.maxByOrNull { files.fileSize(it.first) }) {
                "Episode $episodeNum is missing from the torrent"
            }.first
        }
    }

    private fun videoExtension(fileName: String): String? =
        fileName.substringAfterLast('.', "").lowercase().takeIf { it in setOf("mkv", "mp4", "webm") }

    private fun applyDownloadLimit(session: SessionManager, limitKbps: Int) {
        session.downloadRateLimit((limitKbps.toLong().coerceAtLeast(0) * 1024L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun addVideoDownload(session: SessionManager, tInfo: TorrentInfo, saveDir: File,
        magnetUri: String, targetIdx: Int): TorrentHandle {
        // download() queues async_add_torrent. Use the synchronous API on Dispatchers.IO,
        // with initial priorities so no asynchronous file-priority change can reset deadlines.
        val params = AddTorrentParams.parseMagnetUri(magnetUri).apply {
            setTorrentInfo(tInfo)
            setSavePath(saveDir.absolutePath)
            filePriorities(Array(tInfo.numFiles()) { if (it == targetIdx) Priority.DEFAULT else Priority.IGNORE })
            setFlags(getFlags().and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.AUTO_MANAGED.inv())
                .and_(TorrentFlags.UPLOAD_MODE.inv()).and_(TorrentFlags.STOP_WHEN_READY.inv())
                .or_(TorrentFlags.SEQUENTIAL_DOWNLOAD))
        }
        val handle = addTorrent(session, params)
        handle.unsetFlags(TorrentFlags.AUTO_MANAGED.or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.STOP_WHEN_READY))
        handle.resume()
        return handle
    }

    private fun addTorrent(session: SessionManager, params: AddTorrentParams): TorrentHandle {
        check(session.isRunning) { "Session not running" }
        val error = ErrorCode(error_code())
        val handle = SessionHandle(session.swig()).addTorrent(params, error)
        check(!error.isError && handle.isValid) { "Native torrent add failed" }
        return handle
    }

    private fun addLiveTorrent(session: SessionManager, magnetUri: String, saveDir: File): TorrentHandle {
        val params = AddTorrentParams.parseMagnetUri(magnetUri).apply {
            setSavePath(saveDir.absolutePath)
            // Fetch metadata on this handle, and keep its peers when video download starts.
            setFlags(getFlags().and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.AUTO_MANAGED.inv())
                .and_(TorrentFlags.STOP_WHEN_READY.inv())
                .or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.SEQUENTIAL_DOWNLOAD))
        }
        return addTorrent(session, params).also { it.resume() }
    }

    private suspend fun awaitMetadata(handle: TorrentHandle, timeoutMs: Long = 25_000L): TorrentInfo = withTimeout(timeoutMs) {
        var info: TorrentInfo? = null
        while (info == null) {
            check(!handle.status().errorCode().isError) { "Could not fetch torrent metadata" }
            info = handle.torrentFile()?.takeIf { it.isValid && it.numFiles() > 0 }
            if (info == null) delay(100)
        }
        info
    }

    private suspend fun selectLiveFile(handle: TorrentHandle, fileCount: Int, targetIdx: Int) {
        if (fileCount == 1) return
        val priorities = Array(fileCount) { if (it == targetIdx) Priority.DEFAULT else Priority.IGNORE }
        handle.prioritizeFiles(priorities)
        // File updates can reset piece priorities after returning. Wait before assigning deadlines.
        withTimeout(5_000L) {
            while (!handle.filePriorities().contentEquals(priorities)) {
                check(!handle.status().errorCode().isError) { "Could not set torrent file priorities" }
                delay(50)
            }
        }
    }

    private fun requestPieces(stream: StreamSession, offset: Long, lookAhead: Int) {
        val firstPiece = ((stream.fileOffset + offset) / stream.pieceLength).toInt()
        val lastPiece = ((stream.fileOffset + stream.size - 1) / stream.pieceLength).toInt()
        for (piece in firstPiece..minOf(lastPiece, firstPiece + lookAhead)) {
            if (stream.handle.havePiece(piece)) continue
            val delayMs = (piece - firstPiece) * 250
            stream.demand.request(piece, delayMs) {
                stream.handle.setPieceDeadline(piece, delayMs)
            }
        }
    }

    private fun hasFileRangeDownloaded(offset: Long, length: Long, stream: StreamSession): Boolean =
        verifiedFileRange(offset, length, stream.size, stream.fileOffset, stream.pieceLength, stream.pieceCount,
            stream.handle::havePiece)

    private fun updateStreamStatus(requestId: String, transform: (ActiveStreamStatus) -> ActiveStreamStatus) {
        _streamStatus.update { current ->
            if (current.requestId == requestId) transform(current) else current
        }
    }

    private fun launchLiveStream(
        magnetUri: String,
        preferredFileIdx: Int?,
        animeTitle: String,
        episodeNum: Int,
        requestId: String
    ) {
        // Each request owns its own native session and cache. Teardown of the old session can finish in parallel.
        streamJob?.cancel()
        activeSession.set(null)
        closeClientSockets()
        streamJob = serviceScope.launch {
            var nativeSession: SessionManager? = null
            var stream: StreamSession? = null
            var phase = "session_start"
            val saveDir = File(cacheDir, "live_stream_cache/$requestId")
            val startedAt = SystemClock.elapsedRealtime()
            try {
                ensureActive()
                val session = SessionManager().also { nativeSession = it; it.start() }
                val prefs = MAnimePrefs(applicationContext)
                check(saveDir.mkdirs() || saveDir.isDirectory) { "Could not create streaming cache" }
                runCatching { File(saveDir, ".nomedia").createNewFile() }

                phase = "metadata"
                updateStreamStatus(requestId) { status ->
                    status.copy(phase = phase, statusMessage = "Loading Episode $episodeNum stream...", progressPercent = 5)
                }
                updateNotification("Playing $animeTitle • Ep $episodeNum")
                val handle = addLiveTorrent(session, magnetUri, saveDir)
                val tInfo = awaitMetadata(handle)
                val metadataAt = SystemClock.elapsedRealtime()
                ensureActive()

                phase = "episode_file"
                val targetIdx = pickVideoFileIndex(tInfo, preferredFileIdx, episodeNum)
                val files = tInfo.files()
                val targetRelPath = files.filePath(targetIdx)
                val expectedBytes = files.fileSize(targetIdx)
                val videoFile = File(saveDir, targetRelPath)
                require(videoFile.canonicalPath.startsWith(saveDir.canonicalPath + File.separator))
                require(tInfo.pieceLength() > 0 && tInfo.numPieces() > 0) { "Torrent has no valid pieces" }

                phase = "file_priorities"
                selectLiveFile(handle, tInfo.numFiles(), targetIdx)
                val serving = StreamSession(requestId, videoFile, expectedBytes, handle,
                    files.fileOffset(targetIdx), tInfo.pieceLength(), tInfo.numPieces())
                stream = serving
                requestPieces(serving, offset = 0, lookAhead = 0)
                applyDownloadLimit(session, prefs.torrentMaxSpeed.first())
                handle.unsetFlags(TorrentFlags.UPLOAD_MODE)

                phase = "prebuffer"
                val minPrebufferBytes = bytesInCurrentPiece(0, expectedBytes, serving.fileOffset, serving.pieceLength)
                withTimeout(35_000L) {
                    while (true) {
                        val status = handle.status()
                        check(!status.errorCode().isError) { "Native download error" }
                        if (hasFileRangeDownloaded(0L, minPrebufferBytes, serving) && videoFile.isFile &&
                            videoFile.length() >= minPrebufferBytes) break
                        val speedKbps = (status.downloadPayloadRate() / 1024).coerceAtLeast(0)
                        val peers = status.numPeers()
                        val pct = ((status.totalWantedDone().toDouble() / minPrebufferBytes) * 100).toInt().coerceIn(5, 99)
                        val speedLabel = if (speedKbps >= 1024) String.format("%.1f MB/s", speedKbps / 1024.0) else "$speedKbps KB/s"
                        updateStreamStatus(requestId) {
                            it.copy(phase = phase, progressPercent = pct, downloadSpeedKbps = speedKbps, connectedPeers = peers,
                                statusMessage = if (peers > 0) "Buffering Episode $episodeNum ($pct% • $speedLabel)"
                                    else "Connecting to Episode $episodeNum stream...")
                        }
                        delay(75)
                    }
                }

                ensureActive()
                phase = "http_start"
                val port = localHttpServerPort()
                phase = "serving"
                val ext = videoExtension(targetRelPath)
                val url = "http://127.0.0.1:$port/$requestId/stream.$ext"
                activeSession.updateAndGet { current ->
                    if (_streamStatus.value.requestId == requestId) serving else current
                }
                updateStreamStatus(requestId) {
                    it.copy(phase = phase, isReady = true, isBuffering = false,
                        statusMessage = "Ready", progressPercent = 100, streamUrl = url)
                }
                Log.d("TorrentStreamService", "Startup: metadata ${metadataAt - startedAt} ms, verified stream ${SystemClock.elapsedRealtime() - startedAt} ms")

                val rateJob = launch {
                    prefs.torrentMaxSpeed.collect { applyDownloadLimit(session, it) }
                }
                try {
                    while (isActive) {
                        val status = handle.status()
                        check(!status.errorCode().isError) { "Native download error" }
                        updateStreamStatus(requestId) {
                            it.copy(progressPercent = (status.progress() * 100).toInt().coerceIn(0, 100),
                                downloadSpeedKbps = (status.downloadPayloadRate() / 1024).coerceAtLeast(0),
                                connectedPeers = status.numPeers())
                        }
                        delay(1200)
                    }
                } finally {
                    withContext(NonCancellable) { rateJob.cancelAndJoin() }
                }
            } catch (e: TimeoutCancellationException) {
                ensureActive()
                updateStreamStatus(requestId) {
                    val reason = when (phase) {
                        "metadata" -> "Timed out waiting for torrent details from peers."
                        "file_priorities" -> "Timed out selecting the episode in this torrent."
                        else -> "Timed out waiting for enough data from this torrent to start playback."
                    }
                    it.copy(isReady = false, isBuffering = false, phase = phase, statusMessage = "Stream timed out",
                        error = reason)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e !is Exception && e !is LinkageError) throw e
                ensureActive()
                Log.w("TorrentStreamService", "Playback engine failed in $phase", e)
                val reason = when {
                    e is LinkageError -> "The torrent engine couldn't load on this device."
                    phase == "episode_file" -> "Couldn't identify a playable episode file in this torrent."
                    else -> "Couldn't play this torrent."
                }
                updateStreamStatus(requestId) {
                    it.copy(isReady = false, isBuffering = false, streamUrl = null, phase = phase,
                        statusMessage = "Playback failed",
                        error = reason)
                }
            } finally {
                stream?.let { activeSession.compareAndSet(it, null) }
                runCatching { nativeSession?.pause() }
                runCatching { nativeSession?.stop() }
                saveDir.deleteRecursively()
                serviceScope.launch(Dispatchers.Main.immediate) { stopIfIdle() }
            }
        }
    }

    private fun launchOfflineDownload(
        magnetUri: String,
        preferredFileIdx: Int?,
        mediaId: Int,
        animeTitle: String,
        episodeNum: Int,
        episodeTitle: String,
        coverUrl: String?,
        thumbnailUrl: String?,
        audioType: String,
        quality: String,
        candidatesJson: String? = null,
        isAdultContent: Boolean? = null
    ) {
        val key = mediaId to episodeNum
        val context = applicationContext
        val decoded = decodeOfflineCandidates(candidatesJson)
        val candidates = decoded.ifEmpty {
            listOf(
                OfflineDownloadCandidate(
                    url = magnetUri,
                    isTorrent = magnetUri.startsWith("magnet:", ignoreCase = true),
                    fileIdx = preferredFileIdx,
                    audioType = audioType,
                    quality = quality
                )
            )
        }
        val persistedCandidatesJson = encodeOfflineCandidates(candidates)

        synchronized(OfflineVault.lock) {
            if (offlineJobs[key]?.isActive == true) return
            val previousDownload = offlineDownloadTail
            val downloadFinished = CompletableDeferred<Unit>()
            offlineDownloadTail = downloadFinished
            val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                if (OfflineVault.getCompleted(context, mediaId, episodeNum) != null) return@launch

                var session: SessionManager? = null
                val finalFile = OfflineVault.episodeFile(context, mediaId, episodeNum)
                var entry = OfflineEpisodeEntry(
                    mediaId = mediaId,
                    animeTitle = animeTitle,
                    episodeNumber = episodeNum,
                    episodeTitle = episodeTitle,
                    coverUrl = coverUrl,
                    thumbnailUrl = thumbnailUrl,
                    audioType = audioType,
                    quality = quality,
                    vaultFilePath = finalFile.absolutePath,
                    sizeBytes = 0,
                    downloadedBytes = 0,
                    progressPercent = 0,
                    isCompleted = false,
                    addedAt = System.currentTimeMillis(),
                    statusText = "Connecting to source…",
                    retryCandidatesJson = persistedCandidatesJson,
                    isAdultContent = isAdultContent
                )

                fun publishEntry(next: OfflineEpisodeEntry) {
                    synchronized(OfflineVault.lock) {
                        ensureActive()
                        OfflineVault.update(context, next)
                        entry = next
                    }
                }

                fun commitFile(source: File, totalBytes: Long, candidate: OfflineDownloadCandidate): Boolean =
                    synchronized(OfflineVault.lock) {
                        ensureActive()
                        if (finalFile.exists() && !finalFile.delete()) return@synchronized false
                        if (!source.renameTo(finalFile)) return@synchronized false
                        try {
                            publishEntry(
                                entry.copy(
                                    audioType = candidate.audioType,
                                    quality = candidate.quality,
                                    verifiedAdultEpisode = candidate.verifiedAdultEpisode && !candidate.isTorrent,
                                    sizeBytes = totalBytes,
                                    downloadedBytes = totalBytes,
                                    progressPercent = 100,
                                    isCompleted = true,
                                    failure = null,
                                    downloadSpeedKbps = 0,
                                    connectedPeers = 0,
                                    statusText = null
                                )
                            )
                        } catch (e: Exception) {
                            finalFile.delete()
                            throw e
                        }
                        true
                    }

                fun finalizeStagingFile(stagingFile: File, totalBytes: Long, candidate: OfflineDownloadCandidate) {
                    if (!commitFile(stagingFile, totalBytes, candidate)) {
                        val copy = File.createTempFile("episode_", ".tmp", finalFile.parentFile)
                        try {
                            stagingFile.copyTo(copy, overwrite = true)
                            check(copy.length() == totalBytes && commitFile(copy, totalBytes, candidate)) {
                                "Could not save the offline file"
                            }
                        } finally {
                            copy.delete()
                        }
                    }
                }

                try {
                    publishEntry(entry.copy(statusText = "Queued for download…"))
                    previousDownload.await()
                    ensureActive()
                    OfflineVault.preparedDirectory(context)
                    val prefsStore = MAnimePrefs(context)
                    if (!canDownloadWithNetworkPolicy(context, prefsStore.allowCellularDownload.first())) {
                        throw OfflineDownloadException(
                            "Cellular downloads are disabled. Connect to Wi-Fi or allow cellular downloads, then tap Retry."
                        )
                    }
                    publishEntry(entry.copy(statusText = "Connecting to source…"))
                    updateNotification("Preparing download: $animeTitle • Ep $episodeNum", progressPercent = 0)
                    val speedLimitKbps = prefsStore.torrentMaxSpeed.first()

                    var lastFailureMessage = "Could not connect to any download source. Try again."
                    var succeeded = false

                    for ((index, candidate) in candidates.withIndex()) {
                        ensureActive()
                        val hasMoreCandidates = index < candidates.lastIndex
                        val sourceTag = if (candidates.size > 1) " (source ${index + 1}/${candidates.size})" else ""
                        val tempDir = Files.createTempDirectory(cacheDir.toPath(), "offline_staging_").toFile()
                        runCatching { File(tempDir, ".nomedia").createNewFile() }

                        try {
                            publishEntry(
                                entry.copy(
                                    audioType = candidate.audioType,
                                    quality = candidate.quality,
                                    progressPercent = 0,
                                    downloadedBytes = 0,
                                    downloadSpeedKbps = 0,
                                    connectedPeers = 0,
                                    failure = null,
                                    statusText = "Connecting$sourceTag…"
                                )
                            )

                            if (!candidate.isTorrent) {
                                val stagingFile = File(tempDir, "direct_ep_${episodeNum}.nanami.tmp")
                                val reqBuilder = Request.Builder()
                                    .url(candidate.url)
                                    .header("User-Agent", StremioAddonClient.BROWSER_UA)
                                for ((hk, hv) in candidate.headers) reqBuilder.header(hk, hv)

                                val call = downloadHttpClient.newCall(reqBuilder.build())
                                val cancelHandle = coroutineContext.job.invokeOnCompletion {
                                    if (it is CancellationException) call.cancel()
                                }
                                try {
                                    call.execute().use { response ->
                                        if (!response.isSuccessful) {
                                            throw OfflineDownloadException("Direct source returned HTTP ${response.code}.")
                                        }
                                        val contentType = response.header("Content-Type").orEmpty().lowercase()
                                        if (contentType.contains("text/html") || contentType.contains("application/json")) {
                                            throw OfflineDownloadException("Direct source did not return a video file.")
                                        }
                                        val body = response.body ?: throw OfflineDownloadException("Direct source was empty.")
                                        val expectedTotal = body.contentLength().takeIf { it > 0L } ?: 0L
                                        if (expectedTotal > 0L && tempDir.usableSpace < expectedTotal) {
                                            throw OfflineDownloadException("There is not enough storage for this episode.")
                                        }

                                        val buffer = ByteArray(64 * 1024)
                                        var downloaded = 0L
                                        var windowStartMs = SystemClock.elapsedRealtime()
                                        var windowStartBytes = 0L
                                        var lastUiUpdateMs = 0L

                                        body.byteStream().use { input ->
                                            FileOutputStream(stagingFile).use { out ->
                                                while (true) {
                                                    ensureActive()
                                                    val read = input.read(buffer)
                                                    if (read == -1) break
                                                    out.write(buffer, 0, read)
                                                    downloaded += read

                                                    val now = SystemClock.elapsedRealtime()
                                                    if (now - lastUiUpdateMs >= 800L) {
                                                        if (!canDownloadWithNetworkPolicy(context, prefsStore.allowCellularDownload.first())) {
                                                            throw OfflineDownloadException(
                                                                "Paused on cellular data. Connect to Wi-Fi or allow cellular downloads, then tap Retry."
                                                            )
                                                        }
                                                        val elapsedSec = ((now - windowStartMs) / 1000.0).coerceAtLeast(0.25)
                                                        val speedKbps = (((downloaded - windowStartBytes) / 1024.0) / elapsedSec).toInt().coerceAtLeast(0)
                                                        windowStartMs = now
                                                        windowStartBytes = downloaded
                                                        lastUiUpdateMs = now

                                                        val pct = if (expectedTotal > 0L) {
                                                            ((downloaded.toDouble() / expectedTotal) * 100).toInt().coerceIn(1, 99)
                                                        } else 50
                                                        val speedLabel = formatSpeed(speedKbps)
                                                        publishEntry(
                                                            entry.copy(
                                                                sizeBytes = expectedTotal.coerceAtLeast(downloaded),
                                                                downloadedBytes = downloaded,
                                                                progressPercent = pct,
                                                                downloadSpeedKbps = speedKbps,
                                                                statusText = if (expectedTotal > 0L) "Downloading $pct% • $speedLabel" else "Downloading • $speedLabel"
                                                            )
                                                        )
                                                        updateNotification("Downloading $animeTitle Ep $episodeNum ($pct%)", progressPercent = pct)
                                                    }
                                                }
                                                out.fd.sync()
                                            }
                                        }

                                        if (downloaded < 512 * 1024L || (expectedTotal > 0L && downloaded != expectedTotal)) {
                                            throw OfflineDownloadException("Direct download ended early.")
                                        }
                                        finalizeStagingFile(stagingFile, downloaded, candidate)
                                        succeeded = true
                                    }
                                } finally {
                                    cancelHandle.dispose()
                                }
                            } else {
                                val torrentSession = session ?: SessionManager().also {
                                    session = it
                                    it.start()
                                    applyDownloadLimit(it, speedLimitKbps)
                                }

                                val handle = addLiveTorrent(torrentSession, candidate.url, tempDir)
                                try {
                                    publishEntry(entry.copy(statusText = "Fetching episode metadata$sourceTag…"))
                                    val info = try {
                                        awaitMetadata(handle, timeoutMs = if (hasMoreCandidates) 30_000L else 50_000L)
                                    } catch (_: TimeoutCancellationException) {
                                        throw OfflineDownloadException("Timed out connecting to peers.")
                                    }

                                    val targetIdx = pickVideoFileIndex(info, candidate.fileIdx, episodeNum)
                                    val files = info.files()
                                    val totalBytes = files.fileSize(targetIdx)
                                    val stagingFile = File(tempDir, files.filePath(targetIdx)).canonicalFile
                                    check(totalBytes > 0 && stagingFile.path.startsWith(tempDir.canonicalPath + File.separator))
                                    if (tempDir.usableSpace < totalBytes) {
                                        // Storage full cannot be fixed by trying another source; abort immediately.
                                        lastFailureMessage = "There is not enough storage for this episode."
                                        break
                                    }

                                    selectLiveFile(handle, info.numFiles(), targetIdx)
                                    // Unset UPLOAD_MODE and SEQUENTIAL_DOWNLOAD so rarest-first swarm downloading runs at max speed!
                                    handle.unsetFlags(
                                        TorrentFlags.AUTO_MANAGED
                                            .or_(TorrentFlags.UPLOAD_MODE)
                                            .or_(TorrentFlags.STOP_WHEN_READY)
                                            .or_(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                                    )
                                    handle.resume()

                                    val fileOffset = files.fileOffset(targetIdx)
                                    val pieceLength = info.pieceLength()
                                    val pieceCount = info.numPieces()
                                    val startedTransferAt = SystemClock.elapsedRealtime()
                                    var lastProgress = startedTransferAt
                                    var mostDownloaded = 0L

                                    while (isActive) {
                                        check(handle.isValid) { "Download handle expired" }
                                        if (!canDownloadWithNetworkPolicy(context, prefsStore.allowCellularDownload.first())) {
                                            throw OfflineDownloadException(
                                                "Paused on cellular data. Connect to Wi-Fi or allow cellular downloads, then tap Retry."
                                            )
                                        }
                                        val status = handle.status()
                                        check(!status.errorCode().isError) { "Torrent download failed" }
                                        val done = status.totalWantedDone().coerceIn(0L, totalBytes)
                                        val speedKbps = (status.downloadPayloadRate() / 1024).coerceAtLeast(0)
                                        val peers = status.numPeers()

                                        if (done >= totalBytes &&
                                            verifiedFileRange(0, totalBytes, totalBytes, fileOffset, pieceLength, pieceCount, handle::havePiece) &&
                                            stagingFile.isFile && stagingFile.length() == totalBytes
                                        ) {
                                            break
                                        }

                                        val now = SystemClock.elapsedRealtime()
                                        if (done > mostDownloaded) {
                                            mostDownloaded = done
                                            lastProgress = now
                                        }

                                        // Fail over quickly if 0 bytes arrived on this torrent and another source is waiting!
                                        val zeroByteTimeoutMs = if (hasMoreCandidates) 35_000L else 75_000L
                                        val stallTimeoutMs = if (hasMoreCandidates) 75_000L else 150_000L
                                        if (mostDownloaded == 0L && now - startedTransferAt > zeroByteTimeoutMs) {
                                            throw OfflineDownloadException("No active peers responded for this source.")
                                        }
                                        if (now - lastProgress > stallTimeoutMs) {
                                            throw OfflineDownloadException("The download stalled. Try again when more peers are online.")
                                        }

                                        val percent = if (done > 0L) {
                                            (done.toDouble() / totalBytes * 100).toInt().coerceIn(1, 99)
                                        } else 0
                                        val statusMsg = when {
                                            percent == 0 && peers > 0 -> "Connected ($peers peers)$sourceTag…"
                                            percent == 0 -> "Finding peers$sourceTag…"
                                            speedKbps > 0 -> "Downloading $percent% • ${formatSpeed(speedKbps)}"
                                            else -> "Downloading $percent% ($peers peers)"
                                        }
                                        publishEntry(
                                            entry.copy(
                                                sizeBytes = totalBytes,
                                                downloadedBytes = done,
                                                progressPercent = percent,
                                                downloadSpeedKbps = speedKbps,
                                                connectedPeers = peers,
                                                statusText = statusMsg
                                            )
                                        )
                                        updateNotification("Downloading $animeTitle Ep $episodeNum ($percent%)", progressPercent = percent)
                                        delay(1_000)
                                    }

                                    ensureActive()
                                    runCatching { handle.pause() }
                                    finalizeStagingFile(stagingFile, totalBytes, candidate)
                                    succeeded = true
                                    startWifiSeedingIfEnabled(
                                        key = key,
                                        magnetUri = candidate.url,
                                        tInfo = info,
                                        targetIdx = targetIdx,
                                        finalFile = finalFile,
                                        totalBytes = totalBytes
                                    )
                                } finally {
                                    runCatching {
                                        if (handle.isValid) SessionHandle(torrentSession.swig()).removeTorrent(handle)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            if (e !is Exception && e !is LinkageError) throw e
                            Log.w("TorrentStreamService", "Candidate ${index + 1}/${candidates.size} failed", e)
                            lastFailureMessage = (e as? OfflineDownloadException)?.message
                                ?: "Source ${index + 1} failed. Retrying…"
                        } finally {
                            tempDir.deleteRecursively()
                        }

                        if (succeeded) break
                    }

                    if (!succeeded) {
                        throw OfflineDownloadException(lastFailureMessage)
                    }
                    updateNotification("Download complete: $animeTitle • Ep $episodeNum")
                } catch (e: CancellationException) {
                    synchronized(OfflineVault.lock) {
                        if (offlineJobs[key] === coroutineContext.job && OfflineVault.episodes.value.contains(entry) &&
                            !entry.isCompleted
                        ) {
                            OfflineVault.update(
                                context,
                                entry.copy(
                                    failure = "Download paused or interrupted. Tap Retry to resume.",
                                    downloadSpeedKbps = 0,
                                    statusText = null
                                )
                            )
                        }
                    }
                    throw e
                } catch (e: Throwable) {
                    if (e !is Exception && e !is LinkageError) throw e
                    Log.w("TorrentStreamService", "Episode download failed", e)
                    if (entry.isCompleted) return@launch
                    val message = (e as? OfflineDownloadException)?.message
                        ?: "The episode could not be saved. Tap Retry to try again."
                    publishEntry(entry.copy(failure = message, downloadSpeedKbps = 0, statusText = null))
                    updateNotification("Download failed: $animeTitle • Ep $episodeNum")
                } finally {
                    runCatching { session?.stop() }
                }
            }
            offlineJobs[key] = job
            job.invokeOnCompletion {
                // A canceled queue entry must still wait its turn before releasing the next one.
                previousDownload.invokeOnCompletion { downloadFinished.complete(Unit) }
                offlineJobs.remove(key, job)
                serviceScope.launch(Dispatchers.Main.immediate) { stopIfIdle() }
            }
            job.start()
        }
    }

    private fun startWifiSeedingIfEnabled(
        key: Pair<Int, Int>,
        magnetUri: String,
        tInfo: TorrentInfo,
        targetIdx: Int,
        finalFile: File,
        totalBytes: Long
    ) {
        val context = applicationContext
        seedingJobs.remove(key)?.cancel()
        val job = serviceScope.launch {
            val prefsStore = MAnimePrefs(context)
            if (!prefsStore.seedDownloadOnWifi.first() || !isWifiOrEthernetActive(context) || !finalFile.isFile) {
                return@launch
            }
            if (!wifiSeedSemaphore.tryAcquire()) return@launch
            var seedSession: SessionManager? = null
            var seedDir: File? = null
            try {
                if (!tInfo.isValid || targetIdx !in 0 until tInfo.numFiles()) return@launch
                val dir = Files.createTempDirectory(cacheDir.toPath(), "wifi_seed_").toFile()
                seedDir = dir
                runCatching { File(dir, ".nomedia").createNewFile() }
                val relPath = tInfo.files().filePath(targetIdx)
                val targetSeedFile = File(dir, relPath).canonicalFile
                if (!targetSeedFile.path.startsWith(dir.canonicalPath + File.separator)) return@launch
                targetSeedFile.parentFile?.mkdirs()
                // Zero-copy POSIX hardlink on app storage so seeding consumes 0 extra disk space!
                val linked = runCatching {
                    Files.createLink(targetSeedFile.toPath(), finalFile.toPath())
                    true
                }.getOrDefault(false)
                if (!linked) return@launch

                val session = SessionManager().also {
                    seedSession = it
                    it.start()
                }
                val params = AddTorrentParams.parseMagnetUri(magnetUri).apply {
                    setTorrentInfo(tInfo)
                    setSavePath(dir.absolutePath)
                    filePriorities(Array(tInfo.numFiles()) { if (it == targetIdx) Priority.DEFAULT else Priority.IGNORE })
                    setFlags(
                        getFlags()
                            .and_(TorrentFlags.PAUSED.inv())
                            .and_(TorrentFlags.AUTO_MANAGED.inv())
                            .and_(TorrentFlags.STOP_WHEN_READY.inv())
                    )
                }
                val handle = addTorrent(session, params)
                handle.resume()

                val startedAt = SystemClock.elapsedRealtime()
                val maxSeedWindowMs = 30L * 60L * 1_000L // Polite 30-minute Wi-Fi seed window or 1.0x share ratio
                while (isActive) {
                    if (!finalFile.isFile || !isWifiOrEthernetActive(context) || !prefsStore.seedDownloadOnWifi.first()) {
                        break
                    }
                    if (!handle.isValid || handle.status().errorCode().isError) break
                    val uploaded = handle.status().totalPayloadUpload()
                    if (totalBytes > 0L && uploaded >= totalBytes) break
                    if (SystemClock.elapsedRealtime() - startedAt >= maxSeedWindowMs) break
                    delay(2_500)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            } finally {
                runCatching { seedSession?.pause() }
                runCatching { seedSession?.stop() }
                runCatching { seedDir?.deleteRecursively() }
                wifiSeedSemaphore.release()
            }
        }
        seedingJobs[key] = job
        job.invokeOnCompletion {
            seedingJobs.remove(key, job)
            serviceScope.launch(Dispatchers.Main.immediate) { stopIfIdle() }
        }
    }

    private fun formatSpeed(speedKbps: Int): String =
        if (speedKbps >= 1024) String.format("%.1f MB/s", speedKbps / 1024.0) else "$speedKbps KB/s"

    /**
     * Local HTTP server with full HTTP 206 Partial Content (Byte-Range) support so ExoPlayer
     * can seek smoothly through MKV & MP4 streams.
     */
    private fun localHttpServerPort(): Int = synchronized(socketLock) {
        serverSocket?.let { if (!it.isClosed) return@synchronized it.localPort }
        check(serviceScope.isActive) { "Playback service is stopping" }
        val server = ServerSocket(0, 16)
        serverSocket = server
        serviceScope.launch {
            try {
                while (isActive && !server.isClosed) {
                    val socket = server.accept()
                    val remoteAddr = socket.inetAddress
                    val allowedPeer = remoteAddr != null && (remoteAddr.isLoopbackAddress || remoteAddr.isSiteLocalAddress)
                    clientSockets.add(socket)
                    if (isActive && allowedPeer) handleClientSocket(socket) else closeClientSocket(socket)
                }
            } catch (e: java.io.IOException) {
                if (!server.isClosed) Log.w("TorrentStreamService", "Loopback HTTP server failed", e)
            } finally {
                runCatching { server.close() }
            }
        }
        server.localPort
    }

    private fun closeClientSocket(socket: Socket) {
        runCatching { socket.close() }
        clientSockets.remove(socket)
    }

    private fun handleClientSocket(socket: Socket) {
        serviceScope.launch {
            try {
                socket.soTimeout = 15_000
                socket.tcpNoDelay = true
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val reader = input.bufferedReader()

                val requestLine = reader.readLine() ?: return@launch
                val request = requestLine.split(' ')
                if (request.size != 3 || request[0] !in listOf("GET", "HEAD", "OPTIONS")) {
                    output.write("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return@launch
                }
                var rangeHeader: String? = null
                var headerBytes = 0
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    headerBytes += line.length
                    check(headerBytes <= 16384) { "Request headers too large" }
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    }
                }
                if (request[0] == "OPTIONS") {
                    output.write(
                        ("HTTP/1.1 204 No Content\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n" +
                            "Access-Control-Allow-Headers: Range, Content-Type\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    output.flush()
                    return@launch
                }

                val offlinePair = activeOfflineCast.get()
                val isOfflineMatch = offlinePair != null &&
                    offlinePair.second.isFile &&
                    request[1] == "/${offlinePair.first}/offline.mp4"

                val stream = if (isOfflineMatch) null else activeSession.get()
                val isLiveMatch = stream != null &&
                    _streamStatus.value.requestId == stream.id &&
                    stream.file.isFile &&
                    request[1] == "/${stream.id}/stream.${videoExtension(stream.file.name)}"

                if (!isOfflineMatch && !isLiveMatch) {
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return@launch
                }

                val file = if (isOfflineMatch) offlinePair!!.second else stream!!.file
                val totalLen = if (isOfflineMatch) file.length() else stream!!.size
                val mimeType = if (isOfflineMatch) {
                    detectOfflineContainerMime(file)
                } else {
                    when (file.extension.lowercase()) {
                        "mp4" -> "video/mp4"
                        "webm" -> "video/webm"
                        else -> "video/x-matroska"
                    }
                }

                val range = parseByteRange(rangeHeader, totalLen)
                if (range == null) {
                    output.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalLen\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                    return@launch
                }
                val startByte = range.start
                val endByte = range.end
                val isPartial = rangeHeader != null
                val contentLength = range.length
                val corsHeaders = "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Expose-Headers: Content-Range, Content-Length, Accept-Ranges\r\n"
                val headers = if (isPartial) {
                    "HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Type: $mimeType\r\n" +
                            "Content-Length: $contentLength\r\n" +
                            "Content-Range: bytes $startByte-$endByte/$totalLen\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            corsHeaders +
                            "Connection: close\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: $mimeType\r\n" +
                            "Content-Length: $totalLen\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            corsHeaders +
                            "Connection: close\r\n\r\n"
                }

                output.write(headers.toByteArray())
                if (request[0] == "HEAD") return@launch

                RandomAccessFile(file, "r").use { raf ->
                    if (isOfflineMatch) {
                        raf.seek(range.start)
                        val buf = ByteArray(64 * 1024)
                        var left = range.length
                        while (left > 0 && activeOfflineCast.get()?.first == offlinePair?.first) {
                            val toRead = minOf(buf.size.toLong(), left).toInt()
                            val n = raf.read(buf, 0, toRead)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            left -= n
                        }
                    } else {
                        val live = stream!!
                        try {
                            copyVerifiedRange(raf, output, range,
                                isCurrent = { activeSession.get() === live && _streamStatus.value.requestId == live.id },
                                isVerified = { offset, count -> hasFileRangeDownloaded(offset, count, live) },
                                verificationLength = { offset, remaining ->
                                    bytesInCurrentPiece(offset, remaining, live.fileOffset, live.pieceLength)
                                },
                                requestPieces = { offset -> requestPieces(live, offset, lookAhead = 6) })
                        } catch (e: TimeoutCancellationException) {
                            // A seek may have already abandoned this connection. Let Media3 retry the range.
                            updateStreamStatus(live.id) { it.copy(phase = "range_wait") }
                        }
                    }
                }
                output.flush()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Closing a truncated response makes the player retry. Never send unverified bytes.
            } finally {
                closeClientSocket(socket)
            }
        }
    }

    private fun detectOfflineContainerMime(file: File): String {
        return try {
            val header = ByteArray(12)
            RandomAccessFile(file, "r").use { raf -> raf.read(header) }
            // EBML magic 0x1A 0x45 0xDF 0xA3 -> Matroska / WebM; "ftyp" at bytes 4..7 -> MP4
            if (header[0] == 0x1A.toByte() && header[1] == 0x45.toByte() &&
                header[2] == 0xDF.toByte() && header[3] == 0xA3.toByte()
            ) {
                "video/x-matroska"
            } else if (header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
            ) {
                "video/mp4"
            } else {
                "video/mp4"
            }
        } catch (_: Exception) {
            "video/mp4"
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        activeSession.set(null)
        activeOfflineCast.set(null)
        synchronized(socketLock) { runCatching { serverSocket?.close() } }
        closeClientSockets()
        streamRequestId?.let { requestId ->
            updateStreamStatus(requestId) {
                if (it.error != null) it else it.copy(isReady = false, isBuffering = false,
                    streamUrl = null, phase = "service_stopped", error = "Playback engine stopped [service_stopped].")
            }
        }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nanami Anime Playback & Offline Sync",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, progressPercent: Int? = null): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nanami Anime")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progressPercent != null) {
            builder.setProgress(100, progressPercent.coerceIn(0, 100), progressPercent <= 0)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, progressPercent: Int? = null) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text, progressPercent))
    }
}
