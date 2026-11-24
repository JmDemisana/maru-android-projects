package io.maru.manime.extensions

import android.content.Context
import io.maru.manime.MAnimePrefs
import io.maru.manime.readResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class ExtensionRouter(
    private val context: Context,
    private val prefs: MAnimePrefs
) {
    private val csClient = CloudstreamRepoClient(context)
    private val csLoader = CloudstreamExtensionLoader(context)
    private val aniyomiLoader = AniyomiExtensionLoader(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private fun generateSearchQueries(titles: List<String>): List<String> {
        val queries = mutableListOf<String>()
        for (title in titles) {
            if (title.isBlank()) continue
            val clean = title
                .replace('×', 'x').replace('✕', 'x')
                .replace(Regex("[♥♡★☆♪♫]"), "")
                .replace("–", "-").replace("—", "-")
                .replace(":", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (clean.isNotBlank()) queries.add(clean)

            // Split by main title before dash or colon
            val baseTitle = title.split(":", "-", "–", "—").firstOrNull()?.trim()
            if (!baseTitle.isNullOrBlank()) {
                queries.add(baseTitle)
            }

            // Alphanumeric only
            val alphaOnly = clean.replace(Regex("[^a-zA-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
            if (alphaOnly.isNotBlank()) {
                queries.add(alphaOnly)
            }
        }
        return queries.distinct()
    }

    // Helper to resolve Kitsu ID from anime titles (prioritizing Romanized titles)
    private suspend fun resolveKitsuId(titles: List<String>): String? = withContext(Dispatchers.IO) {
        val searchVariants = generateSearchQueries(titles)
        for (query in searchVariants.take(6)) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://kitsu.io/api/edge/anime?filter[text]=$encoded&page[limit]=1"
                val req = Request.Builder().url(url).build()
                val body = httpClient.newCall(req).readResponse { response ->
                    if (response.isSuccessful) response.body?.string() else null
                } ?: continue
                val json = JSONObject(body)
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    return@withContext data.getJSONObject(0).optString("id")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}
        }
        null
    }

    private fun detectAudioType(raw: String): String {
        val lower = raw.lowercase()
        return if (lower.contains("dual") || lower.contains("multi-audio") || lower.contains("multi audio")) {
            "DUAL_AUDIO"
        } else if (lower.contains("dub") || lower.contains("eng dub") || lower.contains("english dub") || lower.contains("english")) {
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
        else "HD"
    }

    private fun detectSeeders(raw: String): Int? {
        val matcher = Pattern.compile("(?:👤|seeders|seeds|peers)\\s*:?\\s*(\\d+)|(\\d+)\\s*(?:seed|👤|peers)").matcher(raw.lowercase())
        return if (matcher.find()) matcher.group(1)?.toIntOrNull() ?: matcher.group(2)?.toIntOrNull() else null
    }

    suspend fun resolveStreamsForEpisode(
        animeTitle: String,
        episodeNum: Int,
        imdbId: String? = null,
        kitsuId: String? = null,
        altTitles: List<String> = emptyList(),
        onStreamsUpdated: (List<StreamLink>) -> Unit = {}
    ): List<StreamLink> = coroutineScope {
        val syncResults = Collections.synchronizedList(mutableListOf<StreamLink>())
        val prioritizedTitles = (altTitles + listOf(animeTitle)).filter { it.isNotBlank() }.distinct()

        fun pushResults(newLinks: List<StreamLink>) {
            if (newLinks.isEmpty()) return
            synchronized(syncResults) {
                syncResults.addAll(newLinks)
                val sorted = syncResults.distinctBy { (it.infoHash ?: it.url) to it.fileIdx }.sortedWith(
                    compareByDescending<StreamLink> { it.seeders ?: 0 }
                        .thenByDescending { it.quality == "1080p" }
                        .thenByDescending { it.quality == "4K" }
                        .thenByDescending { it.quality == "720p" }
                )
                onStreamsUpdated(sorted)
            }
        }

        // 1. Resolve Kitsu ID asynchronously (prioritizing Romanized candidate titles)
        val resolvedKitsuId = kitsuId ?: withTimeoutOrNull(3000) { resolveKitsuId(prioritizedTitles) }

        // 2. Query Stremio Addons
        val stremioAddons = prefs.stremioAddons.first()
        val stremioDeferreds = stremioAddons.map { addonUrl ->
            async(Dispatchers.IO) {
                val results = mutableListOf<StreamLink>()
                try {
                    withTimeoutOrNull(6000) {
                        val stremioClient = StremioAddonClient(addonUrl, httpClient)
                        val queryIds = mutableListOf<String>()
                        if (resolvedKitsuId != null) queryIds.add("kitsu:$resolvedKitsuId:$episodeNum")
                        if (imdbId != null) queryIds.add("$imdbId:1:$episodeNum")

                        for (qid in queryIds) {
                            val streams = stremioClient.getStreams("series", qid)
                            for (s in streams) {
                                val fullTitle = listOfNotNull(s.name, s.title, s.filename).joinToString(" ")
                                val audio = detectAudioType(fullTitle)
                                val quality = detectQuality(fullTitle)
                                val seeds = detectSeeders(fullTitle)

                                if (!s.url.isNullOrEmpty()) {
                                    results.add(
                                        StreamLink(
                                            url = s.url,
                                            quality = quality,
                                            sourceName = s.name ?: "Stremio Stream",
                                            isTorrent = false,
                                            audioType = audio,
                                            methodType = "STREMIO",
                                            seeders = null,
                                            filename = s.filename ?: s.title,
                                            headers = s.headers,
                                            subtitles = s.subtitles,
                                            sizeBytes = s.sizeBytes,
                                            formatBadge = "HTTP Stream"
                                        )
                                    )
                                } else if (!s.infoHash.isNullOrEmpty()) {
                                    val hash = s.infoHash.trim().lowercase()
                                    val trackers = s.sources.distinct().joinToString("") { "&tr=${URLEncoder.encode(it, "UTF-8")}" }
                                    val magnet = "magnet:?xt=urn:btih:$hash&dn=${URLEncoder.encode(animeTitle, "UTF-8")}$trackers"
                                    results.add(
                                        StreamLink(
                                            url = magnet,
                                            quality = quality,
                                            sourceName = s.name ?: "Torrentio P2P",
                                            isTorrent = true,
                                            infoHash = hash,
                                            fileIdx = s.fileIdx,
                                            audioType = audio,
                                            methodType = "TORRENT",
                                            seeders = seeds,
                                            filename = s.filename ?: s.title,
                                            subtitles = s.subtitles,
                                            sizeBytes = s.sizeBytes,
                                            formatBadge = "MKV (Soft Subs)"
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}
                pushResults(results)
                results
            }
        }

        // 3. Query Installed Cloudstream Plugins concurrently
        val installedCs = csClient.getInstalledPlugins()
        val searchVariants = generateSearchQueries(prioritizedTitles)

        val csDeferreds = installedCs.map { jar ->
            async(Dispatchers.IO) {
                val results = mutableListOf<StreamLink>()
                try {
                    withTimeoutOrNull(20000) {
                        val sources = csLoader.loadPluginSources(jar)
                        for (source in sources) {
                            try {
                                var searchMatches = emptyList<SearchResult>()
                                for (q in searchVariants) {
                                    android.util.Log.d("ExtensionRouter", "Querying CS source ${source.name} for: $q")
                                    searchMatches = source.search(q)
                                    if (searchMatches.isNotEmpty()) {
                                        android.util.Log.d("ExtensionRouter", "Found ${searchMatches.size} matches from ${source.name}")
                                        break
                                    }
                                }

                                val firstMatch = searchMatches.firstOrNull()
                                if (firstMatch != null) {
                                    val episodes = source.getEpisodes(firstMatch.url)
                                    val targetEp = episodes.firstOrNull { it.episodeNumber == episodeNum }
                                    if (targetEp != null) {
                                        val links = source.getStreamLinks(targetEp.url)
                                        for (link in links) {
                                            val fullText = "${link.sourceName} ${link.quality}"
                                            results.add(
                                                link.copy(
                                                    audioType = detectAudioType(fullText),
                                                    methodType = "CLOUDSTREAM",
                                                    formatBadge = if (link.isTorrent) "P2P Torrent" else "Direct Stream"
                                                )
                                            )
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                if (t is CancellationException) throw t
                                android.util.Log.e("ExtensionRouter", "Error running CS source ${source.name}", t)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    android.util.Log.e("ExtensionRouter", "Error in CS plugin execution for ${jar.name}", t)
                }
                pushResults(results)
                results
            }
        }

        // 4. Query Installed Aniyomi Extensions concurrently
        val installedAniyomi = aniyomiLoader.getInstalledExtensions()
        val aniyomiDeferreds = installedAniyomi.map { extInfo ->
            async(Dispatchers.IO) {
                val results = mutableListOf<StreamLink>()
                try {
                    withTimeoutOrNull(20000) {
                        val source = aniyomiLoader.loadSource(extInfo)
                        if (source != null) {
                            var searchMatches = emptyList<SearchResult>()
                            for (q in searchVariants) {
                                android.util.Log.d("ExtensionRouter", "Querying Aniyomi source ${source.name} for: $q")
                                searchMatches = source.search(q)
                                if (searchMatches.isNotEmpty()) {
                                    android.util.Log.d("ExtensionRouter", "Found ${searchMatches.size} matches from ${source.name}")
                                    break
                                }
                            }

                            val firstMatch = searchMatches.firstOrNull()
                            if (firstMatch != null) {
                                val episodes = source.getEpisodes(firstMatch.url)
                                val targetEp = episodes.firstOrNull { it.episodeNumber == episodeNum }
                                if (targetEp != null) {
                                    val links = source.getStreamLinks(targetEp.url)
                                    for (link in links) {
                                        val fullText = "${link.sourceName} ${link.quality}"
                                        results.add(
                                            link.copy(
                                                audioType = detectAudioType(fullText),
                                                methodType = "ANIYOMI",
                                                formatBadge = "Direct Stream"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    android.util.Log.e("ExtensionRouter", "Error in Aniyomi extension execution for ${extInfo.sourceName}", t)
                }
                pushResults(results)
                results
            }
        }

        // Await all
        stremioDeferreds.awaitAll()
        csDeferreds.awaitAll()
        aniyomiDeferreds.awaitAll()

        synchronized(syncResults) {
            syncResults.distinctBy { (it.infoHash ?: it.url) to it.fileIdx }.sortedWith(
                compareByDescending<StreamLink> { it.seeders ?: 0 }
                    .thenByDescending { it.quality == "1080p" }
                    .thenByDescending { it.quality == "4K" }
                    .thenByDescending { it.quality == "720p" }
            )
        }
    }
}
