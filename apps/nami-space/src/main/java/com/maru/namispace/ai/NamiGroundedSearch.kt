package com.maru.namispace.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Lightweight local search client for grounding Nami's on-device brain with live facts.
 * Features instant 0ms offline bailout when Wi-Fi/data is disconnected.
 */
object NamiGroundedSearch {

    private const val TAG = "NamiGroundedSearch"

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class SearchResult(
        val title: String,
        val snippet: String,
        val url: String,
    )

    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Determines if a user query requires live web search grounding.
     * Bails out in 0ms if the device is offline.
     */
    fun shouldSearch(context: Context, query: String, history: List<Pair<String, Boolean>> = emptyList()): Boolean {
        if (!isOnline(context)) return false

        val q = query.lowercase().trim()
        val searchKeywords = listOf(
            "who is", "what is", "when is", "where is", "how much", "do you know",
            "release date", "lyrics of", "song by", "latest", "news",
            "weather", "event", "anime", "vocaloid", "schedule", "search", "google"
        )
        if (searchKeywords.any { q.contains(it) } || q.endsWith("?")) return true

        val confirmations = listOf("sure", "yes", "yeah", "yep", "ok", "okay", "tell me", "go ahead", "what is it")
        if (confirmations.any { q == it || q.startsWith("$it ") }) {
            val lastAssistantMsg = history.lastOrNull { !it.second }?.first?.lowercase() ?: ""
            if (lastAssistantMsg.contains("google") || lastAssistantMsg.contains("search") || lastAssistantMsg.contains("look")) {
                return true
            }
        }
        return false
    }

    fun cleanQuery(rawQuery: String): String {
        var q = rawQuery.trim()
        val fillerPatterns = listOf(
            Regex("(?i)^do you know (about )?"),
            Regex("(?i)^can you (tell|search|find|google) (me )?(about )?"),
            Regex("(?i)^tell me about "),
            Regex("(?i)^who is "),
            Regex("(?i)^what is "),
            Regex("(?i)^where is "),
            Regex("(?i)^when is "),
            Regex("(?i)^how about "),
            Regex("(?i)^search (for )?"),
            Regex("(?i)^google ")
        )
        for (pattern in fillerPatterns) {
            q = q.replace(pattern, "")
        }
        return q.trim().trimEnd('?').trim()
    }

    suspend fun search(query: String, history: List<Pair<String, Boolean>> = emptyList()): List<SearchResult> = withContext(Dispatchers.IO) {
        val cleaned = cleanQuery(query)
        if (cleaned.isBlank()) return@withContext emptyList()

        try {
            val encoded = URLEncoder.encode(cleaned, "UTF-8")
            val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
            val wikiReq = Request.Builder()
                .url(wikiUrl)
                .header("User-Agent", "NamiSpace/1.0 (Android; Local AI Assistant)")
                .get()
                .build()

            client.newCall(wikiReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JSONObject(body)
                    val title = json.optString("title", "")
                    val extract = json.optString("extract", "")
                    val pageUrl = json.optJSONObject("content_urls")
                        ?.optJSONObject("desktop")
                        ?.optString("page", "") ?: ""

                    if (extract.isNotBlank()) {
                        return@withContext listOf(
                            SearchResult(
                                title = title,
                                snippet = extract.take(400),
                                url = pageUrl
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia lookup skipped: ${e.message}")
        }

        try {
            val encoded = URLEncoder.encode(cleaned, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encoded"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val html = resp.body?.string() ?: return@withContext emptyList()
                parseDuckDuckGoHtml(html)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Search network call failed (offline): ${e.message}")
            emptyList()
        }
    }

    private fun parseDuckDuckGoHtml(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        try {
            val snippetPattern = java.util.regex.Pattern.compile("<a class=\"result__snippet[^\"]*\"[^>]*>(.*?)</a>", java.util.regex.Pattern.DOTALL)
            val titlePattern = java.util.regex.Pattern.compile("<a class=\"result__url[^\"]*\"[^>]*>(.*?)</a>", java.util.regex.Pattern.DOTALL)

            val snippetMatcher = snippetPattern.matcher(html)
            val titleMatcher = titlePattern.matcher(html)

            while (snippetMatcher.find() && results.size < 2) {
                val snippet = snippetMatcher.group(1)?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""
                val title = if (titleMatcher.find()) titleMatcher.group(1)?.replace(Regex("<[^>]*>"), "")?.trim() ?: "" else "Web Result"
                if (snippet.isNotBlank()) {
                    results.add(SearchResult(title = title, snippet = snippet.take(300), url = ""))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing search results", e)
        }
        return results
    }

    fun formatContext(results: List<SearchResult>): String {
        if (results.isEmpty()) return ""
        return buildString {
            appendLine("=== Grounded Knowledge Facts ===")
            results.forEachIndexed { i, r ->
                appendLine("[${i + 1}] ${r.title}: ${r.snippet}")
            }
            appendLine("===============================")
        }
    }
}
