package io.maru.manime.torrent

import io.maru.manime.extensions.StreamLink
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineVaultTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun resumesOnlyValidatedCompletedEntries() {
        val vaultDir = folder.newFolder()
        val completed = File(vaultDir, "ep_10_1.nanami").apply { writeText("good") }
        File(vaultDir, "ep_10_2.nanami").writeText("part")
        File(vaultDir, "ep_10_3.nanami").writeText("bad")
        val index = """
            [
              {"mediaId":10,"episodeNumber":1,"isCompleted":true,"sizeBytes":4,"audioType":"DUAL_AUDIO"},
              {"mediaId":10,"episodeNumber":2,"isCompleted":false,"sizeBytes":4,"progressPercent":70},
              {"mediaId":10,"episodeNumber":3,"isCompleted":true,"sizeBytes":4},
              {"mediaId":10,"episodeNumber":4,"isCompleted":true,"sizeBytes":4},
              null
            ]
        """.trimIndent()

        val entries = readOfflineIndex(vaultDir, index)

        assertEquals(listOf(1), entries.map { it.episodeNumber })
        assertEquals(completed.absolutePath, entries.single().vaultFilePath)
        assertEquals("DUAL_AUDIO", entries.single().audioType)
        assertEquals(100, entries.single().progressPercent)
    }

    @Test
    fun cannotUseAPathFromOutsideTheVault() {
        val vaultDir = folder.newFolder()
        val outside = folder.newFile().apply { writeText("file") }
        val index = org.json.JSONArray().put(org.json.JSONObject().apply {
            put("mediaId", 10)
            put("episodeNumber", 1)
            put("isCompleted", true)
            put("sizeBytes", outside.length())
            put("vaultFilePath", outside.absolutePath)
        })

        assertTrue(readOfflineIndex(vaultDir, index.toString()).isEmpty())
    }

    @Test
    fun adultOfflinePlaybackRequiresVerifiedSourceProvenance() {
        val vaultDir = folder.newFolder()
        File(vaultDir, "ep_10_1.nanami").writeText("file")
        File(vaultDir, "ep_10_2.nanami").writeText("file")
        val (legacy, verified) = readOfflineIndex(vaultDir, """
            [
              {"mediaId":10,"episodeNumber":1,"isCompleted":true,"sizeBytes":4,"isAdultContent":true},
              {"mediaId":10,"episodeNumber":2,"isCompleted":true,"sizeBytes":4,"isAdultContent":true,"verifiedAdultEpisode":true}
            ]
        """.trimIndent())

        assertFalse(legacy.canPlayWithRating(isAdult = true, hasKnownAdultRating = true))
        assertFalse(legacy.copy(isAdultContent = null).canPlayWithRating(isAdult = false, hasKnownAdultRating = false))
        assertTrue(legacy.copy(isAdultContent = null).canPlayWithRating(isAdult = false, hasKnownAdultRating = true))
        assertTrue(verified.canPlayWithRating(isAdult = true, hasKnownAdultRating = true))
        assertTrue(File(legacy.vaultFilePath).exists())
    }

    @Test
    fun adultDownloadsKeepVerificationOnTheChosenProgressiveCandidate() {
        val unverified = StreamLink("https://example.com/other.mp4", "1080p", "Direct")
        val verified = unverified.copy(url = "https://example.com/episode.mp4", verifiedAdultEpisode = true)
        val manifest = verified.copy(url = "https://example.com/episode.mpd")

        val candidates = selectDownloadCandidates(
            listOf(unverified, manifest, verified), "SUB", requireVerifiedAdultEpisode = true
        )

        assertEquals(listOf(verified.url), candidates.map { it.url })
        assertTrue(decodeOfflineCandidates(encodeOfflineCandidates(candidates)).single().verifiedAdultEpisode)
    }
}
