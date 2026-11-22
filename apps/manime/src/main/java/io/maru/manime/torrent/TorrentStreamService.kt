package io.maru.manime.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket

data class OfflineEpisodeEntry(
    val mediaId: Int,
    val animeTitle: String,
    val episodeNumber: Int,
    val episodeTitle: String,
    val coverUrl: String?,
    val thumbnailUrl: String?,
    val audioType: String, // "SUB" | "DUB" | "DUAL_AUDIO"
    val quality: String,
    val vaultFilePath: String, // Stored strictly in internal app storage with .nanami extension
    val sizeBytes: Long,
    val downloadedBytes: Long,
    val progressPercent: Int, // 0..100
    val isCompleted: Boolean,
    val addedAt: Long
)

data class ActiveStreamStatus(
    val isReady: Boolean = false,
    val isBuffering: Boolean = false,
    val statusMessage: String = "Preparing episode...",
    val progressPercent: Int = 0,
    val streamUrl: String? = null,
    val error: String? = null
)

class TorrentStreamService : Service() {

    companion object {
        const val ACTION_START_STREAM = "io.maru.manime.action.START_STREAM"
        const val ACTION_DOWNLOAD_OFFLINE = "io.maru.manime.action.DOWNLOAD_OFFLINE"
        const val ACTION_STOP_STREAM = "io.maru.manime.action.STOP_STREAM"

        const val EXTRA_MAGNET = "extra_magnet"
        const val EXTRA_FILE_IDX = "extra_file_idx"
        const val EXTRA_MEDIA_ID = "extra_media_id"
        const val EXTRA_ANIME_TITLE = "extra_anime_title"
        const val EXTRA_EPISODE_NUM = "extra_episode_num"
        const val EXTRA_EPISODE_TITLE = "extra_episode_title"
        const val EXTRA_COVER_URL = "extra_cover_url"
        const val EXTRA_THUMB_URL = "extra_thumb_url"
        const val EXTRA_AUDIO_TYPE = "extra_audio_type"
        const val EXTRA_QUALITY = "extra_quality"

        const val NOTIFICATION_ID = 9002
        const val CHANNEL_ID = "nanami_anime_stream_channel"
        const val STREAM_PORT = 19876

        private val _streamStatus = MutableStateFlow(ActiveStreamStatus())
        val streamStatus: StateFlow<ActiveStreamStatus> = _streamStatus.asStateFlow()

        private val _offlineEpisodes = MutableStateFlow<List<OfflineEpisodeEntry>>(emptyList())
        val offlineEpisodes: StateFlow<List<OfflineEpisodeEntry>> = _offlineEpisodes.asStateFlow()

        private fun getVaultDir(context: Context): File {
            // Private internal app directory (never scanned by MediaStore or visible to external video players)
            val dir = File(context.filesDir, "offline_vault")
            if (!dir.exists()) dir.mkdirs()
            val noMedia = File(dir, ".nomedia")
            if (!noMedia.exists()) {
                try { noMedia.createNewFile() } catch (_: Exception) {}
            }
            return dir
        }

        private fun getVaultIndexFile(context: Context): File {
            return File(getVaultDir(context), "vault_index.json")
        }

        fun loadOfflineVault(context: Context): List<OfflineEpisodeEntry> {
            val file = getVaultIndexFile(context)
            if (!file.exists()) {
                _offlineEpisodes.value = emptyList()
                return emptyList()
            }
            return try {
                val arr = JSONArray(file.readText())
                val list = mutableListOf<OfflineEpisodeEntry>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val path = o.optString("vaultFilePath")
                    val vaultFile = File(path)
                    val completed = o.optBoolean("isCompleted", false)
                    if (completed && !vaultFile.exists()) continue
                    list.add(
                        OfflineEpisodeEntry(
                            mediaId = o.optInt("mediaId"),
                            animeTitle = o.optString("animeTitle"),
                            episodeNumber = o.optInt("episodeNumber"),
                            episodeTitle = o.optString("episodeTitle"),
                            coverUrl = o.optString("coverUrl").takeIf { it.isNotBlank() },
                            thumbnailUrl = o.optString("thumbnailUrl").takeIf { it.isNotBlank() },
                            audioType = o.optString("audioType", "SUB"),
                            quality = o.optString("quality", "1080p"),
                            vaultFilePath = path,
                            sizeBytes = o.optLong("sizeBytes", vaultFile.length()),
                            downloadedBytes = o.optLong("downloadedBytes", vaultFile.length()),
                            progressPercent = o.optInt("progressPercent", if (completed) 100 else 0),
                            isCompleted = completed,
                            addedAt = o.optLong("addedAt", System.currentTimeMillis())
                        )
                    )
                }
                _offlineEpisodes.value = list
                list
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun saveOfflineVault(context: Context, list: List<OfflineEpisodeEntry>) {
            _offlineEpisodes.value = list
            try {
                val arr = JSONArray()
                for (item in list) {
                    val o = JSONObject().apply {
                        put("mediaId", item.mediaId)
                        put("animeTitle", item.animeTitle)
                        put("episodeNumber", item.episodeNumber)
                        put("episodeTitle", item.episodeTitle)
                        put("coverUrl", item.coverUrl ?: "")
                        put("thumbnailUrl", item.thumbnailUrl ?: "")
                        put("audioType", item.audioType)
                        put("quality", item.quality)
                        put("vaultFilePath", item.vaultFilePath)
                        put("sizeBytes", item.sizeBytes)
                        put("downloadedBytes", item.downloadedBytes)
                        put("progressPercent", item.progressPercent)
                        put("isCompleted", item.isCompleted)
                        put("addedAt", item.addedAt)
                    }
                    arr.put(o)
                }
                getVaultIndexFile(context).writeText(arr.toString())
            } catch (_: Exception) {}
        }

        fun getCompletedOfflineEpisode(context: Context, mediaId: Int, episodeNum: Int): OfflineEpisodeEntry? {
            val current = _offlineEpisodes.value.ifEmpty { loadOfflineVault(context) }
            return current.firstOrNull {
                it.mediaId == mediaId && it.episodeNumber == episodeNum && it.isCompleted && File(it.vaultFilePath).exists()
            }
        }

        fun deleteOfflineEpisode(context: Context, mediaId: Int, episodeNum: Int) {
            val current = _offlineEpisodes.value.ifEmpty { loadOfflineVault(context) }.toMutableList()
            val target = current.firstOrNull { it.mediaId == mediaId && it.episodeNumber == episodeNum }
            if (target != null) {
                try { File(target.vaultFilePath).delete() } catch (_: Exception) {}
                current.removeAll { it.mediaId == mediaId && it.episodeNumber == episodeNum }
                saveOfflineVault(context, current)
            }
        }

        fun startStream(
            context: Context,
            magnetUri: String,
            fileIdx: Int? = null,
            animeTitle: String = "Anime",
            episodeNum: Int = 1
        ) {
            _streamStatus.value = ActiveStreamStatus(
                isReady = false,
                isBuffering = true,
                statusMessage = "Connecting to Episode $episodeNum...",
                progressPercent = 0
            )
            val intent = Intent(context, TorrentStreamService::class.java).apply {
                action = ACTION_START_STREAM
                putExtra(EXTRA_MAGNET, magnetUri)
                putExtra(EXTRA_FILE_IDX, fileIdx ?: -1)
                putExtra(EXTRA_ANIME_TITLE, animeTitle)
                putExtra(EXTRA_EPISODE_NUM, episodeNum)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
            quality: String
        ) {
            val intent = Intent(context, TorrentStreamService::class.java).apply {
                action = ACTION_DOWNLOAD_OFFLINE
                putExtra(EXTRA_MAGNET, magnetUri)
                putExtra(EXTRA_FILE_IDX, fileIdx ?: -1)
                putExtra(EXTRA_MEDIA_ID, mediaId)
                putExtra(EXTRA_ANIME_TITLE, animeTitle)
                putExtra(EXTRA_EPISODE_NUM, episodeNum)
                putExtra(EXTRA_EPISODE_TITLE, episodeTitle)
                putExtra(EXTRA_COVER_URL, coverUrl ?: "")
                putExtra(EXTRA_THUMB_URL, thumbnailUrl ?: "")
                putExtra(EXTRA_AUDIO_TYPE, audioType)
                putExtra(EXTRA_QUALITY, quality)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopStream(context: Context) {
            _streamStatus.value = ActiveStreamStatus()
            val intent = Intent(context, TorrentStreamService::class.java).apply {
                action = ACTION_STOP_STREAM
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sessionManager: SessionManager? = null
    private var serverSocket: ServerSocket? = null
    @Volatile private var activeStreamFile: File? = null
    @Volatile private var activeExpectedSize: Long = 0L
    @Volatile private var activeHandle: TorrentHandle? = null
    @Volatile private var activeTorrentInfo: TorrentInfo? = null
    @Volatile private var activeFileIndex: Int = 0
    private var streamJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        loadOfflineVault(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification("Nanami Anime Playback Engine"))
        ensureSessionStarted()
        startLocalHttpServer()
    }

    private fun ensureSessionStarted() {
        if (sessionManager == null) {
            sessionManager = SessionManager().apply {
                start()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAM -> {
                val magnet = intent.getStringExtra(EXTRA_MAGNET)
                val fileIdx = intent.getIntExtra(EXTRA_FILE_IDX, -1).takeIf { it >= 0 }
                val epNum = intent.getIntExtra(EXTRA_EPISODE_NUM, 1)
                val title = intent.getStringExtra(EXTRA_ANIME_TITLE) ?: "Anime"
                if (!magnet.isNullOrEmpty()) {
                    launchLiveStream(magnet, fileIdx, title, epNum)
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
                    quality = quality
                )
            }
            ACTION_STOP_STREAM -> {
                streamJob?.cancel()
                _streamStatus.value = ActiveStreamStatus()
                if (_offlineEpisodes.value.none { !it.isCompleted }) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun pickVideoFileIndex(tInfo: TorrentInfo, preferredIdx: Int?, episodeNum: Int): Int {
        val files = tInfo.files()
        val numFiles = files.numFiles()
        if (numFiles <= 1) return 0
        if (preferredIdx != null && preferredIdx in 0 until numFiles) {
            val path = files.filePath(preferredIdx).lowercase()
            if (path.endsWith(".mkv") || path.endsWith(".mp4") || path.endsWith(".webm")) {
                return preferredIdx
            }
        }

        val epStrPadded = String.format("%02d", episodeNum)
        var bestIdx = 0
        var largestSize = -1L

        for (i in 0 until numFiles) {
            val name = files.fileName(i).lowercase()
            val size = files.fileSize(i)
            val isVideo = name.endsWith(".mkv") || name.endsWith(".mp4") || name.endsWith(".webm")
            if (!isVideo) continue

            // Match episode number in batch torrent
            val matchesEp = name.contains(" $epStrPadded ") ||
                    name.contains("-$epStrPadded") ||
                    name.contains("e$epStrPadded") ||
                    name.contains("ep$epStrPadded") ||
                    name.contains("_${epStrPadded}_")

            if (matchesEp) return i
            if (size > largestSize) {
                largestSize = size
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun configureSequentialVideoDownload(tInfo: TorrentInfo, handle: TorrentHandle, targetIdx: Int) {
        try {
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            val files = tInfo.files()
            val numFiles = files.numFiles()
            if (numFiles > 1) {
                val priorities = Array(numFiles) { idx ->
                    if (idx == targetIdx) Priority.TOP_PRIORITY else Priority.IGNORE
                }
                handle.prioritizeFiles(priorities)
            }

            // Prioritize first 4 pieces (MKV header + tracks) and last 2 pieces (MKV Cues index) immediately
            val pieceLen = tInfo.pieceLength().coerceAtLeast(16384)
            val fileOffset = files.fileOffset(targetIdx)
            val fileSize = files.fileSize(targetIdx)
            val firstPiece = (fileOffset / pieceLen).toInt()
            val lastPiece = ((fileOffset + fileSize - 1).coerceAtLeast(fileOffset) / pieceLen).toInt()
            val totalPieces = tInfo.numPieces()

            for (p in firstPiece..(firstPiece + 4).coerceAtMost(lastPiece)) {
                if (p in 0 until totalPieces) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, 1000)
                }
            }
            for (p in (lastPiece - 1).coerceAtLeast(firstPiece)..lastPiece) {
                if (p in 0 until totalPieces) {
                    handle.piecePriority(p, Priority.SEVEN)
                    handle.setPieceDeadline(p, 2500)
                }
            }
        } catch (_: Exception) {}
    }

    private fun hasFileRangeDownloaded(byteOffsetInFile: Long, byteLength: Long): Boolean {
        val handle = activeHandle ?: return true
        val tInfo = activeTorrentInfo ?: return true
        return try {
            val files = tInfo.files()
            val fileOffset = files.fileOffset(activeFileIndex)
            val pieceLen = tInfo.pieceLength().coerceAtLeast(16384).toLong()
            val totalPieces = tInfo.numPieces()

            val absStart = fileOffset + byteOffsetInFile
            val absEnd = (absStart + byteLength.coerceAtLeast(1L) - 1L).coerceAtLeast(absStart)
            val startPiece = (absStart / pieceLen).toInt().coerceIn(0, totalPieces - 1)
            val endPiece = (absEnd / pieceLen).toInt().coerceIn(0, totalPieces - 1)

            for (p in startPiece..endPiece) {
                if (!handle.havePiece(p)) {
                    // Boost missing piece deadline so libtorrent fetches what ExoPlayer is waiting for right now!
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, 600)
                    if (p + 1 < totalPieces && !handle.havePiece(p + 1)) {
                        handle.piecePriority(p + 1, Priority.TOP_PRIORITY)
                        handle.setPieceDeadline(p + 1, 1200)
                    }
                    return false
                }
            }
            true
        } catch (_: Exception) {
            true
        }
    }

    private fun launchLiveStream(
        magnetUri: String,
        preferredFileIdx: Int?,
        animeTitle: String,
        episodeNum: Int
    ) {
        streamJob?.cancel()
        streamJob = serviceScope.launch {
            try {
                ensureSessionStarted()
                val session = sessionManager ?: return@launch
                val saveDir = File(cacheDir, "live_stream_cache").apply {
                    if (!exists()) mkdirs()
                }

                _streamStatus.value = ActiveStreamStatus(
                    isReady = false,
                    isBuffering = true,
                    statusMessage = "Loading Episode $episodeNum stream...",
                    progressPercent = 5
                )
                updateNotification("Playing $animeTitle • Ep $episodeNum")

                val rawMeta = session.fetchMagnet(magnetUri, 25, saveDir)
                if (rawMeta == null) {
                    _streamStatus.value = ActiveStreamStatus(
                        isReady = false,
                        isBuffering = false,
                        statusMessage = "Stream timed out",
                        error = "Could not connect to episode stream. Try switching audio or retrying."
                    )
                    return@launch
                }

                val tInfo = TorrentInfo.bdecode(rawMeta)
                val targetIdx = pickVideoFileIndex(tInfo, preferredFileIdx, episodeNum)
                val targetRelPath = tInfo.files().filePath(targetIdx)
                val expectedBytes = tInfo.files().fileSize(targetIdx)

                session.download(tInfo, saveDir)
                val handle = session.find(tInfo.infoHash())
                activeTorrentInfo = tInfo
                activeHandle = handle
                activeFileIndex = targetIdx

                if (handle != null) {
                    configureSequentialVideoDownload(tInfo, handle, targetIdx)
                }

                val videoFile = File(saveDir, targetRelPath)
                activeStreamFile = videoFile
                activeExpectedSize = expectedBytes

                // Wait until the first 2 pieces (MKV header + first cluster) and at least ~2 MB are downloaded
                val minPrebufferBytes = minOf(2_500_000L, (expectedBytes / 50).coerceAtLeast(1_200_000L))
                var waitedMs = 0
                while (isActive && waitedMs < 35_000) {
                    val status = handle?.status()
                    val downloaded = status?.totalDone() ?: 0L
                    val hasFirstChunk = hasFileRangeDownloaded(0L, 512 * 1024L)
                    val pct = if (minPrebufferBytes > 0) {
                        ((downloaded.toDouble() / minPrebufferBytes.toDouble()) * 100.0).toInt().coerceIn(5, 99)
                    } else 50

                    if (videoFile.exists() && hasFirstChunk && downloaded >= minPrebufferBytes) {
                        break
                    }

                    _streamStatus.value = ActiveStreamStatus(
                        isReady = false,
                        isBuffering = true,
                        statusMessage = "Buffering Episode $episodeNum ($pct%)...",
                        progressPercent = pct
                    )
                    delay(400)
                    waitedMs += 400
                }

                val ext = if (targetRelPath.lowercase().endsWith(".mp4")) "mp4" else "mkv"
                val url = "http://127.0.0.1:$STREAM_PORT/stream.$ext"
                _streamStatus.value = ActiveStreamStatus(
                    isReady = true,
                    isBuffering = false,
                    statusMessage = "Ready",
                    progressPercent = 100,
                    streamUrl = url
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _streamStatus.value = ActiveStreamStatus(
                    isReady = false,
                    isBuffering = false,
                    statusMessage = "Playback failed",
                    error = e.localizedMessage ?: "Failed to load episode"
                )
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
        quality: String
    ) {
        serviceScope.launch {
            try {
                ensureSessionStarted()
                val session = sessionManager ?: return@launch
                val vaultDir = getVaultDir(applicationContext)
                val tempDir = File(cacheDir, "offline_staging_${mediaId}_$episodeNum").apply {
                    if (!exists()) mkdirs()
                }
                val finalVaultFile = File(vaultDir, "ep_${mediaId}_${episodeNum}.nanami")

                fun upsertEntry(
                    sizeBytes: Long,
                    downloadedBytes: Long,
                    progress: Int,
                    completed: Boolean
                ) {
                    val current = _offlineEpisodes.value.toMutableList()
                    val idx = current.indexOfFirst { it.mediaId == mediaId && it.episodeNumber == episodeNum }
                    val entry = OfflineEpisodeEntry(
                        mediaId = mediaId,
                        animeTitle = animeTitle,
                        episodeNumber = episodeNum,
                        episodeTitle = episodeTitle,
                        coverUrl = coverUrl,
                        thumbnailUrl = thumbnailUrl,
                        audioType = audioType,
                        quality = quality,
                        vaultFilePath = finalVaultFile.absolutePath,
                        sizeBytes = sizeBytes,
                        downloadedBytes = downloadedBytes,
                        progressPercent = progress,
                        isCompleted = completed,
                        addedAt = if (idx >= 0) current[idx].addedAt else System.currentTimeMillis()
                    )
                    if (idx >= 0) current[idx] = entry else current.add(0, entry)
                    saveOfflineVault(applicationContext, current)
                }

                upsertEntry(0L, 0L, 1, false)
                updateNotification("Saving Offline: $animeTitle • Ep $episodeNum")

                val rawMeta = session.fetchMagnet(magnetUri, 30, tempDir)
                if (rawMeta == null) {
                    deleteOfflineEpisode(applicationContext, mediaId, episodeNum)
                    return@launch
                }

                val tInfo = TorrentInfo.bdecode(rawMeta)
                val targetIdx = pickVideoFileIndex(tInfo, preferredFileIdx, episodeNum)
                val targetRelPath = tInfo.files().filePath(targetIdx)
                val totalBytes = tInfo.files().fileSize(targetIdx)

                session.download(tInfo, tempDir)
                val handle = session.find(tInfo.infoHash())
                if (handle != null) {
                    configureSequentialVideoDownload(tInfo, handle, targetIdx)
                }

                val stagingFile = File(tempDir, targetRelPath)

                while (isActive) {
                    val status = handle?.status()
                    val done = status?.totalDone() ?: stagingFile.length()
                    val pct = if (totalBytes > 0) ((done * 100L) / totalBytes).toInt().coerceIn(1, 99) else 10
                    val isFinished = status?.isFinished == true || status?.isSeeding == true || (totalBytes > 0 && done >= totalBytes)

                    if (isFinished && stagingFile.exists()) {
                        // Move into private internal vault with .nanami extension so external video players never see it
                        if (finalVaultFile.exists()) finalVaultFile.delete()
                        val moved = stagingFile.renameTo(finalVaultFile)
                        if (!moved) {
                            stagingFile.copyTo(finalVaultFile, overwrite = true)
                        }
                        try {
                            if (handle != null) session.remove(handle)
                            tempDir.deleteRecursively()
                        } catch (_: Exception) {}

                        upsertEntry(
                            sizeBytes = finalVaultFile.length().coerceAtLeast(totalBytes),
                            downloadedBytes = finalVaultFile.length().coerceAtLeast(totalBytes),
                            progress = 100,
                            completed = true
                        )
                        updateNotification("Offline Ready: $animeTitle • Ep $episodeNum")
                        break
                    } else {
                        upsertEntry(totalBytes, done, pct, false)
                        updateNotification("Downloading $animeTitle Ep $episodeNum ($pct%)")
                    }
                    delay(1500)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Local HTTP server with full HTTP 206 Partial Content (Byte-Range) support so ExoPlayer
     * can seek smoothly through MKV & MP4 streams.
     */
    private fun startLocalHttpServer() {
        try {
            serverSocket?.close()
            serverSocket = ServerSocket(STREAM_PORT)
            serviceScope.launch {
                while (isActive && serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        handleClientSocket(socket)
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleClientSocket(socket: Socket) {
        serviceScope.launch {
            try {
                socket.soTimeout = 15_000
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val reader = input.bufferedReader()

                val requestLine = reader.readLine() ?: return@launch
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        rangeHeader = line.substringAfter(":").trim()
                    }
                }

                // Wait briefly if the file is still being created on disk
                var file = activeStreamFile
                var retries = 0
                while ((file == null || !file.exists()) && retries < 20) {
                    delay(250)
                    file = activeStreamFile
                    retries++
                }

                if (file == null || !file.exists()) {
                    output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    socket.close()
                    return@launch
                }

                val totalLen = activeExpectedSize.takeIf { it > 0 } ?: file.length()
                val mimeType = if (requestLine.contains(".mp4", ignoreCase = true)) "video/mp4" else "video/x-matroska"

                var startByte = 0L
                var endByte = (totalLen - 1).coerceAtLeast(0L)
                var isPartial = false

                if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=")) {
                    isPartial = true
                    val spec = rangeHeader.removePrefix("bytes=").trim()
                    val parts = spec.split("-")
                    startByte = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                    val reqEnd = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull()
                    if (reqEnd != null && reqEnd < totalLen) {
                        endByte = reqEnd
                    }
                }

                val contentLength = (endByte - startByte + 1).coerceAtLeast(0L)
                val headers = if (isPartial) {
                    "HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Type: $mimeType\r\n" +
                            "Content-Length: $contentLength\r\n" +
                            "Content-Range: bytes $startByte-$endByte/$totalLen\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Connection: close\r\n\r\n"
                } else {
                    "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: $mimeType\r\n" +
                            "Content-Length: $totalLen\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Connection: close\r\n\r\n"
                }

                output.write(headers.toByteArray())

                val raf = RandomAccessFile(file, "r")
                try {
                    var currentOffset = startByte
                    val buffer = ByteArray(64 * 1024)
                    var remaining = contentLength
                    while (remaining > 0 && isActive) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()

                        // CRITICAL: Do NOT read un-downloaded sparse zeroes from RandomAccessFile!
                        // Wait until libtorrent has actually verified the piece covering [currentOffset, currentOffset + toRead)
                        var waitTicks = 0
                        while (isActive && !hasFileRangeDownloaded(currentOffset, toRead.toLong()) && waitTicks < 120) {
                            delay(150)
                            waitTicks++
                        }

                        raf.seek(currentOffset)
                        val read = raf.read(buffer, 0, toRead)
                        if (read <= 0) {
                            delay(150)
                            continue
                        }
                        output.write(buffer, 0, read)
                        currentOffset += read
                        remaining -= read
                    }
                } finally {
                    try { raf.close() } catch (_: Exception) {}
                }

                output.flush()
                socket.close()
            } catch (_: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        try {
            streamJob?.cancel()
            serverSocket?.close()
            sessionManager?.stop()
        } catch (_: Exception) {}
        serviceScope.cancel()
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

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nanami Anime")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
