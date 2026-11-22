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

object CrunchyrollStreamEngine {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // Built-in public trackers for fast magnet discovery without exposing technical settings
    private val FAST_TRACKERS = listOf(
        "http://nyaa.tracker.wf:7777/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://open.demonii.com:1337/announce"
    )

    // Built-in invisible streaming providers (Torrentio + AnimeKitsu)
    private val BUILTIN_PROVIDERS = listOf(
        "https://torrentio.strem.fun",
        "https://animekitsu.strem.fun"
    )

    private val mappingCache = ConcurrentHashMap<Int, AnimeIdMappings>()

    /**
     * Resolves AniList ID -> Kitsu ID, MAL ID, IMDb ID, and rich Crunchyroll-style Episode metadata via api.ani.zip
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
                .header("User-Agent", "NanamiAnime/2.0")
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
                        val epTitle = titleObj?.optString("en")?.takeIf { it.isNotBlank() }
                            ?: titleObj?.optString("x-jat")?.takeIf { it.isNotBlank() }
                            ?: titleObj?.optString("ja")?.takeIf { it.isNotBlank() }
                            ?: "Episode $epNum"

                        val image = epObj.optString("image").takeIf { it.isNotBlank() && it != "null" }
                        val overview = epObj.optString("overview").takeIf { it.isNotBlank() && it != "null" }
                        val airDate = epObj.optString("airDate").takeIf { it.isNotBlank() && it != "null" }
                        val length = epObj.optInt("length", 0).takeIf { it > 0 }

                        episodesList.add(
                            CrunchyEpisodeMeta(
                                episodeNumber = epNum,
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

        // Fallback Kitsu lookup by title if AniZip didn't have kitsu_id
        if (kitsuId.isNullOrBlank() && fallbackTitle.isNotBlank()) {
            kitsuId = resolveKitsuIdByTitle(fallbackTitle)
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

    /**
     * Merges AniList streamingEpisodes with AniZip episode metadata so every anime has a complete,
     * Crunchyroll-style episode list with thumbnails and synopses.
     */
    fun buildCompleteEpisodeList(
        mappings: AnimeIdMappings?,
        anilistStreamingEps: List<StreamingEpisode>,
        totalEpisodes: Int?,
        latestAired: Int?
    ): List<CrunchyEpisodeMeta> {
        val maxEp = latestAired
            ?: totalEpisodes
            ?: mappings?.episodeCount
            ?: mappings?.episodes?.maxOfOrNull { it.episodeNumber }
            ?: anilistStreamingEps.maxOfOrNull { it.episodeNumber }
            ?: 12

        val safeCap = maxEp.coerceIn(1, 1500)
        val aniZipMap = mappings?.episodes?.associateBy { it.episodeNumber } ?: emptyMap()
        val anilistMap = anilistStreamingEps.associateBy { it.episodeNumber }

        val list = mutableListOf<CrunchyEpisodeMeta>()
        val targetCount = maxOf(safeCap, aniZipMap.keys.maxOrNull() ?: 0, anilistMap.keys.maxOrNull() ?: 0).coerceAtMost(1500)

        for (epNum in 1..targetCount) {
            val az = aniZipMap[epNum]
            val al = anilistMap[epNum]

            val rawAlTitle = al?.title?.replaceFirst(Regex("^Episode\\s*\\d+\\s*[-:\\.]\\s*", RegexOption.IGNORE_CASE), "")?.trim()
            val finalTitle = when {
                !az?.title.isNullOrBlank() && az!!.title != "Episode $epNum" -> az.title
                !rawAlTitle.isNullOrBlank() -> rawAlTitle
                else -> "Episode $epNum"
            }

            list.add(
                CrunchyEpisodeMeta(
                    episodeNumber = epNum,
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
     * Fetches OP/ED skip timestamps from AniSkip using the MAL ID
     */
    suspend fun getAniSkipIntervals(malId: Int?, episodeNumber: Int): List<AniSkipInterval> = withContext(Dispatchers.IO) {
        if (malId == null || malId <= 0) return@withContext emptyList()
        val results = mutableListOf<AniSkipInterval>()
        try {
            val url = "https://api.aniskip.com/v2/skip-times/$malId/$episodeNumber?types[]=op&types[]=ed&episodeLength=0"
            val req = Request.Builder().url(url).build()
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

        val queryIds = mutableListOf<String>()
        if (!mappings.kitsuId.isNullOrBlank()) {
            queryIds.add("kitsu:${mappings.kitsuId}:$episodeNum")
        }
        if (!mappings.imdbId.isNullOrBlank()) {
            queryIds.add("${mappings.imdbId}:1:$episodeNum")
        }

        if (queryIds.isEmpty()) return@coroutineScope emptyList()

        val trackerParams = FAST_TRACKERS.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }

        val jobs = BUILTIN_PROVIDERS.flatMap { providerUrl ->
            queryIds.map { qid ->
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

                            val score = scoreStream(
                                rawText = combinedText,
                                audioType = audio,
                                quality = quality,
                                seeders = seeds,
                                preferredAudio = preferredAudio,
                                preferredQuality = preferredQuality
                            )

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
                                        score = score + 50
                                    )
                                )
                            } else if (!s.infoHash.isNullOrBlank()) {
                                val hash = s.infoHash.lowercase().trim()
                                val dn = URLEncoder.encode("$animeTitle - Episode $episodeNum", "UTF-8")
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

        // 1. Seeders health (up to 400 pts, critical for instant start)
        score += when {
            seeders >= 100 -> 400
            seeders >= 50 -> 340
            seeders >= 20 -> 280
            seeders >= 10 -> 220
            seeders >= 5 -> 150
            seeders >= 2 -> 80
            seeders == 1 -> 20
            else -> -300
        }
        score += (seeders.coerceAtMost(300) / 3)

        // 2. Audio preference matching
        if (preferredAudio.equals("DUB", ignoreCase = true)) {
            score += when (audioType) {
                "DUAL_AUDIO" -> 350
                "DUB" -> 320
                else -> -120
            }
        } else {
            // SUB preferred
            score += when (audioType) {
                "SUB" -> 300
                "DUAL_AUDIO" -> 280
                "DUB" -> -150
                else -> 100
            }
        }

        // 3. Resolution matching
        if (quality.equals(preferredQuality, ignoreCase = true)) {
            score += 220
        } else if (quality == "1080p") {
            score += 180
        } else if (quality == "720p") {
            score += 140
        } else if (quality == "4K") {
            score += 90
        }

        // 4. Trusted fast-playback release groups (H.264 / fast-decode HEVC with clean soft subs)
        if (lower.contains("subsplease") || lower.contains("erai-raws") || lower.contains("horriblesubs")) {
            score += 160
        } else if (lower.contains("judas") || lower.contains("ember") || lower.contains("dkb") || lower.contains("asw")) {
            score += 130
        }

        // 5. Avoid 10-bit Hi10P legacy encodes or huge BD remuxes (> 3 GB per ep) that buffer on mobile
        if (lower.contains("remux") || lower.contains("bdrip 1080p flac")) {
            score -= 100
        }

        return score
    }

    private fun detectAudioType(raw: String): String {
        val lower = raw.lowercase()
        return if (lower.contains("dual") || lower.contains("multi-audio") || lower.contains("multi audio") || lower.contains("ja+en") || lower.contains("jpn+eng")) {
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
