package io.maru.manime.extensions

import io.maru.manime.readResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal object HstreamResolver {
    const val SOURCE_NAME = "Hstream"
    private val site = "https://hstream.moe/".toHttpUrl()
    private val playbackEndpoint = site.resolve("player/api")!!
    private val videoHeaders = mapOf("Referer" to site.toString())
    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private val mediaHosts = setOf(
        "imoto-str.ane-h.xyz", "chibi-str.imoto-h.xyz", "koneko-str.musume-h.xyz",
        "shinobu-str.rorikon-h.xyz", "oppai-str.shoujo-h.org"
    )
    private val assetSegment = Regex("[A-Za-z0-9_-][A-Za-z0-9_.-]*")
    private val slugEpisodeRegex = Regex("^/hentai/([a-z0-9]+(?:-[a-z0-9]+)*)-(\\d{1,3})/?$", RegexOption.IGNORE_CASE)
    private val videoEpisodeSuffixRegex = Regex("^(.*?)\\s+Episode\\s+(\\d{1,3})$", RegexOption.IGNORE_CASE)
    private val cardEpisodeSuffix = Regex("\\s*(?:-|Episode)\\s*\\d{1,3}$", RegexOption.IGNORE_CASE)
    private val animationSuffix = Regex("\\s+the\\s+animation$", RegexOption.IGNORE_CASE)
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    private data class Series(val slug: String, val title: String, val episodes: Int)

    private val verifiedByAniListId = mapOf(
        10851 to Series("euphoria", "Euphoria", 6),
        6893 to Series("oni-chichi", "Oni Chichi", 2),
        9308 to Series("oni-chichi-s2", "Oni Chichi 2", 2)
    )
    private val discoveredSlugByAniListId = ConcurrentHashMap<Int, String>()

    fun supports(anilistId: Int, episode: Int, candidateTitles: List<String> = emptyList()): Boolean {
        if (episode <= 0) return false
        verifiedByAniListId[anilistId]?.let { return episode in 1..it.episodes }
        return discoveredSlugByAniListId.containsKey(anilistId) || candidateTitles.any { it.isNotBlank() }
    }

    suspend fun resolve(
        anilistId: Int,
        episode: Int,
        candidateTitles: List<String> = emptyList()
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        if (episode <= 0) return@withContext emptyList()

        val pinned = verifiedByAniListId[anilistId]
        if (pinned != null) {
            if (episode !in 1..pinned.episodes) return@withContext emptyList()
            return@withContext resolveEpisodeSlug(pinned.slug, episode, listOf(pinned.title))
        }

        if (candidateTitles.none { it.isNotBlank() }) return@withContext emptyList()

        discoveredSlugByAniListId[anilistId]?.let { cachedSlug ->
            val fromCached = resolveEpisodeSlug(cachedSlug, episode, candidateTitles)
            if (fromCached.isNotEmpty()) return@withContext fromCached
            discoveredSlugByAniListId.remove(anilistId, cachedSlug)
        }

        val slug = searchCandidateSlugs(candidateTitles, episode).singleOrNull() ?: return@withContext emptyList()
        val streams = resolveEpisodeSlug(slug, episode, candidateTitles)
        if (streams.isNotEmpty()) discoveredSlugByAniListId[anilistId] = slug
        streams
    }

    private suspend fun searchCandidateSlugs(candidateTitles: List<String>, targetEpisode: Int): List<String> =
        coroutineScope {
            buildSearchQueries(candidateTitles).take(4).map { query ->
                async { searchEpisodeSlugs(query, candidateTitles, targetEpisode) }
            }.awaitAll().flatten().distinct()
        }

    private suspend fun searchEpisodeSlugs(query: String, aliases: List<String>, episode: Int): List<String> {
        val url = site.newBuilder().addPathSegment("search").addQueryParameter("search", query).build()
        val request = Request.Builder().url(url).header("User-Agent", StremioAddonClient.BROWSER_UA).build()
        val html = try {
            client.fetch(request)
        } catch (_: IOException) {
            null
        } ?: return emptyList()
        return Jsoup.parse(html, site.toString()).select("a[href*='/hentai/']").mapNotNull { anchor ->
            val episodeUrl = site.resolve(anchor.attr("href")) ?: return@mapNotNull null
            if (episodeUrl.host != site.host || !episodeUrl.isHttps) return@mapNotNull null
            val match = slugEpisodeRegex.matchEntire(episodeUrl.encodedPath) ?: return@mapNotNull null
            if (match.groupValues[2].toIntOrNull() != episode) return@mapNotNull null
            val slug = match.groupValues[1].lowercase(Locale.ROOT)
            val imageLabel = anchor.selectFirst("img[alt]")?.attr("alt").orEmpty()
            val title = cardEpisodeSuffix.replace(imageLabel, "").trim()
            slug.takeIf { matchesHstreamSeriesTitle(title, aliases) || matchesHstreamSeriesTitle(slug, aliases) }
        }
    }

    internal fun buildSearchQueries(candidateTitles: List<String>): List<String> =
        candidateTitles.map { normalizeTitleWords(it).replace(animationSuffix, "").trim() }
            .filter { it.length >= 2 }
            .distinctBy { it.lowercase(Locale.ROOT) }

    internal fun matchesHstreamSeriesTitle(hstreamSeriesTitle: String, candidateTitles: List<String>): Boolean {
        val title = normalizeSeriesKey(hstreamSeriesTitle)
        return title.isNotEmpty() && candidateTitles.any { normalizeSeriesKey(it) == title }
    }

    private fun normalizeSeriesKey(title: String): String =
        normalizeTitleWords(title).replace(animationSuffix, "").replace(" ", "").lowercase(Locale.ROOT)

    private fun normalizeTitleWords(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKC)
            .replace('×', 'x')
            .replace('✕', 'x')
            .replace(separators, " ")
            .trim()

    internal fun validatedAssetPath(raw: String): String? =
        raw.trim('/').takeIf { path -> path.split('/').all(assetSegment::matches) }

    private suspend fun resolveEpisodeSlug(
        seriesSlug: String,
        episode: Int,
        candidateTitles: List<String>
    ): List<StreamLink> {
        val episodeUrl = site.resolve("hentai/$seriesSlug-$episode") ?: return emptyList()
        val cookieJar = EpisodeCookies()
        val session = client.newBuilder().cookieJar(cookieJar).build()
        return try {
            val html = session.fetch(Request.Builder().url(episodeUrl).header("User-Agent", StremioAddonClient.BROWSER_UA).build())
                ?: return emptyList()
            val document = Jsoup.parse(html, episodeUrl.toString())
            val exactEpisode = document.select("script[type='application/ld+json']").any { node ->
                val metadata = runCatching { JSONObject(node.data()) }.getOrNull() ?: return@any false
                if (metadata.optString("@type") != "VideoObject") return@any false
                if (metadata.optString("url").toHttpUrlOrNull() != episodeUrl) return@any false
                val videoName = metadata.optString("name").trim()
                val match = videoEpisodeSuffixRegex.matchEntire(videoName) ?: return@any false
                val seriesName = match.groupValues[1].trim()
                val epNum = match.groupValues[2].toIntOrNull() ?: return@any false
                epNum == episode && matchesHstreamSeriesTitle(seriesName, candidateTitles)
            }
            if (!exactEpisode) return emptyList()
            val episodeId = document.selectFirst("input#e_id")?.attr("value")?.toLongOrNull()
                ?: return emptyList()
            val csrf = cookieJar.loadForRequest(playbackEndpoint).firstOrNull { it.name == "XSRF-TOKEN" }
                ?: return emptyList()
            val request = Request.Builder().url(playbackEndpoint)
                .header("User-Agent", StremioAddonClient.BROWSER_UA)
                .header("Origin", "https://hstream.moe")
                .header("Referer", episodeUrl.toString())
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-XSRF-TOKEN", URLDecoder.decode(csrf.value, "UTF-8"))
                .post(JSONObject().put("episode_id", episodeId.toString()).toString().toRequestBody("application/json".toMediaType()))
                .build()
            val payload = session.fetch(request)?.let(::JSONObject) ?: return emptyList()
            val path = validatedAssetPath(payload.optString("stream_url")) ?: return emptyList()
            val domains = payload.optJSONArray("stream_domains") ?: return emptyList()
            (0 until domains.length()).mapNotNull { index ->
                val host = domains.optString(index).toHttpUrlOrNull() ?: return@mapNotNull null
                if (!host.isHttps || host.port != 443 || host.host !in mediaHosts || host.encodedPath != "/" ||
                    host.username.isNotEmpty() || host.password.isNotEmpty() || host.query != null || host.fragment != null
                ) return@mapNotNull null
                val base = host.newBuilder().addPathSegments(path).build()
                val streamPath = if (payload.optInt("legacy") != 0) "x264.720p.mp4" else "720/manifest.mpd"
                StreamLink(
                    url = base.newBuilder().addPathSegments(streamPath).build().toString(),
                    quality = "720p",
                    sourceName = SOURCE_NAME,
                    methodType = "DIRECT",
                    headers = videoHeaders,
                    subtitles = listOf(SubtitleTrack(base.newBuilder().addPathSegment("eng.ass").build().toString(), "en")),
                    score = 780,
                    verifiedAdultEpisode = true
                )
            }.distinctBy { it.url }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            emptyList()
        } catch (_: org.json.JSONException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private suspend fun OkHttpClient.fetch(request: Request): String? = newCall(request).readResponse { response ->
        if (response.isSuccessful) response.body?.string() else null
    }

    private class EpisodeCookies : CookieJar {
        private val cookies = linkedMapOf<String, Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { this.cookies[it.name] = it }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.values.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
    }
}
