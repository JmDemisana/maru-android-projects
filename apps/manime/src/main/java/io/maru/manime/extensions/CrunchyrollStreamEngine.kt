package io.maru.manime.extensions

import io.maru.manime.AniListClient
import io.maru.manime.AniListSeasonContext
import io.maru.manime.StreamingEpisode
import io.maru.manime.cleanAnimeTitle
import io.maru.manime.extractExplicitSeasonFromTitle
import io.maru.manime.isExplicitAdultAnime
import io.maru.manime.readResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class KitsuEpisodeSegment(
    val kitsuId: String,
    val startEpisode: Int,
    val endEpisode: Int
)

data class AnimeIdMappings(
    val anilistId: Int,
    val malId: Int? = null,
    val kitsuId: String? = null,
    val imdbId: String? = null,
    val tvdbId: Int? = null,
    val episodeCount: Int? = null,
    val episodes: List<CrunchyEpisodeMeta> = emptyList(),
    val inferredSeasonNumber: Int = 1,
    val prequelEpisodeOffset: Int = 0,
    val kitsuEpisodeCount: Int? = null,
    val kitsuUsesPrequelOffset: Boolean = false,
    val combinedRootKitsuId: String? = null,
    val kitsuSegments: List<KitsuEpisodeSegment> = emptyList()
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
    val durationMinutes: Int? = null,
    val hasExplicitSeason: Boolean = true
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
        "http://sukebei.tracker.wf:8888/announce",
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

    private data class MappingCacheKey(val anilistId: Int, val isAdult: Boolean)

    private val mappingCache = ConcurrentHashMap<MappingCacheKey, Pair<Long, AnimeIdMappings>>()
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
        isMovie: Boolean = false,
        altTitles: List<String> = emptyList()
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
                isMovie = isMovie,
                altTitles = altTitles
            )
            val dubStreams = streams.filter {
                (it.audioType == "DUAL_AUDIO" || it.audioType == "DUB") &&
                    (!it.isTorrent || (it.seeders ?: 1) > 0)
            }
            val hasDub = dubStreams.isNotEmpty()
            val langs = mutableSetOf<String>()
            for (s in dubStreams) {
                val raw = listOfNotNull(s.filename, s.sourceName).joinToString(" ").lowercase()
                if (s.audioType == "DUAL_AUDIO" || raw.contains("dual")) {
                    langs.add("English / Dual Audio")
                } else {
                    langs.add("English Dub")
                }
            }
            val status = StreamDubStatus(hasStreamDubOrDual = hasDub, detectedLanguages = langs.toList())
            if (hasDub) io.maru.manime.KnownDubRegistry.recordKnownDub(anilistId)
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
        includeEpisodeMetadata: Boolean = true,
        candidateTitles: List<String> = emptyList(),
        isAdult: Boolean = false
    ): AnimeIdMappings = withContext(Dispatchers.IO) {
        val prioritizedTitles = (candidateTitles + listOf(fallbackTitle))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val primaryFallback = prioritizedTitles.firstOrNull().orEmpty()
        val cacheKey = MappingCacheKey(anilistId, isAdult)
        val lock = mappingLocks[anilistId and (mappingLocks.size - 1)]
        val mappings = cachedMappings(cacheKey, primaryFallback) ?: lock.withLock {
            cachedMappings(cacheKey, primaryFallback)
                ?: fetchAnimeMappings(anilistId, prioritizedTitles, isAdult).also {
                    if (it.hasSourceIds() || primaryFallback.isNotBlank()) {
                        mappingCache[cacheKey] = System.nanoTime() to it
                    }
                }
        }
        val kitsuId = mappings.kitsuId
        if (!includeEpisodeMetadata || mappings.episodes.isNotEmpty() || kitsuId == null) {
            return@withContext mappings
        }

        val episodes = fetchKitsuEpisodes(kitsuId, mappings.inferredSeasonNumber, mappings.prequelEpisodeOffset)
        if (episodes.isEmpty()) return@withContext mappings
        val enriched = mappings.copy(episodes = episodes.sortedBy { it.episodeNumber })
        mappingCache.computeIfPresent(cacheKey) { _, cached ->
            if (cached.second === mappings) cached.first to enriched else cached
        }
        enriched
    }

    private fun AnimeIdMappings.hasSourceIds(): Boolean = kitsuId != null || imdbId != null || combinedRootKitsuId != null

    private fun cachedMappings(cacheKey: MappingCacheKey, fallbackTitle: String): AnimeIdMappings? {
        mappingCache[cacheKey]?.let { (cachedAt, mappings) ->
            val maxAge = if (mappings.hasSourceIds() || mappings.episodes.isNotEmpty()) {
                mappingCacheNanos
            } else if (fallbackTitle.isNotBlank()) {
                missingMappingCacheNanos
            } else 0L
            if (System.nanoTime() - cachedAt < maxAge) return mappings
        }
        return null
    }

    private suspend fun fetchAnimeMappings(
        anilistId: Int,
        prioritizedTitles: List<String>,
        isAdult: Boolean
    ): AnimeIdMappings {
        var kitsuId: String? = null
        var malId: Int? = null
        var imdbId: String? = null
        var tvdbId: Int? = null
        var epCount: Int? = null
        val rawEpisodesList = mutableListOf<CrunchyEpisodeMeta>()

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
                        val parsedSeason = epObj.optInt("seasonNumber", -1).takeIf { it >= 0 }
                        val tvdbEpNum = epObj.optInt("episodeNumber", epNum).takeIf { it > 0 } ?: epNum
                        val absEpNum = epObj.optInt("absoluteEpisodeNumber", 0).takeIf { it > 0 }

                        rawEpisodesList.add(
                            CrunchyEpisodeMeta(
                                episodeNumber = epNum,
                                seasonNumber = parsedSeason ?: 1,
                                tvdbEpisodeNumber = tvdbEpNum,
                                absoluteEpisodeNumber = absEpNum,
                                title = epTitle,
                                overview = overview,
                                thumbnail = image,
                                airDate = airDate,
                                durationMinutes = length,
                                hasExplicitSeason = parsedSeason != null
                            )
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}

        if (kitsuId.isNullOrBlank() && prioritizedTitles.isNotEmpty() && !isAdult) {
            kitsuId = resolveKitsuIdByTitles(prioritizedTitles)
        }

        // Resolve combined / split season context across AniList prequels & Kitsu
        val seasonCtx = if (!isAdult && anilistId > 0) {
            AniListClient.getSeasonContext(anilistId)
        } else {
            AniListSeasonContext(
                mediaId = anilistId,
                inferredSeasonNumber = extractExplicitSeasonFromTitle(*prioritizedTitles.toTypedArray()) ?: 1
            )
        }

        val inferredSeason = extractExplicitSeasonFromTitle(*prioritizedTitles.toTypedArray())
            ?: seasonCtx.inferredSeasonNumber
        val prequelOffset = seasonCtx.prequelEpisodeOffset
        val expectedSeriesEpisodes = epCount ?: seasonCtx.totalEpisodes

        val episodesList = rawEpisodesList.map { ep ->
            if (ep.hasExplicitSeason) ep else ep.copy(
                seasonNumber = inferredSeason,
                absoluteEpisodeNumber = ep.absoluteEpisodeNumber
                    ?: (prequelOffset + ep.episodeNumber).takeIf { prequelOffset > 0 }
            )
        }

        var kitsuEpCount: Int? = null
        var kitsuUsesPrequelOffset = false
        var combinedRootKitsuId: String? = null
        var kitsuSegments: List<KitsuEpisodeSegment> = emptyList()

        if (!isAdult) {
            if (!kitsuId.isNullOrBlank()) {
                kitsuEpCount = fetchKitsuEpisodeCount(kitsuId)
                // Case A: AniList split seasons (e.g. S3 = 13 eps, prequelOffset = 26), while Kitsu combined them (39 eps)
                if (prequelOffset > 0 && kitsuEpCount != null && expectedSeriesEpisodes != null &&
                    kitsuEpCount >= prequelOffset + expectedSeriesEpisodes
                ) {
                    kitsuUsesPrequelOffset = true
                }
                // Case B: AniList combined seasons (e.g. Seasons 1-2 = 26 eps), while Kitsu split S1 (14 eps) + S2 (12 eps)
                if (!kitsuUsesPrequelOffset && kitsuEpCount != null && expectedSeriesEpisodes != null &&
                    kitsuEpCount in 1 until expectedSeriesEpisodes
                ) {
                    kitsuSegments = fetchKitsuSequelSegments(kitsuId, kitsuEpCount, expectedSeriesEpisodes)
                }
            }

            // Also check root prequel's Kitsu ID when AniList split seasons so Stremio addons that merged Kitsu seasons still match
            if (prequelOffset > 0 && !kitsuUsesPrequelOffset && seasonCtx.rootPrequelMediaId != null) {
                val rootKitsu = fetchAniZipKitsuIdOnly(seasonCtx.rootPrequelMediaId)
                if (!rootKitsu.isNullOrBlank() && rootKitsu != kitsuId) {
                    combinedRootKitsuId = rootKitsu
                }
            }
        }

        return AnimeIdMappings(
            anilistId = anilistId,
            malId = malId,
            kitsuId = kitsuId,
            imdbId = imdbId,
            tvdbId = tvdbId,
            episodeCount = expectedSeriesEpisodes,
            episodes = episodesList.sortedBy { it.episodeNumber },
            inferredSeasonNumber = inferredSeason,
            prequelEpisodeOffset = prequelOffset,
            kitsuEpisodeCount = kitsuEpCount,
            kitsuUsesPrequelOffset = kitsuUsesPrequelOffset,
            combinedRootKitsuId = combinedRootKitsuId,
            kitsuSegments = kitsuSegments
        )
    }

    private suspend fun fetchAniZipKitsuIdOnly(anilistId: Int): String? {
        mappingCache[MappingCacheKey(anilistId, false)]?.second?.kitsuId?.let { return it }
        return try {
            val req = Request.Builder()
                .url("https://api.ani.zip/mappings?anilist_id=$anilistId")
                .header("User-Agent", StremioAddonClient.BROWSER_UA)
                .build()
            val body = httpClient.newCall(req).readResponse { res ->
                if (res.isSuccessful) res.body?.string() else null
            } ?: return null
            val rawKitsu = JSONObject(body).optJSONObject("mappings")?.opt("kitsu_id")
            rawKitsu?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchKitsuEpisodeCount(kitsuId: String): Int? = try {
        val req = Request.Builder()
            .url("https://kitsu.io/api/edge/anime/$kitsuId")
            .header("User-Agent", StremioAddonClient.BROWSER_UA)
            .build()
        val body = httpClient.newCall(req).readResponse { res ->
            if (res.isSuccessful) res.body?.string() else null
        }
        body?.let {
            JSONObject(it).optJSONObject("data")?.optJSONObject("attributes")?.optInt("episodeCount", 0)
                ?.takeIf { count -> count > 0 }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun fetchKitsuSequelSegments(
        firstKitsuId: String,
        firstEpisodeCount: Int,
        targetTotalEpisodes: Int
    ): List<KitsuEpisodeSegment> {
        val segments = mutableListOf(KitsuEpisodeSegment(firstKitsuId, 1, firstEpisodeCount))
        var covered = firstEpisodeCount
        var cursorId = firstKitsuId
        val visited = hashSetOf(firstKitsuId)

        while (covered < targetTotalEpisodes && segments.size < 5) {
            val nextPair = try {
                val req = Request.Builder()
                    .url("https://kitsu.io/api/edge/anime/$cursorId/media-relationships?filter[role]=sequel&include=destination")
                    .header("User-Agent", StremioAddonClient.BROWSER_UA)
                    .build()
                val body = httpClient.newCall(req).readResponse { res ->
                    if (res.isSuccessful) res.body?.string() else null
                } ?: break
                val included = JSONObject(body).optJSONArray("included") ?: break
                var chosen: Pair<String, Int>? = null
                for (i in 0 until included.length()) {
                    val item = included.optJSONObject(i) ?: continue
                    if (!item.optString("type").equals("anime", ignoreCase = true)) continue
                    val id = item.optString("id").takeIf { it.isNotBlank() && it !in visited } ?: continue
                    val attrs = item.optJSONObject("attributes") ?: continue
                    val subtype = attrs.optString("subtype").lowercase()
                    if (subtype in setOf("movie", "music", "special")) continue
                    val eps = attrs.optInt("episodeCount", 0).takeIf { it > 0 } ?: continue
                    chosen = id to eps
                    break
                }
                chosen
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: break

            val (nextId, nextEps) = nextPair
            visited += nextId
            segments += KitsuEpisodeSegment(nextId, covered + 1, covered + nextEps)
            covered += nextEps
            cursorId = nextId
        }
        return if (segments.size > 1) segments else emptyList()
    }

    private fun normalizeTitleLookupKey(s: String): String =
        s.replace('×', 'x').replace('✕', 'x').lowercase().filter { it.isLetterOrDigit() }

    private suspend fun resolveKitsuIdByTitles(titles: List<String>): String? {
        val candidateKeys = titles.map(::normalizeTitleLookupKey).filter { it.isNotEmpty() }.toSet()
        if (candidateKeys.isEmpty()) return null
        for (queryTitle in titles.take(4)) {
            val cleanQuery = cleanAnimeTitle(queryTitle).ifBlank { queryTitle.trim() }
            if (cleanQuery.isBlank()) continue
            try {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val req = Request.Builder()
                    .url("https://kitsu.io/api/edge/anime?filter[text]=$encoded&page[limit]=10")
                    .header("User-Agent", StremioAddonClient.BROWSER_UA)
                    .build()
                val body = httpClient.newCall(req).readResponse { response ->
                    if (response.isSuccessful) response.body?.string() else null
                } ?: continue
                val data = JSONObject(body).optJSONArray("data") ?: continue
                val matchingIds = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val attrs = item.optJSONObject("attributes") ?: continue
                    val itemTitles = mutableListOf(attrs.optString("canonicalTitle"))
                    attrs.optJSONObject("titles")?.let { aliases ->
                        for (key in aliases.keys()) itemTitles += aliases.optString(key)
                    }
                    val normalizedAliases = itemTitles.map(::normalizeTitleLookupKey).filter { it.isNotEmpty() }
                    if (normalizedAliases.any { it in candidateKeys }) {
                        item.optString("id").takeIf { it.toIntOrNull()?.let { id -> id > 0 } == true }
                            ?.let { id -> if (id !in matchingIds) matchingIds += id }
                    }
                }
                if (matchingIds.isNotEmpty()) return matchingIds.first()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return null
    }

    private suspend fun fetchKitsuEpisodes(
        kitsuId: String,
        defaultSeasonNumber: Int = 1,
        prequelOffset: Int = 0
    ): List<CrunchyEpisodeMeta> {
        val list = mutableListOf<CrunchyEpisodeMeta>()
        try {
            var offset = 0
            while (offset <= 40) {
                val req = Request.Builder()
                    .url("https://kitsu.io/api/edge/anime/$kitsuId/episodes?page[limit]=20&page[offset]=$offset&sort=number")
                    .header("User-Agent", StremioAddonClient.BROWSER_UA)
                    .build()
                val body = httpClient.newCall(req).readResponse { response ->
                    if (response.isSuccessful) response.body?.string() else null
                } ?: break
                val data = JSONObject(body).optJSONArray("data") ?: break
                if (data.length() == 0) break
                for (i in 0 until data.length()) {
                    val attr = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                    val num = attr.optInt("number", offset + i + 1)
                    val canonical = attr.optString("canonicalTitle", "").takeIf { it.isNotBlank() && it != "null" }
                        ?: "Episode $num"
                    val synopsis = attr.optString("synopsis", "").takeIf { it.isNotBlank() && it != "null" }
                    val thumb = attr.optJSONObject("thumbnail")?.optString("original")?.takeIf { it.isNotBlank() && it != "null" }
                    val airDate = attr.optString("airdate", "").takeIf { it.isNotBlank() && it != "null" }
                    val length = attr.optInt("length", 24).takeIf { it > 0 } ?: 24
                    list.add(
                        CrunchyEpisodeMeta(
                            episodeNumber = num,
                            seasonNumber = defaultSeasonNumber,
                            absoluteEpisodeNumber = (prequelOffset + num).takeIf { prequelOffset > 0 },
                            title = canonical,
                            overview = synopsis,
                            thumbnail = thumb,
                            airDate = airDate,
                            durationMinutes = length,
                            hasExplicitSeason = defaultSeasonNumber > 1
                        )
                    )
                }
                if (data.length() < 20) break
                offset += 20
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
        return list.distinctBy { it.episodeNumber }
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
        altTitles: List<String> = emptyList(),
        isStandalone: Boolean = isMovie,
        isAdult: Boolean = false,
        genres: List<String> = emptyList(),
        isMusicOrConcert: Boolean = false,
        trailerYoutubeId: String? = null,
        externalLinks: List<io.maru.manime.ExternalLink> = emptyList(),
        onStreamsUpdated: (List<StreamLink>) -> Unit = {}
    ): List<StreamLink> = coroutineScope {
        val isExplicitAdult = isExplicitAdultAnime(isAdult, genres)
        val rawTitles = altTitles + animeTitle
        val candidateTitles = (if (isExplicitAdult) rawTitles else rawTitles.flatMap { listOf(it, cleanAnimeTitle(it)) })
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
        val isEcchi = genres.any { it.equals("Ecchi", true) }
        val mappings = getAnimeMappings(
            anilistId = anilistId,
            fallbackTitle = animeTitle,
            includeEpisodeMetadata = false,
            candidateTitles = candidateTitles,
            isAdult = isExplicitAdult
        )
        val lookups = streamLookups(mappings, episodeNum, isMovie)
        val expectedEpisodeMeta = mappings.episodes.firstOrNull { it.episodeNumber == episodeNum }
        val expectedSeason = expectedEpisodeMeta?.takeIf { it.hasExplicitSeason }?.seasonNumber
            ?: mappings.inferredSeasonNumber.takeIf { it > 1 }
            ?: expectedEpisodeMeta?.seasonNumber
            ?: 1
        val expectedAbsoluteEp = expectedEpisodeMeta?.absoluteEpisodeNumber
            ?: (mappings.prequelEpisodeOffset + episodeNum).takeIf { mappings.prequelEpisodeOffset > 0 }
        val isVerifiedStandalone = isMovie || (isStandalone && mappings.episodeCount == 1)
        val runFallbackImmediately = !isExplicitAdult && (lookups.isEmpty() || isEcchi || isMusicOrConcert)

        val displayName = URLEncoder.encode("$animeTitle - Episode $episodeNum", "UTF-8")

        val jobs = mutableListOf<Deferred<List<StreamLink>>>()
        BUILTIN_PROVIDERS.forEach { providerUrl ->
            val client = StremioAddonClient(providerUrl, streamHttpClient)
            lookups.forEach { (type, id, priority) ->
                jobs.add(
                    async(Dispatchers.IO) {
                        val found = mutableListOf<StreamLink>()
                        try {
                            val rawStreams = client.getStreams(type, id)
                            for (s in rawStreams) {
                                val fileLine = s.filename ?: s.title?.substringAfterLast('\n') ?: ""
                                if (isExplicitAdult) {
                                    val directUrl = s.url?.toHttpUrlOrNull()
                                    if (directUrl?.isHttps != true) continue
                                    if (!matchesAdultEpisodeLabel(
                                            label = fileLine,
                                            candidateTitles = candidateTitles,
                                            episode = episodeNum,
                                            expectedSeason = expectedSeason,
                                            expectedAbsoluteEpisode = expectedAbsoluteEp,
                                            isVerifiedStandalone = isVerifiedStandalone
                                        )) continue
                                } else if (!isMovie && isMismatchedSeasonFile(fileLine, expectedSeason, expectedAbsoluteEp)) {
                                    continue
                                }
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
                                            score = score + 80,
                                            verifiedAdultEpisode = isExplicitAdult
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
                        found.toList()
                    }
                )
            }
        }

        if (runFallbackImmediately) {
            jobs.add(
                async(Dispatchers.IO) {
                    FallbackStreamResolvers.resolveFallbackStreams(
                        titles = candidateTitles,
                        episodeNum = episodeNum,
                        isStandalone = isStandalone,
                        isEcchi = isEcchi,
                        isMusicOrConcert = isMusicOrConcert,
                        preferredAudio = preferredAudio,
                        preferredQuality = preferredQuality,
                        trailerYoutubeId = trailerYoutubeId,
                        externalLinks = externalLinks
                    )
                }
            )
        }

        val initial = if (jobs.isEmpty()) emptyList() else collectRankedStreams(jobs, onStreamsUpdated)
        if (isExplicitAdult) {
            if (initial.isNotEmpty()) return@coroutineScope initial
            val adultFallback = AdultStreamFallback.resolve(anilistId, episodeNum, candidateTitles)
            if (adultFallback.isNotEmpty()) onStreamsUpdated(adultFallback)
            return@coroutineScope adultFallback
        }
        val needsFallback = initial.isEmpty() || initial.all { it.isTorrent && it.seeders == 0 }
        if (!runFallbackImmediately && needsFallback) {
            val fallback = FallbackStreamResolvers.resolveFallbackStreams(
                titles = candidateTitles,
                episodeNum = episodeNum,
                isStandalone = isStandalone,
                isEcchi = isEcchi,
                isMusicOrConcert = isMusicOrConcert,
                preferredAudio = preferredAudio,
                preferredQuality = preferredQuality,
                trailerYoutubeId = trailerYoutubeId,
                externalLinks = externalLinks
            )
            if (fallback.isNotEmpty()) {
                val merged = (initial + fallback)
                    .distinctBy { it.playbackIdentity }
                    .sortedByDescending { it.score }
                onStreamsUpdated(merged)
                return@coroutineScope merged
            }
        }
        initial
    }

    internal data class StreamLookup(val type: String, val id: String, val priority: Int = 0)

    internal fun streamLookups(mappings: AnimeIdMappings, episodeNum: Int, isMovie: Boolean): List<StreamLookup> = buildList {
        val seenIds = HashSet<String>()
        fun addLookup(type: String, id: String, priority: Int) {
            if (seenIds.add("$type|$id")) add(StreamLookup(type, id, priority))
        }

        // 1. Kitsu lookups with combined/split season translation
        val matchedSegment = mappings.kitsuSegments.firstOrNull { episodeNum in it.startEpisode..it.endEpisode }
        if (matchedSegment != null) {
            // Combined AniList entry (e.g. Seasons 1-2 = 26 eps) mapped onto split Kitsu entries (S1 = 14 eps, S2 = 12 eps)
            val relativeEp = (episodeNum - matchedSegment.startEpisode + 1).coerceAtLeast(1)
            addLookup("series", "kitsu:${matchedSegment.kitsuId}:$relativeEp", 220)
            mappings.kitsuId?.let { rootId ->
                if (rootId != matchedSegment.kitsuId) {
                    addLookup("series", "kitsu:$rootId:$episodeNum", 110)
                }
            }
        } else if (mappings.kitsuId != null) {
            if (mappings.kitsuUsesPrequelOffset && mappings.prequelEpisodeOffset > 0) {
                // Split AniList season (e.g. S3 = 13 eps) mapped onto combined Kitsu entry (e.g. 39 eps)
                val cumulativeEp = mappings.prequelEpisodeOffset + episodeNum
                addLookup("series", "kitsu:${mappings.kitsuId}:$cumulativeEp", 220)
                addLookup("series", "kitsu:${mappings.kitsuId}:$episodeNum", -120)
            } else {
                addLookup("series", "kitsu:${mappings.kitsuId}:$episodeNum", 200)
            }
        }

        // Secondary fallback to root prequel's Kitsu ID at cumulative offset when addons merged Kitsu seasons
        if (mappings.combinedRootKitsuId != null && mappings.prequelEpisodeOffset > 0) {
            val cumulativeEp = mappings.prequelEpisodeOffset + episodeNum
            addLookup("series", "kitsu:${mappings.combinedRootKitsuId}:$cumulativeEp", 80)
        }

        // 2. IMDb / TVDB lookups (respecting inferredSeasonNumber when ani.zip omits seasonNumber, e.g. Sailor Moon Crystal S3!)
        val imdb = mappings.imdbId ?: return@buildList
        if (isMovie) {
            addLookup("movie", imdb, 0)
            return@buildList
        }
        val episode = mappings.episodes.firstOrNull { it.episodeNumber == episodeNum }
        val defaultMappingSeason = mappings.episodes.firstOrNull { it.hasExplicitSeason }?.seasonNumber
            ?: mappings.inferredSeasonNumber.takeIf { it > 1 }
            ?: mappings.episodes.firstOrNull()?.seasonNumber
            ?: 1
        val season = when {
            episode != null && episode.hasExplicitSeason -> episode.seasonNumber
            mappings.inferredSeasonNumber > 1 -> mappings.inferredSeasonNumber
            episode != null -> episode.seasonNumber
            else -> defaultMappingSeason
        }
        val number = episode?.tvdbEpisodeNumber ?: episodeNum
        addLookup("series", "$imdb:$season:$number", 0)
        val absolute = episode?.absoluteEpisodeNumber
            ?: (mappings.prequelEpisodeOffset + episodeNum).takeIf { mappings.prequelEpisodeOffset > 0 }
        if (absolute != null && (season != 1 || absolute != number)) {
            addLookup("series", "$imdb:1:$absolute", -300)
        }
    }

    private val explicitFileSeasonEpRegex = Regex(
        "(?i)(?:\\bs0*(\\d{1,2})\\s*e0*(\\d{1,3})\\b|\\bseason\\s*0*(\\d{1,2})(?!\\s*[-~]\\s*(?:s?0*\\d))\\s*(?:/|[-_:]|\\be|ep\\.?\\s*)\\s*0*(\\d{1,3})\\b)"
    )

    private val adultFileExtension = Regex("""(?i)\.(?:mkv|mp4|m4v|webm|mov|m3u8|mpd)$""")
    private val adultReleaseGroup = Regex(
        """(?i)^\[(?![^\]]*\b(?:tv|ova|oad|ona|movie|specials?|season|cut|uncensored|censored|\d{4})\b)[^\]]+]\s*"""
    )
    private const val TECHNICAL_TAG =
        """(?:\d{3,4}[pi]|4k|web[- ]?dl|web[- ]?rip|bd(?:rip)?|blu[- ]?ray|avc|hevc|[hx]\.?26[45]|aac|flac|opus|\d{1,2}[- ]?bit|mp4|mkv|[0-9a-f]{8})"""
    private val adultTechnicalSuffix = Regex(
        """(?i)(?:\s*(?:\[$TECHNICAL_TAG(?:[ ._-]+$TECHNICAL_TAG)*]|\($TECHNICAL_TAG(?:[ ._-]+$TECHNICAL_TAG)*\)))+$"""
    )
    private val adultSeasonEpisode = Regex("""(?i)^(.+?)[\s._-]+s(\d{1,2})[\s._]*e(\d{1,4})(?:v\d+)?$""")
    private val adultEpisode = Regex("""(?i)^(.+?)(?:\s+-\s+|[\s._]+e(?:p(?:isode)?)?[\s._]*)(\d{1,4})(?:v\d+)?$""")
    private val adultTitleSeparators = Regex("""[^\p{L}\p{M}\p{N}]+""")

    private fun adultTitleKey(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKC)
            .replace('×', 'x').replace('✕', 'x').lowercase()
            .replace(adultTitleSeparators, " ").trim()

    internal fun matchesAdultEpisodeLabel(
        label: String,
        candidateTitles: List<String>,
        episode: Int,
        expectedSeason: Int,
        expectedAbsoluteEpisode: Int?,
        isVerifiedStandalone: Boolean
    ): Boolean {
        if (episode <= 0) return false
        val aliases = candidateTitles.map(::adultTitleKey).filter(String::isNotEmpty).toSet()
        val fileLabel = label.trim().substringAfterLast('/').substringAfterLast('\\')
            .replace(adultFileExtension, "")
            .replace(adultReleaseGroup, "")
            .replace(adultTechnicalSuffix, "")
            .trim()
        if (isVerifiedStandalone && episode == 1 && adultTitleKey(fileLabel) in aliases) return true

        adultSeasonEpisode.matchEntire(fileLabel)?.let { match ->
            val (title, seasonText, episodeText) = match.destructured
            val fileSeason = seasonText.toInt()
            val fileEpisode = episodeText.toInt()
            return adultTitleKey(title) in aliases &&
                ((fileSeason == expectedSeason && fileEpisode == episode) ||
                    (fileSeason == 1 && fileEpisode == expectedAbsoluteEpisode))
        }

        val (title, episodeText) = adultEpisode.matchEntire(fileLabel)?.destructured ?: return false
        val fileEpisode = episodeText.toInt()
        return adultTitleKey(title) in aliases &&
            (fileEpisode == expectedAbsoluteEpisode ||
                (fileEpisode == episode &&
                    (expectedSeason == 1 || extractExplicitSeasonFromTitle(title) == expectedSeason)))
    }

    internal fun isMismatchedSeasonFile(
        fileLine: String,
        expectedSeason: Int,
        expectedAbsoluteEpisode: Int?
    ): Boolean {
        if (expectedSeason <= 1 || fileLine.isBlank()) return false
        val match = explicitFileSeasonEpRegex.find(fileLine) ?: return false
        val fileSeason = (match.groupValues[1].ifEmpty { match.groupValues[3] }).toIntOrNull() ?: return false
        val fileEp = (match.groupValues[2].ifEmpty { match.groupValues[4] }).toIntOrNull() ?: return false
        if (fileSeason == expectedSeason) return false
        // Allow S01E<absolute> when release group numbered multi-season anime continuously under S01
        if (fileSeason == 1 && expectedAbsoluteEpisode != null && fileEp == expectedAbsoluteEpisode) return false
        return true
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

    private val dualAudioRegex = Regex(
        "(?i)(?:\\bdual[ ._\\-]?audio\\b|\\bdualaudio\\b|\\[dual]|\\(dual\\)|\\.dual\\.|\\bmulti[ ._\\-]?audio\\b|\\b2audio\\b|\\b(?:ja|jpn|jap)\\s*[+/\\-]\\s*(?:en|eng)\\b|\\b(?:en|eng)\\s*[+/\\-]\\s*(?:ja|jpn|jap)\\b)"
    )
    private val englishDubRegex = Regex(
        "(?i)(?:\\b(?:eng|english|en)[ ._\\-]?dub(?:bed)?\\b|\\bdub(?:bed)?[ ._\\-]?(?:eng|english)\\b|\\[dub]|\\(dub\\)|\\[yameii]|\\bdubbed\\b|\\bdub\\b)"
    )
    private val foreignOnlyDubRegex = Regex(
        "(?i)(?:\\banidub\\b|\\bdublado\\b|\\bsubtitulado\\b|\\b(?:rus|russian|ukr|ukrainian|ger|german|ita|italian|spa|spanish|latino|castellano|por|portuguese|pt[._\\-]?br|fra|french|vf|vostfr|hin|hindi|tam|tamil|tel|telugu|ara|arabic|pol|polish|tur|turkish)\\b)"
    )
    private val explicitEnglishTagRegex = Regex("(?i)(?:\\beng\\b|\\benglish\\b|🇺🇸)")

    internal fun detectAudioType(raw: String): String {
        val lower = raw.lowercase()
        val hasForeignTag = foreignOnlyDubRegex.containsMatchIn(lower)
        val hasExplicitEnglish = explicitEnglishTagRegex.containsMatchIn(lower)

        if (dualAudioRegex.containsMatchIn(lower)) {
            if (!hasForeignTag || hasExplicitEnglish || lower.contains("dual audio") || lower.contains("dual-audio")) {
                return "DUAL_AUDIO"
            }
        }
        if (englishDubRegex.containsMatchIn(lower)) {
            if (!hasForeignTag || hasExplicitEnglish) {
                return "DUB"
            }
        }
        return "SUB"
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
