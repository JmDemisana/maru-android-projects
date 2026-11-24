package io.maru.manime.torrent

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

data class OfflineEpisodeEntry(
    val mediaId: Int,
    val animeTitle: String,
    val episodeNumber: Int,
    val episodeTitle: String,
    val coverUrl: String?,
    val thumbnailUrl: String?,
    val audioType: String,
    val quality: String,
    val vaultFilePath: String,
    val sizeBytes: Long,
    val downloadedBytes: Long,
    val progressPercent: Int,
    val isCompleted: Boolean,
    val addedAt: Long,
    val failure: String? = null,
    val downloadSpeedKbps: Int = 0,
    val connectedPeers: Int = 0,
    val statusText: String? = null,
    val retryCandidatesJson: String? = null,
    val isAdultContent: Boolean? = null,
    val verifiedAdultEpisode: Boolean = false
)

internal fun OfflineEpisodeEntry.canPlayWithRating(isAdult: Boolean, hasKnownAdultRating: Boolean): Boolean =
    verifiedAdultEpisode || (!isAdult && isAdultContent != true && (hasKnownAdultRating || isAdultContent == false))

internal object OfflineVault {
    val lock = Any()
    private val entries = MutableStateFlow<List<OfflineEpisodeEntry>>(emptyList())
    val episodes = entries.asStateFlow()
    private var loaded = false

    private fun directory(context: Context) = File(context.filesDir, "offline_vault")

    fun preparedDirectory(context: Context): File = directory(context).also { dir ->
        check(dir.isDirectory || dir.mkdirs() || dir.isDirectory) { "Cannot create offline vault" }
        val marker = File(dir, ".nomedia")
        if (!marker.exists()) runCatching { marker.createNewFile() }
    }

    fun episodeFile(context: Context, mediaId: Int, episodeNumber: Int): File =
        offlineEpisodeFile(directory(context), mediaId, episodeNumber)

    private fun index(context: Context) = AtomicFile(File(directory(context), "vault_index.json"))

    fun load(context: Context): List<OfflineEpisodeEntry> = synchronized(lock) {
        if (!loaded) {
            entries.value = try {
                readOfflineIndex(directory(context), index(context).readFully().toString(Charsets.UTF_8))
            } catch (_: FileNotFoundException) {
                emptyList()
            } catch (e: Exception) {
                Log.w("OfflineVault", "Could not read the offline index", e)
                emptyList()
            }
            loaded = true
        }
        entries.value
    }

    fun getCompleted(context: Context, mediaId: Int, episodeNumber: Int): OfflineEpisodeEntry? = synchronized(lock) {
        val entry = load(context).firstOrNull {
            it.mediaId == mediaId && it.episodeNumber == episodeNumber && it.isCompleted
        } ?: return@synchronized null
        val file = episodeFile(context, mediaId, episodeNumber)
        if (file.isFile && file.length() == entry.sizeBytes) return@synchronized entry
        update(context, entry.copy(
            isCompleted = false,
            failure = "The saved file is missing or incomplete. Download this episode again."
        ))
        null
    }

    fun update(context: Context, entry: OfflineEpisodeEntry) {
        synchronized(lock) {
            val next = load(context).toMutableList()
            val position = next.indexOfFirst { it.mediaId == entry.mediaId && it.episodeNumber == entry.episodeNumber }
            if (position >= 0) next[position] = entry else next.add(0, entry)
            if (entry.isCompleted) persist(context, next)
            entries.value = next
        }
    }

    fun applyAdultRatings(context: Context, ratings: Map<Int, Boolean>) = synchronized(lock) {
        val current = load(context)
        val updated = current.map { entry ->
            val rating = ratings[entry.mediaId]
            if (entry.isCompleted && entry.isAdultContent == null && rating != null) {
                entry.copy(isAdultContent = rating)
            } else entry
        }
        if (updated != current) {
            persist(context, updated)
            entries.value = updated
        }
    }

