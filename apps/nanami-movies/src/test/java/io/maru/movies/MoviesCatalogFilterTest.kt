package io.maru.movies

import io.maru.manime.extensions.StremioStreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoviesCatalogFilterTest {
    @Test
    fun keepsNonAnimeCartoons() {
        assertTrue(includeAnimation(listOf("Australia", "United Kingdom", "United States"), false))
        assertTrue(includeAnimation(listOf("United States", "South Korea"), false))
        assertTrue(includeAnimation(listOf("United States", "Japan"), false))
    }

    @Test
    fun hidesAnimeWithJapaneseOriginOrAnAnimeDatabaseMatch() {
        assertFalse(includeAnimation(listOf("Japan", "United States"), false))
        assertFalse(includeAnimation(listOf("United Kingdom", "United States", "Japan"), true))
        assertFalse(includeAnimation(listOf("United States"), true))
    }

    @Test
    fun holdsBackUnverifiedAnimationWhenLookupIsUnavailable() {
        assertFalse(includeAnimation(listOf("United States"), null))
        assertFalse(includeAnimation(listOf("United States", "Japan"), null))
        assertFalse(includeAnimation(emptyList(), null))
    }

    @Test
    fun distinguishesAgeCertificatesFromViewerRatings() {
        assertTrue(isMatureCertification("NC-17"))
        assertTrue(isMatureCertification("tv-ma"))
        assertFalse(isMatureCertification("PG-13"))
        assertFalse(isMatureCertification("9.3"))
        assertFalse(isMatureCertification(null))
    }

    @Test
    fun englishAudioSelectionRequiresAudioRatherThanSubtitles() {
        fun source(label: String) = StremioStreamResult(title = label, name = null, url = null, infoHash = null)

        assertTrue(source("Dual audio: English + Spanish").listsEnglishAudio())
        assertTrue(source("Title.2024.English.Dub.1080p").listsEnglishAudio())
        assertFalse(source("Korean audio + English subtitles").listsEnglishAudio())
        assertFalse(source("English SDH").listsEnglishAudio())
    }

    @Test
    fun decodesHtmlEntitiesAndCleansOngoingReleaseYears() {
        assertEquals("Grant O'Brien & Sam Reich", "Grant O&apos;Brien &amp; Sam Reich".decodeHtmlEntities())
        assertEquals("Spider-Man: \"Homecoming\"", "Spider-Man: &#34;Homecoming&#34;".decodeHtmlEntities())
        assertEquals("2019", "2019–".cleanReleaseInfo())
        assertEquals("2019–2023", "2019–2023".cleanReleaseInfo())
    }
}
