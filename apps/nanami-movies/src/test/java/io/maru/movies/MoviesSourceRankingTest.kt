package io.maru.movies

import io.maru.manime.extensions.StremioStreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoviesSourceRankingTest {
    @Test
    fun ranksHealthyHdAheadOfOversizedOrUnseededTorrentioResults() {
        val empty = stream("2160p 👤 0")
        val large = stream("2160p 👤 50")
        val weak = stream("720p 👤 2")
        val healthy = stream("1080p 👤 42")
        val unknown = stream("1080p")
        val direct = stream("Direct", "https://example.com/video.mp4")

        assertEquals(
            listOf(direct, healthy, weak, large, unknown, empty),
            rankMovieSources(listOf(empty, large, weak, healthy, unknown, direct)),
        )
    }

    @Test
    fun honorsPreferred4kQualityWhenSeeded() {
        val uhd = stream("2160p 👤 50")
        val hd = stream("1080p 👤 90")
        val sd = stream("480p 👤 120")

        assertEquals(
            listOf(uhd, hd, sd),
            rankMovieSources(listOf(sd, hd, uhd), preferredQuality = "4K"),
        )
    }

    @Test
    fun pkceChallengeMatchesRfc7636S256Vector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", computePkceChallengeS256(verifier))
        val randomVerifier = generatePkceVerifier()
        assertTrue(randomVerifier.length in 43..128)
        assertFalse(randomVerifier.contains('='))
    }

    @Test
    fun derivesNetflixAdvisoryFromCertificationAndGenres() {
        val matureThriller = MovieTitle(
            kind = MovieKind.SERIES,
            imdbId = "tt0903747",
            title = "Sample Thriller",
            certification = "TV-MA",
            genres = listOf("Crime", "Drama", "Thriller"),
        )
        val advisory = deriveMovieAdvisory(matureThriller)
        assertEquals("TV-MA", advisory.ratingBadge)
        assertTrue(advisory.isMature)
        assertTrue("Violence" in advisory.warnings)
    }

    private fun stream(title: String, url: String? = null) =
        StremioStreamResult(title = title, name = "Torrentio", url = url, infoHash = null)
}
