package io.maru.manime

import io.maru.manime.extensions.AnimeIdMappings
import io.maru.manime.extensions.CrunchyEpisodeMeta
import io.maru.manime.extensions.CrunchyrollStreamEngine
import io.maru.manime.extensions.FallbackStreamResolvers
import io.maru.manime.screens.ListSortOption
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlaybackPolicyTest {
    @Test fun upcomingEpisodeOneMeansZeroReleased() {
        assertEquals(0, latestReleasedEpisode("RELEASING", 12, 1))
        assertEquals(0, latestReleasedEpisode("NOT_YET_RELEASED", 12, null))
        assertNull(latestReleasedEpisode("RELEASING", 12, null))
        assertEquals(12, latestReleasedEpisode("FINISHED", 12, null))
    }

    @Test fun unknownAndUnreleasedShowsDoNotInventEpisodes() {
        assertTrue(CrunchyrollStreamEngine.buildCompleteEpisodeList(null, emptyList(), null, null).isEmpty())
        assertTrue(CrunchyrollStreamEngine.buildCompleteEpisodeList(null, emptyList(), 12, 0, true).isEmpty())
        assertTrue(CrunchyrollStreamEngine.buildCompleteEpisodeList(null, emptyList(), 12, null, true).isEmpty())
        val future = AnimeIdMappings(1, episodeCount = 12,
            episodes = listOf(CrunchyEpisodeMeta(1, title = "Future", airDate = "2999-01-01")))
        assertTrue(CrunchyrollStreamEngine.buildCompleteEpisodeList(future, emptyList(), 12, null, true).isEmpty())
        assertEquals(2, CrunchyrollStreamEngine.buildCompleteEpisodeList(future, emptyList(), 12, 2, true).size)
    }

    @Test fun pausedAndCancelledShowsRequireReleaseEvidence() {
        assertNull(latestReleasedEpisode("HIATUS", 12, null))
        assertNull(latestReleasedEpisode("CANCELLED", 12, null))
        assertNull(latestReleasedEpisode(null, 12, null))
        assertEquals(4, latestReleasedEpisode("HIATUS", 12, 5))

        val mappings = AnimeIdMappings(1, episodeCount = 12, episodes = listOf(
            CrunchyEpisodeMeta(1, title = "Released", airDate = "2000-01-01"),
            CrunchyEpisodeMeta(2, title = "Scheduled", airDate = "2999-01-01"),
            CrunchyEpisodeMeta(3, title = "Unknown", airDate = "TBA")
        ))
        assertEquals(listOf(1), CrunchyrollStreamEngine.buildCompleteEpisodeList(
            mappings, emptyList(), 12, null, true
        ).map { it.episodeNumber })
    }

    @Test fun releaseDatesUseSingaporeEvenBeforeUtcMidnight() {
        assertEquals("2026-11-24", singaporeDate(Instant.parse("2026-11-23T16:01:00Z")))
        assertEquals("2026-11-23", singaporeDate(Instant.parse("2026-11-23T15:59:00Z")))
    }

    @Test fun collectionVariablesAreArraysNotQuotedKotlinLists() {
        val roundTrip = JSONObject(aniListVariables(mapOf("ids" to listOf(1, 2),
            "nested" to mapOf("flags" to listOf(true, false)), "omitted" to null)).toString())
        assertEquals(2, roundTrip.getJSONArray("ids").getInt(1))
        assertTrue(roundTrip.getJSONObject("nested").getJSONArray("flags").getBoolean(0))
        assertFalse(roundTrip.has("omitted"))
    }

    @Test fun nativeScoresConvertToAniListRawScale() {
        assertEquals(85, nativeScoreToRaw(8.5, "POINT_10_DECIMAL"))
        assertEquals(80, nativeScoreToRaw(8.0, "POINT_10"))
        assertEquals(80, nativeScoreToRaw(4.0, "POINT_5"))
        assertEquals(35, nativeScoreToRaw(1.0, "POINT_3"))
        assertEquals(60, nativeScoreToRaw(2.0, "POINT_3"))
        assertEquals(85, nativeScoreToRaw(3.0, "POINT_3"))
        assertEquals(0, nativeScoreToRaw(0.0, "POINT_3"))
        assertEquals(92, nativeScoreToRaw(92.0, "POINT_100"))
    }

    @Test fun contentAdvisoryMatchesCrunchyrollMaturityDescriptors() {
        // Sailor Moon Crystal: Mahou Shoujo transformation silhouettes have Nudity (isAdult=true on AniList tag),
        // which must NEVER mark Sailor Moon as 18+ or Nudity!
        val sailorMoon = deriveContentAdvisory(
            isAdult = false,
            genres = listOf("Action", "Fantasy", "Mahou Shoujo", "Romance"),
            tags = listOf(
                MediaTag("Mahou Shoujo", rank = 92),
                MediaTag("Henshin", rank = 80),
                MediaTag("Nudity", rank = 56, isAdult = true),
                MediaTag("Suicide", rank = 52)
            )
        )
        assertEquals("14+", sailorMoon.ageRating)
        assertFalse(sailorMoon.isMature)
        assertEquals(listOf("Fantasy Violence"), sailorMoon.warnings)

        // Genuine 18+ mature horror/gore or adult entry
        val mature = deriveContentAdvisory(
            isAdult = false,
            genres = listOf("Action", "Horror", "Psychological"),
            tags = listOf(
                MediaTag("Gore", rank = 88),
                MediaTag("Drugs", rank = 75)
            )
        )
        assertEquals("18+", mature.ageRating)
        assertTrue(mature.isMature)
        assertTrue("Violence" in mature.warnings)
        assertTrue("Gore" in mature.warnings)
        assertTrue("Drugs" in mature.warnings)

        // Standard Ecchi (e.g. Eromanga Sensei / kissxsis) -> 16+, not 18+
        val ecchi = deriveContentAdvisory(
            isAdult = false,
            genres = listOf("Comedy", "Ecchi"),
            tags = listOf(MediaTag("Large Breasts", rank = 72, isAdult = true))
        )
        assertEquals("16+", ecchi.ageRating)
        assertEquals(listOf("Sexual Themes"), ecchi.warnings)

        val wholesome = deriveContentAdvisory(
            isAdult = false,
            genres = listOf("Music", "Slice of Life"),
            tags = listOf(MediaTag("Idol", rank = 90))
        )
        assertEquals("PG", wholesome.ageRating)
        assertTrue(wholesome.warnings.isEmpty())
    }

    @Test fun multiAccountJsonRoundTripsAndDeduplicatesUsernames() {
        val encoded = encodeAccountsJson(
            listOf(
                SavedAniListAccount(10, "MaruSenpai", "https://img/1.png", "POINT_10_DECIMAL", "tok_1"),
                SavedAniListAccount(10, "marusenpai", null, "POINT_100", "tok_dup"),
                SavedAniListAccount(22, "NanamiAlt", null, "POINT_5", "tok_2")
            )
        )
        val parsed = parseAccountsJson(encoded)
        assertEquals(2, parsed.size)
        assertEquals("MaruSenpai", parsed[0].username)
        assertEquals("POINT_10_DECIMAL", parsed[0].scoreFormat)
        assertEquals("NanamiAlt", parsed[1].username)
    }

    @Test fun guestWatchlistAndAccountScopedOfflineQueueMergeCleanly() {
        fun sampleAnime(id: Int, title: String, status: String?, progress: Int, episodes: Int? = 12) = AnimeMedia(
            mediaId = id,
            title = title,
            titleEnglish = title,
            titleRomaji = title,
            coverUrl = null,
            bannerUrl = null,
            accentColor = null,
            episodes = episodes,
            format = "TV",
            status = "FINISHED",
            season = null,
            seasonYear = 2026,
            averageScore = 84,
            genres = listOf("Music"),
            description = null,
            siteUrl = null,
            isAdult = false,
            nextEpisode = null,
            nextEpisodeAt = null,
            externalLinks = emptyList(),
            listEntryId = 1,
            listStatus = status,
            progress = progress,
            score = null,
            notes = null,
            isPrivate = false,
            updatedAt = 100L
        )

        val localShow = sampleAnime(101, "Guest Show", "CURRENT", 3)
        val guestMerged = LocalLibraryStore.buildMergedLists(
            remoteLists = emptyMap(),
            localMap = mapOf(101 to localShow),
            pending = emptyList(),
            isLoggedIn = false
        )
        assertEquals(listOf(101), guestMerged["Watching"]?.map { it.mediaId })

        val queueJson = """[{"mediaId":101,"title":"Guest Show","status":"CURRENT","progress":4,"isWatchedAutoBump":true,"accountUsername":"MaruSenpai","queuedAt":500000}]"""
        val parsedQueue = LocalLibraryStore.parseQueueJson(queueJson)
        assertEquals(1, parsedQueue.size)
        assertEquals("MaruSenpai", parsedQueue.first().accountUsername)

        val remoteShow = sampleAnime(101, "Guest Show", "CURRENT", 2)
        val mergedForOwner = LocalLibraryStore.buildMergedLists(
            remoteLists = mapOf("Watching" to listOf(remoteShow)),
            localMap = mapOf(101 to localShow),
            pending = parsedQueue,
            isLoggedIn = true,
            activeUsername = "marusenpai"
        )
        assertEquals(4, mergedForOwner["Watching"]?.first()?.progress)

        val mergedForOtherAccount = LocalLibraryStore.buildMergedLists(
            remoteLists = mapOf("Watching" to listOf(remoteShow)),
            localMap = mapOf(101 to localShow),
            pending = parsedQueue,
            isLoggedIn = true,
            activeUsername = "AnotherUser"
        )
        assertEquals(2, mergedForOtherAccount["Watching"]?.first()?.progress)
    }

    @Test fun splitCompletedListsArePreservedWithoutSquashing() {
        val tvShow = AnimeMedia(
            mediaId = 201,
            title = "KissxSis (TV)",
            titleEnglish = "KissxSis (TV)",
            titleRomaji = "kissxsis (TV)",
            titleNative = "キス×シス",
            coverUrl = null, bannerUrl = null, accentColor = null, episodes = 12, format = "TV",
            status = "FINISHED", season = null, seasonYear = 2010, averageScore = 70,
            genres = listOf("Comedy", "Ecchi", "Romance"), description = null, siteUrl = null, isAdult = false,
            nextEpisode = null, nextEpisodeAt = null, externalLinks = emptyList(), listEntryId = null,
            listStatus = "COMPLETED", progress = 12, score = 8.0, notes = null, isPrivate = false, updatedAt = 200L
        )
        val movieShow = AnimeMedia(
            mediaId = 202,
            title = "Eromanga Sensei OVA",
            titleEnglish = "Eromanga Sensei OVA",
            titleRomaji = "Eromanga Sensei OVA",
            titleNative = "エロマンガ先生 OVA",
            coverUrl = null, bannerUrl = null, accentColor = null, episodes = 2, format = "OVA",
            status = "FINISHED", season = null, seasonYear = 2019, averageScore = 72,
            genres = listOf("Comedy", "Ecchi"), description = null, siteUrl = null, isAdult = false,
            nextEpisode = null, nextEpisodeAt = null, externalLinks = emptyList(), listEntryId = null,
            listStatus = "COMPLETED", progress = 2, score = 8.5, notes = null, isPrivate = false, updatedAt = 300L
        )

        val remoteLists = linkedMapOf(
            "Watching" to listOf(tvShow.copy(mediaId = 203, listStatus = "CURRENT", progress = 1)),
            "Completed TV" to listOf(tvShow),
            "Completed OVA" to listOf(movieShow)
        )

        val merged = LocalLibraryStore.buildMergedLists(
            remoteLists = remoteLists,
            localMap = emptyMap(),
            pending = emptyList(),
            isLoggedIn = true,
            activeUsername = "PpolHi"
        )

        assertTrue("Completed TV should exist", merged.containsKey("Completed TV"))
        assertTrue("Completed OVA should exist", merged.containsKey("Completed OVA"))
        assertFalse("Generic Completed list should not be synthesized", merged.containsKey("Completed"))
        assertEquals(listOf(201), merged["Completed TV"]?.map { it.mediaId })
        assertEquals(listOf(202), merged["Completed OVA"]?.map { it.mediaId })
    }

    @Test fun cleanAnimeTitleAndCandidateGeneration() {
        assertEquals("kissxsis", cleanAnimeTitle("kissxsis (TV)"))
        assertEquals("Eromanga Sensei", cleanAnimeTitle("Eromanga Sensei (OVA)"))
        assertEquals("Pretty Guardian Sailor Moon Crystal", cleanAnimeTitle("Pretty Guardian Sailor Moon Crystal: Seasons 1-2"))

        val media = AnimeMedia(
            mediaId = 301,
            title = "KissxSis (TV)",
            titleEnglish = "KissxSis (TV)",
            titleRomaji = "kissxsis (TV)",
            titleNative = "キス×シス",
            coverUrl = null, bannerUrl = null, accentColor = null, episodes = 12, format = "TV",
            status = "FINISHED", season = null, seasonYear = 2010, averageScore = 70,
            genres = listOf("Comedy", "Ecchi"), description = null, siteUrl = null, isAdult = false,
            nextEpisode = null, nextEpisodeAt = null, externalLinks = emptyList(), listEntryId = null,
            listStatus = null, progress = 0, score = null, notes = null, isPrivate = false, updatedAt = null
        )

        val candidates = media.candidateTitlesForStream
        assertTrue("Should include romaji", candidates.contains("kissxsis (TV)"))
        assertTrue("Should include native", candidates.contains("キス×シス"))
        assertTrue("Should include cleaned title", candidates.contains("kissxsis"))
    }

    @Test fun eroticAnimeStreamTitleMatching() {
        val candidates = listOf("KissxSis (TV)", "kissxsis", "キス×シス")
        val release1 = "[Anonymous]_KissxSis_-_01_[H.264_MP3][894F19F6](english_subtitles).mkv"
        val release2 = "[Seed-Raws][2008-2015] キス×シス kiss×sis KissXSis (無修正) - TV+OAD [BD][720p][AVC][MP4][simple version]"
        val release3 = "[KKF-Kashikoi]_kissXsis_-_01~12_[BDRip_1920x1080_x264_AAC][Sub_Esp][MP4]"

        assertTrue(FallbackStreamResolvers.matchesTitleAndEpisode(release1, candidates, 1, false))
        assertTrue(FallbackStreamResolvers.matchesTitleAndEpisode(release2, candidates, 1, false))
        assertTrue(FallbackStreamResolvers.matchesTitleAndEpisode(release3, candidates, 1, false))

        val unrelated1 = "[SomeGroup] Dragon Ball Super OVA - 01.mkv"
        val unrelated2 = "Random Non Anime Video From Archive.mp4"
        val differentEp = "[Anonymous]_KissxSis_-_02_[H.264_MP3].mkv"

        assertFalse(FallbackStreamResolvers.matchesTitleAndEpisode(unrelated1, candidates, 1, false))
        assertFalse(FallbackStreamResolvers.matchesTitleAndEpisode(unrelated2, candidates, 1, false))
        assertFalse(FallbackStreamResolvers.matchesTitleAndEpisode(differentEp, candidates, 1, false))
    }

    @Test fun listSortOptionsOrderCorrectly() {
        fun makeItem(id: Int, title: String, score: Double?, year: Int?, season: String?, added: Long) = AnimeMedia(
            mediaId = id,
            title = title,
            titleEnglish = title,
            titleRomaji = title,
            coverUrl = null, bannerUrl = null, accentColor = null, episodes = 12, format = "TV",
            status = "FINISHED", season = season, seasonYear = year, averageScore = 75,
            genres = listOf("Action"), description = null, siteUrl = null, isAdult = false,
            nextEpisode = null, nextEpisodeAt = null, externalLinks = emptyList(), listEntryId = id,
            listStatus = "COMPLETED", progress = 12, score = score, notes = null, isPrivate = false,
            updatedAt = added, createdAt = added, startDateYear = year
        )

        val itemA = makeItem(1, "Bocchi the Rock!", score = 9.0, year = 2022, season = "FALL", added = 1000L)
        val itemB = makeItem(2, "A-Channel", score = 7.0, year = 2011, season = "SPRING", added = 3000L)
        val itemC = makeItem(3, "Chihayafuru", score = 9.0, year = 2011, season = "FALL", added = 2000L)
        val list = listOf(itemA, itemB, itemC)

        // Alphabetical: A-Channel (2), Bocchi (1), Chihayafuru (3)
        assertEquals(listOf(2, 1, 3), ListSortOption.ALPHABETICAL.sort(list).map { it.mediaId })

        // Recently Added: itemB (3000), itemC (2000), itemA (1000)
        assertEquals(listOf(2, 3, 1), ListSortOption.RECENTLY_ADDED.sort(list).map { it.mediaId })

        // Recently Released: itemA (2022), itemC (2011 Fall), itemB (2011 Spring)
        assertEquals(listOf(1, 3, 2), ListSortOption.RECENTLY_RELEASED.sort(list).map { it.mediaId })

        // Rating, Then Recently Released: 9.0 (itemA 2022, itemC 2011), then 7.0 (itemB)
        assertEquals(listOf(1, 3, 2), ListSortOption.RATING_THEN_RECENTLY_RELEASED.sort(list).map { it.mediaId })

        // Rating, Then Recently Added: 9.0 (itemC 2000, itemA 1000), then 7.0 (itemB)
        assertEquals(listOf(3, 1, 2), ListSortOption.RATING_THEN_RECENTLY_ADDED.sort(list).map { it.mediaId })

        // Rating, Then Alphabetical: 9.0 (itemA "Bocchi", itemC "Chihayafuru"), then 7.0 (itemB "A-Channel")
        assertEquals(listOf(1, 3, 2), ListSortOption.RATING_THEN_ALPHABETICAL.sort(list).map { it.mediaId })
    }
}
