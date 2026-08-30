package com.lagradost.cloudstream3.syncproviders.providers

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncSearchResult
import io.maru.manime.AniListClient
import io.maru.manime.AnimeMedia

open class AniListApi : SyncAPI {
    override val name = "AniList"
    override val idPrefix = "anilist"

    override suspend fun search(query: String): List<SyncSearchResult>? {
        return try {
            val searchPage = AniListClient.search(query, page = 1, perPage = 10)
            searchPage.results.map { media: AnimeMedia ->
                SyncSearchResult(
                    name = media.titleEnglish ?: media.titleRomaji ?: media.title,
                    apiName = name,
                    syncId = media.mediaId.toString(),
                    url = media.siteUrl ?: "https://anilist.co/anime/${media.mediaId}",
                    posterUrl = media.coverUrl,
                    type = TvType.Anime
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

open class MALApi : SyncAPI {
    override val name = "MAL"
    override val idPrefix = "mal"
}

open class KitsuApi : SyncAPI {
    override val name = "Kitsu"
    override val idPrefix = "kitsu"
}

open class OpenSubtitlesApi : SyncAPI {
    override val name = "OpenSubtitles"
    override val idPrefix = "opensubtitles"
}