    fun delete(context: Context, mediaId: Int, episodeNumber: Int): Boolean = synchronized(lock) {
        val current = load(context)
        val entry = current.firstOrNull {
            it.mediaId == mediaId && it.episodeNumber == episodeNumber
        } ?: return@synchronized true
        val file = episodeFile(context, mediaId, episodeNumber)
        if (file.exists() && !file.delete()) return@synchronized false

        val next = current - entry
        entries.value = next
        if (entry.isCompleted) {
            // The loader skips missing files even if index refresh fails.
            runCatching { persist(context, next) }
                .onFailure { Log.w("OfflineVault", "Could not refresh the index after deleting an episode", it) }
        }
        true
    }

    private fun persist(context: Context, allEntries: List<OfflineEpisodeEntry>) {
        val data = JSONArray()
        for (entry in allEntries) {
            if (!entry.isCompleted) continue
            data.put(JSONObject().apply {
                put("mediaId", entry.mediaId)
                put("animeTitle", entry.animeTitle)
                put("episodeNumber", entry.episodeNumber)
                put("episodeTitle", entry.episodeTitle)
                put("coverUrl", entry.coverUrl ?: "")
                put("thumbnailUrl", entry.thumbnailUrl ?: "")
                put("audioType", entry.audioType)
                put("quality", entry.quality)
                put("vaultFilePath", entry.vaultFilePath)
                put("sizeBytes", entry.sizeBytes)
                put("downloadedBytes", entry.downloadedBytes)
                put("progressPercent", entry.progressPercent)
                put("isCompleted", entry.isCompleted)
                put("addedAt", entry.addedAt)
                entry.isAdultContent?.let { put("isAdultContent", it) }
                if (entry.verifiedAdultEpisode) put("verifiedAdultEpisode", true)
            })
        }
        val index = index(context)
        val output = index.startWrite()
        try {
            output.write(data.toString().toByteArray(Charsets.UTF_8))
            index.finishWrite(output)
        } catch (e: Exception) {
            index.failWrite(output)
            throw e
        }
    }
}

private fun offlineEpisodeFile(vaultDir: File, mediaId: Int, episodeNumber: Int): File =
    File(vaultDir, "ep_${mediaId}_${episodeNumber}.nanami")

internal fun readOfflineIndex(vaultDir: File, json: String): List<OfflineEpisodeEntry> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { position ->
        val saved = array.optJSONObject(position) ?: return@mapNotNull null
        val mediaId = saved.optInt("mediaId")
        val episodeNumber = saved.optInt("episodeNumber")
        if (!saved.optBoolean("isCompleted") || mediaId <= 0 || episodeNumber <= 0) {
            return@mapNotNull null
        }
        val file = offlineEpisodeFile(vaultDir, mediaId, episodeNumber)
        val size = saved.optLong("sizeBytes", file.length())
        if (size <= 0 || !file.isFile || file.length() != size) return@mapNotNull null

        OfflineEpisodeEntry(
            mediaId = mediaId,
            animeTitle = saved.optString("animeTitle", "Anime"),
            episodeNumber = episodeNumber,
            episodeTitle = saved.optString("episodeTitle", "Episode $episodeNumber"),
            coverUrl = saved.optString("coverUrl").takeIf { it.isNotBlank() },
            thumbnailUrl = saved.optString("thumbnailUrl").takeIf { it.isNotBlank() },
            audioType = saved.optString("audioType", "SUB"),
            quality = saved.optString("quality", "1080p"),
            vaultFilePath = file.absolutePath,
            sizeBytes = size,
            downloadedBytes = size,
            progressPercent = 100,
            isCompleted = true,
            addedAt = saved.optLong("addedAt", file.lastModified()),
            isAdultContent = saved.opt("isAdultContent") as? Boolean,
            verifiedAdultEpisode = saved.optBoolean("verifiedAdultEpisode")
        )
    }.distinctBy { it.mediaId to it.episodeNumber }
}
