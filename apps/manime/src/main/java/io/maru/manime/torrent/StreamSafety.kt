package io.maru.manime.torrent

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal suspend fun awaitVerifiedRange(
    timeoutMillis: Long = 18_000, pollMillis: Long = 150,
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
