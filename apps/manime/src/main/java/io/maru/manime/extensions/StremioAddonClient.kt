package io.maru.manime.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class StremioManifest(
    val id: String,
    val name: String,
    val description: String?,
    val version: String,
    val resources: List<String>,
    val types: List<String>,
    val catalogs: List<String>
)

data class StremioStreamResult(
    val title: String?,
    val name: String?,
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int? = null,
    val behaviorHints: Map<String, Any> = emptyMap()
)

class StremioAddonClient(
    private val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val cleanBase = baseUrl.trimEnd('/')

    suspend fun getManifest(): StremioManifest = withContext(Dispatchers.IO) {
        val url = if (cleanBase.endsWith("/manifest.json")) cleanBase else "$cleanBase/manifest.json"
        val req = Request.Builder().url(url).build()
        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: throw Exception("Empty manifest response")
        val json = JSONObject(body)

        val resources = mutableListOf<String>()
        val rawRes = json.optJSONArray("resources")
        if (rawRes != null) {
            for (i in 0 until rawRes.length()) {
                val item = rawRes.get(i)
                if (item is String) resources.add(item)
                else if (item is JSONObject) resources.add(item.optString("name"))
            }
        }

        val types = mutableListOf<String>()
        val rawTypes = json.optJSONArray("types")
        if (rawTypes != null) {
            for (i in 0 until rawTypes.length()) types.add(rawTypes.getString(i))
        }

        StremioManifest(
            id = json.optString("id", cleanBase),
            name = json.optString("name", "Stremio Addon"),
            description = json.optString("description").ifEmpty { null },
            version = json.optString("version", "1.0.0"),
            resources = resources,
            types = types,
            catalogs = emptyList()
        )
    }

    suspend fun getStreams(type: String, id: String): List<StremioStreamResult> = withContext(Dispatchers.IO) {
        // Stream URL endpoint: /stream/{type}/{id}.json
        val root = if (cleanBase.endsWith("/manifest.json")) cleanBase.substringBeforeLast("/manifest.json") else cleanBase
        val url = "$root/stream/$type/$id.json"
        try {
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val streams = json.optJSONArray("streams") ?: return@withContext emptyList()

            val results = mutableListOf<StremioStreamResult>()
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                val streamUrl = s.optString("url").ifEmpty { null }
                val infoHash = s.optString("infoHash").ifEmpty { null }
                val title = s.optString("title").ifEmpty { null }
                val name = s.optString("name").ifEmpty { null }
                val fileIdx = if (s.has("fileIdx")) s.getInt("fileIdx") else null

                if (streamUrl != null || infoHash != null) {
                    results.add(
                        StremioStreamResult(
                            title = title,
                            name = name,
                            url = streamUrl,
                            infoHash = infoHash,
                            fileIdx = fileIdx
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }
}
