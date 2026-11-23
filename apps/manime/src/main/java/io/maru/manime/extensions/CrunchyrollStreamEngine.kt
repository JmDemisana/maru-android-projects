package io.maru.manime.extensions

import io.maru.manime.StreamingEpisode
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
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

    private val mappingCache = ConcurrentHashMap<Int, AnimeIdMappings>()
    private val dubStatusCache = ConcurrentHashMap<Int, StreamDubStatus>()

    /**
     * Checks live Torrentio/Kitsu streams for Episode 1 to detect if Dual-Audio / Multi-Audio / Dubbed
     * streams exist even when AniList hasn't cataloged the dub yet!
     */
    suspend fun checkStreamDubAvailability(anilistId: Int, animeTitle: String, episodeNum: Int = 1): StreamDubStatus = withContext(Dispatchers.IO) {
        dubStatusCache[anilistId]?.let { return@withContext it }
        try {
            val streams = resolveRankedStreams(
                anilistId = anilistId,
                animeTitle = animeTitle,
                episodeNum = episodeNum,
                preferredAudio = "DUB"
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
            dubStatusCache[anilistId] = status
            status
        } catch (_: Exception) {
            StreamDubStatus()
        }
    }

    /**
     * Resolves AniList ID -> Kitsu ID, MAL ID, IMDb ID, and rich Crunchyroll-style Episode metadata
     * via api.ani.zip, with Kitsu API fallback so episode titles/thumbnails/overviews are always populated.
     */
    suspend fun getAnimeMappings(anilistId: Int, fallbackTitle: String = ""): AnimeIdMappings = withContext(Dispatchers.IO) {
        mappingCache[anilistId]?.let { return@withContext it }

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
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string()
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
                        val epNum = key.toIntOrNull() ?: continue
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
                        val seasonNum = epObj.optInt("seasonNumber", 1).takeIf { it > 0 } ?: 1
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
        } catch (_: Exception) {}

        if (kitsuId.isNullOrBlank() && fallbackTitle.isNotBlank()) {
            kitsuId = resolveKitsuIdByTitle(fallbackTitle)
        }

        // If AniZip didn't return rich episode metadata, fetch episode titles/thumbnails/synopses from Kitsu API
        if (episodesList.isEmpty() && !kitsuId.isNullOrBlank()) {
            episodesList.addAll(fetchKitsuEpisodes(kitsuId!!))
        }

        val sortedEps = episodesList.sortedBy { it.episodeNumber }
        val result = AnimeIdMappings(
            anilistId = anilistId,
            malId = malId,
            kitsuId = kitsuId,
            imdbId = imdbId,
            tvdbId = tvdbId,
            episodeCount = epCount,
            episodes = sortedEps
        )
        mappingCache[anilistId] = result
        result
    }

    private fun resolveKitsuIdByTitle(title: String): String? {
        val clean = title.replace(Regex("[^a-zA-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return null
        return try {
            val encoded = URLEncoder.encode(clean, "UTF-8")
            val req = Request.Builder()
                .url("https://kitsu.io/api/edge/anime?filter[text]=$encoded&page[limit]=1")
                .header("User-Agent", StremioAddonClient.BROWSER_UA)
                .build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: return null
            val data = JSONObject(body).optJSONArray("data")
            if (data != null && data.length() > 0) {
                data.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchKitsuEpisodes(kitsuId: String): List<CrunchyEpisodeMeta> {
        val list = mutableListOf<CrunchyEpisodeMeta>()
        try {
            val req = Request.Builder()
                .url("https://kitsu.io/api/edge/anime/$kitsuId/episodes?page[limit]=20&sort=number")
                .header("User-Agent", StremioAddonClient.BROWSER_UA)
                .build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: return emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            for (i in 0 until data.length()) {
                val attr = data.getJSONObject(i).optJSONObject("attributes") ?: continue
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

        // Today in YYYY-MM-DD (UTC+8) to filter out future scheduled episodes when latestAired is unknown
        val todayIso = io.maru.manime.singaporeDate()
        val highestAiredFromDates = mappings?.episodes
            ?.filter { !it.airDate.isNullOrBlank() && it.airDate <= todayIso }
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
        }.coerceIn(0, 1500)

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
        episodeNum: Int
    ): List<ExternalSubtitleItem> = withContext(Dispatchers.IO) {
        val mappings = getAnimeMappings(anilistId, animeTitle)
        val epMeta = mappings.episodes.firstOrNull { it.episodeNumber == episodeNum }
        val trueSeason = epMeta?.seasonNumber ?: mappings.episodes.firstOrNull()?.seasonNumber ?: 1
        val trueTvdbEp = epMeta?.tvdbEpisodeNumber ?: episodeNum

        val queryIds = mutableListOf<String>()
        if (!mappings.imdbId.isNullOrBlank()) {
            queryIds.add("${mappings.imdbId}:$trueSeason:$trueTvdbEp")
            if (trueSeason != 1 && epMeta?.absoluteEpisodeNumber != null) {
                queryIds.add("${mappings.imdbId}:1:${epMeta.absoluteEpisodeNumber}")
            }
        }
        if (!mappings.kitsuId.isNullOrBlank()) {
            queryIds.add("kitsu:${mappings.kitsuId}:$episodeNum")
        }
        if (queryIds.isEmpty()) return@withContext emptyList()

        val results = mutableListOf<ExternalSubtitleItem>()
        for (addon in OPENSUBTITLES_ADDONS) {
            for (qid in queryIds) {
                try {
                    val req = Request.Builder()
                        .url("$addon/subtitles/series/$qid.json")
                        .header("User-Agent", StremioAddonClient.BROWSER_UA)
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string() ?: continue
                    val subsArr = JSONObject(body).optJSONArray("subtitles") ?: continue

                    for (i in 0 until subsArr.length()) {
                        val s = subsArr.getJSONObject(i)
                        val subUrl = s.optString("url", "").trim()
                        if (subUrl.isEmpty()) continue
                        val lang = s.optString("lang", "eng").lowercase()
                        val id = s.optString("id", "os_$i")
                        val fileName = s.optString("subtitleFileName", "").lowercase()
                        val fmt = when {
                            fileName.endsWith(".ass") || fileName.endsWith(".ssa") || subUrl.endsWith(".ass") -> "ASS"
                            fileName.endsWith(".vtt") || subUrl.endsWith(".vtt") -> "VTT"
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
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            if (json.optBoolean("found", false)) {
                val arr = json.optJSONArray("results") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val interval = item.optJSONObject("interval") ?: continue
                    val start = interval.optDouble("startTime", -1.0)
                    val end = interval.optDouble("endTime", -1.0)
                    val skipType = item.optString("skipType", "op")
                    if (start >= 0 && end > start) {
                        results.add(AniSkipInterval(start, end, skipType))
                    }
                }
            }
        } catch (_: Exception) {}
        results
    }

    /**
     * Fetches and scores all available streams from built-in Torrentio/Kitsu providers,
     * returning them ranked from best to fallback for the user's preferred audio (SUB or DUB) and quality.
     */
    suspend fun resolveRankedStreams(
        anilistId: Int,
        animeTitle: String,
        episodeNum: Int,
        preferredAudio: String = "SUB", // "SUB" | "DUB"
        preferredQuality: String = "1080p" // "1080p" | "720p" | "4K"
    ): List<StreamLink> = coroutineScope {
        val mappings = getAnimeMappings(anilistId, animeTitle)
        val epMeta = mappings.episodes.firstOrNull { it.episodeNumber == episodeNum }
        val trueSeason = epMeta?.seasonNumber ?: mappings.episodes.firstOrNull()?.seasonNumber ?: 1
        val trueTvdbEp = epMeta?.tvdbEpisodeNumber ?: episodeNum

        // 1. Kitsu ID uses per-season AniList episode numbering (kitsu:{id}:{episodeNum})
        // 2. IMDb ID uses combined multi-season numbering (tt{id}:{seasonNumber}:{tvdbEpisodeNumber} and absoluteEpisodeNumber)
        val queryIds = mutableListOf<Pair<String, Boolean>>() // (queryId, isKitsu)
        if (!mappings.kitsuId.isNullOrBlank()) {
            queryIds.add("kitsu:${mappings.kitsuId}:$episodeNum" to true)
        }
        if (!mappings.imdbId.isNullOrBlank()) {
            queryIds.add("${mappings.imdbId}:$trueSeason:$trueTvdbEp" to false)
            if (epMeta?.absoluteEpisodeNumber != null && epMeta.absoluteEpisodeNumber != episodeNum) {
                queryIds.add("${mappings.imdbId}:1:${epMeta.absoluteEpisodeNumber}" to false)
            }
        }

        if (queryIds.isEmpty()) return@coroutineScope emptyList()

        val jobs = BUILTIN_PROVIDERS.flatMap { providerUrl ->
            queryIds.map { (qid, isKitsuSource) ->
                async(Dispatchers.IO) {
                    val found = mutableListOf<StreamLink>()
                    try {
                        val client = StremioAddonClient(providerUrl, httpClient)
                        val rawStreams = client.getStreams("series", qid)
                        for (s in rawStreams) {
                            val combinedText = "${s.name ?: ""} ${s.title ?: ""}"
                            val audio = detectAudioType(combinedText)
                            val quality = detectQuality(combinedText)
                            val seeds = detectSeeders(combinedText) ?: 0

                            var score = scoreStream(
                                rawText = combinedText,
                                audioType = audio,
                                quality = quality,
                                seeders = seeds,
                                preferredAudio = preferredAudio,
                                preferredQuality = preferredQuality
                            )
                            if (isKitsuSource) score += 200 // Strongly prefer exact Kitsu anime ID match

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
                                        filename = s.title,
                                        formatBadge = quality,
                                        score = score + 80
                                    )
                                )
                            } else if (!s.infoHash.isNullOrBlank()) {
                                val hash = s.infoHash.lowercase().trim()
                                val dn = URLEncoder.encode("$animeTitle - Episode $episodeNum", "UTF-8")
                                val allTrackers = (s.sources + FAST_TRACKERS).distinct()
                                val trackerParams = allTrackers.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
                                val magnet = "magnet:?xt=urn:btih:$hash&dn=$dn$trackerParams"
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
                                        filename = s.title,
                                        formatBadge = quality,
                                        score = score
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {}
                    found
                }
            }
        }

        val allStreams = jobs.awaitAll().flatten()
        allStreams
            .distinctBy { "${it.infoHash ?: it.url}_${it.fileIdx ?: -1}" }
            .filter { (it.seeders ?: 0) > 0 || !it.isTorrent }
            .sortedByDescending { it.score }
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
        seeders: Int,
        preferredAudio: String,
        preferredQuality: String
    ): Int {
        var score = 0
        val lower = rawText.lowercase()

        // 1. Seeders health
        score += when {
            seeders >= 100 -> 450
            seeders >= 50 -> 380
            seeders >= 20 -> 310
            seeders >= 10 -> 240
            seeders >= 5 -> 160
            seeders >= 2 -> 80
            seeders == 1 -> 20
            else -> -400
        }
        score += (seeders.coerceAtMost(300) / 3)

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
            lower.contains("jpn+eng") ||
            lower.contains("dubbed")
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
        val matcher = Pattern.compile("(?:👤|seeders|seeds|peers)\\s*(\\d+)|(\\d+)\\s*(?:seed|👤|peers)").matcher(raw.lowercase())
        if (matcher.find()) {
            return matcher.group(1)?.toIntOrNull() ?: matcher.group(2)?.toIntOrNull()
        }
        return null
    }
}
