package io.maru.movies

import android.util.LruCache
import io.maru.manime.extensions.StremioAddonClient
import io.maru.manime.extensions.StremioStreamResult
import io.maru.manime.readResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
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
    val episodes: List<MovieEpisode> = emptyList(),
    val logo: String? = null,
    val rating: Double? = null,
    val certification: String? = null,
    val explicitlyAdult: Boolean = false,
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val trailerId: String? = null,
    val status: String? = null,
    val releasedAt: Instant? = null,
) {
    val isMature: Boolean get() = isMatureCertification(certification) || explicitlyAdult
}

data class MovieEpisode(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val thumbnail: String? = null,
    val overview: String? = null,
    val releasedAt: Instant? = null,
    val rating: Double? = null,
)

internal enum class MovieFeed(val catalogId: String) {
    POPULAR("top"), NEW("year"), TOP_RATED("imdbRating")
}

internal data class MoviePage(val titles: List<MovieTitle>, val nextOffset: Int?)

class MoviesCatalog(private val client: OkHttpClient = OkHttpClient()) {
    private val animeMappings = LruCache<String, Boolean>(512)
    private val englishAudio = LruCache<String, Boolean>(128)
    private val torrentio = StremioAddonClient("https://torrentio.strem.fun", client)
    private val responses = object : LruCache<HttpUrl, CatalogResponse>(6 * 1024 * 1024) {
        override fun sizeOf(key: HttpUrl, value: CatalogResponse): Int = value.size
    }

    private data class CatalogResponse(val data: JSONObject, val size: Int, val loadedAt: Long)

    suspend fun popular(kind: MovieKind, skip: Int = 0, genre: String? = null): List<MovieTitle> =
        discover(kind, MovieFeed.POPULAR, skip, genre).titles

