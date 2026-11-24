package io.maru.manime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AniListTrackingAccountsTest {
    private val main = SavedAniListAccount(11, "Main", null, token = "main-token")
    private val adult = SavedAniListAccount(22, "Adult", null, token = "adult-token")

    @Test
    fun explicitAnimeUsesSelectedAccountWhileGuestModeStaysLocal() {
        val tracking = AniListTrackingAccounts(main, adult, listOf(main, adult))

        assertEquals(main, tracking.forTitle(isNsfw = false))
        assertEquals(adult, tracking.forTitle(isNsfw = true))
        assertEquals(main, AniListTrackingAccounts(active = main).forTitle(isNsfw = true))
        assertNull(AniListTrackingAccounts(nsfw = adult, saved = listOf(adult)).forTitle(isNsfw = true))
    }

    @Test
    fun queuedRemovalKeepsItsAccountAfterRouteAndCredentialsChange() {
        val updatedAccount = adult.copy(username = "Renamed", token = "new-token", scoreFormat = "POINT_10")
        val queued = LocalLibraryStore.parseQueueJson(
            """[{"mediaId":123,"title":"Example","accountUsername":"Adult","accountId":22,"isRemoval":true}]"""
        ).single()

        assertEquals(true, queued.isRemoval)
        assertEquals(updatedAccount, AniListTrackingAccounts(main, saved = listOf(updatedAccount)).forUpdate(queued))
        assertNull(AniListTrackingAccounts(main, saved = listOf(adult.copy(id = 33))).forUpdate(queued))
    }

    @Test
    fun legacyQueuedEditsKeepTheirOriginalUsernameScope() {
        val queued = LocalLibraryStore.parseQueueJson(
            """[{"mediaId":123,"title":"Example","accountUsername":"main","progress":2}]"""
        ).single()

        assertEquals(main, AniListTrackingAccounts(main, adult, listOf(main, adult)).forUpdate(queued))
        assertNull(AniListTrackingAccounts(adult, saved = listOf(main, adult)).forUpdate(queued))
    }
}
