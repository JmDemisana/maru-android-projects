@file:JvmName("MainActivityKt")
package com.lagradost.cloudstream3

import android.content.Context
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile

// This file compiles into com.lagradost.cloudstream3.MainActivityKt for Cloudstream 3 plugin compatibility

@JvmName("fixUrl")
fun MainAPI.mainActivityFixUrl(url: String): String {
    if (url.startsWith("//")) {
        return "https:$url"
    }
    if (url.startsWith("/")) {
        return "${this.mainUrl.trimEnd('/')}$url"
    }
    return url
}

@JvmName("fixUrlNull")
fun MainAPI.mainActivityFixUrlNull(url: String?): String? {
    if (url == null) return null
    return mainActivityFixUrl(url)
}

@JvmName("mainPageOf")
fun mainActivityMainPageOf(vararg elements: Pair<String, String>): List<MainPageData> {
    return elements.map { MainPageData(it.second, it.first) }
}

@JvmName("newAnimeSearchResponse")
fun MainAPI.mainActivityNewAnimeSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Anime,
    fix: Boolean = true,
    builder: AnimeSearchResponse.() -> Unit = {}
): AnimeSearchResponse {
    val fixedUrl = if (fix) mainActivityFixUrl(url) else url
    return AnimeSearchResponse(
        name = name,
        url = fixedUrl,
        apiName = this.name,
        type = type
    ).apply(builder)
}

@JvmName("newMovieSearchResponse")
fun MainAPI.mainActivityNewMovieSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.Movie,
    fix: Boolean = true,
    builder: MovieSearchResponse.() -> Unit = {}
): MovieSearchResponse {
    val fixedUrl = if (fix) mainActivityFixUrl(url) else url
    return MovieSearchResponse(
        name = name,
        url = fixedUrl,
        apiName = this.name,
        type = type
    ).apply(builder)
}

@JvmName("newTvSeriesSearchResponse")
fun MainAPI.mainActivityNewTvSeriesSearchResponse(
    name: String,
    url: String,
    type: TvType = TvType.TvSeries,
    fix: Boolean = true,
    builder: TvSeriesSearchResponse.() -> Unit = {}
): TvSeriesSearchResponse {
    val fixedUrl = if (fix) mainActivityFixUrl(url) else url
    return TvSeriesSearchResponse(
        name = name,
        url = fixedUrl,
        apiName = this.name,
        type = type
    ).apply(builder)
}

@JvmName("newAnimeLoadResponse")
fun MainAPI.mainActivityNewAnimeLoadResponse(
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

@JvmName("newMovieLoadResponse")
fun MainAPI.mainActivityNewMovieLoadResponse(
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

@JvmName("newTvSeriesLoadResponse")
fun MainAPI.mainActivityNewTvSeriesLoadResponse(
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
