package io.maru.manime.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.maru.manime.MAnimePrefs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

private class OfflineDownloadException(message: String) : Exception(message)

class TorrentStreamService : Service() {

    companion object {
        const val ACTION_START_STREAM = "io.maru.manime.action.START_STREAM"
        const val ACTION_DOWNLOAD_OFFLINE = "io.maru.manime.action.DOWNLOAD_OFFLINE"

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

        private val _streamStatus = MutableStateFlow(ActiveStreamStatus())
        val streamStatus: StateFlow<ActiveStreamStatus> = _streamStatus.asStateFlow()

        val offlineEpisodes = OfflineVault.episodes
        private val offlineJobs = ConcurrentHashMap<Pair<Int, Int>, Job>()

        suspend fun loadOfflineVault(context: Context) = withContext(Dispatchers.IO) {
            OfflineVault.load(context)
        }

        suspend fun getCompletedOfflineEpisode(context: Context, mediaId: Int, episodeNum: Int) =
            withContext(Dispatchers.IO) { OfflineVault.getCompleted(context, mediaId, episodeNum) }

        suspend fun deleteOfflineEpisode(context: Context, mediaId: Int, episodeNum: Int): Boolean =
            withContext(Dispatchers.IO) {
                synchronized(OfflineVault.lock) {
                    offlineJobs[mediaId to episodeNum]?.cancel()
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
                        error = "Playback engine could not start [service_start].", statusMessage = "Playback failed"
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

        fun stopStream(requestId: String) {
            _streamStatus.update { current ->
                if (current.requestId == requestId) ActiveStreamStatus() else current
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
        if ((status.requestId == null || status.error != null) && offlineJobs.values.none { !it.isCompleted }) {
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

    private suspend fun awaitMetadata(handle: TorrentHandle): TorrentInfo = withTimeout(25_000L) {
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
            val delayMs = (piece - firstPiece) * 400
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
                        "metadata" -> "Metadata timed out"
                        "file_priorities" -> "Episode selection timed out"
                        else -> "Verified media bytes timed out"
                    }
                    it.copy(isReady = false, isBuffering = false, phase = phase, statusMessage = "Stream timed out",
                        error = "$reason [$phase]. Try another source.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (e !is Exception && e !is LinkageError) throw e
                ensureActive()
                Log.w("TorrentStreamService", "Playback engine failed in $phase", e)
                updateStreamStatus(requestId) {
                    it.copy(isReady = false, isBuffering = false, streamUrl = null, phase = phase,
                        statusMessage = "Playback failed",
                        error = "Playback engine failed [$phase/${if (e is LinkageError) "native_link" else "engine_error"}]. Try another source.")
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
        quality: String
    ) {
        val key = mediaId to episodeNum
        val context = applicationContext
        synchronized(OfflineVault.lock) {
            if (offlineJobs[key]?.isActive == true) return
            val job = serviceScope.launch(start = CoroutineStart.LAZY) {
                if (OfflineVault.getCompleted(context, mediaId, episodeNum) != null) return@launch

                var session: SessionManager? = null
                var stagingDir: File? = null
                var pendingCopy: File? = null
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
                    addedAt = System.currentTimeMillis()
                )

                fun publishEntry(next: OfflineEpisodeEntry) {
                    synchronized(OfflineVault.lock) {
                        ensureActive()
                        OfflineVault.update(context, next)
                        entry = next
                    }
                }

                fun commitFile(source: File, totalBytes: Long): Boolean = synchronized(OfflineVault.lock) {
                    ensureActive()
                    if (!source.renameTo(finalFile)) return@synchronized false
                    try {
                        publishEntry(entry.copy(
                            sizeBytes = totalBytes,
                            downloadedBytes = totalBytes,
                            progressPercent = 100,
                            isCompleted = true
                        ))
                    } catch (e: Exception) {
                        finalFile.delete()
                        throw e
                    }
                    true
                }

                try {
                    publishEntry(entry)
                    updateNotification("Preparing download: $animeTitle • Ep $episodeNum")
                    OfflineVault.preparedDirectory(context)
                    val tempDir = Files.createTempDirectory(cacheDir.toPath(), "offline_staging_").toFile()
                    stagingDir = tempDir

                    val torrentSession = SessionManager().also { session = it; it.start() }
                    applyDownloadLimit(torrentSession, MAnimePrefs(context).torrentMaxSpeed.first())
                    val rawMeta = runInterruptible { torrentSession.fetchMagnet(magnetUri, 30, tempDir) }
                        ?: throw OfflineDownloadException("Could not connect to the download source.")
                    val info = TorrentInfo.bdecode(rawMeta)
                    val targetIdx = pickVideoFileIndex(info, preferredFileIdx, episodeNum)
                    val files = info.files()
                    val totalBytes = files.fileSize(targetIdx)
                    val stagingFile = File(tempDir, files.filePath(targetIdx)).canonicalFile
                    check(totalBytes > 0 && stagingFile.path.startsWith(tempDir.canonicalPath + File.separator))
                    if (tempDir.usableSpace < totalBytes) {
                        throw OfflineDownloadException("There is not enough storage for this episode.")
                    }

                    val handle = addVideoDownload(torrentSession, info, tempDir, magnetUri, targetIdx)

                    val fileOffset = files.fileOffset(targetIdx)
                    val pieceLength = info.pieceLength()
                    val pieceCount = info.numPieces()
                    var lastProgress = SystemClock.elapsedRealtime()
                    var mostDownloaded = 0L
                    while (isActive) {
                        check(handle.isValid) { "Download handle expired" }
                        val status = handle.status()
                        check(!status.errorCode().isError) { "Torrent download failed" }
                        val done = status.totalWantedDone().coerceIn(0L, totalBytes)

                        if (done >= totalBytes &&
                            verifiedFileRange(0, totalBytes, totalBytes, fileOffset, pieceLength, pieceCount, handle::havePiece) &&
                            stagingFile.isFile && stagingFile.length() == totalBytes
                        ) {
                            break
                        }
                        if (done > mostDownloaded) {
                            mostDownloaded = done
                            lastProgress = SystemClock.elapsedRealtime()
                        }
                        if (SystemClock.elapsedRealtime() - lastProgress > 120_000L) {
                            throw OfflineDownloadException("The download stalled. Try again when the source is available.")
                        }
                        val percent = (done.toDouble() / totalBytes * 100).toInt().coerceIn(1, 99)
                        publishEntry(entry.copy(sizeBytes = totalBytes, downloadedBytes = done, progressPercent = percent))
                        updateNotification("Downloading $animeTitle Ep $episodeNum ($percent%)")
                        delay(1_500)
                    }

                    ensureActive()
                    torrentSession.stop()
                    session = null
                    if (!commitFile(stagingFile, totalBytes)) {
                        val copy = File.createTempFile("episode_", ".tmp", finalFile.parentFile)
                        pendingCopy = copy
                        stagingFile.copyTo(copy, overwrite = true)
                        check(copy.length() == totalBytes && commitFile(copy, totalBytes)) { "Could not save the offline file" }
                    }
                    updateNotification("Offline ready: $animeTitle • Ep $episodeNum")
                } catch (e: CancellationException) {
                    synchronized(OfflineVault.lock) {
                        if (offlineJobs[key] === coroutineContext.job && OfflineVault.episodes.value.contains(entry) &&
                            !entry.isCompleted
                        ) {
                            OfflineVault.update(context, entry.copy(failure = "The download was interrupted. Try again."))
                        }
                    }
                    throw e
                } catch (e: Throwable) {
                    if (e !is Exception && e !is LinkageError) throw e
                    Log.w("TorrentStreamService", "Episode download failed", e)
                    if (entry.isCompleted) return@launch
                    val message = (e as? OfflineDownloadException)?.message
                        ?: "The episode could not be saved. Try again."
                    publishEntry(entry.copy(failure = message))
                    updateNotification("Download failed: $animeTitle • Ep $episodeNum")
                } finally {
                    runCatching { session?.stop() }
                    pendingCopy?.delete()
                    stagingDir?.deleteRecursively()
                }
            }
            offlineJobs[key] = job
            job.invokeOnCompletion {
                offlineJobs.remove(key, job)
                serviceScope.launch(Dispatchers.Main.immediate) { stopIfIdle() }
            }
            job.start()
        }
    }

    /**
     * Local HTTP server with full HTTP 206 Partial Content (Byte-Range) support so ExoPlayer
     * can seek smoothly through MKV & MP4 streams.
     */
    private fun localHttpServerPort(): Int = synchronized(socketLock) {
        serverSocket?.let { if (!it.isClosed) return@synchronized it.localPort }
        check(serviceScope.isActive) { "Playback service is stopping" }
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverSocket = server
        serviceScope.launch {
            try {
                while (isActive && !server.isClosed) {
                    val socket = server.accept()
                    clientSockets.add(socket)
                    if (isActive) handleClientSocket(socket) else closeClientSocket(socket)
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

                val stream = activeSession.get()
                if (stream == null || _streamStatus.value.requestId != stream.id || !stream.file.isFile ||
                    request[1] != "/${stream.id}/stream.${videoExtension(stream.file.name)}") {
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    return@launch
                }

                val file = stream.file
                val totalLen = stream.size
                val mimeType = when (file.extension.lowercase()) {
                    "mp4" -> "video/mp4"
                    "webm" -> "video/webm"
                    else -> "video/x-matroska"
                }

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

                RandomAccessFile(file, "r").use { raf ->
                    try {
                        copyVerifiedRange(raf, output, range,
                            isCurrent = { activeSession.get() === stream && _streamStatus.value.requestId == stream.id },
                            isVerified = { offset, count -> hasFileRangeDownloaded(offset, count, stream) },
                            verificationLength = { offset, remaining ->
                                bytesInCurrentPiece(offset, remaining, stream.fileOffset, stream.pieceLength)
                            },
                            requestPieces = { offset -> requestPieces(stream, offset, lookAhead = 2) })
                    } catch (e: TimeoutCancellationException) {
                        // A seek may have already abandoned this connection. Let Media3 retry the range.
                        updateStreamStatus(stream.id) { it.copy(phase = "range_wait") }
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

    override fun onDestroy() {
        serviceScope.cancel()
        activeSession.set(null)
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
