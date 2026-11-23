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
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    val requestId: String? = null,
    val isReady: Boolean = false,
    val isBuffering: Boolean = false,
    val statusMessage: String = "Preparing episode...",
    val progressPercent: Int = 0,
    val downloadSpeedKbps: Int = 0,
    val connectedPeers: Int = 0,
    val streamUrl: String? = null,
    val error: String? = null
)

class TorrentStreamService : Service() {

    companion object {
        const val ACTION_START_STREAM = "io.maru.manime.action.START_STREAM"
        const val ACTION_DOWNLOAD_OFFLINE = "io.maru.manime.action.DOWNLOAD_OFFLINE"
        const val ACTION_STOP_STREAM = "io.maru.manime.action.STOP_STREAM"

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

        const val NOTIFICATION_ID = 9002
        const val CHANNEL_ID = "nanami_anime_stream_channel"
        const val STREAM_PORT = 19876

        private val _streamStatus = MutableStateFlow(ActiveStreamStatus())
        val streamStatus: StateFlow<ActiveStreamStatus> = _streamStatus.asStateFlow()

        private val _offlineEpisodes = MutableStateFlow<List<OfflineEpisodeEntry>>(emptyList())
        val offlineEpisodes: StateFlow<List<OfflineEpisodeEntry>> = _offlineEpisodes.asStateFlow()
        private val offlineJobs = ConcurrentHashMap<Pair<Int, Int>, Job>()
        private val vaultLock = Any()

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
          synchronized(vaultLock) {
            offlineJobs[mediaId to episodeNum]?.cancel()
            val current = _offlineEpisodes.value.ifEmpty { loadOfflineVault(context) }.toMutableList()
            val target = current.firstOrNull { it.mediaId == mediaId && it.episodeNumber == episodeNum }
            if (target != null) {
                try { File(target.vaultFilePath).delete() } catch (_: Exception) {}
                current.removeAll { it.mediaId == mediaId && it.episodeNumber == episodeNum }
                saveOfflineVault(context, current)
            }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
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
    private var streamJob: Job? = null
    private data class StreamSession(val id: String, val file: File, val size: Long,
        val handle: TorrentHandle, val info: TorrentInfo, val fileIndex: Int)
    @Volatile private var activeSession: StreamSession? = null

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
                    launchLiveStream(magnet, fileIdx, title, epNum,
                        intent.getStringExtra(EXTRA_REQUEST_ID) ?: UUID.randomUUID().toString())
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
                activeSession = null
                _streamStatus.value = ActiveStreamStatus()
                if (offlineJobs.values.none { it.isActive }) {
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
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, 2500)
                }
            }
        } catch (_: Exception) {}
    }

    private fun hasFileRangeDownloaded(byteOffsetInFile: Long, byteLength: Long,
        stream: StreamSession? = activeSession): Boolean {
        val snapshot = stream ?: return false
        val handle = snapshot.handle
        val tInfo = snapshot.info
        return try {
            verifiedFileRange(byteOffsetInFile, byteLength, snapshot.size,
                tInfo.files().fileOffset(snapshot.fileIndex), tInfo.pieceLength(), tInfo.numPieces()) { p ->
                val verified = handle.havePiece(p)
                if (!verified) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, 600)
                }
                verified
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun launchLiveStream(
        magnetUri: String,
        preferredFileIdx: Int?,
        animeTitle: String,
        episodeNum: Int,
        requestId: String
    ) {
        val previous = streamJob
        previous?.cancel()
        activeSession = null
        streamJob = serviceScope.launch {
            var liveHandle: TorrentHandle? = null
            try {
                previous?.join()
                ensureActive()
                ensureSessionStarted()
                val session = sessionManager ?: return@launch
                val saveDir = File(cacheDir, "live_stream_cache").apply {
                    if (!exists()) mkdirs()
                }

                _streamStatus.value = ActiveStreamStatus(
                    requestId = requestId,
                    isReady = false,
                    isBuffering = true,
                    statusMessage = "Loading Episode $episodeNum stream...",
                    progressPercent = 5
                )
                updateNotification("Playing $animeTitle • Ep $episodeNum")

                val rawMeta = session.fetchMagnet(magnetUri, 25, saveDir)
                ensureActive()
                if (rawMeta == null) {
                    _streamStatus.value = ActiveStreamStatus(
                        requestId = requestId,
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
                val handle = requireNotNull(session.find(tInfo.infoHash())) { "Torrent handle unavailable" }
                liveHandle = handle
                if (handle != null) {
                    configureSequentialVideoDownload(tInfo, handle, targetIdx)
                }

                val videoFile = File(saveDir, targetRelPath)
                require(videoFile.canonicalPath.startsWith(saveDir.canonicalPath + File.separator))
                require(expectedBytes > 0) { "Empty torrent file" }
                activeSession = StreamSession(requestId, videoFile, expectedBytes, handle, tInfo, targetIdx)

                // Wait until the first 2 pieces (MKV header + first cluster) and at least ~2 MB are downloaded
                val minPrebufferBytes = minOf(expectedBytes, 2_500_000L, (expectedBytes / 50).coerceAtLeast(1_200_000L))
                var waitedMs = 0
                while (isActive && waitedMs < 35_000) {
                    val status = handle?.status()
                    val downloaded = status?.totalDone() ?: 0L
                    val speedKbps = ((status?.downloadPayloadRate() ?: 0) / 1024).coerceAtLeast(0)
                    val peers = status?.numPeers() ?: 0
                    val hasFirstChunk = hasFileRangeDownloaded(0L, minOf(expectedBytes, 512 * 1024L))
                    val pct = if (minPrebufferBytes > 0) {
                        ((downloaded.toDouble() / minPrebufferBytes.toDouble()) * 100.0).toInt().coerceIn(5, 99)
                    } else 50

                    if (videoFile.exists() && hasFirstChunk && downloaded >= minPrebufferBytes) {
                        break
                    }

                    val speedLabel = if (speedKbps >= 1024) String.format("%.1f MB/s", speedKbps / 1024.0) else "$speedKbps KB/s"
                    _streamStatus.value = ActiveStreamStatus(
                        requestId = requestId,
                        isReady = false,
                        isBuffering = true,
                        statusMessage = if (peers > 0) "Buffering Episode $episodeNum ($pct% • $speedLabel)" else "Connecting to Episode $episodeNum stream...",
                        progressPercent = pct,
                        downloadSpeedKbps = speedKbps,
                        connectedPeers = peers
                    )
                    delay(350)
                    waitedMs += 350
                }

                ensureActive()
                check(videoFile.exists() && hasFileRangeDownloaded(0L, minOf(expectedBytes, 512 * 1024L))) {
                    "Timed out waiting for verified torrent pieces"
                }
                val ext = if (targetRelPath.lowercase().endsWith(".mp4")) "mp4" else "mkv"
                val url = "http://127.0.0.1:$STREAM_PORT/$requestId/stream.$ext"
                _streamStatus.value = ActiveStreamStatus(
                    requestId = requestId,
                    isReady = true,
                    isBuffering = false,
                    statusMessage = "Ready",
                    progressPercent = 100,
                    streamUrl = url
                )

                // Keep updating download speed & overall cache percentage while playing
                while (isActive && handle != null) {
                    val st = handle.status()
                    val done = st.totalDone()
                    val totalPct = if (expectedBytes > 0) ((done * 100L) / expectedBytes).toInt().coerceIn(1, 100) else 100
                    val spd = (st.downloadPayloadRate() / 1024).coerceAtLeast(0)
                    val prs = st.numPeers()
                    _streamStatus.value = _streamStatus.value.copy(
                        progressPercent = totalPct,
                        downloadSpeedKbps = spd,
                        connectedPeers = prs
                    )
                    delay(1200)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                activeSession = null
                _streamStatus.value = ActiveStreamStatus(
                    requestId = requestId,
                    isReady = false,
                    isBuffering = false,
                    statusMessage = "Playback failed",
                    error = e.localizedMessage ?: "Failed to load episode"
                )
            } finally {
                if (activeSession?.id == requestId) activeSession = null
                try { liveHandle?.let { sessionManager?.remove(it) } } catch (_: Exception) {}
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
        val key = mediaId to episodeNum
        synchronized(vaultLock) {
        if (offlineJobs[key]?.isActive == true) return
        val previous = offlineJobs[key]
        val job = serviceScope.launch(start = CoroutineStart.LAZY) {
            var offlineSession: SessionManager? = null
            val tempDir = File(cacheDir, "offline_staging_${mediaId}_$episodeNum")
            try {
                previous?.join()
                ensureActive()
                val session = SessionManager().also { offlineSession = it; it.start() }
                val vaultDir = getVaultDir(applicationContext)
                if (!tempDir.exists()) tempDir.mkdirs()
                val finalVaultFile = File(vaultDir, "ep_${mediaId}_${episodeNum}.nanami")

                fun upsertEntry(
                    sizeBytes: Long,
                    downloadedBytes: Long,
                    progress: Int,
                    completed: Boolean
                ) {
                  synchronized(vaultLock) {
                    ensureActive()
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
                }

                upsertEntry(0L, 0L, 1, false)
                updateNotification("Saving Offline: $animeTitle • Ep $episodeNum")

                val rawMeta = session.fetchMagnet(magnetUri, 30, tempDir)
                ensureActive()
                if (rawMeta == null) {
                    deleteOfflineEpisode(applicationContext, mediaId, episodeNum)
                    return@launch
                }

                val tInfo = TorrentInfo.bdecode(rawMeta)
                val targetIdx = pickVideoFileIndex(tInfo, preferredFileIdx, episodeNum)
                val targetRelPath = tInfo.files().filePath(targetIdx)
                val totalBytes = tInfo.files().fileSize(targetIdx)
                require(totalBytes > 0)
                require(File(tempDir, targetRelPath).canonicalPath.startsWith(tempDir.canonicalPath + File.separator))
                session.download(tInfo, tempDir)
                val handle = requireNotNull(session.find(tInfo.infoHash())) { "Torrent handle unavailable" }
                if (handle != null) {
                    configureSequentialVideoDownload(tInfo, handle, targetIdx)
                }

                val stagingFile = File(tempDir, targetRelPath)

                while (isActive) {
                    val status = handle?.status()
                    val done = status?.totalDone() ?: 0L
                    val pct = if (totalBytes > 0) ((done * 100L) / totalBytes).toInt().coerceIn(1, 99) else 10
                    val isFinished = verifiedFileRange(0, totalBytes, totalBytes,
                        tInfo.files().fileOffset(targetIdx), tInfo.pieceLength(), tInfo.numPieces(), handle::havePiece)

                    if (isFinished && stagingFile.exists()) {
                      synchronized(vaultLock) {
                        ensureActive()
                        check(stagingFile.length() == totalBytes) { "Incomplete offline file" }
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
                      }
                        break
                    } else {
                        upsertEntry(totalBytes, done, pct, false)
                        updateNotification("Downloading $animeTitle Ep $episodeNum ($pct%)")
                    }
                    delay(1500)
                }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) {
                synchronized(vaultLock) {
                    if (isActive) deleteOfflineEpisode(applicationContext, mediaId, episodeNum)
                }
              } finally {
                try {
                    offlineSession?.stop()
                    tempDir.deleteRecursively()
                } catch (_: Exception) {}
              }
        }
        offlineJobs[key] = job
        job.start()
        }
    }

    /**
     * Local HTTP server with full HTTP 206 Partial Content (Byte-Range) support so ExoPlayer
     * can seek smoothly through MKV & MP4 streams.
     */
    private fun startLocalHttpServer() {
        try {
            serverSocket?.close()
            serverSocket = ServerSocket(STREAM_PORT, 8, InetAddress.getByName("127.0.0.1"))
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
                val request = requestLine.split(' ')
                if (request.size != 3 || request[0] !in listOf("GET", "HEAD")) {
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

                val stream = activeSession
                val file = stream?.file
                if (stream == null || file == null || !file.exists() ||
                    !request[1].startsWith("/${stream.id}/stream.")) {
                    output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    socket.close()
                    return@launch
                }

                val totalLen = stream.size
                val mimeType = if (requestLine.contains(".mp4", ignoreCase = true)) "video/mp4" else "video/x-matroska"

                val range = parseByteRange(rangeHeader, totalLen)
                if (range == null) {
                    output.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$totalLen\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                    return@launch
                }
                val startByte = range.start
                val endByte = range.end
                val isPartial = rangeHeader != null
                val contentLength = range.length
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
                if (request[0] == "HEAD") return@launch

                val raf = RandomAccessFile(file, "r")
                try {
                    var currentOffset = startByte
                    val buffer = ByteArray(64 * 1024)
                    var remaining = contentLength
                    while (remaining > 0 && isActive) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()

                        // CRITICAL: Do NOT read un-downloaded sparse zeroes from RandomAccessFile!
                        // Wait until libtorrent has actually verified the piece covering [currentOffset, currentOffset + toRead)
                        awaitVerifiedRange(isCurrent = { activeSession === stream },
                            isVerified = { hasFileRangeDownloaded(currentOffset, toRead.toLong(), stream) })
                        ensureActive()
                        check(activeSession === stream) { "Stream replaced" }
                        check(raf.length() >= currentOffset + toRead) { "Verified bytes not available on disk" }
                        raf.seek(currentOffset)
                        val read = raf.read(buffer, 0, toRead)
                        check(read > 0) { "Unexpected end of torrent file" }
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
                // Closing a truncated response makes the player retry. Never send unverified bytes.
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        try {
            streamJob?.cancel()
            activeSession = null
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
