package io.maru.manime.extensions

import android.content.Context
import io.maru.manime.MAnimePrefs
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

    private fun generateSearchQueries(title: String): List<String> {
        val queries = mutableListOf<String>()
        val clean = title.replace("–", "-").replace("—", "-").replace(":", " ").replace(Regex("\\s+"), " ").trim()
        queries.add(clean)

        // Split by main title before dash or colon
        val baseTitle = title.split(":", "-", "–", "—").firstOrNull()?.trim()
        if (!baseTitle.isNullOrBlank() && !queries.contains(baseTitle)) {
            queries.add(baseTitle)
        }

        // Alphanumeric only
        val alphaOnly = title.replace(Regex("[^a-zA-Z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (alphaOnly.isNotBlank() && !queries.contains(alphaOnly)) {
            queries.add(alphaOnly)
        }
        return queries.distinct()
    }

    // Helper to resolve Kitsu ID from anime title
    private suspend fun resolveKitsuId(animeTitle: String): String? = withContext(Dispatchers.IO) {
        val searchVariants = generateSearchQueries(animeTitle)
        for (query in searchVariants) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "https://kitsu.io/api/edge/anime?filter[text]=$encoded&page[limit]=1"
                val req = Request.Builder().url(url).build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body?.string() ?: continue
                val json = JSONObject(body)
                val data = json.optJSONArray("data")
                if (data != null && data.length() > 0) {
                    return@withContext data.getJSONObject(0).optString("id")
                }
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
        val matcher = Pattern.compile("(\\d+)\\s*(?:seed|👤|peers|p)").matcher(raw.lowercase())
        return if (matcher.find()) matcher.group(1)?.toIntOrNull() else null
    }

    suspend fun resolveStreamsForEpisode(
        animeTitle: String,
        episodeNum: Int,
        imdbId: String? = null,
        kitsuId: String? = null,
        onStreamsUpdated: (List<StreamLink>) -> Unit = {}
    ): List<StreamLink> = coroutineScope {
        val syncResults = Collections.synchronizedList(mutableListOf<StreamLink>())

        fun pushResults(newLinks: List<StreamLink>) {
            if (newLinks.isEmpty()) return
            synchronized(syncResults) {
                syncResults.addAll(newLinks)
                val sorted = syncResults.distinctBy { it.url }.sortedWith(
                    compareByDescending<StreamLink> { it.seeders ?: 0 }
                        .thenByDescending { it.quality == "1080p" }
                        .thenByDescending { it.quality == "4K" }
                        .thenByDescending { it.quality == "720p" }
                )
                onStreamsUpdated(sorted)
            }
        }

        // 1. Resolve Kitsu ID asynchronously
        val resolvedKitsuIdDeferred = async(Dispatchers.IO) {
            kitsuId ?: resolveKitsuId(animeTitle)
        }

        val resolvedKitsuId = withTimeoutOrNull(3000) { resolvedKitsuIdDeferred.await() }

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
                                val fullTitle = "${s.name ?: ""} ${s.title ?: ""}"
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
                                            filename = s.title,
                                            formatBadge = "HTTP Stream"
                                        )
                                    )
                                } else if (!s.infoHash.isNullOrEmpty()) {
                                    val magnet = "magnet:?xt=urn:btih:${s.infoHash}&dn=${URLEncoder.encode(animeTitle, "UTF-8")}"
                                    results.add(
                                        StreamLink(
                                            url = magnet,
                                            quality = quality,
                                            sourceName = s.name ?: "Torrentio P2P",
                                            isTorrent = true,
                                            audioType = audio,
                                            methodType = "TORRENT",
                                            seeders = seeds,
                                            filename = s.title,
                                            formatBadge = "MKV (Soft Subs)"
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
                pushResults(results)
                results
            }
        }

        // 3. Query Installed Cloudstream Plugins concurrently
        val installedCs = csClient.getInstalledPlugins()
        val searchVariants = generateSearchQueries(animeTitle)

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
                                        ?: episodes.firstOrNull()
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
                                android.util.Log.e("ExtensionRouter", "Error running CS source ${source.name}", t)
                            }
                        }
                    }
                } catch (t: Throwable) {
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
                                    ?: episodes.firstOrNull()
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
            syncResults.distinctBy { it.url }.sortedWith(
                compareByDescending<StreamLink> { it.seeders ?: 0 }
                    .thenByDescending { it.quality == "1080p" }
                    .thenByDescending { it.quality == "4K" }
                    .thenByDescending { it.quality == "720p" }
            )
        }
    }
}
