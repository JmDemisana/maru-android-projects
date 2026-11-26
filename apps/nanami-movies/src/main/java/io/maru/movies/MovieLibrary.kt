package io.maru.movies

import android.content.Context
import org.json.JSONObject

internal data class TitleRef(val kind: MovieKind, val imdbId: String) {
    val key: String get() = "${kind.name}:$imdbId"

    companion object {
        fun parse(key: String): TitleRef? {
            val kind = MovieKind.entries.firstOrNull { it.name == key.substringBefore(':') } ?: return null
            val id = key.substringAfter(':', "")
            return id.takeIf { it.matches(Regex("tt[0-9]+")) }?.let { TitleRef(kind, it) }
        }
    }
}

internal val MovieTitle.ref: TitleRef get() = TitleRef(kind, imdbId)

internal data class WatchProgress(
    val title: TitleRef,
    val season: Int?,
    val episode: Int?,
    val position: Long,
    val duration: Long,
    val updated: Long,
) {
    val key: String get() = "${title.key}/${season ?: "movie"}/${episode ?: "movie"}"
    val completed: Boolean get() = duration > 0 && position >= duration * 0.9
    val canResume: Boolean get() = position >= 5_000 && !completed
}

internal class MovieLibrary(context: Context) {
    private val prefs = context.getSharedPreferences("nanami_movies_library", Context.MODE_PRIVATE)

    fun saved(): Set<TitleRef> = prefs.getStringSet("saved", emptySet()).orEmpty()
        .mapNotNull(TitleRef::parse).toSet()

    fun save(items: Set<TitleRef>) {
        prefs.edit().putStringSet("saved", items.map(TitleRef::key).toSet()).apply()
    }

    fun recent(): List<WatchProgress> = prefs.all.keys.asSequence()
        .filter { it.startsWith("progress/") }
        .mapNotNull(::read)
        .sortedByDescending(WatchProgress::updated)
        .filter(WatchProgress::canResume)
        .distinctBy(WatchProgress::title)
        .take(15)
        .toList()

    fun progress(title: TitleRef, episode: MovieEpisode?): WatchProgress? =
        read("progress/${WatchProgress(title, episode?.season, episode?.episode, 0, 0, 0).key}")

    fun record(title: TitleRef, episode: MovieEpisode?, position: Long, duration: Long): WatchProgress? {
        if (position <= 0 || duration <= 0) return null
        val progress = WatchProgress(title, episode?.season, episode?.episode, position, duration, System.currentTimeMillis())
        val key = "progress/${progress.key}"
        val previous = read(key)
        if (previous != null && previous.completed && progress.completed) return previous
        if (previous != null && previous.completed && position < 5_000) return previous
        if (previous != null && !progress.completed && progress.updated - previous.updated < 1_500 &&
            kotlin.math.abs(position - previous.position) < 2_000
        ) return previous
        prefs.edit().putString(key, JSONObject().apply {
            put("title", title.key)
            if (episode != null) {
                put("season", episode.season)
                put("episode", episode.episode)
            }
            put("position", progress.position)
            put("duration", progress.duration)
            put("updated", progress.updated)
        }.toString()).apply()
        return progress
    }

    fun sent(key: String): Boolean = prefs.getBoolean("simkl/$key", false)

    fun markSent(key: String) {
        prefs.edit().putBoolean("simkl/$key", true).apply()
    }

    private fun read(key: String): WatchProgress? {
        val raw = prefs.getString(key, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val title = TitleRef.parse(json.optString("title")) ?: return null
        return WatchProgress(
            title,
            json.optInt("season", -1).takeIf { it >= 0 },
            json.optInt("episode", -1).takeIf { it >= 0 },
            json.optLong("position"),
            json.optLong("duration"),
            json.optLong("updated"),
        )
    }
}
