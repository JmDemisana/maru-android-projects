package io.maru.manime.extensions

import io.maru.manime.StreamingEpisode
import io.maru.manime.readResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class AnimeIdMappings(
    val anilistId: Int,
    val malId: Int? = null,
    val kitsuId: String? = null,
    val imdbId: String? = null,
    val tvdbId: Int? = null,
    val episodeCount: Int? = null,
    val episodes: List<CrunchyEpisodeMeta> = emptyList()
)

data class CrunchyEpisodeMeta(
    val episodeNumber: Int,
    val seasonNumber: Int = 1,
    val tvdbEpisodeNumber: Int? = null,
    val absoluteEpisodeNumber: Int? = null,
    val title: String,
    val overview: String? = null,
    val thumbnail: String? = null,
    val airDate: String? = null,
    val durationMinutes: Int? = null
)

data class AniSkipInterval(
    val startTimeSeconds: Double,
    val endTimeSeconds: Double,
    val skipType: String // "op" | "ed"
)

data class ExternalSubtitleItem(
    val id: String,
    val url: String,
    val langCode: String,
    val langName: String,
    val format: String // "ASS" | "VTT" | "SRT"
)

data class StreamDubStatus(
    val hasStreamDubOrDual: Boolean = false,
    val detectedLanguages: List<String> = emptyList()
)

object CrunchyrollStreamEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private val streamHttpClient = httpClient.newBuilder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    // High-availability anime & global trackers for fast peer discovery
    private val FAST_TRACKERS = listOf(
        "http://nyaa.tracker.wf:7777/announce",
        "http://anidex.moe:6969/announce",
        "http://tracker.anirena.com:80/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://explodie.org:6969/announce",
        "http://t.nyaatracker.com:80/announce"
    )

    private val BUILTIN_PROVIDERS = listOf(
        "https://torrentio.strem.fun",
        "https://animekitsu.strem.fun"
    )

    private val OPENSUBTITLES_ADDONS = listOf(
        "https://opensubtitles-v3.strem.io",
        "https://opensubtitles.strem.io"
    )

    private val mappingCache = ConcurrentHashMap<Int, Pair<Long, AnimeIdMappings>>()
    private val mappingLocks = Array(64) { Mutex() }
    private val dubStatusCache = ConcurrentHashMap<Pair<Int, Int>, Pair<Long, StreamDubStatus>>()
    private val mappingCacheNanos = TimeUnit.MINUTES.toNanos(30)
    private val missingMappingCacheNanos = TimeUnit.SECONDS.toNanos(3)
    private val dubCacheNanos = TimeUnit.MINUTES.toNanos(10)
    private val seedersPattern = Pattern.compile("(?:👤|seeders|seeds|peers)\\s*:?\\s*(\\d+)|(\\d+)\\s*(?:seed|👤|peers)")

    /**
     * Checks live Torrentio/Kitsu streams for Episode 1 to detect if Dual-Audio / Multi-Audio / Dubbed
     * streams exist even when AniList hasn't cataloged the dub yet!
     */
    suspend fun checkStreamDubAvailability(
        anilistId: Int,
        animeTitle: String,
        episodeNum: Int = 1,
        isMovie: Boolean = false
    ): StreamDubStatus = withContext(Dispatchers.IO) {
        val cacheKey = anilistId to episodeNum
        dubStatusCache[cacheKey]?.let { (cachedAt, status) ->
            if (System.nanoTime() - cachedAt < dubCacheNanos) return@withContext status
        }
        try {
            val streams = resolveRankedStreams(
                anilistId = anilistId,
                animeTitle = animeTitle,
                episodeNum = episodeNum,
                preferredAudio = "DUB",
                isMovie = isMovie
            )
            val dubStreams = streams.filter { it.audioType == "DUAL_AUDIO" || it.audioType == "DUB" }
            val hasDub = dubStreams.isNotEmpty()
            val langs = mutableSetOf<String>()
            for (s in dubStreams) {
                val raw = (s.filename ?: "").lowercase()
                if (raw.contains("🇬🇧") || raw.contains("🇺🇸") || raw.contains("eng") || raw.contains("english") || raw.contains("dual")) {
                    langs.add("English / Dual Audio")
                }
                if (raw.contains("multi")) {
                    langs.add("Multi-Language Dub")
                }
            }
            if (hasDub && langs.isEmpty()) langs.add("Dual Audio / Dub")
            val status = StreamDubStatus(hasStreamDubOrDual = hasDub, detectedLanguages = langs.toList())
            if (streams.isNotEmpty()) dubStatusCache[cacheKey] = System.nanoTime() to status
            status
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StreamDubStatus()
        }
    }

    /**
     * Resolves AniList ID -> Kitsu ID, MAL ID, IMDb ID, and rich Crunchyroll-style Episode metadata
     * via api.ani.zip, with Kitsu API fallback so episode titles/thumbnails/overviews are always populated.
     */
    suspend fun getAnimeMappings(
        anilistId: Int,
        fallbackTitle: String = "",
        includeEpisodeMetadata: Boolean = true
    ): AnimeIdMappings = withContext(Dispatchers.IO) {
        val lock = mappingLocks[anilistId and (mappingLocks.size - 1)]
        val mappings = cachedMappings(anilistId, fallbackTitle) ?: lock.withLock {
            cachedMappings(anilistId, fallbackTitle) ?: fetchAnimeMappings(anilistId, fallbackTitle).also {
                if (it.hasSourceIds() || fallbackTitle.isNotBlank()) {
                    mappingCache[anilistId] = System.nanoTime() to it
                }
            }
        }
        val kitsuId = mappings.kitsuId
        if (!includeEpisodeMetadata || mappings.episodes.isNotEmpty() || kitsuId == null) {
            return@withContext mappings
        }

        val episodes = fetchKitsuEpisodes(kitsuId)
        if (episodes.isEmpty()) return@withContext mappings
        val enriched = mappings.copy(episodes = episodes.sortedBy { it.episodeNumber })
        mappingCache.computeIfPresent(anilistId) { _, cached ->
            if (cached.second === mappings) cached.first to enriched else cached
        }
        enriched
    }

    private fun AnimeIdMappings.hasSourceIds(): Boolean = kitsuId != null || imdbId != null

    private fun cachedMappings(anilistId: Int, fallbackTitle: String): AnimeIdMappings? {
        mappingCache[anilistId]?.let { (cachedAt, mappings) ->
            val maxAge = if (mappings.hasSourceIds() || mappings.episodes.isNotEmpty()) {
                mappingCacheNanos
            } else if (fallbackTitle.isNotBlank()) {
                missingMappingCacheNanos
            } else 0L
            if (System.nanoTime() - cachedAt < maxAge) return mappings
        }
        return null
    }

    private suspend fun fetchAnimeMappings(anilistId: Int, fallbackTitle: String): AnimeIdMappings {
        var kitsuId: String? = null
        var malId: Int? = null
        var imdbId: String? = null
        var tvdbId: Int? = null
        var epCount: Int? = null
        val episodesList = mutableListOf<CrunchyEpisodeMeta>()

        try {
            val req = Request.Builder()
                .url("https://api.ani.zip/mappings?anilist_id=$anilistId")
                .header("User-Agent", StremioAddonClient.BROWSER_UA)
                .build()
            val body = httpClient.newCall(req).readResponse { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
            if (!body.isNullOrBlank()) {
                val json = JSONObject(body)
                val mappings = json.optJSONObject("mappings")
                if (mappings != null) {
                    val rawKitsu = mappings.opt("kitsu_id")
                    if (rawKitsu != null && rawKitsu.toString() != "null") {
                        kitsuId = rawKitsu.toString()
                    }
                    val rawMal = mappings.optInt("mal_id", 0)
                    if (rawMal > 0) malId = rawMal
                    val rawImdb = mappings.optString("imdb_id", "")
                    if (rawImdb.startsWith("tt")) imdbId = rawImdb
                    val rawTvdb = mappings.optInt("thetvdb_id", 0)
                    if (rawTvdb > 0) tvdbId = rawTvdb
                }

                epCount = json.optInt("episodeCount", 0).takeIf { it > 0 }

                val epsJson = json.optJSONObject("episodes")
                if (epsJson != null) {
                    val keys = epsJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val epNum = key.toIntOrNull()?.takeIf { it > 0 } ?: continue
                        val epObj = epsJson.optJSONObject(key) ?: continue

                        val titleObj = epObj.optJSONObject("title")
                        val epTitle = titleObj?.optString("en")?.takeIf { it.isNotBlank() && it != "null" }
                            ?: titleObj?.optString("x-jat")?.takeIf { it.isNotBlank() && it != "null" }
                            ?: titleObj?.optString("ja")?.takeIf { it.isNotBlank() && it != "null" }
                            ?: "Episode $epNum"

                        val image = epObj.optString("image").takeIf { it.isNotBlank() && it != "null" }
                        val overview = epObj.optString("overview").takeIf { it.isNotBlank() && it != "null" }
                            ?: epObj.optString("summary").takeIf { it.isNotBlank() && it != "null" }
                        val airDate = epObj.optString("airDate").takeIf { it.isNotBlank() && it != "null" }
                            ?: epObj.optString("airdate").takeIf { it.isNotBlank() && it != "null" }
                        val length = epObj.optInt("length", 0).takeIf { it > 0 }
                            ?: epObj.optInt("runtime", 0).takeIf { it > 0 }
                        val seasonNum = epObj.optInt("seasonNumber", -1).takeIf { it >= 0 } ?: 1
                        val tvdbEpNum = epObj.optInt("episodeNumber", epNum).takeIf { it > 0 } ?: epNum
                        val absEpNum = epObj.optInt("absoluteEpisodeNumber", 0).takeIf { it > 0 }

                        episodesList.add(
                            CrunchyEpisodeMeta(
                                episodeNumber = epNum,
                                seasonNumber = seasonNum,
                                tvdbEpisodeNumber = tvdbEpNum,
                                absoluteEpisodeNumber = absEpNum,
                                title = epTitle,
                                overview = overview,
                                thumbnail = image,
                                airDate = airDate,
                                durationMinutes = length
                            )
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}

        if (kitsuId.isNullOrBlank() && fallbackTitle.isNotBlank()) {
            kitsuId = resolveKitsuIdByTitle(fallbackTitle)
        }

        return AnimeIdMappings(
            anilistId = anilistId,
            malId = malId,
            kitsuId = kitsuId,
            imdbId = imdbId,
            tvdbId = tvdbId,
            episodeCount = epCount,
            episodes = episodesList.sortedBy { it.episodeNumber }
        )
    }

    private suspend fun resolveKitsuIdByTitle(title: String): String? {
        val titleKey = title.filter { it.isLetterOrDigit() }.lowercase()
        if (titleKey.isEmpty()) return null
        return try {
            val encoded = URLEncoder.encode(title.trim(), "UTF-8")
            val req = Request.Builder()
                .url("https://kitsu.io/api/edge/anime?filter[text]=$encoded&page[limit]=10")
                .header("User-Agent", StremioAddonClient.BROWSER_UA)
                .build()
            val body = httpClient.newCall(req).readResponse { response ->
                if (response.isSuccessful) response.body?.string() else null
            } ?: return null
            val data = JSONObject(body).optJSONArray("data") ?: return null
            val matchingIds = mutableSetOf<String>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val attrs = item.optJSONObject("attributes") ?: continue
                val titles = mutableListOf(attrs.optString("canonicalTitle"))
                attrs.optJSONObject("titles")?.let { aliases ->
                    for (key in aliases.keys()) titles += aliases.optString(key)
                }
                if (titles.any { it.filter { c -> c.isLetterOrDigit() }.lowercase() == titleKey }) {
                    item.optString("id").takeIf { it.toIntOrNull()?.let { id -> id > 0 } == true }
                        ?.let(matchingIds::add)
                }
            }
            matchingIds.singleOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchKitsuEpisodes(kitsuId: String): List<CrunchyEpisodeMeta> {
        val list = mutableListOf<CrunchyEpisodeMeta>()
        try {
            val req = Request.Builder()
                .url("https://kitsu.io/api/edge/anime/$kitsuId/episodes?page[limit]=20&sort=number")
                .header("User-Agent", StremioAddonClient.BROWSER_UA)
                .build()
            val body = httpClient.newCall(req).readResponse { response ->
                if (response.isSuccessful) response.body?.string() else null
            } ?: return emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            for (i in 0 until data.length()) {
                val attr = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val num = attr.optInt("number", i + 1)
                val canonical = attr.optString("canonicalTitle", "").takeIf { it.isNotBlank() && it != "null" }
                    ?: "Episode $num"
                val synopsis = attr.optString("synopsis", "").takeIf { it.isNotBlank() && it != "null" }
                val thumb = attr.optJSONObject("thumbnail")?.optString("original")?.takeIf { it.isNotBlank() && it != "null" }
                val airDate = attr.optString("airdate", "").takeIf { it.isNotBlank() && it != "null" }
                val length = attr.optInt("length", 24).takeIf { it > 0 } ?: 24
                list.add(
                    CrunchyEpisodeMeta(
                        episodeNumber = num,
                        title = canonical,
                        overview = synopsis,
                        thumbnail = thumb,
                        airDate = airDate,
                        durationMinutes = length
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        return list
    }

    /**
     * Merges AniList streamingEpisodes with AniZip/Kitsu episode metadata so every anime has a complete,
     * Crunchyroll-style episode list with thumbnails, air dates, and episode synopses.
     * Strictly caps releasing shows at latestAired (or airDate <= today) so unreleased future episodes never appear!
     */
    fun buildCompleteEpisodeList(
        mappings: AnimeIdMappings?,
        anilistStreamingEps: List<StreamingEpisode>,
        totalEpisodes: Int?,
        latestAired: Int?,
        isReleasing: Boolean = false
    ): List<CrunchyEpisodeMeta> {
        val aniZipMap = mappings?.episodes?.associateBy { it.episodeNumber } ?: emptyMap()
        val anilistMap = anilistStreamingEps.associateBy { it.episodeNumber }
        val defaultSeasonNum = mappings?.episodes?.firstOrNull()?.seasonNumber ?: 1

        val today = LocalDate.parse(io.maru.manime.singaporeDate())
        val highestAiredFromDates = mappings?.episodes
            ?.filter { episode ->
                val airDate = episode.airDate ?: return@filter false
                try {
                    LocalDate.parse(airDate) <= today
                } catch (_: DateTimeParseException) {
                    false
                }
            }
            ?.maxOfOrNull { it.episodeNumber }

        val targetCount = when {
            isReleasing && latestAired != null -> latestAired.coerceAtLeast(0)
            isReleasing -> maxOf(highestAiredFromDates ?: 0, anilistMap.keys.maxOrNull() ?: 0)
            latestAired != null -> latestAired
            totalEpisodes != null && totalEpisodes > 0 -> totalEpisodes
            mappings?.episodeCount != null && mappings.episodeCount > 0 -> mappings.episodeCount
            aniZipMap.isNotEmpty() -> aniZipMap.keys.maxOrNull() ?: 0
            anilistMap.isNotEmpty() -> anilistMap.keys.maxOrNull() ?: 0
            else -> 0
        }.coerceIn(0, totalEpisodes?.takeIf { it > 0 }?.coerceAtMost(1500) ?: 1500)

        if (targetCount <= 0) return emptyList()

        val list = mutableListOf<CrunchyEpisodeMeta>()
        for (epNum in 1..targetCount) {
            val az = aniZipMap[epNum]
            val al = anilistMap[epNum]

            val rawAlTitle = al?.title?.replaceFirst(Regex("^Episode\\s*\\d+\\s*[-:\\.]\\s*", RegexOption.IGNORE_CASE), "")?.trim()
            val finalTitle = when {
                !az?.title.isNullOrBlank() && !az!!.title.equals("Episode $epNum", ignoreCase = true) -> az.title
                !rawAlTitle.isNullOrBlank() -> rawAlTitle
                else -> "Episode $epNum"
            }

            list.add(
                CrunchyEpisodeMeta(
                    episodeNumber = epNum,
                    seasonNumber = az?.seasonNumber ?: defaultSeasonNum,
                    tvdbEpisodeNumber = az?.tvdbEpisodeNumber ?: epNum,
                    absoluteEpisodeNumber = az?.absoluteEpisodeNumber,
                    title = finalTitle,
                    overview = az?.overview,
                    thumbnail = az?.thumbnail ?: al?.thumbnail,
                    airDate = az?.airDate,
                    durationMinutes = az?.durationMinutes ?: 24
                )
            )
        }
        return list
    }

    /**
     * Fetches external subtitles from OpenSubtitles Stremio v3 Addon for the given anime & episode.
     */
    suspend fun fetchOpenSubtitles(
        anilistId: Int,
        animeTitle: String,
        episodeNum: Int,
        isMovie: Boolean = false
    ): List<ExternalSubtitleItem> = withContext(Dispatchers.IO) {
        val mappings = getAnimeMappings(anilistId, animeTitle, includeEpisodeMetadata = false)
        val lookups = streamLookups(mappings, episodeNum, isMovie).sortedBy { it.id.startsWith("kitsu:") }
        if (lookups.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<ExternalSubtitleItem>()
        for (addon in OPENSUBTITLES_ADDONS) {
            for ((type, id) in lookups) {
                try {
                    val req = Request.Builder()
                        .url("$addon/subtitles/$type/$id.json")
                        .header("User-Agent", StremioAddonClient.BROWSER_UA)
                        .build()
                    val body = httpClient.newCall(req).readResponse { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    } ?: continue
                    val subsArr = JSONObject(body).optJSONArray("subtitles") ?: continue

                    for (i in 0 until subsArr.length()) {
                        val s = subsArr.optJSONObject(i) ?: continue
                        val subUrl = s.optString("url", "").trim()
                        if (subUrl.isEmpty() || s.isNull("url")) continue
                        val lang = s.optString("lang", "eng").lowercase()
                        val id = s.optString("id", "os_$i")
                        val fileName = s.optString("subtitleFileName", "").lowercase()
                        val urlPath = subUrl.substringBefore('?').substringBefore('#').lowercase()
                        val fmt = when {
                            fileName.endsWith(".ass") || fileName.endsWith(".ssa") ||
                                urlPath.endsWith(".ass") || urlPath.endsWith(".ssa") -> "ASS"
                            fileName.endsWith(".vtt") || urlPath.endsWith(".vtt") -> "VTT"
                            else -> "SRT"
                        }

                        val readableLang = when (lang) {
                            "eng", "en" -> "English (OpenSubtitles)"
                            "spa", "es" -> "Español (OpenSubtitles)"
                            "por", "pt", "pob" -> "Português (OpenSubtitles)"
                            "fre", "fra", "fr" -> "Français (OpenSubtitles)"
                            "ger", "deu", "de" -> "Deutsch (OpenSubtitles)"
                            "ita", "it" -> "Italiano (OpenSubtitles)"
                            "ara", "ar" -> "العربية (OpenSubtitles)"
                            "ind", "id" -> "Indonesia (OpenSubtitles)"
                            "jpn", "ja" -> "Japanese CC (OpenSubtitles)"
                            else -> "${lang.uppercase()} (OpenSubtitles)"
                        }

                        results.add(
                            ExternalSubtitleItem(
                                id = id,
                                url = subUrl,
                                langCode = lang,
                                langName = readableLang,
                                format = fmt
                            )
                        )
                    }
                    if (results.isNotEmpty()) break
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
            }
            if (results.isNotEmpty()) break
        }

        // Prioritize English first, then other languages
        results
            .distinctBy { it.url }
            .sortedWith(
                compareByDescending<ExternalSubtitleItem> { it.langCode == "eng" || it.langCode == "en" }
                    .thenByDescending { it.format == "ASS" }
                    .thenBy { it.langName }
            )
            .take(25)
    }

    /**
     * Fetches OP/ED skip timestamps from AniSkip using the MAL ID
     */
    suspend fun getAniSkipIntervals(malId: Int?, episodeNumber: Int): List<AniSkipInterval> = withContext(Dispatchers.IO) {
        if (malId == null || malId <= 0) return@withContext emptyList()
        val results = mutableListOf<AniSkipInterval>()
        try {
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber?types[]=op&types[]=ed&episodeLength=0"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", StremioAddonClient.BROWSER_UA)
                .build()
            val body = httpClient.newCall(req).readResponse { response ->
                if (response.isSuccessful) response.body?.string() else null
            } ?: return@withContext emptyList()
            val json = JSONObject(body)
            if (json.optBoolean("found", false)) {
                val arr = json.optJSONArray("results") ?: return@withContext emptyList()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val interval = item.optJSONObject("interval") ?: continue
                    val start = interval.optDouble("startTime", -1.0)
                    val end = interval.optDouble("endTime", -1.0)
                    val skipType = item.optString("skipType", "op")
                    if (start >= 0 && end.isFinite() && end > start && (skipType == "op" || skipType == "ed")) {
                        results.add(AniSkipInterval(start, end, skipType))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        results
    }

    /**
     * Fetches and scores all available streams from built-in Torrentio/Kitsu providers,
     * reporting ranked snapshots on the caller's coroutine and returning all routes' results.
     */
    suspend fun resolveRankedStreams(
        anilistId: Int,
        animeTitle: String,
        episodeNum: Int,
        preferredAudio: String = "SUB", // "SUB" | "DUB"
        preferredQuality: String = "1080p", // "1080p" | "720p" | "4K"
        isMovie: Boolean = false,
        onStreamsUpdated: (List<StreamLink>) -> Unit = {}
    ): List<StreamLink> = coroutineScope {
        val mappings = getAnimeMappings(anilistId, animeTitle, includeEpisodeMetadata = false)
        val lookups = streamLookups(mappings, episodeNum, isMovie)
        if (lookups.isEmpty()) return@coroutineScope emptyList()
        val displayName = URLEncoder.encode("$animeTitle - Episode $episodeNum", "UTF-8")

        val jobs = BUILTIN_PROVIDERS.flatMap { providerUrl ->
            val client = StremioAddonClient(providerUrl, streamHttpClient)
            lookups.map { (type, id, priority) ->
                async(Dispatchers.IO) {
                    val found = mutableListOf<StreamLink>()
                    try {
                        val rawStreams = client.getStreams(type, id)
                        for (s in rawStreams) {
                            val combinedText = listOfNotNull(s.name, s.title, s.filename).joinToString(" ")
                            val audio = detectAudioType(combinedText)
                            val quality = detectQuality(combinedText)
                            val seeds = detectSeeders(combinedText)

                            val score = scoreStream(
                                rawText = combinedText,
                                audioType = audio,
                                quality = quality,
                                seeders = seeds,
                                preferredAudio = preferredAudio,
                                preferredQuality = preferredQuality
                            ) + priority

                            if (!s.url.isNullOrBlank()) {
                                found.add(
                                    StreamLink(
                                        url = s.url,
                                        quality = quality,
                                        sourceName = formatCleanSourceLabel(audio, quality),
                                        isTorrent = false,
                                        audioType = audio,
                                        methodType = "DIRECT",
                                        seeders = seeds,
                                        filename = s.filename ?: s.title,
                                        formatBadge = quality,
                                        headers = s.headers,
                                        subtitles = s.subtitles,
                                        sizeBytes = s.sizeBytes,
                                        score = score + 80
                                    )
                                )
                            } else if (!s.infoHash.isNullOrBlank()) {
                                val hash = s.infoHash.lowercase().trim()
                                val allTrackers = (s.sources + FAST_TRACKERS).distinct()
                                val trackerParams = allTrackers.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
                                val magnet = "magnet:?xt=urn:btih:$hash&dn=$displayName$trackerParams"
                                found.add(
                                    StreamLink(
                                        url = magnet,
                                        quality = quality,
                                        sourceName = formatCleanSourceLabel(audio, quality),
                                        isTorrent = true,
                                        infoHash = hash,
                                        fileIdx = s.fileIdx,
                                        audioType = audio,
                                        methodType = "BUILTIN",
                                        seeders = seeds,
                                        filename = s.filename ?: s.title,
                                        formatBadge = quality,
                                        subtitles = s.subtitles,
                                        sizeBytes = s.sizeBytes,
                                        score = score
                                    )
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {}
                    found
                }
            }
        }

        collectRankedStreams(jobs, onStreamsUpdated)
    }

    private data class StreamLookup(val type: String, val id: String, val priority: Int = 0)

    private fun streamLookups(mappings: AnimeIdMappings, episodeNum: Int, isMovie: Boolean): List<StreamLookup> = buildList {
        mappings.kitsuId?.let { add(StreamLookup("series", "kitsu:$it:$episodeNum", 200)) }
        val imdb = mappings.imdbId ?: return@buildList
        if (isMovie) {
            add(StreamLookup("movie", imdb))
            return@buildList
        }
        val episode = mappings.episodes.firstOrNull { it.episodeNumber == episodeNum }
        val season = episode?.seasonNumber ?: mappings.episodes.firstOrNull()?.seasonNumber ?: 1
        val number = episode?.tvdbEpisodeNumber ?: episodeNum
        add(StreamLookup("series", "$imdb:$season:$number"))
        val absolute = episode?.absoluteEpisodeNumber
        if (absolute != null && (season != 1 || absolute != number)) {
            add(StreamLookup("series", "$imdb:1:$absolute", -300))
        }
    }

    private fun formatCleanSourceLabel(audioType: String, quality: String): String {
        val audioName = when (audioType) {
            "DUAL_AUDIO" -> "Japanese / English (Dual Audio)"
            "DUB" -> "English Dub"
            else -> "Japanese (Original Audio)"
        }
        return "$audioName • $quality"
    }

    private fun scoreStream(
        rawText: String,
        audioType: String,
        quality: String,
        seeders: Int?,
        preferredAudio: String,
        preferredQuality: String
    ): Int {
        var score = 0
        val lower = rawText.lowercase()

        // 1. Seeders health
        score += when {
            seeders == null -> -100
            seeders >= 100 -> 450
            seeders >= 50 -> 380
            seeders >= 20 -> 310
            seeders >= 10 -> 240
            seeders >= 5 -> 160
            seeders >= 2 -> 80
            seeders == 1 -> 20
            else -> -400
        }
        score += ((seeders ?: 0).coerceAtMost(300) / 3)

        // 2. Single-episode torrents connect & prebuffer MUCH faster than 1000-episode mega-batches
        val isMegaBatch = lower.contains("[batch]") || lower.contains("0001-") || lower.contains("season 01 -")
        if (isMegaBatch) {
            score -= 180
        } else {
            score += 140
        }

        // 3. Audio preference matching
        if (preferredAudio.equals("DUB", ignoreCase = true)) {
            score += when (audioType) {
                "DUAL_AUDIO" -> 380
                "DUB" -> 350
                else -> -150
            }
        } else {
            score += when (audioType) {
                "SUB" -> 320
                "DUAL_AUDIO" -> 300
                "DUB" -> -180
                else -> 100
            }
        }

        // 4. Resolution matching
        if (quality.equals(preferredQuality, ignoreCase = true)) {
            score += 240
        } else if (quality == "1080p") {
            score += 200
        } else if (quality == "720p") {
            score += 150
        } else if (quality == "4K") {
            score += 60
        }

        // 5. Trusted fast-playback release groups (H.264 / 8-bit / fast-decode with full ASS positioned subtitles)
        if (lower.contains("subsplease") || lower.contains("erai-raws") || lower.contains("horriblesubs")) {
            score += 220
        } else if (lower.contains("judas") || lower.contains("ember") || lower.contains("dkb") || lower.contains("asw") || lower.contains(" ToonsHub".lowercase())) {
            score += 160
        }

        // 6. Penalize Dolby Vision (DV) / live-action NF WEB-DL / huge remuxes that fail hardware decoding on mobile
        if (lower.contains(" dv ") || lower.contains(".dv.") || lower.contains("dolby vision") || lower.contains("remux")) {
            score -= 350
        }

        return score
    }

    private fun detectAudioType(raw: String): String {
        val lower = raw.lowercase()
        return if (
            lower.contains("dual") ||
            lower.contains("multi-audio") ||
            lower.contains("multi audio") ||
            lower.contains("ja+en") ||
            lower.contains("jpn+eng")
        ) {
            "DUAL_AUDIO"
        } else if (lower.contains("dub") || lower.contains("eng dub") || lower.contains("english dub")) {
            "DUB"
        } else {
            "SUB"
        }
    }

    private fun detectQuality(raw: String): String {
        val lower = raw.lowercase()
        return if (lower.contains("2160p") || lower.contains("4k")) "4K"
        else if (lower.contains("1080p")) "1080p"
        else if (lower.contains("720p")) "720p"
        else if (lower.contains("480p")) "480p"
        else "1080p"
    }

    private fun detectSeeders(raw: String): Int? {
        val matcher = seedersPattern.matcher(raw.lowercase())
        if (matcher.find()) {
            return matcher.group(1)?.toIntOrNull() ?: matcher.group(2)?.toIntOrNull()
        }
        return null
    }
}

internal suspend fun collectRankedStreams(
    routes: List<Deferred<List<StreamLink>>>,
    onStreamsUpdated: (List<StreamLink>) -> Unit
): List<StreamLink> {
    val pendingRoutes = routes.toMutableList()
    val bestBySource = linkedMapOf<Pair<String, Int?>, StreamLink>()
    var ranked = emptyList<StreamLink>()
    while (pendingRoutes.isNotEmpty()) {
        val (finished, streams) = select<Pair<Deferred<List<StreamLink>>, List<StreamLink>>> {
            pendingRoutes.forEach { route -> route.onAwait { route to it } }
        }
        pendingRoutes.remove(finished)
        for (stream in streams) {
            val key = stream.playbackIdentity
            val existing = bestBySource[key]
            if (existing == null || stream.score > existing.score) bestBySource[key] = stream
        }
        val updated = bestBySource.values.sortedByDescending { it.score }
        if (updated != ranked) {
            ranked = updated
            onStreamsUpdated(ranked)
        }
    }
    return ranked
}
