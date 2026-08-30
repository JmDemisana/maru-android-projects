package io.maru.manime.extensions

import android.content.Context
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import dalvik.system.DexClassLoader
import dalvik.system.DexFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

class CloudstreamExtensionLoader(private val context: Context) {

    fun loadPluginSources(jarFile: File): List<ExtensionSource> {
        return try {
            val optDir = File(context.codeCacheDir, "cs_opt").apply { mkdirs() }
            val classLoader = DexClassLoader(
                jarFile.absolutePath,
                optDir.absolutePath,
                null,
                context.classLoader
            )

            val pluginClasses = mutableListOf<Class<*>>()

            // Read manifest.json from the jar
            try {
                ZipFile(jarFile).use { zip ->
                    val entry = zip.getEntry("manifest.json")
                    if (entry != null) {
                        val content = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                        val json = JSONObject(content)
                        val pluginClassName = json.optString("pluginClassName").ifEmpty {
                            json.optString("entryClass").ifEmpty {
                                json.optString("mainClass")
                            }
                        }
                        if (pluginClassName.isNotEmpty()) {
                            android.util.Log.d("CloudstreamLoader", "Found manifest entryClass: $pluginClassName in ${jarFile.name}")
                            try {
                                val loadedClass = classLoader.loadClass(pluginClassName)
                                pluginClasses.add(loadedClass)
                            } catch (e: Exception) {
                                android.util.Log.e("CloudstreamLoader", "Failed to load manifest class $pluginClassName", e)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("CloudstreamLoader", "Could not read manifest.json from ${jarFile.name}", e)
            }

            // Also dynamically scan all classes in the dex
            try {
                @Suppress("DEPRECATION")
                val dex = DexFile.loadDex(jarFile.absolutePath, File(optDir, "${jarFile.name}.opt").absolutePath, 0)
                val entries = dex.entries()
                while (entries.hasMoreElements()) {
                    val className = entries.nextElement()
                    if (className.endsWith("Plugin") || className.contains("Provider") || className.contains("Api")) {
                        try {
                            val clazz = classLoader.loadClass(className)
                            if (BasePlugin::class.java.isAssignableFrom(clazz) || MainAPI::class.java.isAssignableFrom(clazz)) {
                                pluginClasses.add(clazz)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.w("CloudstreamLoader", "DexFile scan fallback skipped for ${jarFile.name}: ${e.message}")
            }

            val sources = mutableListOf<ExtensionSource>()

            for (clazz in pluginClasses.distinct()) {
                try {
                    val constructor = clazz.getDeclaredConstructor().apply { isAccessible = true }
                    val instance = constructor.newInstance()

                    // If it is a Cloudstream Plugin base
                    if (instance is BasePlugin) {
                        try {
                            instance.load(context)
                        } catch (t: Throwable) {
                            android.util.Log.e("CloudstreamLoader", "Error invoking load() on ${clazz.name}", t)
                        }

                        for (api in instance.registeredApis) {
                            android.util.Log.d("CloudstreamLoader", "Registered API: ${api.name} from plugin ${clazz.name}")
                            sources.add(CloudstreamMainApiSource(api))
                        }
                    } else if (instance is MainAPI) {
                        android.util.Log.d("CloudstreamLoader", "Loaded direct MainAPI: ${instance.name}")
                        sources.add(CloudstreamMainApiSource(instance))
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("CloudstreamLoader", "Failed to instantiate ${clazz.name}", e)
                }
            }

            android.util.Log.d("CloudstreamLoader", "Loaded ${sources.size} sources from ${jarFile.name}")
            sources
        } catch (e: Throwable) {
            android.util.Log.e("CloudstreamLoader", "Failed to load ${jarFile.name}", e)
            emptyList()
        }
    }

    fun loadPlugin(jarFile: File): ExtensionSource? {
        return loadPluginSources(jarFile).firstOrNull()
    }

    private class CloudstreamMainApiSource(
        private val api: MainAPI
    ) : ExtensionSource {
        override val name: String get() = api.name
        override val id: String get() = "cs_${api.name.replace(" ", "_").lowercase()}"

        override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("CloudstreamSource", "[$name] Searching for: $query")
                val responses = api.search(query)
                android.util.Log.d("CloudstreamSource", "[$name] Got ${responses.size} results for: $query")
                responses.map { item ->
                    SearchResult(
                        id = item.url,
                        title = item.name,
                        url = item.url,
                        sourceName = api.name
                    )
                }
            } catch (t: Throwable) {
                android.util.Log.e("CloudstreamSource", "[$name] Search error for $query", t)
                emptyList()
            }
        }

        override suspend fun getEpisodes(mediaUrl: String): List<Episode> = withContext(Dispatchers.IO) {
            try {
                android.util.Log.d("CloudstreamSource", "[$name] Loading episodes for URL: $mediaUrl")
                val loadResponse = api.load(mediaUrl) ?: return@withContext emptyList()
                val results = mutableListOf<Episode>()

                when (loadResponse) {
                    is AnimeLoadResponse -> {
                        val allEps = loadResponse.episodes.values.flatten()
                        for (ep in allEps) {
                            results.add(
                                Episode(
                                    episodeNumber = ep.episode ?: 1,
                                    title = ep.name ?: "Episode ${ep.episode ?: 1}",
                                    url = ep.data
                                )
                            )
                        }
                    }
                    is TvSeriesLoadResponse -> {
                        for (ep in loadResponse.episodes) {
                            results.add(
                                Episode(
                                    episodeNumber = ep.episode ?: 1,
                                    title = ep.name ?: "Episode ${ep.episode ?: 1}",
                                    url = ep.data
                                )
                            )
                        }
                    }
                    is MovieLoadResponse -> {
                        results.add(
                            Episode(
                                episodeNumber = 1,
                                title = "Full Movie",
                                url = loadResponse.dataUrl
                            )
                        )
                    }
                    else -> {
                        // Reflection fallback
                        try {
                            val epMethod = loadResponse.javaClass.methods.firstOrNull { it.name == "getEpisodes" }
                            val rawEps = epMethod?.invoke(loadResponse)
                            if (rawEps is Map<*, *>) {
                                for (entry in rawEps.values) {
                                    if (entry is List<*>) {
                                        for (item in entry) {
                                            if (item is com.lagradost.cloudstream3.Episode) {
                                                results.add(
                                                    Episode(
                                                        episodeNumber = item.episode ?: 1,
                                                        title = item.name ?: "Episode ${item.episode ?: 1}",
                                                        url = item.data
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            } else if (rawEps is List<*>) {
                                for (item in rawEps) {
                                    if (item is com.lagradost.cloudstream3.Episode) {
                                        results.add(
                                            Episode(
                                                episodeNumber = item.episode ?: 1,
                                                title = item.name ?: "Episode ${item.episode ?: 1}",
                                                url = item.data
                                            )
                                        )
                                    }
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
                android.util.Log.d("CloudstreamSource", "[$name] Found ${results.size} episodes")
                results
            } catch (t: Throwable) {
                android.util.Log.e("CloudstreamSource", "[$name] getEpisodes error for $mediaUrl", t)
                emptyList()
            }
        }

        override suspend fun getStreamLinks(episodeUrl: String): List<StreamLink> = withContext(Dispatchers.IO) {
            val collectedLinks = mutableListOf<StreamLink>()
            try {
                android.util.Log.d("CloudstreamSource", "[$name] getStreamLinks for episodeUrl: $episodeUrl")
                api.loadLinks(
                    data = episodeUrl,
                    isCasting = false,
                    subtitleCallback = {},
                    callback = { extractorLink ->
                        val isTorrent = extractorLink.type == ExtractorLinkType.TORRENT ||
                                extractorLink.type == ExtractorLinkType.MAGNET

                        collectedLinks.add(
                            StreamLink(
                                url = extractorLink.url,
                                quality = Qualities.getStringByInt(extractorLink.quality),
                                sourceName = "${api.name} (${extractorLink.name})",
                                isTorrent = isTorrent,
                                audioType = "SUB",
                                methodType = "CLOUDSTREAM",
                                filename = extractorLink.name,
                                formatBadge = if (isTorrent) "P2P Torrent" else "Direct Stream"
                            )
                        )
                    }
                )
                android.util.Log.d("CloudstreamSource", "[$name] Found ${collectedLinks.size} stream links")
            } catch (t: Throwable) {
                android.util.Log.e("CloudstreamSource", "[$name] getStreamLinks error for $episodeUrl", t)
            }
            collectedLinks
        }
    }
}
