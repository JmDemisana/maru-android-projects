package io.maru.movies

import android.content.Context
import android.net.Uri
import io.maru.manime.extensions.StremioAddonClient
import io.maru.manime.extensions.StremioStreamResult
import io.maru.manime.extensions.SubtitleTrack
import io.maru.manime.readResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TorrentioUrl = "https://torrentio.strem.fun"
private val subtitleAddons = listOf("https://opensubtitles-v3.strem.io", "https://opensubtitles.strem.io")
private val sourceHttp = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
private val IntroDbSegments = "https://api.introdb.app/segments".toHttpUrl()
private val releaseGroup = Regex("-([A-Za-z][A-Za-z0-9]{1,26})$")
private val resolution = Regex("(?i)\\b(?:2160p|1080p|720p|480p|360p|4K)\\b")
private val reportedSeeders = Regex("(?:👤\\s*|\\bseeders?\\s*[:=]?\\s*)(\\d[\\d,]*)", RegexOption.IGNORE_CASE)

internal fun movieStreamId(title: MovieTitle, episode: MovieEpisode?): String =
    episode?.let { "${title.imdbId}:${it.season}:${it.episode}" } ?: title.imdbId

internal fun StremioStreamResult.httpUri(): Uri? = url?.let(::httpsUri)

private fun httpsUri(url: String): Uri? = Uri.parse(url).takeIf {
    it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank()
}

internal fun StremioStreamResult.reportedSeeds(): Int? =
    reportedSeeders.find(title.orEmpty())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

