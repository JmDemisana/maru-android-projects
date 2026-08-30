@file:JvmName("MainAPIKt")
package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile

enum class TvType {
    Movie,
    TvSeries,
    Anime,
    AnimeMovie,
    OVA,
    Cartoon,
    Documentary,
    LiveStream,
    NSFW,
    Others,
    AsianDrama,
    Torrent,
    Music,
    Audio,
    AudioBook,
    Podcast,
    Custom
}

enum class VPNStatus {
    None,
    Torrent,
    MightBeNeeded,
    Custom
}

enum class ProviderType {
    All,
    Anime,
    Movie,
    TvSeries,
    Cartoon,
    Documentary,
    Custom
}

enum class DubStatus {
    None,
    Subbed,
    Dubbed
}

enum class SearchQuality {
    FourK,
    HD,
    SD,
    HQ,
    CAM,
    TeleSync,
    TeleCine,
    WorkPrint,
    DVD,
    Bluray
}

data class MainPageData(
    val name: String,
    val data: String,
    val horizontalImages: Boolean = false
)

interface SearchResponse {
    val name: String
    val url: String
    val apiName: String
    val type: TvType?
    val posterUrl: String?
    val id: Int?
    val quality: SearchQuality?
    val posterHeaders: Map<String, String>?
}

data class AnimeSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.Anime,
    override var posterUrl: String? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    var dubStatus: DubStatus? = null,
    var otherNames: List<String>? = null,
    var latestEpisodeNumber: Int? = null,
    var episodeCount: Int? = null
) : SearchResponse

data class MovieSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.Movie,
    override var posterUrl: String? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    var year: Int? = null
) : SearchResponse

data class TvSeriesSearchResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType? = TvType.TvSeries,
    override var posterUrl: String? = null,
    override var id: Int? = null,
    override var quality: SearchQuality? = null,
    override var posterHeaders: Map<String, String>? = null,
    var episodes: Int? = null
) : SearchResponse

interface LoadResponse {
    val name: String
    val url: String
    val apiName: String
    val type: TvType
    val posterUrl: String?
    val syncData: Map<String, String>
    val plot: String?
    val tags: List<String>?
    val rating: Int?
    val year: Int?
}

data class Episode(
    var data: String,
    var name: String? = null,
    var season: Int? = null,
    var episode: Int? = null,
    var posterUrl: String? = null,
    var rating: Int? = null,
    var description: String? = null,
    var date: String? = null
)

data class AnimeLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType = TvType.Anime,
    override var posterUrl: String? = null,
    override var syncData: Map<String, String> = emptyMap(),
    override var plot: String? = null,
    override var tags: List<String>? = null,
    override var rating: Int? = null,
    override var year: Int? = null,
    var episodes: MutableMap<DubStatus, List<Episode>> = mutableMapOf(),
    var japanTitle: String? = null,
    var englishTitle: String? = null
) : LoadResponse

data class MovieLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType = TvType.Movie,
    override var posterUrl: String? = null,
    override var syncData: Map<String, String> = emptyMap(),
    override var plot: String? = null,
    override var tags: List<String>? = null,
    override var rating: Int? = null,
    override var year: Int? = null,
    var dataUrl: String = url
) : LoadResponse

data class TvSeriesLoadResponse(
    override var name: String,
    override var url: String,
    override var apiName: String,
    override var type: TvType = TvType.TvSeries,
    override var posterUrl: String? = null,
    override var syncData: Map<String, String> = emptyMap(),
    override var plot: String? = null,
    override var tags: List<String>? = null,
    override var rating: Int? = null,
    override var year: Int? = null,
    var episodes: MutableList<Episode> = mutableListOf()
) : LoadResponse

abstract class MainAPI {
    open var name: String = "Unnamed Provider"
    open var mainUrl: String = ""
    open var lang: String = "en"
    open val supportedTypes: Set<TvType> = setOf(TvType.Anime, TvType.Movie, TvType.TvSeries)
    open var hasMainPage: Boolean = false
    open var hasQuickSearch: Boolean = false
    open var hasChromecastSupport: Boolean = true
    open var hasDownloadSupport: Boolean = true
    open var usesWebView: Boolean = false
    open var vpnStatus: VPNStatus = VPNStatus.None
    open var providerType: ProviderType = ProviderType.All
    open var mainPage: List<MainPageData> = emptyList()

    open suspend fun search(query: String): List<SearchResponse> = emptyList()
    open suspend fun load(url: String): LoadResponse? = null
    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false

    open suspend fun loadLinks(
        data: String,
        isCasting: Boolean = false,
        callback: (ExtractorLink) -> Unit
    ): Boolean = loadLinks(data, isCasting, {}, callback)
}

fun mainPageOf(vararg elements: Pair<String, String>): List<MainPageData> {
    return elements.map { MainPageData(it.second, it.first) }
}

fun MainAPI.newAnimeSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    fix: Boolean = true,
    builder: AnimeSearchResponse.() -> Unit = {}
): AnimeSearchResponse {
    return AnimeSearchResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type
    ).apply(builder)
}

fun MainAPI.newMovieSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    fix: Boolean = true,
    builder: MovieSearchResponse.() -> Unit = {}
): MovieSearchResponse {
    return MovieSearchResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type
    ).apply(builder)
}

fun MainAPI.newTvSeriesSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    fix: Boolean = true,
    builder: TvSeriesSearchResponse.() -> Unit = {}
): TvSeriesSearchResponse {
    return TvSeriesSearchResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type
    ).apply(builder)
}

fun MainAPI.newAnimeLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    builder: AnimeLoadResponse.() -> Unit = {}
): AnimeLoadResponse {
    return AnimeLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type
    ).apply(builder)
}

fun MainAPI.newMovieLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    dataUrl: String = url,
    builder: MovieLoadResponse.() -> Unit = {}
): MovieLoadResponse {
    return MovieLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type,
        dataUrl = dataUrl
    ).apply(builder)
}

fun MainAPI.newTvSeriesLoadResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    builder: TvSeriesLoadResponse.() -> Unit = {}
): TvSeriesLoadResponse {
    return TvSeriesLoadResponse(
        name = name,
        url = url,
        apiName = this.name,
        type = type
    ).apply(builder)
}
