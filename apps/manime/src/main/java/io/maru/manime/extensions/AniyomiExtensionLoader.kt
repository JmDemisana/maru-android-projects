package io.maru.manime.extensions

import android.content.Context
import android.content.pm.PackageManager
import dalvik.system.PathClassLoader
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
            AniyomiSourceWrapper(info.sourceName, info.packageName, classLoader, info.className)
        } catch (e: Exception) {
            android.util.Log.e("AniyomiLoader", "Error loading ${info.sourceName}", e)
            null
        }
    }

    private class AniyomiSourceWrapper(
        override val name: String,
        override val id: String,
        private val classLoader: PathClassLoader,
        private val mainClassName: String
    ) : ExtensionSource {
        override suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
            emptyList()
        }

        override suspend fun getEpisodes(mediaUrl: String): List<Episode> = withContext(Dispatchers.IO) {
            emptyList()
        }

        override suspend fun getStreamLinks(episodeUrl: String): List<StreamLink> = withContext(Dispatchers.IO) {
            emptyList()
        }
    }
}
