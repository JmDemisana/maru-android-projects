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

    @Test fun romanizedCandidatesLeadWesternTitlesAndMatchHstreamSafely() {
        val media = io.maru.manime.AnimeMedia(
            mediaId = 21067,
            title = "Vampire Vixen",
            titleEnglish = "Vampire Vixen",
            titleRomaji = "Itadaki! Seieki♥",
            titleNative = "いただきっ！セーエキ♥",
            coverUrl = null, bannerUrl = null, accentColor = null,
            episodes = 1, format = "OVA", status = "FINISHED", season = null, seasonYear = 2014,
            averageScore = 76, genres = listOf("Hentai"), description = null, siteUrl = null,
            isAdult = true, nextEpisode = null, nextEpisodeAt = null, externalLinks = emptyList(),
            listEntryId = null, listStatus = null, progress = 0, score = null, notes = null,
            isPrivate = false, updatedAt = null,
            synonyms = listOf("Gimme That Semen!")
        )

        val candidates = media.candidateTitlesForStream
        assertEquals("Itadaki! Seieki♥", candidates.first())
        assertTrue(candidates.indexOf("Itadaki! Seieki") < candidates.indexOf("Vampire Vixen"))
        assertEquals("itadaki-seieki", HstreamResolver.toHstreamSlug("Itadaki! Seieki♥"))
        assertTrue(HstreamResolver.matchesHstreamSeriesTitle("Itadaki! Seieki♥", candidates))
        assertFalse(HstreamResolver.matchesHstreamSeriesTitle("Itadaki! Seieki Special", candidates))

        val erogeCandidates = listOf("Eroge!: H mo Game mo Kaihatsu Zanmai", "Eroge!", "Eroge! Sex and Gamedev")
        assertTrue(HstreamResolver.matchesHstreamSeriesTitle("Eroge! H mo Game mo Kaihatsu Zanmai", erogeCandidates))

        val oniChichiCandidates = listOf("Oni Chichi", "Demon Father")
        assertTrue(HstreamResolver.matchesHstreamSeriesTitle("Oni Chichi", oniChichiCandidates))
        assertFalse(HstreamResolver.matchesHstreamSeriesTitle("Oni Chichi 2", oniChichiCandidates))
        assertFalse(HstreamResolver.matchesHstreamSeriesTitle("Oni Chichi Harem", oniChichiCandidates))
    }

    @Test fun combinedAndSplitAniListKitsuSeasonLookupsAvoidSeasonOneCollision() {
        assertEquals(2, io.maru.manime.inferSeasonsSpannedFromTitle("Pretty Guardian Sailor Moon Crystal: Seasons 1-2"))
        assertEquals(3, io.maru.manime.extractExplicitSeasonFromTitle("Pretty Guardian Sailor Moon Crystal: Season 3"))
        assertEquals(3, io.maru.manime.extractExplicitSeasonFromTitle("Bishoujo Senshi Sailor Moon Crystal Season III"))

        // Sailor Moon Crystal Season 3: ani.zip has missing seasonNumber on episodes, shared IMDb tt3124992, prequel offset 26
        val smcS3Mappings = AnimeIdMappings(
            anilistId = 21462,
            kitsuId = "11415",
            imdbId = "tt3124992",
            episodeCount = 13,
            episodes = listOf(
                CrunchyEpisodeMeta(episodeNumber = 1, seasonNumber = 3, title = "Premonition: First Part", hasExplicitSeason = false)
            ),
            inferredSeasonNumber = 3,
            prequelEpisodeOffset = 26,
            combinedRootKitsuId = "7163"
        )
        val s3Lookups = CrunchyrollStreamEngine.streamLookups(smcS3Mappings, episodeNum = 1, isMovie = false).map { it.id }
        assertTrue(s3Lookups.contains("kitsu:11415:1"))
        assertTrue(s3Lookups.contains("kitsu:7163:27"))
        assertTrue(s3Lookups.contains("tt3124992:3:1"))
        assertTrue(s3Lookups.contains("tt3124992:1:27"))
        assertFalse("Must never query Season 1 Episode 1 for Season 3!", s3Lookups.contains("tt3124992:1:1"))

        // Combined AniList entry (26 eps) mapped onto split Kitsu entries (S1 = 1..14, S2 = 15..26)
        val combinedAniListSplitKitsu = AnimeIdMappings(
            anilistId = 14751,
            kitsuId = "7001",
            episodeCount = 26,
            kitsuSegments = listOf(
                KitsuEpisodeSegment("7001", 1, 14),
                KitsuEpisodeSegment("7002", 15, 26)
            )
        )
        val ep15Lookups = CrunchyrollStreamEngine.streamLookups(combinedAniListSplitKitsu, episodeNum = 15, isMovie = false).map { it.id }
        assertEquals("kitsu:7002:1", ep15Lookups.first())

        // Split AniList season mapped onto a single combined Kitsu entry (39 eps)
        val splitAniListCombinedKitsu = AnimeIdMappings(
            anilistId = 21462,
            kitsuId = "7163",
            episodeCount = 13,
            inferredSeasonNumber = 3,
            prequelEpisodeOffset = 26,
            kitsuEpisodeCount = 39,
            kitsuUsesPrequelOffset = true
        )
        val combinedKitsuLookups = CrunchyrollStreamEngine.streamLookups(splitAniListCombinedKitsu, episodeNum = 1, isMovie = false).map { it.id }
        assertEquals("kitsu:7163:27", combinedKitsuLookups.first())

        // Mismatched season file guard
        assertTrue(
            CrunchyrollStreamEngine.isMismatchedSeasonFile(
                fileLine = "Season 01-02/[Sokudo] Sailor Moon Crystal - S01E01 [1080p].mkv",
                expectedSeason = 3,
                expectedAbsoluteEpisode = 27
            )
        )
        assertFalse(
            CrunchyrollStreamEngine.isMismatchedSeasonFile(
                fileLine = "Season 03/[Sokudo] Sailor Moon Crystal - S03E01 [1080p].mkv",
                expectedSeason = 3,
                expectedAbsoluteEpisode = 27
            )
        )
        assertFalse(
            CrunchyrollStreamEngine.isMismatchedSeasonFile(
                fileLine = "[Group] Sailor Moon Crystal - S01E27 [1080p].mkv",
                expectedSeason = 3,
                expectedAbsoluteEpisode = 27
            )
        )
    }

    @Test fun hentaiSearchPillRequiresThreeConsecutivePressesWithinWindow() {
        assertEquals(
            "Press this pill three times in a row to confirm that you want to see NSFW Anime suggestions.",
            io.maru.manime.screens.HENTAI_CONFIRM_TOAST_MESSAGE
        )
        val (c1, ok1) = io.maru.manime.screens.nextHentaiConfirmTapState(0, 0L, 1_000L)
        assertEquals(1, c1)
        assertFalse(ok1)

        val (c2, ok2) = io.maru.manime.screens.nextHentaiConfirmTapState(c1, 1_000L, 2_000L)
        assertEquals(2, c2)
        assertFalse(ok2)

        // Waiting longer than 4.5s resets the streak back to 1
        val (cReset, okReset) = io.maru.manime.screens.nextHentaiConfirmTapState(c2, 2_000L, 9_000L)
        assertEquals(1, cReset)
        assertFalse(okReset)

        val (cSecond, okSecond) = io.maru.manime.screens.nextHentaiConfirmTapState(cReset, 9_000L, 9_800L)
        assertEquals(2, cSecond)
        assertFalse(okSecond)

        val (cThird, okThird) = io.maru.manime.screens.nextHentaiConfirmTapState(cSecond, 9_800L, 10_400L)
        assertEquals(0, cThird)
        assertTrue(okThird)
    }
}
