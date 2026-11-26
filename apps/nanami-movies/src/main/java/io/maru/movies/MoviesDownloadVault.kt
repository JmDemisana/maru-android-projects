package io.maru.movies

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal enum class MovieDownloadState { QUEUED, DOWNLOADING, COMPLETE, FAILED, CANCELLED }

internal data class MovieDownloadProgress(val bytes: Long, val total: Long, val speed: Long = 0, val peers: Int = 0) {
    val fraction: Float? get() = total.takeIf { it > 0 }?.let { (bytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
}

internal data class MovieDownload(
    val title: MovieTitle,
    val episode: MovieEpisode?,
    val state: MovieDownloadState = MovieDownloadState.QUEUED,
    val requestedAt: Long = System.currentTimeMillis(),
    val message: String? = null,
    val fileExtension: String? = null,
    val progress: MovieDownloadProgress? = null,
    val attempt: String? = null,
) {
    val key: String get() = downloadKey(title.imdbId, episode)
    val label: String get() = episode?.let { "S${it.season} E${it.episode} · ${it.title}" } ?: "Movie"
    val isActive: Boolean get() = state == MovieDownloadState.DOWNLOADING || state == MovieDownloadState.QUEUED
}

internal open class MovieDownloadException(message: String) : IOException(message)
internal class DownloadStorageException : MovieDownloadException("There isn't enough room for this download.")
internal class DownloadNetworkException(message: String) : MovieDownloadException(message)

internal object MoviesDownloadVault {
    private val lock = Any()
    private val entries = MutableStateFlow<List<MovieDownload>>(emptyList())
    val items = entries.asStateFlow()
    private var loaded = false

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val pending = synchronized(lock) {
            if (loaded) return@synchronized emptyList()
            loadLocked(context)
            entries.value.filter { it.state == MovieDownloadState.QUEUED }.map(MovieDownload::key)
        }
        startQueue(context, pending)
    }

    suspend fun enqueue(context: Context, title: MovieTitle, episodes: List<MovieEpisode?>): Int = withContext(Dispatchers.IO) {
        require(title.imdbId.matches(imdbIdPattern)) { "Invalid IMDb title ID." }
        require(episodes.all { episode ->
            if (title.kind == MovieKind.MOVIE) episode == null else
                episode != null && episode.season >= 0 && episode.episode > 0 &&
                    episode.id == "${title.imdbId}:${episode.season}:${episode.episode}"
        }) { "Invalid episode for this title." }

        val selected = synchronized(lock) {
            loadLocked(context)
            val existing = entries.value.associateBy(MovieDownload::key)
            val requested = episodes.map { MovieDownload(title.copy(episodes = emptyList()), it) }
                .distinctBy(MovieDownload::key).filter { existing[it.key]?.let { row ->
                    row.state == MovieDownloadState.FAILED || row.state == MovieDownloadState.CANCELLED
                } ?: true }
            if (requested.isNotEmpty()) {
                val keys = requested.map(MovieDownload::key).toSet()
                publishLocked(context, entries.value.filterNot { it.key in keys } + requested)
            }
            requested
        }
        startQueue(context, selected.map(MovieDownload::key))
        selected.size
    }

    fun completedFile(context: Context, title: MovieTitle, episode: MovieEpisode?): File? =
        items.value.firstOrNull { it.key == downloadKey(title.imdbId, episode) }?.let { completedFile(context, it) }

    fun completedFile(context: Context, item: MovieDownload): File? {
        if (item.state != MovieDownloadState.COMPLETE) return null
        val size = item.progress?.total?.takeIf { it > 0 } ?: return null
        return destination(context, item)?.takeIf { it.isFile && it.length() == size }
    }

    suspend fun markMissing(context: Context, item: MovieDownload) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = entries.value.firstOrNull { it.key == item.key } ?: return@synchronized
            if (current.state != MovieDownloadState.COMPLETE || completedFile(context, current) != null) return@synchronized
            replaceLocked(context, current.copy(state = MovieDownloadState.FAILED, progress = null,
                message = "The saved file is missing or incomplete."))
        }
    }

    suspend fun retry(context: Context, item: MovieDownload) = withContext(Dispatchers.IO) {
        val queued = synchronized(lock) {
            loadLocked(context)
            val current = entries.value.firstOrNull { it.key == item.key } ?: return@synchronized false
            if (current.isActive || completedFile(context, current) != null) return@synchronized false
            destination(context, current)?.delete()
            val pending = MovieDownload(current.title, current.episode)
            publishLocked(context, entries.value.filterNot { it.key == item.key } + pending)
            true
        }
        if (queued) startQueue(context, listOf(item.key))
    }

    suspend fun cancel(context: Context, key: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            loadLocked(context)
            val item = entries.value.firstOrNull { it.key == key && it.isActive } ?: return@synchronized
            replaceLocked(context, item.copy(state = MovieDownloadState.CANCELLED, progress = null,
                message = "Download canceled.", attempt = null))
        }
    }

    suspend fun delete(context: Context, item: MovieDownload) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            loadLocked(context)
            val entry = entries.value.firstOrNull { it.key == item.key } ?: return@synchronized
            val file = destination(context, entry)
            if (file != null && file.exists() && !file.delete()) throw MovieDownloadException("Couldn't delete the saved file.")
            publishLocked(context, entries.value.filterNot { it.key == item.key })
        }
    }

    fun allowCellular(context: Context): Boolean = settings(context).getBoolean("allow_cellular", false)

    fun setAllowCellular(context: Context, allowed: Boolean) {
        settings(context).edit().putBoolean("allow_cellular", allowed).apply()
    }

    fun seedOnWifi(context: Context): Boolean = settings(context).getBoolean("seed_wifi", false)

    fun setSeedOnWifi(context: Context, enabled: Boolean) {
        settings(context).edit().putBoolean("seed_wifi", enabled).apply()
    }

    fun torrentSpeedKib(context: Context): Int = settings(context).getInt("torrent_speed_kib", 0).coerceIn(0, 102_400)

    fun setTorrentSpeedKib(context: Context, value: Int) {
        settings(context).edit().putInt("torrent_speed_kib", value.coerceIn(0, 102_400)).apply()
    }

    suspend fun takeNext(context: Context): MovieDownload? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            loadLocked(context)
            val next = entries.value.firstOrNull { it.state == MovieDownloadState.QUEUED } ?: return@synchronized null
            val active = next.copy(state = MovieDownloadState.DOWNLOADING, message = "Finding sources…",
                attempt = UUID.randomUUID().toString())
            replaceLocked(context, active)
            active
        }
    }

    fun isCurrent(item: MovieDownload): Boolean = item.attempt != null && items.value.any { current ->
        current.key == item.key && current.attempt == item.attempt && current.state == MovieDownloadState.DOWNLOADING
    }

    fun progress(item: MovieDownload, message: String, progress: MovieDownloadProgress?) {
        synchronized(lock) {
            if (!isCurrent(item)) return
            entries.value = entries.value.map { if (it.key == item.key) it.copy(message = message, progress = progress) else it }
        }
    }

    suspend fun fail(context: Context, item: MovieDownload, message: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (isCurrent(item)) replaceLocked(context,
                item.copy(state = MovieDownloadState.FAILED, progress = null, message = message, attempt = null))
        }
    }

    fun finish(context: Context, item: MovieDownload, source: File, preserveForSeed: Boolean = false): File =
        synchronized(lock) {
            if (!isCurrent(item)) throw kotlinx.coroutines.CancellationException("Download was stopped.")
            val extension = source.extension.lowercase().takeIf { it in extensions }
                ?: throw MovieDownloadException("This download uses an unsupported video format.")
            val size = source.length()
            if (!source.isFile || size <= 0) throw MovieDownloadException("The downloaded file is incomplete.")
            val complete = item.copy(state = MovieDownloadState.COMPLETE, message = null, fileExtension = extension,
                progress = MovieDownloadProgress(size, size), attempt = null)
            val destination = requireNotNull(destination(context, complete))
            val linked = preserveForSeed && runCatching {
                Files.deleteIfExists(destination.toPath())
                Files.createLink(destination.toPath(), source.toPath())
            }.isSuccess
            if (!linked) Files.move(source.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            try {
                replaceLocked(context, complete)
            } catch (failure: Exception) {
                destination.delete()
                throw failure
            }
            destination
        }

    fun workDirectory(context: Context): File {
        val staging = staging(context)
        if (!staging.isDirectory && !staging.mkdirs()) throw MovieDownloadException("Couldn't prepare download storage.")
        return Files.createTempDirectory(staging.toPath(), "transfer-").toFile()
    }

    private fun startQueue(context: Context, keys: List<String>) {
        if (keys.isEmpty()) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, MoviesDownloadService::class.java))
        } catch (failure: RuntimeException) {
            synchronized(lock) {
                val updated = entries.value.map { entry ->
                    if (entry.key in keys && entry.state == MovieDownloadState.QUEUED) {
                        entry.copy(state = MovieDownloadState.FAILED, message = "Open Downloads and tap Retry to start.")
                    } else entry
                }
                publishLocked(context, updated)
            }
            Log.w("MoviesDownloads", "Could not start download service", failure)
        }
    }

    private fun loadLocked(context: Context) {
        if (loaded) return
        val pending = try {
            readIndex(index(context).readFully().toString(Charsets.UTF_8))
        } catch (_: FileNotFoundException) {
            emptyList()
        } catch (failure: Exception) {
            Log.w("MoviesDownloads", "Could not read downloads index", failure)
            emptyList()
        }
        entries.value = pending.map { item ->
            when {
                item.state == MovieDownloadState.DOWNLOADING -> item.copy(state = MovieDownloadState.FAILED,
                    message = "Download interrupted. Tap Retry to start again.")
                item.state == MovieDownloadState.COMPLETE && completedFile(context, item) == null ->
                    item.copy(state = MovieDownloadState.FAILED, message = "The saved file is missing or incomplete.")
                else -> item
            }
        }
        staging(context).deleteRecursively()
        loaded = true
    }

    private fun publishLocked(context: Context, updated: List<MovieDownload>) {
        val directory = directory(context)
        if (!directory.isDirectory && !directory.mkdirs()) throw MovieDownloadException("Couldn't create the private vault.")
        val marker = File(directory, ".nomedia")
        if (!marker.exists()) runCatching { marker.createNewFile() }
        val json = JSONArray()
        updated.forEach { json.put(encode(it)) }
        val atomic = index(context)
        val output = atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
        entries.value = updated
    }

    private fun replaceLocked(context: Context, row: MovieDownload) =
        publishLocked(context, entries.value.map { if (it.key == row.key) row else it })

    private fun settings(context: Context) = context.getSharedPreferences("movies_downloads", Context.MODE_PRIVATE)
    private fun directory(context: Context) = File(context.filesDir, "movies_offline_vault")
    private fun staging(context: Context) = File(directory(context), ".staging")
    private fun index(context: Context) = AtomicFile(File(directory(context), "index.json"))
    private fun destination(context: Context, item: MovieDownload): File? {
        val extension = item.fileExtension?.takeIf { it in extensions } ?: return null
        return File(directory(context), "${item.key}.$extension")
    }
}

