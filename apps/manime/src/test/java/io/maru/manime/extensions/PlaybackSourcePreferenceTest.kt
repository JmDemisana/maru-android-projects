package io.maru.manime.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSourcePreferenceTest {
    @Test fun replayPrefersTheExactFileInAPack() {
        val selected = torrent("pack", file = 2)
        val otherFile = selected.copy(fileIdx = 1)
        val preference = PlaybackSourcePreference.from(selected, "SUB")

        assertNull(preference.pick(listOf(otherFile), partial = true))
        assertEquals(selected, preference.pick(listOf(otherFile, selected), partial = true))
    }

    @Test fun laterEpisodesPreferThePackThenReleaseGroupAndQuality() {
        val selected = torrent("pack", file = 2)
        val samePack = selected.copy(fileIdx = 3, filename = null)
        val sameGroup = torrent("another-pack", file = 3)
        val lowerQuality = sameGroup.copy(infoHash = "lower-quality", quality = "720p")
        val otherGroup = sameGroup.copy(infoHash = "other-group", filename = "[Other Group] Series - 03.mkv")
        val preference = checkNotNull(PlaybackSourcePreference.from(selected, "SUB").forSeries())

        assertEquals(samePack, preference.pick(listOf(otherGroup, sameGroup, samePack)))
        assertEquals(sameGroup, preference.pick(listOf(otherGroup, lowerQuality, sameGroup), partial = true))
        assertNull(preference.pick(listOf(lowerQuality), partial = true))
        assertEquals(lowerQuality, preference.pick(listOf(otherGroup, lowerQuality)))
    }

    @Test fun deadTorrentsAndDifferentAudioAreSkipped() {
        val selected = torrent("pack", file = 2).copy(audioType = "DUAL_AUDIO")
        val unseeded = selected.copy(seeders = 0)
        val dubOnly = torrent("dub", file = 3).copy(audioType = "DUB")
        val subtitled = torrent("sub", file = 3)
        val genericTag = selected.copy(filename = "[1080p] Series")
        val preference = PlaybackSourcePreference.from(selected, "SUB")

        assertNull(preference.pick(listOf(unseeded, dubOnly)))
        assertEquals(subtitled, preference.forSeries()?.pick(listOf(dubOnly, subtitled)))
        assertNull(PlaybackSourcePreference.from(genericTag, "SUB").family)
    }

    @Test fun rotatingHttpLinksCanMatchTheHostWithoutStoringTheUrl() {
        val selected = StreamLink(
            url = "https://media.example/episode-one.mp4?token=private-value",
            quality = "720p",
            sourceName = "HTTP source"
        )
        val refreshed = selected.copy(url = "https://media.example/episode-one.mp4?token=new-value")
        val otherHost = selected.copy(url = "https://other.example/episode-one.mp4")
        val preference = PlaybackSourcePreference.from(selected, "SUB")
        val saved = preference.encode()

        assertFalse(saved.contains("private-value"))
        assertEquals(preference, PlaybackSourcePreference.decode(saved))
        assertEquals(refreshed, preference.pick(listOf(otherHost, refreshed)))
    }

    private fun torrent(hash: String, file: Int) = StreamLink(
        url = "magnet:?xt=urn:btih:$hash",
        quality = "1080p",
        sourceName = "Torrent source",
        isTorrent = true,
        infoHash = hash,
        fileIdx = file,
        seeders = 12,
        filename = "[Release Group] Series - $file.mkv"
    )
}
