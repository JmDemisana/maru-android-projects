package io.maru.manime.torrent

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.OutputStream
import java.io.RandomAccessFile

internal const val VERIFIED_RANGE_TIMEOUT_MS = 45_000L
internal const val STREAM_READ_TIMEOUT_MS = 60_000

private val markedEpisode = Regex("(?i)(?<![a-z0-9])(?:s\\d{1,3}[ ._-]*)?e(?:p(?:isode)?)?[ ._-]*(\\d{1,4})(?:v\\d+)?(?=[^a-z0-9]|$)")
private val releaseEpisode = Regex("(?i)(?:\\s-\\s*|_)(\\d{1,4})(?:v\\d+)?(?=[\\s._\\[()\\]-]|$)")
private val numberedEpisode = Regex("(?i)^(\\d{1,4})(?:v\\d+)?$")

internal fun torrentEpisodeNumber(fileName: String): Int? {
    val stem = fileName.substringBeforeLast('.')
    val match = markedEpisode.find(stem) ?: releaseEpisode.find(stem) ?: numberedEpisode.matchEntire(stem)
    return match?.groupValues?.get(1)?.toIntOrNull()
}

/** Deadlines are relative to now. Polling must never postpone an outstanding request. */
internal class PieceDemand {
    private val requested = HashSet<Int>()
    @Synchronized fun request(piece: Int, schedule: () -> Unit) {
        if (piece !in requested) {
            schedule()
            requested.add(piece)
        }
    }
}

/** Production read path, also exercised with genuinely incomplete files in JVM tests. */
internal suspend fun copyVerifiedRange(
    file: RandomAccessFile, output: OutputStream, range: ByteRange,
    isCurrent: () -> Boolean, isVerified: (Long, Long) -> Boolean,
    timeoutMillis: Long = VERIFIED_RANGE_TIMEOUT_MS, pollMillis: Long = 150
) {
    val buffer = ByteArray(64 * 1024)
    var offset = range.start
    var remaining = range.length
    while (remaining > 0) {
        val count = minOf(buffer.size.toLong(), remaining).toInt()
        awaitVerifiedRange(timeoutMillis, pollMillis, isCurrent) { isVerified(offset, count.toLong()) }
        check(isCurrent()) { "Stream replaced" }
        check(file.length() >= offset + count) { "Verified bytes not available on disk" }
        file.seek(offset)
        file.readFully(buffer, 0, count)
        check(isCurrent()) { "Stream replaced" }
        output.write(buffer, 0, count)
        offset += count
        remaining -= count
    }
}

internal suspend fun awaitVerifiedRange(
    timeoutMillis: Long = VERIFIED_RANGE_TIMEOUT_MS, pollMillis: Long = 150,
    isCurrent: () -> Boolean, isVerified: () -> Boolean
) = withTimeout(timeoutMillis) {
    while (true) {
        check(isCurrent()) { "Stream replaced" }
        if (isVerified()) return@withTimeout
        delay(pollMillis)
    }
}

internal data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

/** A single RFC 7233 byte range. Invalid/unsatisfiable ranges return null (HTTP 416). */
internal fun parseByteRange(header: String?, size: Long): ByteRange? {
    if (size <= 0) return null
    if (header == null) return ByteRange(0, size - 1)
    val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: return null
    val (first, last) = match.destructured
    if (first.isEmpty()) {
        val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
        return ByteRange((size - suffix).coerceAtLeast(0), size - 1)
    }
    val start = first.toLongOrNull()?.takeIf { it in 0 until size } ?: return null
    val end = if (last.isEmpty()) size - 1 else last.toLongOrNull() ?: return null
    if (end < start) return null
    return ByteRange(start, minOf(end, size - 1))
}

internal fun verifiedFileRange(
    offset: Long, length: Long, fileSize: Long, fileOffset: Long,
    pieceLength: Int, pieceCount: Int, havePiece: (Int) -> Boolean
): Boolean = try {
    if (offset < 0 || length <= 0 || fileSize <= 0 || offset >= fileSize ||
        length > fileSize - offset || fileOffset < 0 || pieceLength <= 0 || pieceCount <= 0 ||
        fileOffset > Long.MAX_VALUE - offset - (length - 1)) false
    else {
        val start = (fileOffset + offset) / pieceLength
        val end = (fileOffset + offset + length - 1) / pieceLength
        start >= 0 && end < pieceCount && (start.toInt()..end.toInt()).all(havePiece)
    }
} catch (_: Exception) { false }
