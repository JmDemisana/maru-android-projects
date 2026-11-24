package io.maru.manime.extensions

import io.maru.manime.ExternalLink
import io.maru.manime.cleanAnimeTitle
import io.maru.manime.readResponse
import io.maru.manime.torrent.torrentEpisodeNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Multi-source fallback stream engine covering content that standard Stremio/Kitsu addons miss:
 * 1. Uncensored / Edgy Ecchi (`[AT-X]`, `[BD]`, `[Uncensored]`): AnimeTosho JSON + Nyaa RSS mirrors
 * 2. 18+ Adult / Hentai (`isAdult = true`): Sukebei Nyaa (`c=1_1` Art - Anime) + Sukebei mirrors
 * 3. Music Videos (`format == "MUSIC"`), Concerts & Hard-to-Find Live Specials:
 *    - Official YouTube external links / AniList trailer IDs resolved via Piped / Invidious APIs into direct MP4/HLS
 *    - Internet Archive (`archive.org`) direct CDN `.mp4` / `.mkv` movies & concerts
 *    - Nyaa All-Categories (`c=0_0`: catches `1_1` Anime MVs, `1_4` Raw Specials, and `4_0` 2.5D Seiyuu Concerts)
 */
internal object FallbackStreamResolvers {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val EXTRA_TRACKERS = listOf(
        "http://sukebei.tracker.wf:8888/announce",
        "http://nyaa.tracker.wf:7777/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.torrent.eu.org:451/announce"
    )

    private val PIPED_INSTANCES = listOf(
        "https://pipedapi.kavin.rocks",
        "https://api.piped.private.coffee"
    )