private val imdbIdPattern = Regex("tt[0-9]+")
private val extensions = setOf("mp4", "m4v", "mkv", "webm", "avi", "ts", "mov")

private fun downloadKey(imdbId: String, episode: MovieEpisode?): String =
    if (episode == null) "${imdbId}_movie" else "${imdbId}_s${episode.season}_e${episode.episode}"

private fun encode(item: MovieDownload): JSONObject = JSONObject().apply {
    put("imdb", item.title.imdbId)
    put("kind", item.title.kind.name)
    put("title", item.title.title)
    put("poster", item.title.poster)
    put("backdrop", item.title.backdrop)
    put("year", item.title.year)
    put("certification", item.title.certification)
    put("adult", item.title.explicitlyAdult)
    put("state", item.state.name)
    put("requested", item.requestedAt)
    put("message", item.message)
    put("extension", item.fileExtension)
    if (item.state == MovieDownloadState.COMPLETE) put("size", item.progress?.total)
    item.episode?.let { episode ->
        put("episode", JSONObject().apply {
            put("name", episode.title)
            put("season", episode.season)
            put("number", episode.episode)
            put("thumbnail", episode.thumbnail)
        })
    }
}

private fun readIndex(raw: String): List<MovieDownload> {
    val data = JSONArray(raw)
    return (0 until data.length()).mapNotNull { index ->
        val row = data.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optString("imdb").takeIf(imdbIdPattern::matches) ?: return@mapNotNull null
        val kind = MovieKind.entries.firstOrNull { it.name == row.optString("kind") } ?: return@mapNotNull null
        val episode = row.optJSONObject("episode")?.let { stored ->
            val season = stored.optInt("season", -1)
            val number = stored.optInt("number", -1)
            if (season < 0 || number <= 0) return@mapNotNull null
            MovieEpisode("$id:$season:$number", stored.optString("name", "Episode $number"), season, number, stored.text("thumbnail"))
        }
        if ((kind == MovieKind.SERIES) != (episode != null)) return@mapNotNull null
        val state = MovieDownloadState.entries.firstOrNull { it.name == row.optString("state") } ?: return@mapNotNull null
        val size = row.optLong("size")
        MovieDownload(
            MovieTitle(
                kind = kind, imdbId = id, title = row.optString("title", id),
                poster = row.text("poster"), backdrop = row.text("backdrop"), year = row.text("year"),
                certification = row.text("certification"), explicitlyAdult = row.optBoolean("adult"),
            ),
            episode, state, row.optLong("requested"), row.text("message"), row.text("extension"),
            size.takeIf { it > 0 }?.let { MovieDownloadProgress(it, it) },
        )
    }.distinctBy(MovieDownload::key)
}

private fun JSONObject.text(key: String): String? = optString(key).takeIf { !isNull(key) && it.isNotBlank() }
