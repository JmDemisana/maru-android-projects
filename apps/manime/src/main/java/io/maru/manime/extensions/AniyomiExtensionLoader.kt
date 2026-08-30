package io.maru.manime.extensions

import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeImpl
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.SEpisodeImpl
import eu.kanade.tachiyomi.animesource.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AniyomiExtensionInfo(
    val packageName: String,
    val sourceName: String,
    val versionName: String,
    val versionCode: Int,
    val className: String,
    val apkPath: String
)

class AniyomiExtensionLoader(private val context: Context) {

    fun getInstalledExtensions(): List<AniyomiExtensionInfo> {
        val pm = context.packageManager
        val installed = try {
            pm.getInstalledPackages(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            emptyList()
        }

        val results = mutableListOf<AniyomiExtensionInfo>()
        for (pkg in installed) {
            val appInfo = pkg.applicationInfo ?: continue
            val meta = appInfo.metaData ?: continue

            // Tachiyomi/Aniyomi extension marker
            val isAnimeExtension = meta.containsKey("tachiyomi.animeextension") ||
                    meta.containsKey("eu.kanade.tachiyomi.animeextension") ||
                    pkg.packageName.startsWith("eu.kanade.tachiyomi.animeextension")

            if (isAnimeExtension) {
                val className = meta.getString("tachiyomi.animeextension.class")
                    ?: meta.getString("eu.kanade.tachiyomi.animeextension.class")
                    ?: ""

                val sourceName = pm.getApplicationLabel(appInfo).toString()
                results.add(
                    AniyomiExtensionInfo(
                        packageName = pkg.packageName,
                        sourceName = sourceName,
                        versionName = pkg.versionName ?: "1.0",
                        versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            pkg.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pkg.versionCode
                        },
                        className = className,
                        apkPath = appInfo.sourceDir
                    )
                )
            }
        }
        return results
    }

    fun loadSource(info: AniyomiExtensionInfo): ExtensionSource? {
        return try {
            val classLoader = PathClassLoader(info.apkPath, context.classLoader)
            val fullClassName = if (info.className.startsWith(".")) {
                info.packageName + info.className
            } else {
                info.className
            }
            val clazz = classLoader.loadClass(fullClassName)
            val constructor = clazz.getDeclaredConstructor().apply { isAccessible = true }
            val instance = constructor.newInstance()

            AniyomiSourceWrapper(info.sourceName, info.packageName, instance)
        } catch (e: Exception) {
            android.util.Log.e("AniyomiLoader", "Error loading Aniyomi: ${info.sourceName}", e)
            null
        }
    }

    private class AniyomiSourceWrapper(
        override val name: String,
        override val id: String,
        private val sourceInstance: Any
    ) : ExtensionSource {

        override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
            try {
                if (sourceInstance is AnimeSource) {
                    val animes = sourceInstance.getSearchAnime(1, query)
                    return@withContext animes.map { anime ->
                        SearchResult(
                            id = anime.url,
                            title = anime.title,
                            url = anime.url,
                            sourceName = name
                        )
                    }
                }

                // Reflection fallback for dynamic implementations
                val method = sourceInstance.javaClass.methods.firstOrNull {
                    it.name == "getSearchAnime" || it.name == "searchAnime" || it.name == "fetchSearchAnime"
                }
                val result = method?.invoke(sourceInstance, 1, query)
                if (result is List<*>) {
                    result.mapNotNull { item ->
                        if (item is SAnime) {
                            SearchResult(id = item.url, title = item.title, url = item.url, sourceName = name)
                        } else null
                    }
                } else emptyList()
            } catch (e: Exception) {
                android.util.Log.e("AniyomiSource", "search error on $name", e)
                emptyList()
            }
        }

        override suspend fun getEpisodes(mediaUrl: String): List<Episode> = withContext(Dispatchers.IO) {
            try {
                val anime = SAnimeImpl().apply { url = mediaUrl }
                if (sourceInstance is AnimeSource) {
                    val episodeList = sourceInstance.getEpisodeList(anime)
                    return@withContext episodeList.map { ep ->
                        Episode(
                            episodeNumber = if (ep.episode_number > 0) ep.episode_number.toInt() else 1,
                            title = ep.name.ifBlank { "Episode ${ep.episode_number.toInt()}" },
                            url = ep.url
                        )
                    }
                }

                // Reflection fallback
                val method = sourceInstance.javaClass.methods.firstOrNull {
                    it.name == "getEpisodeList" || it.name == "fetchEpisodeList"
                }
                val result = method?.invoke(sourceInstance, anime)
                if (result is List<*>) {
                    result.mapNotNull { item ->
                        if (item is SEpisode) {
                            Episode(
                                episodeNumber = if (item.episode_number > 0) item.episode_number.toInt() else 1,
                                title = item.name.ifBlank { "Episode ${item.episode_number.toInt()}" },
                                url = item.url
                            )
                        } else null
                    }
                } else emptyList()
            } catch (e: Exception) {
                android.util.Log.e("AniyomiSource", "getEpisodes error on $name", e)
                emptyList()
            }
        }

        override suspend fun getStreamLinks(episodeUrl: String): List<StreamLink> = withContext(Dispatchers.IO) {
            try {
                val episode = SEpisodeImpl().apply { url = episodeUrl }
                val videos: List<Video> = if (sourceInstance is AnimeSource) {
                    sourceInstance.getVideoList(episode)
                } else {
                    val method = sourceInstance.javaClass.methods.firstOrNull {
                        it.name == "getVideoList" || it.name == "fetchVideoList"
                    }
                    val result = method?.invoke(sourceInstance, episode)
                    if (result is List<*>) {
                        result.filterIsInstance<Video>()
                    } else emptyList()
                }

                videos.map { video ->
                    StreamLink(
                        url = video.videoUrl,
                        quality = video.quality.ifBlank { "HD" },
                        sourceName = "$name (${video.quality})",
                        isTorrent = false,
                        audioType = "SUB",
                        methodType = "ANIYOMI",
                        filename = null,
                        formatBadge = "Direct Stream"
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("AniyomiSource", "getStreamLinks error on $name", e)
                emptyList()
            }
        }
    }
}
