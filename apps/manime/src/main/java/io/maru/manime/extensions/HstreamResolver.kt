package io.maru.manime.extensions

import io.maru.manime.readResponse
import kotlinx.coroutines.Dispatchers
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
    private val assetPath = Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")

    private data class Series(val slug: String, val title: String, val episodes: Int)

    // This catalog does not expose AniList IDs. Add bindings only after checking the release.
    private val seriesByAniListId = mapOf(10851 to Series("euphoria", "Euphoria", 6))

    fun supports(anilistId: Int, episode: Int): Boolean =
        seriesByAniListId[anilistId]?.let { episode in 1..it.episodes } == true

    suspend fun resolve(anilistId: Int, episode: Int): List<StreamLink> = withContext(Dispatchers.IO) {
        val series = seriesByAniListId[anilistId] ?: return@withContext emptyList()
        if (episode !in 1..series.episodes) return@withContext emptyList()

        val episodeUrl = site.resolve("hentai/${series.slug}-$episode") ?: return@withContext emptyList()
        val cookieJar = EpisodeCookies()
        val session = client.newBuilder().cookieJar(cookieJar).build()
        try {
            val html = session.fetch(Request.Builder().url(episodeUrl).build()) ?: return@withContext emptyList()
            val document = Jsoup.parse(html, episodeUrl.toString())
            val expectedTitle = "${series.title} Episode $episode"
            val exactEpisode = document.select("script[type='application/ld+json']").any { node ->
                val metadata = runCatching { JSONObject(node.data()) }.getOrNull()
                metadata != null && metadata.optString("@type") == "VideoObject" &&
                    metadata.optString("name").equals(expectedTitle, ignoreCase = true) &&
                    metadata.optString("url").toHttpUrlOrNull() == episodeUrl
            }
            if (!exactEpisode) return@withContext emptyList()
            val episodeId = document.selectFirst("input#e_id")?.attr("value")?.toLongOrNull()
                ?: return@withContext emptyList()
            val csrf = cookieJar.loadForRequest(playbackEndpoint).firstOrNull { it.name == "XSRF-TOKEN" }
                ?: return@withContext emptyList()
            val request = Request.Builder().url(playbackEndpoint)
                .header("Origin", "https://hstream.moe")
                .header("Referer", episodeUrl.toString())
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-XSRF-TOKEN", URLDecoder.decode(csrf.value, "UTF-8"))
                .post(JSONObject().put("episode_id", episodeId.toString()).toString().toRequestBody("application/json".toMediaType()))
                .build()
            val payload = session.fetch(request)?.let(::JSONObject) ?: return@withContext emptyList()
            val path = payload.optString("stream_url").trim('/')
            if (!assetPath.matches(path)) return@withContext emptyList()
            val domains = payload.optJSONArray("stream_domains") ?: return@withContext emptyList()
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
                    subtitles = listOf(SubtitleTrack(base.newBuilder().addPathSegment("eng.ass").build().toString(), "en"))
                )
            }.distinctBy { it.url }
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
