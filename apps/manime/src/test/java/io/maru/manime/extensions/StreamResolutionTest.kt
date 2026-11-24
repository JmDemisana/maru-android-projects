package io.maru.manime.extensions

import io.maru.manime.torrent.selectDownloadCandidates
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test fun detectAudioTypeDistinguishesEnglishDubsFromForeignDubs() {
        assertEquals("DUAL_AUDIO", CrunchyrollStreamEngine.detectAudioType("[Judas] Frieren - S01E01 [1080p][HEVC x265 10bit][Dual-Audio][Multi-Subs]"))
        assertEquals("DUB", CrunchyrollStreamEngine.detectAudioType("[Yameii] Solo Leveling - 01 [English Dub] [WEB-DL 1080p]"))
        assertEquals("SUB", CrunchyrollStreamEngine.detectAudioType("[AniDub] Chainsaw Man - 01 [1080p] [Rus]"))
        assertEquals("SUB", CrunchyrollStreamEngine.detectAudioType("Naruto Shippuden Ep 05 Dublado PT-BR 1080p"))
        assertEquals("SUB", CrunchyrollStreamEngine.detectAudioType("[SubsPlease] Love Live! Nijigasaki - 03 (1080p)"))
    }

    @Test fun selectDownloadCandidatesIncludesDirectMp4AndSeededMagnetsOnly() {
        val directMp4 = StreamLink(
            url = "https://archive.org/download/sample-concert/concert_1080p.mp4",
            quality = "1080p",
            sourceName = "Internet Archive",
            isTorrent = false,
            audioType = "SUB"
        )
        val hlsManifest = StreamLink(
            url = "https://cdn.example.com/live/master.m3u8",
            quality = "1080p",
            sourceName = "HLS Stream",
            isTorrent = false,
            audioType = "SUB"
        )
        val deadTorrent = StreamLink(
            url = "magnet:?xt=urn:btih:dead0000",
            quality = "1080p",
            sourceName = "Dead swarm",
            isTorrent = true,
            infoHash = "dead0000",
            seeders = 0,
            audioType = "DUB"
        )
        val healthyDubMagnet = StreamLink(
            url = "magnet:?xt=urn:btih:good1111",
            quality = "1080p",
            sourceName = "Healthy Dub",
            isTorrent = true,
            infoHash = "good1111",
            fileIdx = 0,
            seeders = 24,
            audioType = "DUB"
        )

        val candidates = selectDownloadCandidates(
            streams = listOf(hlsManifest, deadTorrent, directMp4, healthyDubMagnet),
            preferredAudio = "DUB"
        )
        assertEquals(2, candidates.size)
        assertEquals("magnet:?xt=urn:btih:good1111", candidates[0].url)
        assertTrue(candidates[0].isTorrent)
        assertEquals("https://archive.org/download/sample-concert/concert_1080p.mp4", candidates[1].url)
        assertFalse(candidates[1].isTorrent)
    }

    @Test fun fallbackResolversMatchEpisodesStandaloneFeaturesAndYoutubeLinks() {
        assertEquals("dQw4w9WgXcQ", FallbackStreamResolvers.extractYoutubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&feature=youtu.be"))
        assertEquals("abcDEF12_34", FallbackStreamResolvers.extractYoutubeId("https://youtu.be/abcDEF12_34"))

        assertTrue(
            FallbackStreamResolvers.matchesTitleAndEpisode(
                releaseTitle = "[ReinForce] High School DxD Hero - 02 (BDRip 1920x1080 x264 FLAC) [Uncensored]",
                candidateTitles = listOf("High School DxD Hero"),
                episodeNum = 2,
                isStandalone = false
            )
        )
        assertFalse(
            FallbackStreamResolvers.matchesTitleAndEpisode(
                releaseTitle = "[ReinForce] High School DxD Hero - 05 (BDRip 1920x1080 x264 FLAC)",
                candidateTitles = listOf("High School DxD Hero"),
                episodeNum = 2,
                isStandalone = false
            )
        )
        assertTrue(
            FallbackStreamResolvers.matchesTitleAndEpisode(
                releaseTitle = "[BDMV] BanG Dream! Pastel*Palettes Special Live ~TITLE IDOL~ [1080p]",
                candidateTitles = listOf("Pastel*Palettes Special Live TITLE IDOL"),
                episodeNum = 1,
                isStandalone = true
            )
        )
    }
}
