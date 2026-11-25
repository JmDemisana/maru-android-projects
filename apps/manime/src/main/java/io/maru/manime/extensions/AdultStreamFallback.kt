package io.maru.manime.extensions

import io.maru.manime.readResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

internal object AdultStreamFallback {
    fun supports(anilistId: Int, episode: Int, aliases: List<String>): Boolean =
        if (anilistId == HentaiMamaResolver.OTOME_DOMAIN_ID) episode == 1
        else HstreamResolver.supports(anilistId, episode, aliases)

    suspend fun resolve(
        anilistId: Int,
        episode: Int,
        aliases: List<String>,
        lookupHealth: StreamLookupHealth? = null
    ): List<StreamLink> =
        if (anilistId == HentaiMamaResolver.OTOME_DOMAIN_ID) {
            if (episode == 1) HentaiMamaResolver.resolveOtomeDomain(lookupHealth) else emptyList()
        } else {
            HstreamResolver.resolve(anilistId, episode, aliases, lookupHealth)
        }

    fun containsSource(streams: List<StreamLink>): Boolean = streams.any {
        it.sourceName == HstreamResolver.SOURCE_NAME || it.sourceName == HentaiMamaResolver.SOURCE_NAME
    }
}

private object HentaiMamaResolver {
    const val OTOME_DOMAIN_ID = 99441
    const val SOURCE_NAME = "HentaiMama"
    private val site = "https://hentaimama.io/".toHttpUrl()
    private val episodeUrl = site.resolve("episodes/otome-domain-animation-episode-1/")!!
    private val endpoint = site.resolve("wp-admin/admin-ajax.php")!!
    private val expectedTitle = Regex(
        "^Otome Domain The Animation Episode 1(?:$|\\s+[–—-])",
        RegexOption.IGNORE_CASE
    )
    private val sources = Regex("""\bsources\s*:\s*(\[[^\]]+])""")
    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun resolveOtomeDomain(lookupHealth: StreamLookupHealth?): List<StreamLink> = withContext(Dispatchers.IO) {
        try {
            val page = fetch(Request.Builder().url(episodeUrl).build(), lookupHealth) ?: return@withContext emptyList()
            val document = Jsoup.parse(page, episodeUrl.toString())
            if (!expectedTitle.containsMatchIn(document.title())) {
                lookupHealth?.recordUnverifiedMatch()
                return@withContext emptyList()
            }
            if (document.selectFirst("a[href='#option-1']") == null) return@withContext emptyList()
            val postId = document.selectFirst("#post_report input[name=idpost]")?.attr("value")?.toLongOrNull()
                ?: return@withContext emptyList()

            val body = FormBody.Builder()
                .add("action", "get_player_contents")
                .add("a", postId.toString())
                .add("i", "1")
                .build()
            val request = Request.Builder().url(endpoint).header("Referer", site.toString()).post(body).build()
            val fragments = fetch(request, lookupHealth)?.let(::JSONArray) ?: return@withContext emptyList()
            val frame = Jsoup.parseBodyFragment(fragments.optString(0), site.toString()).selectFirst("iframe")
                ?: return@withContext emptyList()
            val playerUrl = frame.absUrl("src").toHttpUrlOrNull()?.takeIf {
                it.isHttps && it.host == site.host && it.port == site.port && it.username.isEmpty() && it.password.isEmpty()
            } ?: return@withContext emptyList()
            val playerPage = fetch(Request.Builder().url(playerUrl).build(), lookupHealth) ?: return@withContext emptyList()
            val playerSources = sources.find(playerPage)?.groupValues?.get(1)?.let(::JSONArray)
                ?: return@withContext emptyList()
            (0 until playerSources.length()).mapNotNull { index ->
                val source = playerSources.optJSONObject(index) ?: return@mapNotNull null
                val url = source.optString("file").toHttpUrlOrNull()?.takeIf {
                    it.isHttps && it.host == "gdvid.info" && it.port == 443 &&
                        it.username.isEmpty() && it.password.isEmpty() && it.encodedPath.endsWith(".mp4", ignoreCase = true)
                } ?: return@mapNotNull null
                StreamLink(
                    url = url.toString(),
                    quality = source.optString("label").ifBlank { "Auto" },
                    sourceName = SOURCE_NAME,
                    methodType = "DIRECT",
                    headers = mapOf("Referer" to site.toString()),
                    verifiedAdultEpisode = true
                )
            }.distinctBy { it.url }
        } catch (_: IOException) {
            lookupHealth?.recordFailure()
            emptyList()
        } catch (_: JSONException) {
            lookupHealth?.recordFailure()
            emptyList()
        }
    }

    private suspend fun fetch(request: Request, lookupHealth: StreamLookupHealth?): String? =
        client.newCall(request).readResponse { response ->
            if (response.isSuccessful) {
                response.body?.string()
            } else {
                if (response.code != 404) lookupHealth?.recordFailure()
                null
            }
        }
}