    internal suspend fun discover(kind: MovieKind, feed: MovieFeed, skip: Int = 0, genre: String? = null): MoviePage {
        val extras = buildList {
            val category = if (feed == MovieFeed.NEW) LocalDate.now().year.toString() else genre
            category?.takeIf(String::isNotBlank)?.let { add("genre" to it) }
            if (skip > 0) add("skip" to skip.toString())
        }
        val raw = catalog(kind, extras, feed)
        val titles = raw.mapParallel { item ->
            parse(kind, item, includeEpisodes = false)
        }.filterNotNull().distinctBy(MovieTitle::imdbId)
        // A provider page can contain filtered items, so advance by the provider's page size.
        val nextOffset = (skip + 50).takeIf { raw.isNotEmpty() }
        return MoviePage(titles, nextOffset)
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

    internal suspend fun hasListedEnglishAudio(title: MovieTitle): Boolean {
        val first = if (title.kind == MovieKind.SERIES) {
            val episodes = details(title.kind, title.imdbId)?.episodes.orEmpty()
            episodes.firstOrNull { it.season > 0 && it.releasedAt?.isAfter(Instant.now()) != true } ?: return false
        } else null
        val streamId = first?.id ?: title.imdbId
        englishAudio.get(streamId)?.let { return it }
        val available = torrentio.getStreams(title.kind.addonType, streamId).any(StremioStreamResult::listsEnglishAudio)
        englishAudio.put(streamId, available)
        return available
    }

    private suspend fun catalog(
        kind: MovieKind,
        extras: List<Pair<String, String>>,
        feed: MovieFeed = MovieFeed.POPULAR,
    ): List<JSONObject> {
        val url = CINEMETA.newBuilder()
            .addPathSegments("catalog/${kind.addonType}")
            .apply {
                if (extras.isEmpty()) addPathSegment("${feed.catalogId}.json")
                else {
                    val encoded = extras.joinToString("&") { (key, value) ->
                        val escaped = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                        "$key=$escaped"
                    }
                    addPathSegment(feed.catalogId).addEncodedPathSegment("$encoded.json")
                }
            }
            .build()
        return json(cinemetaRequest(url))?.optJSONArray("metas").objects()
    }

    private suspend fun metadata(kind: MovieKind, imdbId: String): JSONObject? {
        val url = CINEMETA.newBuilder()
            .addPathSegments("meta/${kind.addonType}/$imdbId.json")
            .build()
        return json(cinemetaRequest(url))?.optJSONObject("meta")
    }

    private fun cinemetaRequest(url: HttpUrl): Request = Request.Builder().url(url)
        .header("User-Agent", StremioAddonClient.BROWSER_UA)
        .header("Accept", "application/json")
        .build()

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
            poster = item.art("poster")?.upgradePosterResolution(),
            backdrop = item.art("background")?.upgradeBackdropResolution(),
            year = (item.text("releaseInfo") ?: item.text("year"))?.cleanReleaseInfo(),
            runtime = item.text("runtime"),
            overview = item.text("description"),
            genres = genres,
            episodes = if (includeEpisodes && kind == MovieKind.SERIES) episodes(item, imdbId) else emptyList(),
            logo = item.art("logo"),
            rating = item.rating("imdbRating"),
            certification = item.certification(),
            explicitlyAdult = item.optBoolean("adult") || item.optBoolean("isAdult") ||
                genres.any { it.equals("Adult", ignoreCase = true) },
            cast = item.optJSONArray("cast").strings(),
            directors = item.optJSONArray("director").strings(),
            writers = item.optJSONArray("writer").strings(),
            trailerId = item.optJSONArray("trailers").objects()
                .filter { trailer -> trailer.text("type").equals("Trailer", ignoreCase = true) }
                .firstNotNullOfOrNull { trailer -> trailer.text("source")?.takeIf(YOUTUBE_ID::matches) },
            status = item.text("status"),
            releasedAt = item.instant("released"),
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
                overview = video.text("overview") ?: video.text("description"),
                releasedAt = video.instant("released") ?: video.instant("firstAired"),
                rating = video.rating("rating")?.takeIf { it > 0 },
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

    private suspend fun json(request: Request): JSONObject? {
        val url = request.url
        responses.get(url)?.takeIf {
            System.nanoTime() - it.loadedAt < TimeUnit.MINUTES.toNanos(3)
        }?.let { return it.data }

        return client.newCall(request).apply { timeout().timeout(12, TimeUnit.SECONDS) }.readResponse {
            if (it.code == 404) return@readResponse null
            if (!it.isSuccessful) throw IOException("Catalog request failed (${it.code}).")
            val body = it.body?.string() ?: throw IOException("Catalog response was empty.")
            val data = JSONObject(body)
            responses.put(url, CatalogResponse(data, body.length * 3, System.nanoTime()))
            data
        }
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
        private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}

internal fun includeAnimation(countries: List<String>, animeLookup: Boolean?): Boolean {
    val primaryCountry = countries.firstOrNull()
    // The origin check catches unmapped Japanese anime. A successful lookup can clear co-productions.
    return !primaryCountry.isNullOrBlank() && !primaryCountry.isJapan() && animeLookup == false
}

private fun String?.isJapan(): Boolean =
    this.equals("Japan", ignoreCase = true) || this.equals("JP", ignoreCase = true)

private fun JSONObject.text(key: String): String? =
    (opt(key) as? String)?.decodeHtmlEntities()?.trim()?.takeUnless { it.isEmpty() || it.equals("N/A", ignoreCase = true) }

private fun JSONObject.art(key: String): String? =
    (opt(key) as? String)?.trim()?.takeIf { it.startsWith("https://") }

internal fun String.cleanReleaseInfo(): String =
    trim().trimEnd('–', '—', '-', ' ').ifEmpty { trim() }

private val numericEntityRegex = Regex("&#(x?[0-9a-fA-F]+);")

internal fun String.decodeHtmlEntities(): String {
    if (!contains('&')) return this
    val namedDecoded = replace("&apos;", "'")
        .replace("&rsquo;", "’")
        .replace("&lsquo;", "‘")
        .replace("&quot;", "\"")
        .replace("&ldquo;", "“")
        .replace("&rdquo;", "”")
        .replace("&ndash;", "–")
        .replace("&mdash;", "—")
        .replace("&hellip;", "…")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
    val numericDecoded = numericEntityRegex.replace(namedDecoded) { match ->
        val raw = match.groupValues[1]
        val codePoint = if (raw.startsWith("x", ignoreCase = true)) {
            raw.drop(1).toIntOrNull(16)
        } else {
            raw.toIntOrNull(10)
        }
        if (codePoint != null && Character.isValidCodePoint(codePoint)) {
            String(Character.toChars(codePoint))
        } else {
            match.value
        }
    }
    return numericDecoded.replace("&amp;", "&")
}

private fun String.upgradePosterResolution(): String =
    replace("images.metahub.space/poster/small/", "images.metahub.space/poster/medium/")

private fun String.upgradeBackdropResolution(): String =
    replace("images.metahub.space/background/small/", "images.metahub.space/background/large/")
        .replace("images.metahub.space/background/medium/", "images.metahub.space/background/large/")

private fun JSONObject.instant(key: String): Instant? = text(key)?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun JSONObject.rating(key: String): Double? = opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it in 0.0..10.0 }

private fun JSONObject.certification(): String? = sequenceOf("certification", "contentRating", "ageRating", "rated")
    .mapNotNull(::text)
    .map { it.uppercase(Locale.ROOT) }
    .firstOrNull { it in CERTIFICATIONS }

internal fun isMatureCertification(value: String?): Boolean = value?.uppercase(Locale.ROOT) in MATURE_CERTIFICATIONS

private val MATURE_CERTIFICATIONS = setOf("R", "NC-17", "TV-MA", "X", "18", "18+", "R18", "R18+", "M18", "R-18")
private val CERTIFICATIONS = MATURE_CERTIFICATIONS + setOf(
    "G", "PG", "PG-13", "TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG", "TV-14", "12", "12A", "13", "15", "16", "16+",
)

private val explicitEnglishAudio = Regex(
    """(?i)(?:\b(?:english|eng)[ ._-]*(?:dub(?:bed)?|audio)\b|\b(?:dubbed\s+in|audio\s*[:=])\s*(?:english|eng)\b)"""
)

internal fun StremioStreamResult.listsEnglishAudio(): Boolean =
    listOfNotNull(title, filename).any(explicitEnglishAudio::containsMatchIn)

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else
    (0 until length()).mapNotNull(::optJSONObject)

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else
    (0 until length()).mapNotNull { (opt(it) as? String)?.decodeHtmlEntities()?.trim()?.takeIf(String::isNotEmpty) }
