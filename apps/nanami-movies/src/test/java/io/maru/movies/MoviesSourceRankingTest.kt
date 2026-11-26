package io.maru.movies

import io.maru.manime.extensions.StremioStreamResult
import org.junit.Assert.assertEquals
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

        assertEquals(listOf(direct, healthy, weak, large, unknown, empty),
            rankMovieSources(listOf(empty, large, weak, healthy, unknown, direct)))
    }

    private fun stream(title: String, url: String? = null) =
        StremioStreamResult(title = title, name = "Torrentio", url = url, infoHash = null)
}
