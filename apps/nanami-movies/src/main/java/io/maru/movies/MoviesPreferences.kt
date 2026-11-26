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
