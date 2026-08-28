package io.maru.manime.extensions

import android.content.Context
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads Cloudstream compiled extension JAR/DEX files via DexClassLoader.
 * CloudStream plugins implement MainAPI or provide scraper extractors.
 */
class CloudstreamExtensionLoader(private val context: Context) {
    private val optimizedDir: File
        get() = File(context.codeCacheDir, "dex_opt").apply { if (!exists()) mkdirs() }

    fun loadPlugin(jarFile: File): ExtensionSource? {
        return try {
            val classLoader = DexClassLoader(
                jarFile.absolutePath,
                optimizedDir.absolutePath,
                null,
                context.classLoader
            )

            // Dynamic wrapper implementing ExtensionSource
            DynamicCloudstreamSource(
                name = jarFile.nameWithoutExtension,
                id = "cs_${jarFile.nameWithoutExtension}",
                classLoader = classLoader
            )
        } catch (e: Exception) {
            android.util.Log.e("CloudstreamLoader", "Failed to load ${jarFile.name}", e)
            null
        }
    }

    private class DynamicCloudstreamSource(
        override val name: String,
        override val id: String,
        private val classLoader: DexClassLoader
    ) : ExtensionSource {
        override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
            // Dynamic invocation of loaded plugin methods if present
            try {
                val pluginClass = classLoader.loadClass("MainPlugin") ?: return@withContext emptyList()
                val instance = pluginClass.getDeclaredConstructor().newInstance()
                // Reflection invocation pattern
                val searchMethod = pluginClass.methods.firstOrNull { it.name == "search" || it.name == "searchMedia" }
                val results = searchMethod?.invoke(instance, query) as? List<*> ?: return@withContext emptyList()
                results.mapNotNull {
                    SearchResult(
                        id = it.toString(),
                        title = it.toString(),
                        url = it.toString(),
                        sourceName = name
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        override suspend fun getEpisodes(mediaUrl: String): List<Episode> = withContext(Dispatchers.IO) {
            emptyList()
        }

        override suspend fun getStreamLinks(episodeUrl: String): List<StreamLink> = withContext(Dispatchers.IO) {
            emptyList()
        }
    }
}
