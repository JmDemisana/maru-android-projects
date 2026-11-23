package io.maru.manime.extensions

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StreamResolutionTest {
    @Test fun completedRoutesPublishBeforeSlowRoutesAndKeepDistinctTorrentFiles() = runBlocking {
        val first = StreamLink(
            url = "magnet:?xt=urn:btih:example",
            quality = "1080p",
            sourceName = "Fast provider",
            isTorrent = true,
            infoHash = "example",
            fileIdx = 1,
            score = 10
        )
        val best = first.copy(sourceName = "Slow provider", score = 30)
        val otherFile = first.copy(fileIdx = 2, score = 20)
        val slowRoute = CompletableDeferred<List<StreamLink>>()
        val fastRoute = CompletableDeferred(listOf(first))
        val updates = Channel<List<StreamLink>>(Channel.UNLIMITED)
        val resolution = async {
            collectRankedStreams(listOf(slowRoute, fastRoute)) { updates.trySend(it) }
        }

        try {
            assertEquals(listOf(first), withTimeout(1_000) { updates.receive() })
            assertFalse(resolution.isCompleted)
        } finally {
            slowRoute.complete(listOf(first.copy(score = 5), otherFile, best))
        }

        val fullRanking = listOf(best, otherFile)
        assertEquals(fullRanking, withTimeout(1_000) { updates.receive() })
        assertEquals(fullRanking, resolution.await())
    }
}
