package io.maru.movies

import android.content.Context
import io.maru.manime.extensions.StremioAddonClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray

internal object MoviesPreferences {
    fun player(context: Context) = context.getSharedPreferences("movies_player", Context.MODE_PRIVATE)

    private fun general(context: Context) = context.getSharedPreferences("movies_settings", Context.MODE_PRIVATE)

    fun completion(context: Context): Int = general(context).getInt("watched_percent", 90).coerceIn(50, 100)

    fun setCompletion(context: Context, percent: Int) {
        general(context).edit().putInt("watched_percent", percent.coerceIn(50, 100)).apply()
    }

    fun hideMature(context: Context): Boolean = general(context).getBoolean("hide_mature", false)

    fun setHideMature(context: Context, enabled: Boolean) {
        general(context).edit().putBoolean("hide_mature", enabled).apply()
    }

    fun doNotTrackMature(context: Context): Boolean = general(context).getBoolean("do_not_track_mature", false)

    fun setDoNotTrackMature(context: Context, enabled: Boolean) {
        general(context).edit().putBoolean("do_not_track_mature", enabled).apply()
    }

    fun reportProgress(context: Context): Boolean = general(context).getBoolean("report_progress", true)

    fun setReportProgress(context: Context, enabled: Boolean) {
        general(context).edit().putBoolean("report_progress", enabled).apply()
    }

    fun rememberPosition(context: Context): Boolean = general(context).getBoolean("remember_position", true)

    fun setRememberPosition(context: Context, enabled: Boolean) {
        general(context).edit().putBoolean("remember_position", enabled).apply()
    }

    fun preferredQuality(context: Context): String =
        general(context).getString("preferred_quality", "1080p")
            ?.takeIf { it in setOf("4K", "1080p", "720p") }
            ?: "1080p"

    fun setPreferredQuality(context: Context, quality: String) {
        val normalized = if (quality in setOf("4K", "1080p", "720p")) quality else "1080p"
        general(context).edit().putString("preferred_quality", normalized).apply()
    }

    fun myListsSortOption(context: Context): String =
        general(context).getString("my_lists_sort_option", "RECENTLY_ADDED") ?: "RECENTLY_ADDED"

    fun setMyListsSortOption(context: Context, option: String) {
        general(context).edit().putString("my_lists_sort_option", option).apply()
    }

    fun addons(context: Context): List<String> = runCatching {
        val array = JSONArray(general(context).getString("addons", "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    suspend fun addAddon(context: Context, input: String): String {
        val url = input.trim().replaceFirst(Regex("(?i)^stremio://"), "https://").trimEnd('/')
        val endpoint = url.toHttpUrlOrNull()
        require(endpoint != null && endpoint.isHttps && endpoint.username.isEmpty() && endpoint.password.isEmpty() &&
            endpoint.query == null && endpoint.fragment == null) { "Enter a valid HTTPS Stremio manifest URL." }
        val manifestUrl = if (url.endsWith("/manifest.json")) url else "$url/manifest.json"
        require(manifestUrl != "https://torrentio.strem.fun/manifest.json") { "Torrentio is already included." }
        val before = addons(context)
        require(manifestUrl !in before) { "This add-on is already installed." }
        require(before.size < 10) { "Remove an add-on before adding another." }
        val manifest = StremioAddonClient(manifestUrl).getManifest()
        require("stream" in manifest.resources && manifest.types.any { it == "movie" || it == "series" }) {
            "This add-on doesn't offer movie or TV streams."
        }
        general(context).edit().putString("addons", JSONArray(before + manifestUrl).toString()).apply()
        return manifest.name
    }

    fun removeAddon(context: Context, url: String) {
        general(context).edit().putString("addons", JSONArray(addons(context) - url).toString()).apply()
    }
}
