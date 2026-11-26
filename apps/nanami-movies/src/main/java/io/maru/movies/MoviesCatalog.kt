package io.maru.movies

import android.util.LruCache
import io.maru.manime.readResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class MovieKind(val addonType: String) {
    MOVIE("movie"),
    SERIES("series")
}

data class MovieTitle(
    val kind: MovieKind,
    val imdbId: String,
    val title: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val year: String? = null,
    val runtime: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val episodes: List<MovieEpisode> = emptyList()
)

data class MovieEpisode(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val thumbnail: String? = null,
    val overview: String? = null
)

class MoviesCatalog(private val client: OkHttpClient = OkHttpClient()) {
    private val animeMappings = LruCache<String, Boolean>(512)

    suspend fun popular(kind: MovieKind, skip: Int = 0, genre: String? = null): List<MovieTitle> {
        val extras = buildList {
            genre?.takeIf(String::isNotBlank)?.let { add("genre" to it) }
            if (skip > 0) add("skip" to skip.toString())
        }
        return catalog(kind, extras).mapParallel { item ->
            parse(kind, item, includeEpisodes = false)
        }.filterNotNull()
    }

    suspend fun search(query: String): List<MovieTitle> = coroutineScope {
        val term = query.trim().take(200).takeIf(String::isNotEmpty) ?: return@coroutineScope emptyList()
        MovieKind.entries.map { kind ->
            async {
                // Cinemeta's search results omit genres and countries. Confirm each match before display.
                catalog(kind, listOf("search" to term)).mapParallel { preview ->
                    val id = preview.imdbId() ?: return@mapParallel null
                    try {
                        metadata(kind, id)?.let { parse(kind, it, includeEpisodes = false) }
                    } catch (_: IOException) {
                        null
                    } catch (_: JSONException) {
                        null
                    }
                }.filterNotNull()
            }
        }.awaitAll().flatten()
    }

    suspend fun details(kind: MovieKind, imdbId: String): MovieTitle? {
        if (!IMDB_ID.matches(imdbId)) return null
        return metadata(kind, imdbId)?.let { parse(kind, it, includeEpisodes = true) }
    }

    private suspend fun catalog(kind: MovieKind, extras: List<Pair<String, String>>): List<JSONObject> {
        val url = CINEMETA.newBuilder()
            .addPathSegments("catalog/${kind.addonType}")
            .apply {
                if (extras.isEmpty()) addPathSegment("top.json")
                else {
                    val encoded = extras.joinToString("&") { (key, value) ->
                        val escaped = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                        "$key=$escaped"
                    }
                    addPathSegment("top").addEncodedPathSegment("$encoded.json")
                }
            }
            .build()
        return json(Request.Builder().url(url).build())?.optJSONArray("metas").objects()
    }

    private suspend fun metadata(kind: MovieKind, imdbId: String): JSONObject? {
        val url = CINEMETA.newBuilder()
            .addPathSegments("meta/${kind.addonType}/$imdbId.json")
            .build()
        return json(Request.Builder().url(url).build())?.optJSONObject("meta")
    }

    private suspend fun parse(kind: MovieKind, item: JSONObject, includeEpisodes: Boolean): MovieTitle? {
        val imdbId = item.imdbId() ?: return null
        val title = item.text("name") ?: return null
        if (item.text("type") != kind.addonType) return null
        val genres = (item.optJSONArray("genres") ?: item.optJSONArray("genre")).strings()
        if (genres.isEmpty()) return null
        val countries = item.text("country")?.split(',')?.map(String::trim).orEmpty()
        val animation = genres.any { it.equals("Animation", ignoreCase = true) }
        if (genres.any { it.equals("Anime", ignoreCase = true) }) return null
        if (animation && !includeAnimation(countries, animeLookup(imdbId, countries))) return null
        return MovieTitle(
            kind = kind,
            imdbId = imdbId,
            title = title,
            poster = item.art("poster"),
            backdrop = item.art("background"),
            year = item.text("releaseInfo") ?: item.text("year"),
            runtime = item.text("runtime"),
            overview = item.text("description"),
            genres = genres,
            episodes = if (includeEpisodes && kind == MovieKind.SERIES) episodes(item, imdbId) else emptyList()
        )
    }

    private fun episodes(item: JSONObject, imdbId: String): List<MovieEpisode> {
        return item.optJSONArray("videos").objects().mapNotNull { video ->
            val season = video.optInt("season", -1)
            val episode = video.optInt("episode", video.optInt("number", -1))
            if (season < 0 || episode < 1) return@mapNotNull null
            MovieEpisode(
                id = "$imdbId:$season:$episode",
                title = video.text("name") ?: video.text("title") ?: "Episode $episode",
                season = season,
                episode = episode,
                thumbnail = video.art("thumbnail"),
                overview = video.text("overview") ?: video.text("description")
            )
        }.distinctBy(MovieEpisode::id).sortedWith(compareBy(MovieEpisode::season, MovieEpisode::episode))
    }

    private suspend fun animeLookup(imdbId: String, countries: List<String>): Boolean? {
        if (countries.firstOrNull().isJapan()) return true
        animeMappings.get(imdbId)?.let { return it }
        val url = ANIME_INDEX.newBuilder().addQueryParameter("imdb_id", imdbId).build()
        val request = Request.Builder().url(url).build()
        val isAnime = try {
            client.newCall(request).apply { timeout().timeout(4, TimeUnit.SECONDS) }.readResponse {
                when {
                    it.code == 404 -> false
                    it.isSuccessful -> true
                    else -> null
                }
            }
        } catch (_: IOException) {
            null
        }
        if (isAnime != null) animeMappings.put(imdbId, isAnime)
        return isAnime
    }

    private suspend fun json(request: Request): JSONObject? =
        client.newCall(request).apply { timeout().timeout(12, TimeUnit.SECONDS) }.readResponse {
            if (it.code == 404) return@readResponse null
            if (!it.isSuccessful) throw IOException("Catalog request failed (${it.code}).")
            val body = it.body ?: throw IOException("Catalog response was empty.")
            JSONObject(body.string())
        }

    private suspend fun <T, R> List<T>.mapParallel(transform: suspend (T) -> R): List<R> = coroutineScope {
        chunked(12).flatMap { batch -> batch.map { async { transform(it) } }.awaitAll() }
    }

    private fun JSONObject.imdbId(): String? =
        (text("imdb_id") ?: text("id"))?.takeIf(IMDB_ID::matches)

    companion object {
        private val CINEMETA = "https://v3-cinemeta.strem.io".toHttpUrl()
        private val ANIME_INDEX = "https://api.ani.zip/mappings".toHttpUrl()
        private val IMDB_ID = Regex("tt[0-9]+")
    }
}

internal fun includeAnimation(countries: List<String>, animeLookup: Boolean?): Boolean {
    val primaryCountry = countries.firstOrNull()
    if (primaryCountry.isNullOrBlank() || primaryCountry.isJapan() || animeLookup == true) return false
    // A Japanese co-production can still be a western cartoon. Check its external classification.
    return animeLookup == false || countries.none { it.isJapan() }
}

private fun String?.isJapan(): Boolean =
    this.equals("Japan", ignoreCase = true) || this.equals("JP", ignoreCase = true)

private fun JSONObject.text(key: String): String? =
    (opt(key) as? String)?.trim()?.takeUnless { it.isEmpty() || it.equals("N/A", ignoreCase = true) }

private fun JSONObject.art(key: String): String? = text(key)?.takeIf { it.startsWith("https://") }

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull(::optJSONObject)

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { (opt(it) as? String)?.trim()?.takeIf(String::isNotEmpty) }
