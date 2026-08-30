package eu.kanade.tachiyomi.animesource.online

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

abstract class AnimeHttpSource : AnimeSource {
    abstract val baseUrl: String
    override val lang: String get() = "en"
    open val network: NetworkHelper = NetworkHelper()
    open val client: OkHttpClient get() = network.client
    open val headers: Headers = Headers.Builder().build()
    open val supportsLatest: Boolean = true

    open fun popularAnimeRequest(page: Int): Request = Request.Builder().url(baseUrl).build()
    open fun popularAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    open fun latestUpdatesRequest(page: Int): Request = Request.Builder().url(baseUrl).build()
    open fun latestUpdatesParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    open fun searchAnimeRequest(page: Int, query: String, filters: Any? = null): Request = Request.Builder().url(baseUrl).build()
    open fun searchAnimeParse(response: Response): AnimesPage = AnimesPage(emptyList(), false)

    open fun animeDetailsParse(response: Response): SAnime = SAnime.create()
    open fun episodeListParse(response: Response): List<SEpisode> = emptyList()
    open fun videoListParse(response: Response): List<Video> = emptyList()
}
