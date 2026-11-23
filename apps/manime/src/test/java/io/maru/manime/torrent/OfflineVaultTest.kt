package io.maru.manime.torrent

import java.io.File
import org.junit.Assert.assertEquals
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
}
