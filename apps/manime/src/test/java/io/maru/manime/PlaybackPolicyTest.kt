package io.maru.manime

import io.maru.manime.extensions.AnimeIdMappings
import io.maru.manime.extensions.CrunchyEpisodeMeta
import io.maru.manime.extensions.CrunchyrollStreamEngine
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
}
