package io.maru.manime.extensions

import io.maru.manime.cleanAnimeTitle
import io.maru.manime.readResponse
import kotlinx.coroutines.CancellationException
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
import java.net.URLEncoder
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
    private val searchClient = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val mediaHosts = setOf(
        "imoto-str.ane-h.xyz", "chibi-str.imoto-h.xyz", "koneko-str.musume-h.xyz",
        "shinobu-str.rorikon-h.xyz", "oppai-str.shoujo-h.org"
    )
    private val assetPath = Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*")
    private val slugEpisodeRegex = Regex("^/hentai/([a-z0-9]+(?:-[a-z0-9]+)*)-(\\d{1,3})/?$", RegexOption.IGNORE_CASE)
    private val videoEpisodeSuffixRegex = Regex("^(.*?)\\s+Episode\\s+(\\d{1,3})$", RegexOption.IGNORE_CASE)

    private data class Series(val slug: String, val title: String, val episodes: Int? = null)

    private val verifiedByAniListId = mapOf(
        10851 to Series("euphoria", "Euphoria", 6)
    )
    private val discoveredSlugByAniListId = ConcurrentHashMap<Int, String>()

    // Edition / sequel markers that must NOT appear on only one side of a title match
    private val EDITION_MARKERS = setOf(
        "2", "3", "4", "ii", "iii", "iv", "special", "specials", "ova2",
        "revenge", "reborn", "harem", "zero", "encore", "after", "returns", "second", "third", "new"
    )

    fun supports(anilistId: Int, episode: Int, candidateTitles: List<String> = emptyList()): Boolean {
        if (episode <= 0) return false
        verifiedByAniListId[anilistId]?.let { return it.episodes == null || episode in 1..it.episodes }
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
            if (pinned.episodes != null && episode !in 1..pinned.episodes) return@withContext emptyList()
            val resolved = resolveEpisodeSlug(pinned.slug, episode, listOf(pinned.title) + candidateTitles)
            if (resolved.isNotEmpty()) return@withContext resolved
        }

        val normalizedCandidates = candidateTitles
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
        if (normalizedCandidates.isEmpty()) return@withContext emptyList()

        discoveredSlugByAniListId[anilistId]?.let { cachedSlug ->
            val fromCached = resolveEpisodeSlug(cachedSlug, episode, normalizedCandidates)
            if (fromCached.isNotEmpty()) return@withContext fromCached
        }

        val slugCandidates = LinkedHashSet<String>()
        // 1. Direct Romanized slug candidates (fastest, 0 search round-trip when exact!)
        for (title in normalizedCandidates.take(4)) {
            toHstreamSlug(title)?.let { slugCandidates += it }
            val strippedAnim = title.replace(Regex("(?i)\\s+the\\s+animation$"), "").trim()
            if (strippedAnim != title) toHstreamSlug(strippedAnim)?.let { slugCandidates += it }
        }

        for (slug in slugCandidates.take(4)) {
            val streams = resolveEpisodeSlug(slug, episode, normalizedCandidates)
            if (streams.isNotEmpty()) {
                discoveredSlugByAniListId[anilistId] = slug
                return@withContext streams
            }
        }

        // 2. Dynamic Hstream search using Romanized / base titles first
        val searchedSlugs = searchCandidateSlugs(normalizedCandidates, episode)
        for (slug in searchedSlugs) {
            if (slug in slugCandidates) continue
            val streams = resolveEpisodeSlug(slug, episode, normalizedCandidates)
            if (streams.isNotEmpty()) {
                discoveredSlugByAniListId[anilistId] = slug
                return@withContext streams
            }
        }

        emptyList()
    }

    private suspend fun searchCandidateSlugs(candidateTitles: List<String>, targetEpisode: Int): List<String> {
        val queries = buildSearchQueries(candidateTitles)
        val matchedSlugs = LinkedHashSet<String>()
        for (query in queries.take(4)) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val searchUrl = site.newBuilder()
                    .addPathSegment("search")
                    .addQueryParameter("search", query)
                    .build()
                val req = Request.Builder()
                    .url(searchUrl)
                    .header("User-Agent", StremioAddonClient.BROWSER_UA)
                    .build()
                val html = searchClient.newCall(req).readResponse { res ->
                    if (res.isSuccessful) res.body?.string() else null
                } ?: continue

                val doc = Jsoup.parse(html, site.toString())
                val exactEpSlugs = mutableListOf<String>()
                val otherEpSlugs = mutableListOf<String>()

                for (anchor in doc.select("a[href*='/hentai/']")) {
                    val href = anchor.attr("href").trim()
                    val parsedUrl = href.toHttpUrlOrNull() ?: site.resolve(href) ?: continue
                    if (!parsedUrl.host.equals(site.host, ignoreCase = true)) continue
                    val match = slugEpisodeRegex.matchEntire(parsedUrl.encodedPath) ?: continue
                    val seriesSlug = match.groupValues[1].lowercase()
                    val epNum = match.groupValues[2].toIntOrNull() ?: continue
                    val cardText = anchor.text()
                    // Pre-filter by slug or card title similarity to our candidate titles
                    val slugAsTitle = seriesSlug.replace('-', ' ')
                    if (matchesHstreamSeriesTitle(slugAsTitle, candidateTitles) ||
                        (cardText.isNotBlank() && matchesHstreamSeriesTitle(extractCardSeriesTitle(cardText), candidateTitles))
                    ) {
                        if (epNum == targetEpisode) exactEpSlugs += seriesSlug else otherEpSlugs += seriesSlug
                    }
                }
                matchedSlugs.addAll(exactEpSlugs)
                matchedSlugs.addAll(otherEpSlugs)
                if (matchedSlugs.isNotEmpty()) break
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return matchedSlugs.take(5)
    }

    internal fun buildSearchQueries(candidateTitles: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (raw in candidateTitles) {
            // Keep ASCII/Latin Romanized & English queries (Hstream search indexes Romaji/Latin text, not Kanji)
            if (raw.none { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }) continue
            val clean = cleanAnimeTitle(raw)
                .replace(Regex("[^A-Za-z0-9!?'\\s-]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.length >= 2) out += clean
            val beforeColon = raw.split(':', '～', '~').firstOrNull()?.let(::cleanAnimeTitle)
                ?.replace(Regex("[^A-Za-z0-9!?'\\s-]"), " ")
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
            if (beforeColon != null && beforeColon.length >= 4) out += beforeColon
        }
        return out.toList()
    }

    private fun extractCardSeriesTitle(cardText: String): String {
        val lines = cardText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val epLine = lines.firstOrNull { Regex("-\\s*\\d{1,3}$").containsMatchIn(it) } ?: cardText
        return epLine.replace(Regex("\\s*-\\s*\\d{1,3}$"), "").trim()
    }

    internal fun toHstreamSlug(title: String): String? {
        if (title.none { it in 'A'..'Z' || it in 'a'..'z' }) return null
        val slug = title
            .replace('×', 'x')
            .replace('✕', 'x')
            .lowercase()
            .replace(Regex("['’`♥♡★☆♪♫]"), "")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.takeIf { it.length >= 2 }
    }

    internal fun matchesHstreamSeriesTitle(hstreamSeriesTitle: String, candidateTitles: List<String>): Boolean {
        val normTarget = normalizeSeriesKey(hstreamSeriesTitle)
        val targetTokens = tokenizeSeries(hstreamSeriesTitle)
        if (normTarget.isEmpty() || targetTokens.isEmpty()) return false
        val targetEditions = targetTokens.intersect(EDITION_MARKERS)

        return candidateTitles.any { candidate ->
            val normCand = normalizeSeriesKey(candidate)
            val candTokens = tokenizeSeries(candidate)
            if (normCand.isEmpty() || candTokens.isEmpty()) return@any false

            val candEditions = candTokens.intersect(EDITION_MARKERS)
            // Guard against sequel/edition cross-matching (e.g. "Oni Chichi" != "Oni Chichi 2" / "Oni Chichi Harem")
            if (targetEditions != candEditions) return@any false

            if (normTarget == normCand) return@any true

            // Allow subtitle omissions when main Romaji title matches cleanly (e.g. "Eroge! H mo Game mo..." vs "Eroge!")
            val meaningfulCand = candTokens.filter { it.length >= 3 && it !in GENERIC_WORDS }
            val meaningfulTarget = targetTokens.filter { it.length >= 3 && it !in GENERIC_WORDS }
            if (meaningfulCand.isEmpty() || meaningfulTarget.isEmpty()) return@any false

            val overlap = meaningfulCand.count { it in meaningfulTarget }
            val smallerSize = minOf(meaningfulCand.size, meaningfulTarget.size)
            val largerSize = maxOf(meaningfulCand.size, meaningfulTarget.size)

            if (smallerSize >= 2 && overlap == smallerSize) {
                // Ensure the shorter title is the leading prefix of the longer title
                normTarget.startsWith(normCand) || normCand.startsWith(normTarget)
            } else {
                largerSize >= 3 && overlap >= maxOf(2, (largerSize * 0.8).toInt())
            }
        }
    }

    private val GENERIC_WORDS = setOf("the", "animation", "ova", "oad", "ona", "tv", "and", "series")

    private fun normalizeSeriesKey(raw: String): String =
        raw.replace('×', 'x')
            .replace('✕', 'x')
            .lowercase()
            .replace(Regex("\\bthe\\s+animation\\b"), "")
            .replace(Regex("[^a-z0-9]+"), "")

    private fun tokenizeSeries(raw: String): Set<String> =
        raw.replace('×', 'x')
            .replace('✕', 'x')
            .lowercase()
            .replace(Regex("\\bthe\\s+animation\\b"), " ")
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
            .toSet()

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
            val path = payload.optString("stream_url").trim('/')
            if (!assetPath.matches(path)) return emptyList()
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
                    score = 780
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
