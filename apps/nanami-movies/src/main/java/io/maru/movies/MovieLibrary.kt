package io.maru.movies

import android.content.Context
import org.json.JSONArray
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
    val completed: Boolean = duration > 0 && position >= duration * 0.9,
) {
    val key: String get() = "${title.key}/${season ?: "movie"}/${episode ?: "movie"}"
    val canResume: Boolean get() = position >= 5_000 && position < duration && !completed
}

internal data class PendingListChange(val title: TitleRef, val listed: Boolean)
internal data class PendingWatchChange(val progress: WatchProgress, val watched: Boolean)
internal data class PendingRating(val title: TitleRef, val score: Int)
internal data class PendingStatus(val title: TitleRef, val status: MovieWatchStatus)

internal enum class MovieWatchStatus(val code: String, val label: String) {
    PLAN("plantowatch", "Plan to Watch"), WATCHING("watching", "Watching"), HOLD("hold", "On Hold"),
    DROPPED("dropped", "Dropped"), COMPLETED("completed", "Completed");

    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.code == value }
    }
}

internal class MovieLibrary(context: Context) {
    private val prefs = context.getSharedPreferences("nanami_movies_library", Context.MODE_PRIVATE)

    fun saved(): Set<TitleRef> = prefs.getStringSet("saved", emptySet()).orEmpty()
        .mapNotNull(TitleRef::parse).toSet()

    fun save(items: Set<TitleRef>) {
        val before = savedOrder().filterKeys { it in items }
        val order = JSONObject()
        for (item in items) order.put(item.key, before[item] ?: System.currentTimeMillis())
        prefs.edit().putStringSet("saved", items.map(TitleRef::key).toSet()).putString("saved_order", order.toString()).apply()
    }

    fun savedOrder(): Map<TitleRef, Long> {
        val data = readJson("saved_order") ?: return emptyMap()
        return data.keys().asSequence().mapNotNull { key -> TitleRef.parse(key)?.let { it to data.optLong(key) } }.toMap()
    }

