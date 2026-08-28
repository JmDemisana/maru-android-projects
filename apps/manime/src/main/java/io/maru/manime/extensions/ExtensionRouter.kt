package io.maru.manime.extensions

import android.content.Context
import io.maru.manime.MAnimePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ExtensionRouter(
    private val context: Context,
    private val prefs: MAnimePrefs
) {
    private val csClient = CloudstreamRepoClient(context)
    private val csLoader = CloudstreamExtensionLoader(context)
    private val aniyomiLoader = AniyomiExtensionLoader(context)
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Helper to resolve Kitsu ID from anime title
    private suspend fun resolveKitsuId(animeTitle: String): String? = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(animeTitle, "UTF-8")
            val url = "https://kitsu.io/api/edge/anime?filter[text]=$encoded&page[limit]=1"
            val req = Request.Builder().url(url).build()
            val resp = httpClient.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val data = json.optJSONArray("data")
            if (data != null && data.length() > 0) {
                data.getJSONObject(0).optString("id")
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun resolveStreamsForEpisode(
        animeTitle: String,
        episodeNum: Int,
        imdbId: String? = null,
        kitsuId: String? = null
    ): List<StreamLink> = withContext(Dispatchers.IO) {
        val streamLinks = mutableListOf<StreamLink>()

        // 1. Resolve Kitsu ID if missing
        val resolvedKitsuId = kitsuId ?: resolveKitsuId(animeTitle)

        // 2. Query all configured Stremio Addons (e.g. Torrentio, Anime Kitsu, etc.)
        val stremioAddons = prefs.stremioAddons.first()
        for (addonUrl in stremioAddons) {
            try {
                val stremioClient = StremioAddonClient(addonUrl, httpClient)
                val queryIds = mutableListOf<String>()
                if (resolvedKitsuId != null) queryIds.add("kitsu:$resolvedKitsuId:$episodeNum")
                if (imdbId != null) queryIds.add("$imdbId:1:$episodeNum")

                for (qid in queryIds) {
                    val streams = stremioClient.getStreams("series", qid)
                    for (s in streams) {
                        if (!s.url.isNullOrEmpty()) {
                            streamLinks.add(
                                StreamLink(
                                    url = s.url,
                                    quality = s.name ?: "Auto",
                                    sourceName = "Stremio (${s.title ?: "Stream"})",
                                    isTorrent = false
                                )
                            )
                        } else if (!s.infoHash.isNullOrEmpty()) {
                            val magnet = "magnet:?xt=urn:btih:${s.infoHash}&dn=${URLEncoder.encode(animeTitle, "UTF-8")}"
                            streamLinks.add(
                                StreamLink(
                                    url = magnet,
                                    quality = s.name ?: "Torrent",
                                    sourceName = "Torrent P2P (${s.title ?: s.name ?: "Torrent"})",
                                    isTorrent = true
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore individual addon failures
            }
        }

        // 2. Query Installed Cloudstream Plugins
        val installedCs = csClient.getInstalledPlugins()
        for (jar in installedCs) {
            val source = csLoader.loadPlugin(jar) ?: continue
            try {
                val results = source.search(animeTitle)
                val firstMatch = results.firstOrNull()
                if (firstMatch != null) {
                    val episodes = source.getEpisodes(firstMatch.url)
                    val targetEp = episodes.firstOrNull { it.episodeNumber == episodeNum }
                    if (targetEp != null) {
                        val links = source.getStreamLinks(targetEp.url)
                        streamLinks.addAll(links)
                    }
                }
            } catch (e: Exception) {
                // Ignore individual source failures
            }
        }

        streamLinks
    }
}
