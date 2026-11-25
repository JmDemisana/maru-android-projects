package io.maru.manime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetRangeTest {

    @Test
    fun defaultSnippetRangeCentersAroundPositionWithinEpisodeBounds() {
        val dur = 24 * 60 * 1000L
        val range = defaultSnippetRange(positionMs = 60_000L, durationMs = dur)
        assertEquals(55_000L, range.startMs)
        assertEquals(70_000L, range.endMs)
        assertEquals(15_000L, range.durationMs)
    }

    @Test
    fun defaultSnippetRangeClampsNearEndOfEpisode() {
        val dur = 120_000L
        val range = defaultSnippetRange(positionMs = 118_000L, durationMs = dur)
        assertEquals(105_000L, range.startMs)
        assertEquals(120_000L, range.endMs)
        assertEquals(15_000L, range.durationMs)
    }

    @Test
    fun normalizeEnforcesMinAndMaxDurations() {
        val dur = 600_000L
        val tooShort = normalizeSnippetRange(10_000L, 10_200L, dur)
        assertEquals(SNIPPET_MIN_DURATION_MS, tooShort.durationMs)

        val tooLong = normalizeSnippetRange(10_000L, 400_000L, dur)
        assertEquals(SNIPPET_MAX_DURATION_MS, tooLong.durationMs)

        val inverted = normalizeSnippetRange(50_000L, 30_000L, dur)
        assertEquals(30_000L, inverted.startMs)
        assertEquals(50_000L, inverted.endMs)
    }

    @Test
    fun applyDurationPresetAdjustsEndAndRespectsEpisodeLimit() {
        val dur = 100_000L
        val base = SnippetRange(85_000L, 95_000L)
        val preset30 = applyDurationPreset(base, 30_000L, dur)
        assertEquals(70_000L, preset30.startMs)
        assertEquals(100_000L, preset30.endMs)
        assertEquals(30_000L, preset30.durationMs)
    }

    @Test
    fun shiftSnippetWindowPreservesLengthAndClampsToBounds() {
        val dur = 100_000L
        val base = SnippetRange(40_000L, 55_000L)

        val shiftedForward = shiftSnippetWindow(base, 80_000L, dur)
        assertEquals(85_000L, shiftedForward.startMs)
        assertEquals(100_000L, shiftedForward.endMs)

        val shiftedBack = shiftSnippetWindow(base, -80_000L, dur)
        assertEquals(0L, shiftedBack.startMs)
        assertEquals(15_000L, shiftedBack.endMs)
    }

    @Test
    fun buildSnippetDisplayNameSanitizesTitleAndFormatsTimestamps() {
        val name = buildSnippetDisplayName(
            animeTitle = "Love Live! Nijigasaki: High School?",
            episodeNum = 3,
            startMs = 83_000L,
            endMs = 98_000L
        )
        assertEquals("Nanami_Love_Live_Nijigasaki_High_School_Ep03_01m23s-01m38s.mp4", name)
        assertTrue(name.endsWith(".mp4"))
    }
}