    fun ratings(): Map<TitleRef, Int> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith("rating/") || value !is Int) return@mapNotNull null
        TitleRef.parse(key.removePrefix("rating/"))?.let { it to value }
    }.toMap()

    fun rate(title: TitleRef, score: Int, account: String?) {
        require(score in 0..10)
        val edit = prefs.edit()
        if (account == null) edit.putInt("rating/${title.key}", score)
        else edit.putInt("rated/$account/${title.key}", score)
        edit.apply()
    }

    fun pendingRatings(account: String): List<PendingRating> {
        val prefix = "rated/$account/"
        return prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(prefix) || value !is Int) return@mapNotNull null
            TitleRef.parse(key.removePrefix(prefix))?.let { PendingRating(it, value) }
        }
    }

    fun finishRating(account: String, rating: PendingRating) {
        val key = "rated/$account/${rating.title.key}"
        if (prefs.all[key] == rating.score) prefs.edit().remove(key).apply()
    }

    fun tracking(): Map<TitleRef, MovieWatchStatus> = statuses("tracking/").associate { it.title to it.status }

    fun track(title: TitleRef, status: MovieWatchStatus?, account: String?) {
        val prefix = if (account == null) "tracking/" else "status/$account/"
        val edit = prefs.edit().putString(prefix + title.key, status?.code)
        if (account != null) edit.remove("list/$account/${title.key}")
        edit.apply()
    }

    fun pendingStatuses(account: String): List<PendingStatus> = statuses("status/$account/")

    fun finishStatus(account: String, pending: PendingStatus) {
        val key = "status/$account/${pending.title.key}"
        if (prefs.getString(key, null) == pending.status.code) prefs.edit().remove(key).apply()
    }

    private fun statuses(prefix: String): List<PendingStatus> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(prefix) || value !is String) return@mapNotNull null
        val title = TitleRef.parse(key.removePrefix(prefix)) ?: return@mapNotNull null
        MovieWatchStatus.parse(value)?.let { PendingStatus(title, it) }
    }

    fun history(): List<WatchProgress> = prefs.all.keys.asSequence()
        .filter { it.startsWith("progress/") }
        .mapNotNull(::read)
        .sortedByDescending(WatchProgress::updated)
        .toList()

    fun recent(): List<WatchProgress> = history()
        .filter(WatchProgress::canResume)
        .distinctBy(WatchProgress::title)
        .take(15)

    fun progress(title: TitleRef, episode: MovieEpisode?): WatchProgress? =
        read("progress/${WatchProgress(title, episode?.season, episode?.episode, 0, 0, 0).key}")

    fun record(title: TitleRef, episode: MovieEpisode?, position: Long, duration: Long, completion: Int): WatchProgress? {
        if (position <= 0 || duration <= 0) return null
        val previous = progress(title, episode)
        val completed = previous?.completed == true || position >= duration * completion.coerceIn(50, 100) / 100.0
        val progress = WatchProgress(title, episode?.season, episode?.episode, position, duration, System.currentTimeMillis(), completed)
        if (previous != null && previous.completed && completed) return previous
        if (previous != null && position == previous.position && duration == previous.duration && !completed) return previous
        if (previous != null && !progress.completed && progress.updated - previous.updated < 1_500 &&
            kotlin.math.abs(position - previous.position) < 2_000
        ) return previous
        prefs.edit().putString("progress/${progress.key}", progress.json().toString()).apply()
        return progress
    }

    fun markWatched(title: TitleRef, episode: MovieEpisode?, watched: Boolean): WatchProgress {
        val previous = progress(title, episode)
        val duration = previous?.duration ?: 0
        val position = if (watched) duration else 0
        val result = WatchProgress(title, episode?.season, episode?.episode, position, duration, System.currentTimeMillis(), watched)
        val edit = prefs.edit()
        if (watched) edit.putString("progress/${result.key}", result.json().toString()) else edit.remove("progress/${result.key}")
        edit.apply()
        return result
    }

    fun sent(key: String): Boolean = prefs.getBoolean("simkl/$key", false)

    fun queueList(account: String, title: TitleRef, listed: Boolean) {
        val edit = prefs.edit().putBoolean("list/$account/${title.key}", listed)
        val statusKey = "status/$account/${title.key}"
        if (prefs.getString(statusKey, null) == MovieWatchStatus.PLAN.code) edit.remove(statusKey)
        edit.apply()
    }

    fun pendingList(account: String): List<PendingListChange> {
        val prefix = "list/$account/"
        return prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith(prefix) || value !is Boolean) return@mapNotNull null
            TitleRef.parse(key.removePrefix(prefix))?.let { PendingListChange(it, value) }
        }
    }

    fun finishList(account: String, change: PendingListChange) {
        val key = "list/$account/${change.title.key}"
        if (prefs.all[key] == change.listed) prefs.edit().remove(key).apply()
    }

    fun queueWatch(account: String, progress: WatchProgress, watched: Boolean): Boolean {
        val key = "watch/$account/${progress.key}"
        if (prefs.contains(key) && readJson(key)?.optBoolean("completed") == watched) return false
        if (watched && sent("$account/${progress.key}") && !prefs.contains(key)) return false
        prefs.edit().putString(key, progress.copy(completed = watched).json().toString()).apply()
        return true
    }

    fun pendingWatch(account: String): List<PendingWatchChange> = prefs.all.keys.mapNotNull { key ->
        if (!key.startsWith("watch/$account/")) return@mapNotNull null
        read(key)?.let { PendingWatchChange(it, it.completed) }
    }

    fun finishWatch(account: String, change: PendingWatchChange) {
        val key = "watch/$account/${change.progress.key}"
        if (readJson(key)?.optBoolean("completed") != change.watched) return
        prefs.edit().remove(key).putBoolean("simkl/$account/${change.progress.key}", change.watched).apply()
    }

    private fun readJson(key: String): JSONObject? = prefs.getString(key, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun read(key: String): WatchProgress? {
        val json = readJson(key) ?: return null
        val title = TitleRef.parse(json.optString("title")) ?: return null
        val position = json.optLong("position")
        val duration = json.optLong("duration")
        return WatchProgress(
            title,
            json.optInt("season", -1).takeIf { it >= 0 },
            json.optInt("episode", -1).takeIf { it >= 0 },
            position,
            duration,
            json.optLong("updated"),
            json.optBoolean("completed", duration > 0 && position >= duration * 0.9),
        )
    }

    private fun WatchProgress.json() = JSONObject().apply {
        put("title", title.key)
        if (season != null) put("season", season)
        if (episode != null) put("episode", episode)
        put("position", position)
        put("duration", duration)
        put("updated", updated)
        put("completed", completed)
    }
}