internal suspend fun findMovieSources(
    title: MovieTitle,
    episode: MovieEpisode?,
    context: Context? = null,
    preferredQuality: String = context?.let { MoviesPreferences.preferredQuality(it) } ?: "1080p",
): List<StremioStreamResult> = coroutineScope {
    val addonUrls = (context?.let { MoviesPreferences.addons(it) }.orEmpty() + TorrentioUrl)
        .map { it.trim().trimEnd('/').removeSuffix("/manifest.json") }
        .filter { httpsUri(it) != null }
        .distinct()
    val results = addonUrls.map { url ->
        async {
            try {
                StremioAddonClient(url, sourceHttp).getStreams(title.kind.addonType, movieStreamId(title, episode))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
    }.awaitAll()
    if (results.all { it == null }) throw IOException("Your video sources could not be reached.")
    rankMovieSources(
        sources = results.filterNotNull().flatten()
            .filter { it.httpUri() != null || it.magnetUri() != null }
            .distinctBy { Triple(it.url, it.infoHash, it.fileIdx) },
        preferredQuality = preferredQuality,
    )
}

internal fun rankMovieSources(
    sources: List<StremioStreamResult>,
    preferredQuality: String = "1080p",
): List<StremioStreamResult> {
    val normalizedPref = if (preferredQuality.equals("2160p", true) || preferredQuality.equals("4K", true)) "4K" else preferredQuality.lowercase(Locale.ROOT)
    return sources.map { stream ->
        val seeds = stream.reportedSeeds()
        val direct = stream.url?.startsWith("https://", ignoreCase = true) == true
        val quality = stream.movieQuality()
        val matchesPref = quality != null && quality.equals(normalizedPref, ignoreCase = true)
        val balancedHd = quality in setOf("1080p", "720p")
        val group = when {
            direct && matchesPref -> 0
            direct -> 1
            seeds != null && seeds > 0 && matchesPref -> 2
            seeds != null && seeds > 0 && balancedHd -> 3
            seeds != null && seeds > 0 -> 4
            seeds == null -> 5
            else -> 6
        }
        Triple(stream, group, seeds ?: 0)
    }.sortedWith(compareBy<Triple<StremioStreamResult, Int, Int>> { it.second }.thenByDescending { it.third })
        .map { it.first }
}

internal suspend fun findMovieSubtitles(title: MovieTitle, episode: MovieEpisode?): List<SubtitleTrack> {
    for (addon in subtitleAddons) {
        val subtitles = try {
            val id = movieStreamId(title, episode)
            val request = Request.Builder().url("$addon/subtitles/${title.kind.addonType}/$id.json")
                .header("User-Agent", StremioAddonClient.BROWSER_UA)
                .build()
            val raw = sourceHttp.newCall(request).readResponse { if (it.isSuccessful) it.body?.string() else null }
            val array = raw?.let(::JSONObject)?.optJSONArray("subtitles") ?: continue
            buildList {
                for (index in 0 until array.length()) {
                    val subtitle = array.optJSONObject(index) ?: continue
                    val url = subtitle.optString("url")
                    if (httpsUri(url) != null) add(SubtitleTrack(url, subtitle.optString("lang", "und")))
                }
            }.distinctBy(SubtitleTrack::url)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            continue
        }
        if (subtitles.isNotEmpty()) return subtitles
    }
    return emptyList()
}

internal enum class MovieSegmentType(val field: String, val action: String) {
    INTRO("intro", "Skip Intro"), RECAP("recap", "Skip Recap"), CREDITS("outro", "Skip Credits"),
}

internal data class MovieSegment(val type: MovieSegmentType, val start: Long, val end: Long) {
    fun at(position: Long, duration: Long): Boolean = position in start until end && end < duration
}

internal suspend fun findMovieSegments(title: MovieTitle, episode: MovieEpisode?): List<MovieSegment> {
    if (title.kind == MovieKind.SERIES && (episode == null || episode.season < 1 || episode.episode < 1)) return emptyList()
    val url = IntroDbSegments.newBuilder().addQueryParameter("imdb_id", title.imdbId).apply {
        if (episode == null) addQueryParameter("is_movie", "true") else {
            addQueryParameter("season", episode.season.toString())
            addQueryParameter("episode", episode.episode.toString())
        }
    }.build()
    return try {
        val request = Request.Builder().url(url).build()
        val content = sourceHttp.newCall(request).apply { timeout().timeout(6, TimeUnit.SECONDS) }
            .readResponse { if (it.isSuccessful) it.body?.string() else null }
        val document = content?.let(::JSONObject) ?: return emptyList()
        if (document.optString("imdb_id") != title.imdbId || document.optBoolean("is_movie") != (episode == null)) return emptyList()
        if (episode != null && (document.optInt("season") != episode.season || document.optInt("episode") != episode.episode)) return emptyList()
        MovieSegmentType.entries.mapNotNull { type ->
            val marker = document.optJSONObject(type.field) ?: return@mapNotNull null
            val start = marker.optLong("start_ms", -1)
            val end = marker.optLong("end_ms", -1)
            val limit = if (type == MovieSegmentType.CREDITS) 30 * 60_000L else 5 * 60_000L
            if (start < 0 || end - start !in 2_000L..limit || marker.optDouble("confidence", 0.0) < 0.8) null
            else MovieSegment(type, start, end)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun languageName(language: String?): String {
    if (language.isNullOrBlank() || language == "und") return "Unspecified language"
    return Locale.forLanguageTag(language.replace('_', '-')).getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar(Char::titlecase)
}

internal fun StremioStreamResult.movieQuality(): String? = resolution.find("$name $title $filename")
    ?.value?.lowercase()?.let { if (it == "2160p" || it == "4k") "4K" else it }

internal fun StremioStreamResult.friendlyCinemaDetail(index: Int): String {
    val raw = "$name $title $filename"
    val tags = buildList {
        if (index == 0) add("Recommended")
        if (httpUri() != null) add("Direct stream")
        when {
            Regex("(?i)\\batmos\\b").containsMatchIn(raw) -> add("Dolby Atmos")
            Regex("(?i)\\b(?:ddp?[ ._-]?5[ ._-]?1|5\\.1|eac3|ac3|truehd|dts)\\b").containsMatchIn(raw) -> add("Surround 5.1")
            Regex("(?i)\\b(?:aac|2\\.0|stereo)\\b").containsMatchIn(raw) -> add("Stereo")
        }
        when {
            Regex("(?i)\\b(?:dv|dolby[ ._-]?vision)\\b").containsMatchIn(raw) -> add("Dolby Vision")
            Regex("(?i)\\bhdr10\\+?\\b|\\bhdr\\b").containsMatchIn(raw) -> add("HDR")
        }
        if (listsEnglishAudio() || Regex("(?i)\\bmulti\\b").containsMatchIn(raw)) {
            add("Multi-Audio")
        }
    }
    return if (tags.isNotEmpty()) tags.distinct().joinToString(" • ") else "Alternate video stream"
}

internal fun StremioStreamResult.movieSourceName(): String =
    name?.lineSequence()?.joinToString(" ")?.trim()?.takeIf(String::isNotBlank)
        ?: httpUri()?.host ?: "Torrent"

internal class MovieSourceMemory(context: Context) {
    private val preferences = context.getSharedPreferences("movies_source_preferences", Context.MODE_PRIVATE)

    fun preferred(title: MovieTitle, episode: MovieEpisode?, available: List<StremioStreamResult>): StremioStreamResult? {
        val exact = preferences.getString("exact/${movieStreamId(title, episode)}", null)
        available.firstOrNull { exact != null && it.fingerprint() == exact }?.let { return it }
        if (episode == null) return null
        val hash = preferences.getString("pack/${title.imdbId}", null)
        available.firstOrNull { hash != null && hash == it.infoHash?.lowercase() }?.let { return it }
        val group = preferences.getString("family/${title.imdbId}", null) ?: return null
        val quality = preferences.getString("quality/${title.imdbId}", null)
        return available.filter { it.sourceFamily() == group }.maxByOrNull { if (it.movieQuality() == quality) 1 else 0 }
    }

    fun save(title: MovieTitle, episode: MovieEpisode?, source: StremioStreamResult) {
        preferences.edit().apply {
            putString("exact/${movieStreamId(title, episode)}", source.fingerprint())
            if (episode != null) {
                putString("pack/${title.imdbId}", source.infoHash?.lowercase())
                putString("family/${title.imdbId}", source.sourceFamily())
                putString("quality/${title.imdbId}", source.movieQuality())
            }
        }.apply()
    }
}

private fun StremioStreamResult.fingerprint(): String {
    val key = "${infoHash?.lowercase() ?: url}\n${fileIdx ?: -1}"
    return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))
}

private fun StremioStreamResult.sourceFamily(): String? {
    if (httpUri() != null) return "host:${httpUri()?.host}"
    val release = (filename ?: title?.lineSequence()?.firstOrNull()).orEmpty()
        .substringAfterLast('/').substringAfterLast('\\').trim()
        .replace(Regex("(?i)\\.(mkv|mp4|webm|avi)$"), "")
    val group = releaseGroup.find(release)?.groupValues?.get(1)?.lowercase() ?: return null
    return "release:$group"
}
