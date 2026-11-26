package io.maru.movies

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.maru.manime.extensions.StremioStreamResult
import io.maru.manime.torrent.PieceDemand
import io.maru.manime.torrent.STREAM_READ_TIMEOUT_MS
import io.maru.manime.torrent.awaitVerifiedRange
import io.maru.manime.torrent.bytesInCurrentPiece
import io.maru.manime.torrent.verifiedFileRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.ErrorCode
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.error_code
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.UUID

private val torrentHash = Regex("[A-Fa-f0-9]{40}")
private val videoExtensions = setOf("mkv", "mp4", "webm", "m4v", "avi", "ts")
private val peerDiscoveryTrackers = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.torrent.eu.org:451/announce"
)

internal fun StremioStreamResult.magnetUri(): String? {
    val hash = infoHash?.takeIf(torrentHash::matches) ?: return null
    val providerTrackers = sources.filter {
        it.startsWith("udp://") || it.startsWith("https://") || it.startsWith("http://")
    }
    return buildString {
        append("magnet:?xt=urn:btih:")
        append(hash.lowercase())
        (providerTrackers + peerDiscoveryTrackers).distinct()
            .forEach { tracker -> append("&tr=").append(Uri.encode(tracker)) }
    }
}

/** Keeps the libtorrent session and its private cache alive only while the player owns it. */
@OptIn(UnstableApi::class)
internal suspend fun withMoviesTorrent(
    cacheDir: File,
    source: StremioStreamResult,
    episode: MovieEpisode?,
    onBuffer: suspend () -> Unit,
    play: suspend (Uri, DataSource.Factory) -> Unit
) = withContext(Dispatchers.IO) {
    val magnet = requireNotNull(source.magnetUri()) { "This torrent source is invalid." }
    val saveDir = File(cacheDir, "movies-playback/${UUID.randomUUID()}")
    val session = SessionManager()
    var stream: VerifiedMovieFile? = null
    try {
        check(saveDir.mkdirs()) { "Could not create the streaming cache." }
        session.start()
        val handle = addMoviesTorrent(session, magnet, saveDir)
        val info = withTimeout(30_000L) { awaitMovieTorrentMetadata(handle) }
        val fileIndex = chooseMovieFile(info, source, episode)
        val files = info.files()
        val file = File(saveDir, files.filePath(fileIndex)).canonicalFile
        check(file.path.startsWith(saveDir.canonicalPath + File.separator)) { "Invalid torrent video path." }
        check(info.pieceLength() > 0 && info.numPieces() > 0) { "The torrent has no valid pieces." }
        selectMovieTorrentFile(handle, info.numFiles(), fileIndex)

        val movie = VerifiedMovieFile(
            file, files.fileSize(fileIndex), files.fileOffset(fileIndex),
            info.pieceLength(), info.numPieces(), handle
        )
        stream = movie
        movie.requestPieces(0L)
        handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
        onBuffer()
        val firstBytes = movie.bytesInPiece(0L, movie.size)
        awaitVerifiedRange(timeoutMillis = 45_000L, isCurrent = { true }) {
            movie.verify(0L, firstBytes) && file.isFile && file.length() >= firstBytes
        }
        val uri = Uri.Builder().scheme("nanami-torrent").authority("playback").appendPath("video.${file.extension}").build()
        play(uri, DataSource.Factory { VerifiedMovieDataSource(movie) })
    } finally {
        stream?.stopReading()
        runCatching { session.stop() }
        saveDir.deleteRecursively()
    }
}

private fun addMoviesTorrent(session: SessionManager, magnet: String, directory: File): TorrentHandle {
    check(session.isRunning) { "The torrent engine could not start." }
    val params = AddTorrentParams.parseMagnetUri(magnet).apply {
        setSavePath(directory.absolutePath)
        // Fetch the metadata on this handle before enabling payload downloads for the selected file.
        setFlags(getFlags().and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.AUTO_MANAGED.inv())
            .and_(TorrentFlags.STOP_WHEN_READY.inv())
            .or_(TorrentFlags.UPLOAD_MODE).or_(TorrentFlags.SEQUENTIAL_DOWNLOAD))
    }
    val error = ErrorCode(error_code())
    val handle = SessionHandle(session.swig()).addTorrent(params, error)
    check(!error.isError && handle.isValid) { "The torrent could not be opened." }
    handle.resume()
    return handle
}

private suspend fun awaitMovieTorrentMetadata(handle: TorrentHandle): TorrentInfo {
    while (true) {
        check(!handle.status().errorCode().isError) { "Could not read torrent details." }
        val info = handle.torrentFile()
        if (info != null && info.isValid && info.numFiles() > 0) return info
        delay(100)
    }
}

