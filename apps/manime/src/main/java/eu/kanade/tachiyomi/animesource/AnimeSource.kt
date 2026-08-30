package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video

interface AnimeSource {
    val id: Long
    val name: String
    val lang: String

    suspend fun getPopularAnime(page: Int): List<SAnime> = emptyList()
    suspend fun getLatestUpdates(page: Int): List<SAnime> = emptyList()
    suspend fun getSearchAnime(page: Int, query: String): List<SAnime> = emptyList()
    suspend fun getAnimeDetails(anime: SAnime): SAnime = anime
    suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = emptyList()
    suspend fun getVideoList(episode: SEpisode): List<Video> = emptyList()
}
