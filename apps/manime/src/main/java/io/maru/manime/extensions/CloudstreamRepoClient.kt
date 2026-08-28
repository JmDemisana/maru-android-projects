package io.maru.manime.extensions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class CloudstreamPluginInfo(
    val name: String,
    val internalName: String,
    val version: Int,
    val url: String, // Download URL for the .jar or .cs3
    val iconUrl: String? = null,
    val description: String? = null,
    val language: String? = null,
    val authors: List<String> = emptyList(),
    val repoUrl: String
)

data class CloudstreamRepo(
    val name: String,
    val description: String?,
    val url: String,
    val pluginLists: List<String>,
    val plugins: List<CloudstreamPluginInfo> = emptyList()
)

class CloudstreamRepoClient(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val pluginsDir: File
        get() = File(context.filesDir, "cloudstream_plugins").apply { if (!exists()) mkdirs() }

    // Resolve shortcode or raw URL
    private suspend fun resolveRepoUrl(input: String): String = withContext(Dispatchers.IO) {
        val trimmed = input.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return@withContext trimmed
        }
        // Shortcode expansion (Cutt.ly or common cloudstream shortener format)
        val shortcodeUrl = "https://cutt.ly/$trimmed"
        try {
            val req = Request.Builder().url(shortcodeUrl).head().build()
            val resp = client.newCall(req).execute()
            resp.request.url.toString()
        } catch (e: Exception) {
            "https://raw.githubusercontent.com/$trimmed/master/repo.json"
        }
    }

    suspend fun fetchRepo(urlOrShortcode: String): CloudstreamRepo = withContext(Dispatchers.IO) {
        val finalUrl = resolveRepoUrl(urlOrShortcode)
        val req = Request.Builder().url(finalUrl).build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: throw Exception("Empty repo response")
        val json = JSONObject(body)

        val name = json.optString("name", "Unknown Repo")
        val desc = json.optString("description").ifEmpty { null }
        val pluginLists = mutableListOf<String>()

        val rawLists = json.optJSONArray("pluginLists")
        if (rawLists != null) {
            for (i in 0 until rawLists.length()) {
                pluginLists.add(rawLists.getString(i))
            }
        } else if (json.has("plugins")) {
            // Some repos inline plugins
            pluginLists.add(finalUrl)
        }

        // Fetch each plugin list
        val allPlugins = mutableListOf<CloudstreamPluginInfo>()
        for (pListUrl in pluginLists) {
            try {
                val pReq = Request.Builder().url(pListUrl).build()
                val pResp = client.newCall(pReq).execute()
                val pBody = pResp.body?.string() ?: continue
                
                val pArray = if (pBody.trim().startsWith("[")) {
                    JSONArray(pBody)
                } else {
                    JSONObject(pBody).optJSONArray("plugins") ?: JSONArray()
                }

                for (i in 0 until pArray.length()) {
                    val pObj = pArray.getJSONObject(i)
                    val pName = pObj.optString("name")
                    val pInternal = pObj.optString("internalName", pName)
                    val pUrl = pObj.optString("url")
                    if (pName.isNotEmpty() && pUrl.isNotEmpty()) {
                        allPlugins.add(
                            CloudstreamPluginInfo(
                                name = pName,
                                internalName = pInternal,
                                version = pObj.optInt("version", 1),
                                url = pUrl,
                                iconUrl = pObj.optString("iconUrl").ifEmpty { null },
                                description = pObj.optString("description").ifEmpty { null },
                                language = pObj.optString("language").ifEmpty { null },
                                repoUrl = finalUrl
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore failure of individual plugin lists
            }
        }

        CloudstreamRepo(
            name = name,
            description = desc,
            url = finalUrl,
            pluginLists = pluginLists,
            plugins = allPlugins
        )
    }

    suspend fun downloadPlugin(plugin: CloudstreamPluginInfo): File = withContext(Dispatchers.IO) {
        val targetFile = File(pluginsDir, "${plugin.internalName}.jar")
        val req = Request.Builder().url(plugin.url).build()
        val resp = client.newCall(req).execute()
        val bytes = resp.body?.bytes() ?: throw Exception("Failed to download plugin bytes")

        FileOutputStream(targetFile).use { it.write(bytes) }
        targetFile
    }

    fun getInstalledPlugins(): List<File> {
        return pluginsDir.listFiles { _, name -> name.endsWith(".jar") }?.toList() ?: emptyList()
    }

    fun deletePlugin(internalName: String): Boolean {
        val f = File(pluginsDir, "$internalName.jar")
        return f.exists() && f.delete()
    }
}