private fun chooseMovieFile(info: TorrentInfo, source: StremioStreamResult, episode: MovieEpisode?): Int {
    val files = info.files()
    val videos = (0 until files.numFiles()).filter { index ->
        files.fileName(index).substringAfterLast('.', "").lowercase() in videoExtensions && files.fileSize(index) > 0
    }
    check(videos.isNotEmpty()) { "This torrent has no supported video files." }
    source.fileIdx?.takeIf { it in videos }?.let { return it }
    source.filename?.substringAfterLast('/')?.let { filename ->
        videos.firstOrNull { files.fileName(it).equals(filename, ignoreCase = true) }?.let { return it }
    }
    if (episode == null) return videos.maxBy(files::fileSize)
    val episodePattern = Regex("(?i)(?:^|[^a-z0-9])(?:s0*${episode.season}[ ._-]*e0*${episode.episode}|0*${episode.season}x0*${episode.episode})(?!\\d)")
    videos.filter { episodePattern.containsMatchIn(files.fileName(it)) }.maxByOrNull(files::fileSize)?.let { return it }
    if (videos.size == 1) return videos.single()
    throw IOException("This torrent does not identify the requested episode.")
}

private suspend fun selectMovieTorrentFile(handle: TorrentHandle, fileCount: Int, selected: Int) {
    if (fileCount < 2) return
    val priorities = Array(fileCount) { if (it == selected) Priority.DEFAULT else Priority.IGNORE }
    handle.prioritizeFiles(priorities)
    // A pending file priority update can reset piece deadlines. Wait for it first.
    withTimeout(5_000L) {
        while (!handle.filePriorities().contentEquals(priorities)) {
            check(!handle.status().errorCode().isError) { "Could not select the torrent video file." }
            delay(50)
        }
    }
}

private class VerifiedMovieFile(
    val file: File,
    val size: Long,
    private val fileOffset: Long,
    private val pieceLength: Int,
    private val pieceCount: Int,
    private val handle: TorrentHandle
) {
    private val demand = PieceDemand()
    @Volatile private var reading = true

    fun checkActive() {
        if (!reading) throw InterruptedIOException("Playback ended.")
    }

    fun bytesInPiece(offset: Long, remaining: Long): Long =
        bytesInCurrentPiece(offset, remaining, fileOffset, pieceLength)

    @Synchronized
    fun requestPieces(offset: Long) {
        checkActive()
        val first = ((fileOffset + offset) / pieceLength).toInt()
        val last = ((fileOffset + size - 1) / pieceLength).toInt()
        for (piece in first..minOf(last, first + 6)) {
            if (handle.havePiece(piece)) continue
            val delayMs = (piece - first) * 250
            demand.request(piece, delayMs) { handle.setPieceDeadline(piece, delayMs) }
        }
    }

    @Synchronized
    fun verify(offset: Long, length: Long): Boolean {
        checkActive()
        return verifiedFileRange(offset, length, size, fileOffset, pieceLength, pieceCount, handle::havePiece)
    }

    @Synchronized
    fun stopReading() {
        reading = false
    }
}

/** Feeds Media3 verified bytes only, including when it jumps to a new position to seek. */
@OptIn(UnstableApi::class)
private class VerifiedMovieDataSource(private val movie: VerifiedMovieFile) : BaseDataSource(true) {
    private var file: RandomAccessFile? = null
    private var activeUri: Uri? = null
    private var position = 0L
    private var left = 0L
    private var verifiedThrough = 0L

    override fun open(dataSpec: DataSpec): Long {
        movie.checkActive()
        transferInitializing(dataSpec)
        if (dataSpec.position > movie.size) throw IOException("Position is outside the video.")
        position = dataSpec.position
        verifiedThrough = position
        val available = movie.size - position
        left = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length, available)
        file = RandomAccessFile(movie.file, "r")
        file?.seek(position)
        activeUri = dataSpec.uri
        transferStarted(dataSpec)
        return if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else dataSpec.length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        movie.checkActive()
        if (position >= verifiedThrough) {
            val available = movie.bytesInPiece(position, left)
            movie.requestPieces(position)
            val started = SystemClock.elapsedRealtime()
            while (!movie.verify(position, available)) {
                if (SystemClock.elapsedRealtime() - started >= STREAM_READ_TIMEOUT_MS) {
                    throw IOException("Timed out waiting for the video data.")
                }
                try {
                    Thread.sleep(60)
                } catch (_: InterruptedException) {
                    throw InterruptedIOException("Playback was interrupted.")
                }
            }
            verifiedThrough = position + available
        }
        val input = checkNotNull(file)
        if (input.length() < verifiedThrough) throw IOException("Verified data has not reached the streaming cache.")
        val count = minOf(length.toLong(), verifiedThrough - position).toInt()
        input.readFully(buffer, offset, count)
        position += count
        left -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = activeUri

    override fun close() {
        try {
            file?.close()
        } finally {
            file = null
            if (activeUri != null) {
                activeUri = null
                transferEnded()
            }
        }
    }
}
