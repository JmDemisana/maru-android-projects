package io.maru.lastnotif

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object LastFmRecommendationsEngine {
    private const val TAG = "LastFmRecsEngine"
    private const val API_KEY = LastFmScrobbler.API_KEY

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    enum class RecCategory(val label: String) {
        ALL("All Tracks"),
        ARTISTS("From Top Artists"),
        TRACKS("Similar Tracks")
    }

    data class RecommendedTrackItem(
        val title: String,
        val artist: String,
        val album: String = "",
        val reason: String = "",
        val category: RecCategory = RecCategory.ALL,
        val itunesMatch: ItunesClient.ItunesSongMatch? = null,
        val lastFmArtworkUrl: String? = null
    ) {
        val effectiveArtworkUrl: String?
            get() = itunesMatch?.artworkUrl?.takeIf { it.isNotBlank() } ?: lastFmArtworkUrl?.takeIf { it.isNotBlank() }
    }

    // DTOs for Last.fm API
    private data class TopArtistsResponse(@SerializedName("topartists") val topartists: TopArtistsBody?)
    private data class TopArtistsBody(@SerializedName("artist") val artist: List<ArtistEntry>?)
    private data class ArtistEntry(
        @SerializedName("name") val name: String,
        @SerializedName("playcount") val playcount: String? = null,
        @SerializedName("image") val image: List<Map<String, String>>? = null
    )

    private data class SimilarArtistsResponse(@SerializedName("similarartists") val similarartists: SimilarArtistsBody?)
    private data class SimilarArtistsBody(@SerializedName("artist") val artist: List<ArtistEntry>?)

    private data class ArtistTopTracksResponse(@SerializedName("toptracks") val toptracks: TopTracksBody?)
    private data class TopTracksBody(@SerializedName("track") val track: List<TrackEntry>?)
    private data class TrackEntry(
        @SerializedName("name") val name: String,
        @SerializedName("artist") val artist: ArtistEntry?,
        @SerializedName("image") val image: List<Map<String, String>>? = null
    )

    private data class UserTopTracksResponse(@SerializedName("toptracks") val toptracks: UserTopTracksBody?)
    private data class UserTopTracksBody(@SerializedName("track") val track: List<TrackEntry>?)

    private data class SimilarTracksResponse(@SerializedName("similartracks") val similartracks: SimilarTracksBody?)
    private data class SimilarTracksBody(@SerializedName("track") val track: List<TrackEntry>?)

    private data class RecCandidate(
        val reason: String,
        val artist: String,
        val title: String,
        val art: String? = null
    )

    private fun List<Map<String, String>>?.extractLastFmImage(): String? {
        return this?.lastOrNull()?.get("#text")?.takeIf { it.isNotBlank() }
    }

    private fun normalizeTitle(t: String): String {
        return t.lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?\\]"), "")
            .replace(Regex("[-–—].*"), "")
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    private fun isSameTrack(titleA: String, artistA: String, titleB: String, artistB: String): Boolean {
        val normA = normalizeTitle(titleA)
        val normB = normalizeTitle(titleB)
        if (normA.isEmpty() || normB.isEmpty()) return false
        if (normA == normB) return true
        if (normA.contains(normB) || normB.contains(normA)) {
            val aA = artistA.lowercase().trim()
            val aB = artistB.lowercase().trim()
            if (aA.isNotEmpty() && aB.isNotEmpty() && (aA.contains(aB) || aB.contains(aA))) {
                return true
            }
        }
        return false
    }

    suspend fun getRecommendations(
        username: String,
        category: RecCategory = RecCategory.ALL,
        page: Int = 1
    ): List<RecommendedTrackItem> = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext emptyList()
        try {
            val periods = listOf("7day", "1month", "3month", "6month", "12month", "overall")
            val randomPeriod = periods[Random.nextInt(periods.size)]

            val topArtistsPool = fetchTopArtists(username, period = randomPeriod, limit = 30)
            val userTopTracksPool = fetchUserTopTracks(username, period = randomPeriod, limit = 30)

            val candidates = mutableListOf<RecCandidate>()

            val userTopTrackNames = userTopTracksPool
            val userTopArtistNames = topArtistsPool.map { it.name.lowercase().trim() }.toSet()

            when (category) {
                RecCategory.ALL -> {
                    // 1. Tracks similar to user's top tracks ("Similar to <Song>")
                    val sampledTracks = if (page == 1) userTopTracksPool.shuffled().take(6)
                    else userTopTracksPool.drop((page - 1) * 3).take(3).ifEmpty { userTopTracksPool.shuffled().take(3) }

                    for (track in sampledTracks) {
                        val artistName = track.artist?.name ?: continue
                        val similar = fetchSimilarTracks(artistName, track.name, limit = 8)
                        var added = 0
                        for (s in similar.shuffled()) {
                            val sArtist = s.artist?.name ?: continue
                            if (isSameTrack(s.name, sArtist, track.name, artistName)) continue
                            if (userTopTrackNames.any { isSameTrack(s.name, sArtist, it.name, it.artist?.name ?: "") }) continue
                            candidates.add(RecCandidate("Similar to \"${track.name}\"", sArtist, s.name, s.image.extractLastFmImage()))
                            added++
                            if (added >= 2) break
                        }
                    }

                    // 2. Notable tracks from user's top artists ("From the same artist")
                    val sampledArtists = if (page == 1) topArtistsPool.shuffled().take(4)
                    else topArtistsPool.drop((page - 1) * 2).take(2).ifEmpty { topArtistsPool.shuffled().take(2) }

                    for (artist in sampledArtists) {
                        val topTracks = fetchArtistTopTracks(artist.name, limit = 8)
                        var added = 0
                        for (t in topTracks.shuffled()) {
                            val tArtist = t.artist?.name ?: artist.name
                            if (userTopTrackNames.any { isSameTrack(t.name, tArtist, it.name, it.artist?.name ?: "") }) continue
                            candidates.add(RecCandidate("From the same artist (${artist.name})", tArtist, t.name, t.image.extractLastFmImage()))
                            added++
                            if (added >= 2) break
                        }
                    }

                    // 3. Similar artist highlights ("Because you listen to <Artist>")
                    for (artist in sampledArtists.take(2)) {
                        val simArtists = fetchSimilarArtists(artist.name, limit = 6)
                        for (sim in simArtists.shuffled().take(2)) {
                            if (userTopArtistNames.contains(sim.name.lowercase().trim())) continue
                            val simTracks = fetchArtistTopTracks(sim.name, limit = 4)
                            val pick = simTracks.firstOrNull { t ->
                                !userTopTrackNames.any { isSameTrack(t.name, sim.name, it.name, it.artist?.name ?: "") }
                            }
                            if (pick != null) {
                                candidates.add(RecCandidate("Because you listen to ${artist.name}", sim.name, pick.name, pick.image.extractLastFmImage()))
                            }
                        }
                    }
                }

                RecCategory.ARTISTS -> {
                    val sampledArtists = if (page == 1) topArtistsPool.shuffled().take(8)
                    else topArtistsPool.drop((page - 1) * 5).take(5).ifEmpty { topArtistsPool.shuffled().take(5) }

                    for (artist in sampledArtists) {
                        val topTracks = fetchArtistTopTracks(artist.name, limit = 8)
                        var added = 0
                        for (t in topTracks.shuffled()) {
                            val tArtist = t.artist?.name ?: artist.name
                            if (userTopTrackNames.any { isSameTrack(t.name, tArtist, it.name, it.artist?.name ?: "") }) continue
                            candidates.add(RecCandidate("From the same artist (${artist.name})", tArtist, t.name, t.image.extractLastFmImage()))
                            added++
                            if (added >= 2) break
                        }
                    }
                }

                RecCategory.TRACKS -> {
                    val sampledTracks = if (page == 1) userTopTracksPool.shuffled().take(8)
                    else userTopTracksPool.drop((page - 1) * 5).take(5).ifEmpty { userTopTracksPool.shuffled().take(5) }

                    for (track in sampledTracks) {
                        val artistName = track.artist?.name ?: continue
                        val similar = fetchSimilarTracks(artistName, track.name, limit = 8)
                        var added = 0
                        for (s in similar.shuffled()) {
                            val sArtist = s.artist?.name ?: continue
                            if (isSameTrack(s.name, sArtist, track.name, artistName)) continue
                            if (userTopTrackNames.any { isSameTrack(s.name, sArtist, it.name, it.artist?.name ?: "") }) continue
                            candidates.add(RecCandidate("Similar to \"${track.name}\"", sArtist, s.name, s.image.extractLastFmImage()))
                            added++
                            if (added >= 2) break
                        }
                    }
                }
            }

            val distinct = candidates.distinctBy { "${it.artist.lowercase()} - ${it.title.lowercase()}" }.take(18)
            coroutineScope {
                distinct.map { cand ->
                    async {
                        val itunes = ItunesClient.searchSong(cand.title, cand.artist)
                        RecommendedTrackItem(
                            title = itunes?.trackName ?: cand.title,
                            artist = itunes?.artistName ?: cand.artist,
                            album = itunes?.collectionName ?: "",
                            reason = cand.reason,
                            category = category,
                            itunesMatch = itunes,
                            lastFmArtworkUrl = cand.art
                        )
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching recommendations for $username (cat=$category, page=$page)", e)
            emptyList()
        }
    }

    private fun fetchTopArtists(user: String, period: String = "1month", page: Int = 1, limit: Int = 30): List<ArtistEntry> {
        return try {
            val url = "https://ws.audioscrobbler.com/2.0/?method=user.getTopArtists&user=${URLEncoder.encode(user, "UTF-8")}&api_key=$API_KEY&format=json&page=$page&limit=$limit&period=$period"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                gson.fromJson(body, TopArtistsResponse::class.java).topartists?.artist ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchUserTopTracks(user: String, period: String = "1month", page: Int = 1, limit: Int = 30): List<TrackEntry> {
        return try {
            val url = "https://ws.audioscrobbler.com/2.0/?method=user.getTopTracks&user=${URLEncoder.encode(user, "UTF-8")}&api_key=$API_KEY&format=json&page=$page&limit=$limit&period=$period"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                gson.fromJson(body, UserTopTracksResponse::class.java).toptracks?.track ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchSimilarTracks(artist: String, track: String, limit: Int = 5): List<TrackEntry> {
        return try {
            val url = "https://ws.audioscrobbler.com/2.0/?method=track.getSimilar&artist=${URLEncoder.encode(artist, "UTF-8")}&track=${URLEncoder.encode(track, "UTF-8")}&api_key=$API_KEY&format=json&limit=$limit"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                gson.fromJson(body, SimilarTracksResponse::class.java).similartracks?.track ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchSimilarArtists(artist: String, limit: Int = 5): List<ArtistEntry> {
        return try {
            val url = "https://ws.audioscrobbler.com/2.0/?method=artist.getSimilar&artist=${URLEncoder.encode(artist, "UTF-8")}&api_key=$API_KEY&format=json&limit=$limit"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                gson.fromJson(body, SimilarArtistsResponse::class.java).similarartists?.artist ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchArtistTopTracks(artist: String, limit: Int = 5): List<TrackEntry> {
        return try {
            val url = "https://ws.audioscrobbler.com/2.0/?method=artist.getTopTracks&artist=${URLEncoder.encode(artist, "UTF-8")}&api_key=$API_KEY&format=json&limit=$limit"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                gson.fromJson(body, ArtistTopTracksResponse::class.java).toptracks?.track ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }
}
