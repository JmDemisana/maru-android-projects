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
        ALL("All"),
        ARTISTS("Artists"),
        ALBUMS("Albums"),
        TRACKS("Tracks")
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

    private data class TopAlbumsResponse(@SerializedName("topalbums") val topalbums: TopAlbumsBody?)
    private data class TopAlbumsBody(@SerializedName("album") val album: List<AlbumEntry>?)
    private data class AlbumEntry(
        @SerializedName("name") val name: String,
        @SerializedName("artist") val artist: ArtistEntry?,
        @SerializedName("image") val image: List<Map<String, String>>? = null
    )

    private data class RecCandidate(
        val reason: String,
        val artist: String,
        val extra: String = "",
        val art: String? = null
    )

    private fun List<Map<String, String>>?.extractLastFmImage(): String? {
        return this?.lastOrNull()?.get("#text")?.takeIf { it.isNotBlank() }
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

            when (category) {
                RecCategory.ALL -> {
                    // Sample diverse seeds from top artists and top tracks
                    val topArtistsPool = fetchTopArtists(username, period = randomPeriod, limit = 30)
                    val userTopTracksPool = fetchUserTopTracks(username, period = randomPeriod, limit = 30)

                    val sampledArtists = if (page == 1) {
                        topArtistsPool.shuffled().take(4)
                    } else {
                        topArtistsPool.drop((page - 1) * 3).take(3).ifEmpty { topArtistsPool.shuffled().take(3) }
                    }

                    val sampledTracks = if (page == 1) {
                        userTopTracksPool.shuffled().take(4)
                    } else {
                        userTopTracksPool.drop((page - 1) * 3).take(3).ifEmpty { userTopTracksPool.shuffled().take(3) }
                    }

                    val tracksRaw = mutableListOf<RecCandidate>() // reason, artist, song, art
                    for (track in sampledTracks) {
                        val artistName = track.artist?.name ?: continue
                        val similar = fetchSimilarTracks(artistName, track.name, limit = 6)
                        for (s in similar.shuffled().take(2)) {
                            val sArtist = s.artist?.name ?: continue
                            tracksRaw.add(RecCandidate("Similar to \"${track.name}\"", sArtist, s.name, s.image.extractLastFmImage()))
                        }
                    }

                    val artistsRaw = mutableListOf<RecCandidate>() // reason, artist, "", art
                    for (artist in sampledArtists) {
                        val similar = fetchSimilarArtists(artist.name, limit = 6)
                        for (simArtist in similar.shuffled().take(2)) {
                            artistsRaw.add(RecCandidate("Similar to ${artist.name}", simArtist.name, "", simArtist.image.extractLastFmImage()))
                        }
                    }

                    val albumsRaw = mutableListOf<RecCandidate>() // reason, artist, album, art
                    for (artist in sampledArtists) {
                        val albums = fetchArtistTopAlbums(artist.name, limit = 6)
                        for (album in albums.shuffled().take(2)) {
                            albumsRaw.add(RecCandidate("Album by ${artist.name}", artist.name, album.name, album.image.extractLastFmImage()))
                        }
                    }

                    coroutineScope {
                        val enrichedTracks = tracksRaw.distinctBy { "${it.artist.lowercase()} - ${it.extra.lowercase()}" }.take(6).map { cand ->
                            async {
                                val itunes = ItunesClient.searchSong(cand.extra, cand.artist)
                                RecommendedTrackItem(
                                    title = itunes?.trackName ?: cand.extra,
                                    artist = itunes?.artistName ?: cand.artist,
                                    album = itunes?.collectionName ?: "",
                                    reason = cand.reason,
                                    category = RecCategory.TRACKS,
                                    itunesMatch = itunes,
                                    lastFmArtworkUrl = cand.art
                                )
                            }
                        }

                        val enrichedArtists = artistsRaw.distinctBy { it.artist.lowercase() }.take(4).map { cand ->
                            async {
                                val itunes = ItunesClient.searchArtist(cand.artist)
                                RecommendedTrackItem(
                                    title = itunes?.artistName ?: cand.artist,
                                    artist = itunes?.collectionName ?: "Artist",
                                    album = "",
                                    reason = cand.reason,
                                    category = RecCategory.ARTISTS,
                                    itunesMatch = itunes,
                                    lastFmArtworkUrl = cand.art
                                )
                            }
                        }

                        val enrichedAlbums = albumsRaw.distinctBy { "${it.artist.lowercase()} - ${it.extra.lowercase()}" }.take(4).map { cand ->
                            async {
                                val itunes = ItunesClient.searchAlbum(cand.extra, cand.artist)
                                RecommendedTrackItem(
                                    title = itunes?.collectionName ?: cand.extra,
                                    artist = itunes?.artistName ?: cand.artist,
                                    album = itunes?.collectionName ?: cand.extra,
                                    reason = cand.reason,
                                    category = RecCategory.ALBUMS,
                                    itunesMatch = itunes,
                                    lastFmArtworkUrl = cand.art
                                )
                            }
                        }

                        val tList = enrichedTracks.awaitAll()
                        val aList = enrichedArtists.awaitAll()
                        val albList = enrichedAlbums.awaitAll()

                        // Interleave into a diverse mixed feed
                        val mixed = mutableListOf<RecommendedTrackItem>()
                        val maxLen = maxOf(tList.size, aList.size, albList.size)
                        for (i in 0 until maxLen) {
                            if (i < tList.size) mixed.add(tList[i])
                            if (i + 1 < tList.size && i % 2 == 0) mixed.add(tList[i + 1])
                            if (i < aList.size) mixed.add(aList[i])
                            if (i < albList.size) mixed.add(albList[i])
                        }
                        mixed.distinctBy { "${it.artist.lowercase()} - ${it.title.lowercase()}" }
                    }
                }

                RecCategory.ARTISTS -> {
                    val topArtistsPool = fetchTopArtists(username, period = randomPeriod, limit = 30)
                    val sampledArtists = if (page == 1) {
                        topArtistsPool.shuffled().take(6)
                    } else {
                        topArtistsPool.drop((page - 1) * 4).take(4).ifEmpty { topArtistsPool.shuffled().take(4) }
                    }

                    val artistsRaw = mutableListOf<RecCandidate>() // reason, artist, art
                    for (artist in sampledArtists) {
                        val similar = fetchSimilarArtists(artist.name, limit = 8)
                        for (simArtist in similar.shuffled().take(3)) {
                            artistsRaw.add(RecCandidate("Similar to ${artist.name}", simArtist.name, "", simArtist.image.extractLastFmImage()))
                        }
                    }

                    val distinct = artistsRaw.distinctBy { it.artist.lowercase() }.take(16)
                    coroutineScope {
                        distinct.map { cand ->
                            async {
                                val itunes = ItunesClient.searchArtist(cand.artist)
                                RecommendedTrackItem(
                                    title = itunes?.artistName ?: cand.artist,
                                    artist = itunes?.collectionName ?: "Artist",
                                    album = "",
                                    reason = cand.reason,
                                    category = RecCategory.ARTISTS,
                                    itunesMatch = itunes,
                                    lastFmArtworkUrl = cand.art
                                )
                            }
                        }.awaitAll()
                    }
                }

                RecCategory.ALBUMS -> {
                    val topArtistsPool = fetchTopArtists(username, period = randomPeriod, limit = 30)
                    val sampledArtists = if (page == 1) {
                        topArtistsPool.shuffled().take(6)
                    } else {
                        topArtistsPool.drop((page - 1) * 4).take(4).ifEmpty { topArtistsPool.shuffled().take(4) }
                    }

                    val albumsRaw = mutableListOf<RecCandidate>() // reason, artist, album, art
                    for (artist in sampledArtists) {
                        val similar = fetchSimilarArtists(artist.name, limit = 5)
                        for (simArtist in similar.shuffled().take(2)) {
                            val albums = fetchArtistTopAlbums(simArtist.name, limit = 6)
                            for (album in albums.shuffled().take(2)) {
                                albumsRaw.add(RecCandidate("Similar to ${artist.name}", simArtist.name, album.name, album.image.extractLastFmImage()))
                            }
                        }
                    }

                    val distinct = albumsRaw.distinctBy { "${it.artist.lowercase()} - ${it.extra.lowercase()}" }.take(16)
                    coroutineScope {
                        distinct.map { cand ->
                            async {
                                val itunes = ItunesClient.searchAlbum(cand.extra, cand.artist)
                                RecommendedTrackItem(
                                    title = itunes?.collectionName ?: cand.extra,
                                    artist = itunes?.artistName ?: cand.artist,
                                    album = itunes?.collectionName ?: cand.extra,
                                    reason = cand.reason,
                                    category = RecCategory.ALBUMS,
                                    itunesMatch = itunes,
                                    lastFmArtworkUrl = cand.art
                                )
                            }
                        }.awaitAll()
                    }
                }

                RecCategory.TRACKS -> {
                    val userTopTracksPool = fetchUserTopTracks(username, period = randomPeriod, limit = 30)
                    val sampledTracks = if (page == 1) {
                        userTopTracksPool.shuffled().take(8)
                    } else {
                        userTopTracksPool.drop((page - 1) * 5).take(5).ifEmpty { userTopTracksPool.shuffled().take(5) }
                    }

                    val tracksRaw = mutableListOf<RecCandidate>() // reason, artist, song, art
                    for (track in sampledTracks) {
                        val artistName = track.artist?.name ?: continue
                        val similar = fetchSimilarTracks(artistName, track.name, limit = 6)
                        for (s in similar.shuffled().take(2)) {
                            val sArtist = s.artist?.name ?: continue
                            tracksRaw.add(RecCandidate("Similar to \"${track.name}\"", sArtist, s.name, s.image.extractLastFmImage()))
                        }
                    }

                    val distinct = tracksRaw.distinctBy { "${it.artist.lowercase()} - ${it.extra.lowercase()}" }.take(16)
                    coroutineScope {
                        distinct.map { cand ->
                            async {
                                val itunes = ItunesClient.searchSong(cand.extra, cand.artist)
                                RecommendedTrackItem(
                                    title = itunes?.trackName ?: cand.extra,
                                    artist = itunes?.artistName ?: cand.artist,
                                    album = itunes?.collectionName ?: "",
                                    reason = cand.reason,
                                    category = RecCategory.TRACKS,
                                    itunesMatch = itunes,
                                    lastFmArtworkUrl = cand.art
                                )
                            }
                        }.awaitAll()
                    }
                }
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

    private fun fetchArtistTopAlbums(artist: String, limit: Int = 5): List<AlbumEntry> {
        return try {
            val url = "https://ws.audioscrobbler.com/2.0/?method=artist.getTopAlbums&artist=${URLEncoder.encode(artist, "UTF-8")}&api_key=$API_KEY&format=json&limit=$limit"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                gson.fromJson(body, TopAlbumsResponse::class.java).topalbums?.album ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }
}
