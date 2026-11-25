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

    @Test
    fun parseSubtitleCuesHandlesSrtVttAndAss() {
        val srt = """
            1
            00:01:02,500 --> 00:01:05,000
            <i>Hello, Senpai!</i>
            Second line
        """.trimIndent()
        val srtCues = parseSubtitleCues(srt)
        assertEquals(1, srtCues.size)
        assertEquals(62_500L, srtCues[0].startMs)
        assertEquals(65_000L, srtCues[0].endMs)
        assertEquals("Hello, Senpai!\nSecond line", srtCues[0].text)
        assertEquals("Hello, Senpai!\nSecond line", cuesActiveAt(srtCues, 63_000L))
        assertEquals("", cuesActiveAt(srtCues, 65_500L))

        val vtt = """
            WEBVTT

            00:10.200 --> 00:12.800
            <c.white>Pastel*Palettes!</c>
        """.trimIndent()
        val vttCues = parseSubtitleCues(vtt)
        assertEquals(1, vttCues.size)
        assertEquals(10_200L, vttCues[0].startMs)
        assertEquals(12_800L, vttCues[0].endMs)
        assertEquals("Pastel*Palettes!", vttCues[0].text)

        val ass = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:02:15.50,0:02:18.25,Default,,0,0,0,,{\an8}Line one\N{\i1}Line two, with comma{\i0}
        """.trimIndent()
        val assCues = parseSubtitleCues(ass)
        assertEquals(1, assCues.size)
        assertEquals(135_500L, assCues[0].startMs)
        assertEquals(138_250L, assCues[0].endMs)
        assertEquals("Line one\nLine two, with comma", assCues[0].text)
    }

    @Test
    fun liveCueRecorderCapturesEmbeddedSubtitleTimeline() {
        val recorder = LiveCueRecorder()
        recorder.onCues(10_000L, "First line")
        recorder.onCues(12_500L, "")
        recorder.onCues(13_000L, "Second line")
        recorder.flush(15_200L)

        val cues = recorder.snapshot()
        assertEquals(2, cues.size)
        assertEquals("First line", cuesActiveAt(cues, 11_000L))
        assertEquals("Second line", cuesActiveAt(cues, 14_000L))
        assertTrue(recorder.hasCuesInRange(10_500L, 14_500L))
    }
}
