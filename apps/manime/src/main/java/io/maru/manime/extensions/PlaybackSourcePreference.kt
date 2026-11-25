package io.maru.manime.extensions

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64

internal data class PlaybackSourcePreference(
    val exact: String?,
    val torrent: String?,
    val family: String?,
    val audio: String,
    val quality: String
) {
    fun pick(streams: List<StreamLink>, partial: Boolean = false): StreamLink? {
        val minimumRank = when {
            !partial -> 1
            exact != null -> 4
            else -> 2
        }
        var best: StreamLink? = null
        var bestRank = minimumRank - 1
        for (stream in streams) {
            if (stream.isTorrent && stream.seeders == 0) continue
            val rank = when {
                exact != null && exact == stream.sourceFingerprint() -> 4
                stream.audioType != audio && stream.audioType != "DUAL_AUDIO" -> 0
                torrent != null && torrent == stream.torrentPack() -> 3
                family != null && family == stream.sourceFamily() -> if (stream.quality == quality) 2 else 1
                else -> 0
            }
            if (rank > bestRank) {
                best = stream
                bestRank = rank
            }
        }
        return best
    }

    fun forSeries(): PlaybackSourcePreference? =
        if (torrent != null || family != null) copy(exact = null) else null

    fun encode(): String = JSONObject()
        .put("exact", exact)
        .put("torrent", torrent)
        .put("family", family)
        .put("audio", audio)
        .put("quality", quality)
        .toString()

    companion object {
        fun from(stream: StreamLink, preferredAudio: String) = PlaybackSourcePreference(
            exact = stream.sourceFingerprint(),
            torrent = stream.torrentPack(),
            family = stream.sourceFamily(),
            audio = if (stream.audioType == "DUAL_AUDIO") preferredAudio else stream.audioType,
            quality = stream.quality
        )

        fun decode(value: String?): PlaybackSourcePreference? {
            if (value.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(value)
                PlaybackSourcePreference(
                    exact = json.optString("exact").takeIf(String::isNotBlank),
                    torrent = json.optString("torrent").takeIf(String::isNotBlank),
                    family = json.optString("family").takeIf(String::isNotBlank),
                    audio = json.getString("audio"),
                    quality = json.getString("quality")
                )
            }.getOrNull()?.takeIf { it.exact != null || it.family != null || it.torrent != null }
        }
    }
}

private val releaseGroup = Regex("""^\[([A-Za-z0-9][A-Za-z0-9 ._-]{1,39})\]\s*\S""")
private val releaseTag = Regex(
    """(?:\d{3,4}[pi]|\d{1,2}bit|\d+k|s\d+(?:e\d+)?|(?:season|episode|vol)[ ._-]*\d+|""" +
        """(?:h|x)[ ._-]?26[45]|(?:hevc|avc|av1|aac|flac|hdr|sdr|blu[ ._-]?ray|web[ ._-]?(?:dl|rip)|""" +
        """bd|batch|complete|raw|sub|dub|eng|jpn|dual[ ._-]?audio))""",
    RegexOption.IGNORE_CASE
)

internal fun StreamLink.sourceFamilyLabel(): String? {
    if (!isTorrent) return url.toHttpUrlOrNull()?.host
    val name = filename?.lineSequence()?.firstOrNull(String::isNotBlank)
        ?.substringAfterLast('/')?.substringAfterLast('\\')?.trimStart() ?: return null
    return releaseGroup.find(name)?.groupValues?.get(1)?.trim()?.takeIf {
        it.any(Char::isLetter) && !releaseTag.matches(it)
    }
}

private fun StreamLink.sourceFamily(): String? =
    sourceFamilyLabel()?.let { label ->
        if (isTorrent) "release:" + label.lowercase() else "host:" + label.lowercase()
    }

private fun StreamLink.torrentPack(): String? = infoHash?.trim()?.takeIf { isTorrent && it.isNotEmpty() }?.lowercase()

private fun StreamLink.sourceFingerprint(): String {
    val key = (torrentPack() ?: playbackIdentity.first) + "\n" + fileIdx?.toString().orEmpty()
    val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
