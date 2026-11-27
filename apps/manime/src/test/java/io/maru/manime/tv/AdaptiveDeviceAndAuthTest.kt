package io.maru.manime.tv

import io.maru.manime.auth.NanamiTvQrAuthClient
import io.maru.manime.auth.TvQrPollResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AdaptiveDeviceAndAuthTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun tvCacheBudgetRespectsFloorAndCeiling() {
        val mb = 1024L * 1024L
        assertEquals(96L * mb, computeTvStreamCacheLimitBytes(0L))
        assertEquals(96L * mb, computeTvStreamCacheLimitBytes(200L * mb))
        assertEquals(200L * mb, computeTvStreamCacheLimitBytes(1000L * mb))
        assertEquals(384L * mb, computeTvStreamCacheLimitBytes(10L * 1024L * mb))
    }

    @Test
    fun enforceStreamCacheBudgetEvictsOldestEntriesFirst() {
        val root = tempFolder.newFolder()
        val oldDir = File(root, "oldest").apply {
            mkdirs()
            File(this, "chunk.bin").writeBytes(ByteArray(40))
            setLastModified(1_000L)
        }
        val midDir = File(root, "middle").apply {
            mkdirs()
            File(this, "chunk.bin").writeBytes(ByteArray(40))
            setLastModified(2_000L)
        }
        val newDir = File(root, "newest").apply {
            mkdirs()
            File(this, "chunk.bin").writeBytes(ByteArray(40))
            setLastModified(3_000L)
        }

        enforceStreamCacheBudget(root, maxBytes = 80L)

        assertFalse(oldDir.exists())
        assertTrue(midDir.exists())
        assertTrue(newDir.exists())
    }

    @Test
    fun parsesQrStartAndPollResponses() {
        val started = NanamiTvQrAuthClient.parseStartResponse(
            """
            {
              "device": "tok-123",
              "shortCode": "ABCD-1234",
              "qrUrl": "https://maruchan.pages.dev/tv-auth?p=anilist&c=ABCD-1234",
              "expiresIn": 300
            }
            """.trimIndent()
        )
        assertNotNull(started)
        assertEquals("tok-123", started!!.deviceToken)
        assertEquals("ABCD-1234", started.shortCode)
        assertEquals(300, started.expiresInSeconds)

        assertNull(NanamiTvQrAuthClient.parseStartResponse("{}"))

        assertEquals(
            TvQrPollResult.Pending,
            NanamiTvQrAuthClient.parsePollResponse(200, """{"status":"pending"}""")
        )
        assertEquals(
            TvQrPollResult.Complete("secret-access-token"),
            NanamiTvQrAuthClient.parsePollResponse(200, """{"status":"complete","access_token":"secret-access-token"}""")
        )
        assertTrue(
            NanamiTvQrAuthClient.parsePollResponse(410, """{"error":"Expired"}""") is TvQrPollResult.Expired
        )
    }
}
