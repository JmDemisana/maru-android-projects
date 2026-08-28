package io.maru.manime.extensions

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
    val quality: String,           // e.g. "1080p", "720p", "Auto", "Torrent"
    val sourceName: String,
    val isTorrent: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val sizeBytes: Long? = null
)

sealed interface ExtensionSource {
    val name: String
    val id: String
    suspend fun search(query: String): List<SearchResult>
    suspend fun getEpisodes(mediaUrl: String): List<Episode>
    suspend fun getStreamLinks(episodeUrl: String): List<StreamLink>
}