    private val INVIDIOUS_INSTANCES = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de"
    )

    private val NYAA_BASES = listOf(
        "https://nyaa.si",
        "https://nyaa.land"
    )

    private val SUKEBEI_BASES = listOf(
        "https://sukebei.nyaa.si",
        "https://sukebei.nyaa.land"
    )

    private val youtubeIdRegex = Regex(
        "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|embed/|shorts/|live/))([A-Za-z0-9_-]{8,})",
        RegexOption.IGNORE_CASE
    )

    suspend fun resolveFallbackStreams(
        titles: List<String>,
        episodeNum: Int,
        isStandalone: Boolean,
        isAdult: Boolean,
        isEcchi: Boolean,
        isMusicOrConcert: Boolean,
        preferredAudio: String,
        preferredQuality: String,
        trailerYoutubeId: String? = null,
        externalLinks: List<ExternalLink> = emptyList()
    ): List<StreamLink> = coroutineScope {
        val cleanTitles = titles
            .flatMap { listOf(it, cleanAnimeTitle(it)) }
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinctBy { normalizeKey(it) }
            .take(6)
        if (cleanTitles.isEmpty()) return@coroutineScope emptyList()

        val displayTitle = cleanTitles.first()
        val isAdultOrEcchi = isAdult || isEcchi
        val tasks = buildList {
            // 1. 18+ Adult / Hentai & Uncensored Ecchi -> Sukebei Nyaa (Art - Anime c=1_1)
            if (isAdultOrEcchi) {
                for (t in cleanTitles.take(3)) {
                    val cleanBase = cleanAnimeTitle(t).ifBlank { t }
                    add(async(Dispatchers.IO) {
                        searchNyaaRss(
                            bases = SUKEBEI_BASES,
                            category = "1_1",
                            queryTitle = cleanBase,
                            allTitles = cleanTitles,
                            episodeNum = episodeNum,
                            isStandalone = isStandalone,
                            isAdultOrEcchi = true,
                            preferredAudio = preferredAudio,
                            preferredQuality = preferredQuality,
                            sourceLabelPrefix = if (isAdult) "Adult Vault" else "Uncensored Vault"
                        )
                    })
                }
            }

            // 2. Nyaa RSS (main anime index):
            // Catches English-translated anime (c=1_2) and general anime (c=1_0), or all categories (c=0_0) for concerts
            val nyaaCategories = when {
                isMusicOrConcert -> listOf("0_0")
                isAdultOrEcchi -> listOf("1_2", "1_0")
                else -> listOf("1_2", "1_0")
            }
            for (t in cleanTitles.take(3)) {
                val cleanBase = cleanAnimeTitle(t).ifBlank { t }
                for (cat in nyaaCategories) {
                    add(async(Dispatchers.IO) {
                        searchNyaaRss(
                            bases = NYAA_BASES,
                            category = cat,
                            queryTitle = cleanBase,
                            allTitles = cleanTitles,
                            episodeNum = episodeNum,
                            isStandalone = isStandalone || isMusicOrConcert,
                            isAdultOrEcchi = isAdultOrEcchi,
                            preferredAudio = preferredAudio,
                            preferredQuality = preferredQuality,
                            sourceLabelPrefix = if (isMusicOrConcert) "Concert / MV Archive" else if (isAdultOrEcchi) "Ecchi / Archive" else "Archive"
                        )
                    })
                }
            }

            // 3. Music Videos, Idol Concerts & Specials ONLY -> Internet Archive CDN + Official YouTube/Piped Direct Streams
            if (isMusicOrConcert) {
                add(async(Dispatchers.IO) {
                    searchInternetArchive(
                        cleanTitles = cleanTitles,
                        episodeNum = episodeNum,
                        isStandalone = true
                    )
                })
                add(async(Dispatchers.IO) {
                    resolveYoutubeMusicOrConcertStreams(
                        displayTitle = displayTitle,
                        cleanTitles = cleanTitles,
                        trailerYoutubeId = trailerYoutubeId,
                        externalLinks = externalLinks
                    )
                })
            }
        }

        tasks.awaitAll()
            .flatten()
            .distinctBy { it.playbackIdentity }
            .sortedByDescending { it.score }
    }

    /**
     * Queries Nyaa / Sukebei RSS XML feeds (with mirror fallback if primary domain fails).
     */
    private suspend fun searchNyaaRss(
        bases: List<String>,
        category: String,
        queryTitle: String,
        allTitles: List<String>,
        episodeNum: Int,
        isStandalone: Boolean,
        isAdultOrEcchi: Boolean,
        preferredAudio: String,
        preferredQuality: String,
        sourceLabelPrefix: String
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val baseQuery = cleanAnimeTitle(queryTitle).ifBlank { queryTitle }
        val queries = buildList {
            if (!isStandalone) {
                add("$baseQuery ${episodeNum.toString().padStart(2, '0')}")
                add("$baseQuery $episodeNum")
            }
            add(baseQuery)
        }.distinct()

        val found = mutableListOf<StreamLink>()
        for (q in queries) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            var xmlBody: String? = null
            for (base in bases) {
                xmlBody = try {
                    val req = Request.Builder()
                        .url("$base/?page=rss&c=$category&f=0&q=$encoded")
                        .header("User-Agent", StremioAddonClient.BROWSER_UA)
                        .build()
                    client.newCall(req).readResponse { res -> if (res.isSuccessful) res.body?.string() else null }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (!xmlBody.isNullOrBlank()) break
            }
            if (xmlBody.isNullOrBlank()) continue

            val doc = runCatching { Jsoup.parse(xmlBody, "", Parser.xmlParser()) }.getOrNull() ?: continue
            val items = doc.select("item")
            for (el in items.take(35)) {
                val releaseTitle = el.selectFirst("title")?.text()?.trim().orEmpty()
                if (releaseTitle.isEmpty()) continue
                if (isNonVideoRelease(releaseTitle)) continue
                if (!matchesTitleAndEpisode(releaseTitle, allTitles, episodeNum, isStandalone)) continue

                val hash = el.getElementsByTag("nyaa:infoHash").firstOrNull()?.text()?.trim()?.lowercase().orEmpty()
                if (hash.length != 40) continue
                val seeds = el.getElementsByTag("nyaa:seeders").firstOrNull()?.text()?.trim()?.toIntOrNull() ?: 0
                if (seeds <= 0) continue

                val magnet = buildEnrichedMagnet(hash, "", releaseTitle)
                val audio = CrunchyrollStreamEngine.detectAudioType(releaseTitle)
                val quality = detectFallbackQuality(releaseTitle)
                val score = scoreFallbackRelease(
                    releaseTitle = releaseTitle,
                    audio = audio,
                    quality = quality,
                    seeders = seeds,
                    isAdultOrEcchi = isAdultOrEcchi,
                    preferredAudio = preferredAudio,
                    preferredQuality = preferredQuality
                )

                found += StreamLink(
                    url = magnet,
                    quality = quality,
                    sourceName = buildFallbackSourceLabel(sourceLabelPrefix, releaseTitle, audio, quality),
                    isTorrent = true,
                    infoHash = hash,
                    audioType = audio,
                    methodType = "ARCHIVE",
                    seeders = seeds,
                    filename = releaseTitle,
                    formatBadge = quality,
                    score = score
                )
            }
            if (found.isNotEmpty()) break
        }
        found
    }

    /**
     * Searches Internet Archive (`archive.org`) for rare anime concerts, MVs, OVAs, and specials
     * and resolves direct HTTPS `.mp4` / `.mkv` CDN links.
     */
    private suspend fun searchInternetArchive(
        cleanTitles: List<String>,
        episodeNum: Int,
        isStandalone: Boolean
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val primary = cleanTitles.first()
        val encoded = URLEncoder.encode("\"$primary\" AND mediatype:(movies)", "UTF-8")
        val searchUrl = "https://archive.org/advancedsearch.php?q=$encoded&fl[]=identifier,title&sort[]=downloads+desc&rows=5&output=json"

        val body = try {
            val req = Request.Builder().url(searchUrl).header("User-Agent", StremioAddonClient.BROWSER_UA).build()
            client.newCall(req).readResponse { res -> if (res.isSuccessful) res.body?.string() else null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext emptyList()

        val docs = runCatching {
            JSONObject(body).optJSONObject("response")?.optJSONArray("docs")
        }.getOrNull() ?: return@withContext emptyList()

        val results = mutableListOf<StreamLink>()
        for (i in 0 until docs.length()) {
            val doc = docs.optJSONObject(i) ?: continue
            val identifier = doc.optString("identifier", "").trim()
            val itemTitle = doc.optString("title", "").trim()
            if (identifier.isEmpty() || !matchesAnyTitleWords(itemTitle, cleanTitles)) continue

            val metaBody = try {
                val req = Request.Builder()
                    .url("https://archive.org/metadata/$identifier")
                    .header("User-Agent", StremioAddonClient.BROWSER_UA)
                    .build()
                client.newCall(req).readResponse { res -> if (res.isSuccessful) res.body?.string() else null }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue

            val files = runCatching { JSONObject(metaBody).optJSONArray("files") }.getOrNull() ?: continue
            val videoFiles = mutableListOf<Pair<String, Long>>()
            for (fIdx in 0 until files.length()) {
                val f = files.optJSONObject(fIdx) ?: continue
                val name = f.optString("name", "")
                val lower = name.lowercase()
                if ((lower.endsWith(".mp4") || lower.endsWith(".mkv")) && !lower.endsWith(".ia.mp4")) {
                    val size = f.optString("size", "0").toLongOrNull() ?: 0L
                    if (size > 2 * 1024 * 1024L) {
                        videoFiles += name to size
                    }
                }
            }
            if (videoFiles.isEmpty()) continue

            val chosen = if (isStandalone || videoFiles.size == 1) {
                videoFiles.maxByOrNull { it.second }
            } else {
                videoFiles.firstOrNull { torrentEpisodeNumber(it.first) == episodeNum }
            } ?: continue

            val encodedPath = chosen.first.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
            val directUrl = "https://archive.org/download/$identifier/$encodedPath"
            val quality = detectFallbackQuality(chosen.first)
            val audio = CrunchyrollStreamEngine.detectAudioType("$itemTitle ${chosen.first}")

            results += StreamLink(
                url = directUrl,
                quality = quality,
                sourceName = "Internet Archive Direct • $quality",
                isTorrent = false,
                audioType = audio,
                methodType = "DIRECT",
                filename = chosen.first,
                formatBadge = quality,
                sizeBytes = chosen.second,
                score = 620
            )
        }
        results
    }

    /**
     * Resolves official YouTube MV / Concert links (from AniList `trailer` or `externalLinks`, or Piped search)
     * into direct ExoPlayer-compatible MP4 / HLS streams via Piped & Invidious APIs.
     */
    private suspend fun resolveYoutubeMusicOrConcertStreams(
        displayTitle: String,
        cleanTitles: List<String>,
        trailerYoutubeId: String?,
        externalLinks: List<ExternalLink>
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val videoIds = LinkedHashSet<String>()
        if (!trailerYoutubeId.isNullOrBlank()) {
            videoIds += trailerYoutubeId.trim()
        }
        for (link in externalLinks) {
            extractYoutubeId(link.url)?.let { videoIds += it }
        }

        if (videoIds.isEmpty()) {
            for (api in PIPED_INSTANCES) {
                val searchedId = try {
                    val q = URLEncoder.encode(displayTitle, "UTF-8")
                    val req = Request.Builder()
                        .url("$api/search?q=$q&filter=videos")
                        .header("User-Agent", StremioAddonClient.BROWSER_UA)
                        .build()
                    val body = client.newCall(req).readResponse { res -> if (res.isSuccessful) res.body?.string() else null }
                    val items = body?.let { JSONObject(it).optJSONArray("items") }
                    var matchedId: String? = null
                    if (items != null) {
                        for (i in 0 until minOf(items.length(), 6)) {
                            val item = items.optJSONObject(i) ?: continue
                            val title = item.optString("title", "")
                            val urlPath = item.optString("url", "")
                            val duration = item.optInt("duration", 0)
                            if (duration >= 60 && matchesAnyTitleWords(title, cleanTitles)) {
                                matchedId = extractYoutubeId("https://www.youtube.com$urlPath")
                                if (matchedId != null) break
                            }
                        }
                    }
                    matchedId
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (searchedId != null) {
                    videoIds += searchedId
                    break
                }
            }
        }

        val streams = mutableListOf<StreamLink>()
        for (vid in videoIds.take(2)) {
            var foundForVid = false
            for (api in PIPED_INSTANCES) {
                val resolved = try {
                    val req = Request.Builder()
                        .url("$api/streams/$vid")
                        .header("User-Agent", StremioAddonClient.BROWSER_UA)
                        .build()
                    val body = client.newCall(req).readResponse { res -> if (res.isSuccessful) res.body?.string() else null }
                    if (body == null) emptyList() else {
                        val json = JSONObject(body)
                        val title = json.optString("title", displayTitle)
                        val out = mutableListOf<StreamLink>()

                        val hls = json.optString("hls", "").trim()
                        if (hls.startsWith("https://")) {
                            out += StreamLink(
                                url = hls,
                                quality = "1080p",
                                sourceName = "Official MV / Live Stream • Adaptive HD",
                                isTorrent = false,
                                audioType = "SUB",
                                methodType = "DIRECT",
                                filename = title,
                                formatBadge = "HLS HD",
                                score = 720
                            )
                        }

                        val videoStreams = json.optJSONArray("videoStreams")
                        if (videoStreams != null) {
                            for (i in 0 until videoStreams.length()) {
                                val vs = videoStreams.optJSONObject(i) ?: continue
                                if (vs.optBoolean("videoOnly", true)) continue
                                val sUrl = vs.optString("url", "").trim()
                                val qual = vs.optString("quality", "720p")
                                if (sUrl.startsWith("https://")) {
                                    out += StreamLink(
                                        url = sUrl,
                                        quality = qual,
                                        sourceName = "Official MV / Live Stream • $qual MP4",
                                        isTorrent = false,
                                        audioType = "SUB",
                                        methodType = "DIRECT",
                                        filename = title,
                                        formatBadge = qual,
                                        score = 700
                                    )
                                }
                            }
                        }
                        out
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                if (resolved.isNotEmpty()) {
                    streams += resolved
                    foundForVid = true
                    break
                }
            }

            if (!foundForVid) {
                for (inv in INVIDIOUS_INSTANCES) {
                    val resolved = try {
                        val req = Request.Builder()
                            .url("$inv/api/v1/videos/$vid")
                            .header("User-Agent", StremioAddonClient.BROWSER_UA)
                            .build()
                        val body = client.newCall(req).readResponse { res -> if (res.isSuccessful) res.body?.string() else null }
                        if (body == null) emptyList() else {
                            val json = JSONObject(body)
                            val fmtStreams = json.optJSONArray("formatStreams")
                            val out = mutableListOf<StreamLink>()
                            if (fmtStreams != null) {
                                for (i in 0 until fmtStreams.length()) {
                                    val fs = fmtStreams.optJSONObject(i) ?: continue
                                    val sUrl = fs.optString("url", "").trim()
                                    val qual = fs.optString("qualityLabel", "720p")
                                    if (sUrl.startsWith("https://")) {
                                        out += StreamLink(
                                            url = sUrl,
                                            quality = qual,
                                            sourceName = "MV / Concert Direct • $qual",
                                            isTorrent = false,
                                            audioType = "SUB",
                                            methodType = "DIRECT",
                                            filename = json.optString("title", displayTitle),
                                            formatBadge = qual,
                                            score = 690
                                        )
                                    }
                                }
                            }
                            out
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                    if (resolved.isNotEmpty()) {
                        streams += resolved
                        break
                    }
                }
            }
        }
        streams
    }

    internal fun extractYoutubeId(rawUrl: String): String? {
        val match = youtubeIdRegex.find(rawUrl.trim()) ?: return null
        return match.groupValues.getOrNull(1)?.takeIf { it.length >= 8 }
    }

    private fun buildEnrichedMagnet(infoHash: String, existingMagnet: String, releaseTitle: String): String {
        val trackerParams = EXTRA_TRACKERS.joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
        if (existingMagnet.startsWith("magnet:", ignoreCase = true)) {
            return if (existingMagnet.contains("&tr=")) existingMagnet else "$existingMagnet$trackerParams"
        }
        val dn = URLEncoder.encode(releaseTitle, "UTF-8")
        return "magnet:?xt=urn:btih:$infoHash&dn=$dn$trackerParams"
    }

    private fun isNonVideoRelease(title: String): Boolean {
        val lower = title.lowercase()
        return lower.contains("[ost]") ||
            lower.contains("soundtrack") ||
            lower.contains("flac]") ||
            lower.contains("mp3]") ||
            lower.endsWith(".zip") ||
            lower.endsWith(".epub") ||
            lower.endsWith(".cbz")
    }

    private val GENERIC_STOP_WORDS = setOf(
        "ova", "oad", "ona", "tv", "movie", "special", "specials", "the", "and", "an", "a",
        "season", "complete", "batch", "bd", "bluray", "dvd", "raw", "sub", "dub",
        "remake", "uncensored", "animation", "part", "vol", "volume", "edition", "series"
    )

    internal fun matchesTitleAndEpisode(
        releaseTitle: String,
        candidateTitles: List<String>,
        episodeNum: Int,
        isStandalone: Boolean
    ): Boolean {
        if (!matchesAnyTitleWords(releaseTitle, candidateTitles)) return false

        val parsedEp = torrentEpisodeNumber(releaseTitle)
        if (parsedEp != null) {
            return parsedEp == episodeNum
        }

        if (isStandalone) {
            val lower = releaseTitle.lowercase()
            val hasExplicitOtherEp = Regex("(?i)\\b(?:ep|episode|vol)\\.?\\s*(\\d{1,3})\\b").find(lower)?.let {
                it.groupValues.getOrNull(1)?.toIntOrNull()
            }
            return hasExplicitOtherEp == null || hasExplicitOtherEp == episodeNum || hasExplicitOtherEp == 1
        }

        val lower = releaseTitle.lowercase()
        val isBatchOrComplete = lower.contains("batch") ||
            lower.contains("complete") ||
            lower.contains("collection") ||
            lower.contains("bdbox") ||
            lower.contains("bd-box") ||
            lower.contains("bluray-box") ||
            lower.contains("s01") ||
            lower.contains("s1") ||
            lower.contains("tv+oad") ||
            lower.contains("tv+ova") ||
            lower.contains("tv + oad") ||
            lower.contains("tv + ova") ||
            Regex("全\\s*\\d+\\s*話").containsMatchIn(releaseTitle) ||
            Regex("(?<![0-9])0*1\\s*[-~]\\s*\\d{1,3}(?![0-9])").containsMatchIn(lower) ||
            Regex("(?i)vol\\.?\\s*0*1\\s*[-~]\\s*\\d{1,3}").containsMatchIn(lower) ||
            Regex("\\[\\d{4}[-~]\\d{4}\\]").containsMatchIn(lower)
        return isBatchOrComplete
    }

    internal fun matchesAnyTitleWords(releaseTitle: String, candidateTitles: List<String>): Boolean {
        val normRelease = normalizeKey(releaseTitle)
        val releaseWords = normalizeWords(releaseTitle)
        if (normRelease.isEmpty() && releaseWords.isEmpty()) return false

        return candidateTitles.any { candidate ->
            val normCandidate = normalizeKey(candidate)
            // 1. Direct key match (e.g. "kissxsis", "キスシス", "eromangasensei"): must be >= 4 chars and not a generic keyword
            if (normCandidate !in GENERIC_STOP_WORDS && normCandidate.length >= 4 && normRelease.contains(normCandidate)) {
                return@any true
            }

            // 2. Meaningful words overlap
            val meaningful = normalizeWords(candidate).filter { it !in GENERIC_STOP_WORDS && it.length >= 3 }
            if (meaningful.isNotEmpty() && releaseWords.isNotEmpty()) {
                val hits = meaningful.count { it in releaseWords }
                val required = if (meaningful.size <= 2) meaningful.size else maxOf(2, (meaningful.size * 0.75).toInt())
                if (hits >= required) {
                    return@any true
                }
            }
            false
        }
    }

    private fun normalizeKey(s: String): String =
        s.lowercase().filter { it.isLetterOrDigit() }

    private fun normalizeWords(s: String): Set<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()

    private fun detectFallbackQuality(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("2160p") || lower.contains("4k") -> "4K"
            lower.contains("1080p") || lower.contains("1920x1080") -> "1080p"
            lower.contains("720p") || lower.contains("1280x720") -> "720p"
            lower.contains("480p") -> "480p"
            else -> "1080p"
        }
    }

    private fun buildFallbackSourceLabel(prefix: String, releaseTitle: String, audio: String, quality: String): String {
        val lower = releaseTitle.lowercase()
        val uncTag = if (lower.contains("uncen") || lower.contains("at-x") || lower.contains("bd") || lower.contains("bluray")) {
            " • Uncensored / BD"
        } else ""
        val audioName = when (audio) {
            "DUAL_AUDIO" -> "Dual Audio"
            "DUB" -> "English Dub"
            else -> "Original Audio"
        }
        return "$prefix • $audioName • $quality$uncTag"
    }

    private fun scoreFallbackRelease(
        releaseTitle: String,
        audio: String,
        quality: String,
        seeders: Int?,
        isAdultOrEcchi: Boolean,
        preferredAudio: String,
        preferredQuality: String
    ): Int {
        var score = 120
        val lower = releaseTitle.lowercase()
        val s = seeders ?: 1
        score += when {
            s >= 50 -> 360
            s >= 20 -> 280
            s >= 8 -> 200
            s >= 3 -> 120
            else -> 40
        }
        if (isAdultOrEcchi && (lower.contains("uncen") || lower.contains("at-x") || lower.contains("bd") || lower.contains("bluray"))) {
            score += 220
        }
        if (preferredAudio.equals("DUB", true) && (audio == "DUB" || audio == "DUAL_AUDIO")) {
            score += 240
        } else if (!preferredAudio.equals("DUB", true) && (audio == "SUB" || audio == "DUAL_AUDIO")) {
            score += 180
        }
        if (quality.equals(preferredQuality, true)) score += 160
        else if (quality == "1080p") score += 120
        return score
    }
}
