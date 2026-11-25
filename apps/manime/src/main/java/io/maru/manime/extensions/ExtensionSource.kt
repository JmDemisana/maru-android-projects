package io.maru.manime.extensions

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

data class SearchResult(
    val id: String,
    val title: String,
    val url: String,
    val posterUrl: String? = null,
    val sourceName: String
)

data class Episode(
    val episodeNumber: Int,
    val title: String? = null,
    val url: String,
    val thumbnail: String? = null
)

data class SubtitleTrack(
    val url: String,
    val language: String,
    val isDefault: Boolean = false
)

data class StreamLink(
    val url: String,               // Direct HTTP URL (mp4/m3u8) or magnet: link
    val quality: String,           // e.g. "1080p", "720p", "4K", "Auto"
    val sourceName: String,
    val isTorrent: Boolean = false,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val audioType: String = "SUB", // "SUB" | "DUB" | "DUAL_AUDIO"
    val methodType: String = "TORRENT",
    val seeders: Int? = null,
    val filename: String? = null,
    val formatBadge: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val sizeBytes: Long? = null,
    val score: Int = 0,
    val verifiedAdultEpisode: Boolean = false
)

internal val StreamLink.playbackIdentity: Pair<String, Int?>
    get() = (infoHash ?: url) to fileIdx

internal class StreamSearchIncompleteException : IOException("A source request failed during the episode search")
internal class StreamMatchUnverifiedException : IOException("No matching episode could be verified")

internal class StreamLookupHealth {
    private val incomplete = AtomicBoolean(false)
    private val unverifiedMatch = AtomicBoolean(false)

    fun recordFailure() {
        incomplete.set(true)
    }

    fun recordUnverifiedMatch() {
        unverifiedMatch.set(true)
    }

    fun checkResult(streams: List<StreamLink>): List<StreamLink> {
        if (streams.isEmpty() && incomplete.get()) throw StreamSearchIncompleteException()
        if (streams.isEmpty() && unverifiedMatch.get()) throw StreamMatchUnverifiedException()
        return streams
    }
}

sealed interface ExtensionSource {
    val name: String
    val id: String
    suspend fun search(query: String): List<SearchResult>
    suspend fun getEpisodes(mediaUrl: String): List<Episode>
    suspend fun getStreamLinks(episodeUrl: String): List<StreamLink>
}
