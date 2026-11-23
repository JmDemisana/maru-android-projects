package io.maru.manime.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

class StreamSafetyTest {
    @Test fun batchEpisodeNamesHandleExactNumbersAndVersionSuffixes() {
        assertEquals(10, torrentEpisodeNumber("Series.S02E010.mkv"))
        assertEquals(1, torrentEpisodeNumber("[Group] Series - 01v2 [1080p].mkv"))
        assertEquals(2, torrentEpisodeNumber("02.mp4"))
        assertNull(torrentEpisodeNumber("Sample - 1080p.mkv"))
    }

    @Test fun sparseByteRangesStopWhereARequestedPieceIsMissing() = runBlocking {
        val file = File.createTempFile("nanami-torrent", ".mkv")
        try {
            val firstChunk = ByteArray(64 * 1024) { 7 }
            val output = ByteArrayOutputStream()
            RandomAccessFile(file, "rw").use { input ->
                input.write(firstChunk)
                input.setLength(firstChunk.size * 2L)
                try {
                    copyVerifiedRange(input, output, ByteRange(0, input.length() - 1),
                        isCurrent = { true }, isVerified = { position, _ -> position < firstChunk.size },
                        timeoutMillis = 30, pollMillis = 1)
                    fail("An incomplete range must time out")
                } catch (_: TimeoutCancellationException) {}
            }
            assertArrayEquals(firstChunk, output.toByteArray())
        } finally {
            file.delete()
        }
    }

    @Test fun seekingPromotesReadAheadWithoutPostponingExistingDeadlines() {
        var clock = 0L
        val demand = PieceDemand { clock }
        val requests = mutableListOf<Int>()
        demand.request(8, 800) { requests += 800 }
        clock = 100
        demand.request(8, 800) { requests += 800 }
        demand.request(8, 0) { requests += 0 }
        clock = 101
        demand.request(8, 0) { requests += 0 }
        assertEquals(listOf(800, 0), requests)
    }

    @Test fun unalignedRangeVerifiesEachPieceOnceAcrossLargerSpans() = runBlocking {
        val file = File.createTempFile("nanami-piece-span", ".mkv")
        try {
            val data = ByteArray(300 * 1024) { (it % 251).toByte() }
            val start = 64L * 1024
            val last = 295L * 1024
            val fileOffset = 32L * 1024
            val pieceLength = 256 * 1024
            val verified = mutableListOf<Pair<Long, Long>>()
            val output = ByteArrayOutputStream()
            RandomAccessFile(file, "rw").use { input ->
                input.write(data)
                copyVerifiedRange(input, output, ByteRange(start, last),
                    isCurrent = { true },
                    isVerified = { offset, length -> verified += offset to length; true },
                    verificationLength = { offset, remaining -> bytesInCurrentPiece(offset, remaining, fileOffset, pieceLength) })
            }
            val boundary = pieceLength - fileOffset
            assertEquals(listOf(start to (boundary - start), boundary to (last - boundary + 1)), verified)
            assertArrayEquals(data.copyOfRange(start.toInt(), last.toInt() + 1), output.toByteArray())
        } finally {
            file.delete()
        }
    }

    @Test fun rangesSupportSuffixOpenAndClampedEnds() {
        assertEquals(ByteRange(0, 99), parseByteRange(null, 100))
        assertEquals(ByteRange(80, 99), parseByteRange("bytes=-20", 100))
        assertEquals(ByteRange(80, 99), parseByteRange("bytes=80-", 100))
        assertEquals(ByteRange(80, 99), parseByteRange("bytes=80-200", 100))
        assertEquals(ByteRange(0, 99), parseByteRange("bytes=-200", 100))
    }

    @Test fun malformedAndUnsatisfiableRangesAreRejected() {
        for (header in listOf("bytes=100-", "bytes=20-10", "bytes=-0", "bytes=-", "bytes=0-1,3-4",
            "bytes=abc-4", "bytes=99999999999999999999999-", "items=1-2")) {
            assertNull(header, parseByteRange(header, 100))
        }
        assertNull(parseByteRange(null, 0))
    }

    @Test fun allCoveringPiecesMustBeVerifiedIncludingUnalignedFileOffset() {
        val checked = mutableListOf<Int>()
        assertTrue(verifiedFileRange(0, 12, 20, 6, 8, 4) { checked += it; true })
        assertEquals(listOf(0, 1, 2), checked)
        assertFalse(verifiedFileRange(0, 12, 20, 6, 8, 4) { it != 1 })
    }

    @Test fun metadataAndVerificationErrorsFailClosed() {
        assertFalse(verifiedFileRange(0, 1, 20, 0, 8, 4) { error("Stale handle") })
        assertFalse(verifiedFileRange(0, 1, 20, 0, 0, 4) { true })
        assertFalse(verifiedFileRange(19, 2, 20, 0, 8, 4) { true })
        assertFalse(verifiedFileRange(0, 20, 20, Long.MAX_VALUE, 8, 4) { true })
        assertFalse(verifiedFileRange(0, 20, 20, 0, 8, 1) { true })
    }

    @Test fun timeoutCannotContinueToFileRead() = runBlocking {
        var readReached = false
        try {
            awaitVerifiedRange(30, 1, { true }, { false })
            readReached = true
            fail("Expected timeout")
        } catch (_: TimeoutCancellationException) {}
        assertFalse(readReached)
    }

    @Test fun replacedSessionCannotPassEvenIfOldPiecesWereVerified() = runBlocking {
        try {
            awaitVerifiedRange(100, 1, { false }, { true })
            fail("Expected stale session rejection")
        } catch (_: IllegalStateException) {}
    }

    @Test fun cancellationCannotContinueToFileRead() = runBlocking {
        try {
            awaitVerifiedRange(100, 1, { true }, { throw CancellationException("Stopped") })
            fail("Expected cancellation")
        } catch (_: CancellationException) {}
    }
}
