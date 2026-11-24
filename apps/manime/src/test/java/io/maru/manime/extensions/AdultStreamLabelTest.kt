package io.maru.manime.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdultStreamLabelTest {
    @Test fun requiresTheExactTitleEditionAndEpisode() {
        val titles = listOf("Example Series (OVA)", "サンプル")

        assertTrue(matches("[Group] Example Series (OVA) - 01v2 [1080p][HEVC].mkv", titles))
        assertTrue(matches("サンプル Episode 1", titles))
        assertFalse(matches("Example Series (TV) - 01", titles))
        assertFalse(matches("Example Series - 01", titles))
        assertFalse(matches("Example Series (OVA) Special - 01", titles))
        assertFalse(matches("Example Series (OVA) - 02", titles))
        assertFalse(matches("Example Series (OVA) - 01.5", titles))
        assertFalse(matches("Example Series (OVA) - 01 [Uncensored]", titles))
    }

    @Test fun verifiesBothSeasonAndEpisodeOrTheMappedAbsoluteNumber() {
        val titles = listOf("Example Series Season 2")

        assertTrue(matches("Example Series Season 2 S02E01", titles, season = 2, absoluteEpisode = 13))
        assertTrue(matches("Example Series Season 2 S01E13", titles, season = 2, absoluteEpisode = 13))
        assertFalse(matches("Example Series Season 2 S02E02", titles, season = 2, absoluteEpisode = 13))
        assertFalse(matches("Example Series Season 2 S01E01", titles, season = 2, absoluteEpisode = 13))
        assertFalse(matches("Example Series Season 2 S03E01", titles, season = 2, absoluteEpisode = 13))
    }

    @Test fun anUnnumberedLabelRequiresAConfirmedStandalone() {
        val titles = listOf("Example Film")

        assertFalse(matches("Example Film [1080p]", titles))
        assertTrue(matches("Example Film [1080p]", titles, standalone = true))
        assertFalse(matches("[OVA] Example Film", titles, standalone = true))
        assertFalse(matches("Example Film - 02", titles, standalone = true))
    }

    private fun matches(
        label: String,
        titles: List<String>,
        season: Int = 1,
        absoluteEpisode: Int? = null,
        standalone: Boolean = false
    ): Boolean = CrunchyrollStreamEngine.matchesAdultEpisodeLabel(
        label = label,
        candidateTitles = titles,
        episode = 1,
        expectedSeason = season,
        expectedAbsoluteEpisode = absoluteEpisode,
        isVerifiedStandalone = standalone
    )
}
