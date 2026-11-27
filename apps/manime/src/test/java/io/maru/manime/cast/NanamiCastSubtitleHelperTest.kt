package io.maru.manime.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class NanamiCastSubtitleHelperTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun convertsSrtToWebVttWithNormalizedTimestamps() {
        val srt = """
            1
            00:00:01,250 --> 00:00:04,500
            <i>Hello</i> Nanami!

            2
            00:01:02,000 --> 00:01:05,750
            Line one
            Line two
        """.trimIndent()

        val vtt = NanamiCastSubtitleHelper.convertRawToWebVtt(srt)
        assertNotNull(vtt)
        assertTrue(vtt!!.startsWith("WEBVTT\n\n"))
        assertTrue(vtt.contains("00:00:01.250 --> 00:00:04.500"))
        assertTrue(vtt.contains("Hello Nanami!"))
        assertTrue(vtt.contains("00:01:02.000 --> 00:01:05.750"))
    }

    @Test
    fun convertsAssDialogueAndStripsOverrideTags() {
        val ass = """
            [Script Info]
            Title: Test

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.50,0:00:06.10,Default,,0,0,0,,{\pos(320,240)}Wait for {\b1}me{\b0}\NSenpai!
        """.trimIndent()

        val vtt = NanamiCastSubtitleHelper.convertRawToWebVtt(ass)
        assertNotNull(vtt)
        assertTrue(vtt!!.contains("00:00:02.500 --> 00:00:06.100"))
        assertTrue(vtt.contains("Wait for me\nSenpai!"))
    }

    @Test
    fun blankSubtitleReturnsNull() {
        assertNull(NanamiCastSubtitleHelper.convertRawToWebVtt("   \n  "))
    }

    @Test
    fun extractsEmbeddedSubtitleTrackFromSyntheticMatroska() {
        val mkvFile = File(tempFolder.newFolder(), "episode.mkv")
        mkvFile.writeBytes(buildSyntheticMkvWithSubtitles())

        val extracted = NanamiCastSubtitleHelper.extractEmbeddedMkvSubtitles(mkvFile)
        assertEquals(1, extracted.size)
        val track = extracted.single()
        assertEquals("eng", track.language)
        assertEquals("English Signs", track.label)
        assertTrue(track.vttContent.startsWith("WEBVTT"))
        assertTrue(track.vttContent.contains("00:00:01.000 --> 00:00:03.500"))
        assertTrue(track.vttContent.contains("Welcome back!"))
    }

    private fun buildSyntheticMkvWithSubtitles(): ByteArray {
        val ebmlHeader = element(0x1A45DFA3, byteArrayOf(0x42, 0x86.toByte(), 0x81.toByte(), 0x01))

        val timecodeScale = element(0x2AD7B1, uintBe(1_000_000L, 4)) // 1ms ticks
        val info = element(0x1549A966, timecodeScale)

        val videoTrackEntry = element(
            0xAE,
            concat(
                element(0xD7, byteArrayOf(1)),
                element(0x83, byteArrayOf(1)),
                element(0x86, "V_MPEG4/ISO/AVC".toByteArray()),
            ),
        )
        val subTrackEntry = element(
            0xAE,
            concat(
                element(0xD7, byteArrayOf(2)),
                element(0x83, byteArrayOf(17)), // Subtitle track type
                element(0x86, "S_TEXT/ASS".toByteArray()),
                element(0x536E, "English Signs".toByteArray()),
                element(0x22B59C, "eng".toByteArray()),
            ),
        )
        val tracks = element(0x1654AE6B, concat(videoTrackEntry, subTrackEntry))

        // Block belonging to track 1 (should be skipped without allocating)
        val videoBlock = element(
            0xA3,
            byteArrayOf(0x81.toByte(), 0x00, 0x00, 0x00, 0xAA.toByte(), 0xBB.toByte()),
        )
        // Subtitle BlockGroup for track 2 at 1000ms for 2500ms
        val assLine = "1,0,Default,,0,0,0,,{\\an8}Welcome back!".toByteArray(Charsets.UTF_8)
        val subPayload = concat(
            byteArrayOf(0x82.toByte(), 0x00, 0x00, 0x00), // track 2 vint, relTime=0, flags=0
            assLine,
        )
        val blockGroup = element(
            0xA0,
            concat(
                element(0xA1, subPayload),
                element(0x9B, uintBe(2_500L, 2)),
            ),
        )
        val cluster = element(
            0x1F43B675,
            concat(
                element(0xE7, uintBe(1_000L, 2)),
                videoBlock,
                blockGroup,
            ),
        )

        val segment = element(0x18538067, concat(info, tracks, cluster))
        return concat(ebmlHeader, segment)
    }

    private fun element(id: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        when {
            id <= 0xFF -> out.write(id)
            id <= 0xFFFF -> {
                out.write((id shr 8) and 0xFF)
                out.write(id and 0xFF)
            }
            id <= 0xFFFFFF -> {
                out.write((id shr 16) and 0xFF)
                out.write((id shr 8) and 0xFF)
                out.write(id and 0xFF)
            }
            else -> {
                out.write((id shr 24) and 0xFF)
                out.write((id shr 16) and 0xFF)
                out.write((id shr 8) and 0xFF)
                out.write(id and 0xFF)
            }
        }
        val size = data.size
        when {
            size < 0x7F -> out.write(0x80 or size)
            size < 0x3FFF -> {
                out.write(0x40 or (size shr 8))
                out.write(size and 0xFF)
            }
            else -> {
                out.write(0x20 or (size shr 16))
                out.write((size shr 8) and 0xFF)
                out.write(size and 0xFF)
            }
        }
        out.write(data)
        return out.toByteArray()
    }

    private fun uintBe(value: Long, bytes: Int): ByteArray =
        ByteArray(bytes) { i -> ((value ushr ((bytes - 1 - i) * 8)) and 0xFF).toByte() }

    private fun concat(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEach(out::writeBytes)
        return out.toByteArray()
    }
}
