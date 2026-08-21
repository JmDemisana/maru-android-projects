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
        val itunesMatch: ItunesClient.ItunesSongMatch? = null
    )

    // DTOs for Last.fm API
    private data class TopArtistsResponse(@SerializedName("topartists") val topartists: TopArtistsBody?)
    private data class TopArtistsBody(@SerializedName("artist") val artist: List<ArtistEntry>?)
    private data class ArtistEntry(
        @SerializedName("name") val name: String,
        @SerializedName("playcount") val playcount: String? = null
    )

    private data class SimilarArtistsResponse(@SerializedName("similarartists") val similarartists: SimilarArtistsBody?)
    private data class SimilarArtistsBody(@SerializedName("artist") val artist: List<ArtistEntry>?)

    private data class ArtistTopTracksResponse(@SerializedName("toptracks") val toptracks: TopTracksBody?)
    private data class TopTracksBody(@SerializedName("track") val track: List<TrackEntry>?)
    private data class TrackEntry(
        @SerializedName("name") val name: String,
        @SerializedName("artist") val artist: ArtistEntry?
    )

    private data class UserTopTracksResponse(@SerializedName("toptracks") val toptracks: UserTopTracksBody?)
    private data class UserTopTracksBody(@SerializedName("track") val track: List<TrackEntry>?)

    private data class SimilarTracksResponse(@SerializedName("similartracks") val similartracks: SimilarTracksBody?)
    private data class SimilarTracksBody(@SerializedName("track") val track: List<TrackEntry>?)

    private data class TopAlbumsResponse(@SerializedName("topalbums") val topalbums: TopAlbumsBody?)
    private data class TopAlbumsBody(@SerializedName("album") val album: List<AlbumEntry>?)
    private data class AlbumEntry(
        @SerializedName("name") val name: String,
        @SerializedName("artist") val artist: ArtistEntry?
    )

    suspend fun getRecommendations(
        username: String,
        category: RecCategory = RecCategory.ALL,
        page: Int = 1
    ): List<RecommendedTrackItem> = withContext(Dispatchers.IO) {
        if (username.isBlank()) return@withContext emptyList()
        try {
            when (category) {
                RecCategory.ALL -> {
                    // Mix of Tracks, Artists, and Albums
                    val topArtists = fetchTopArtists(username, page = page, limit = 3)
                    val userTopTracks = fetchUserTopTracks(username, page = page, limit = 3)

                    val tracksRaw = mutableListOf<Triple<String, String, String>>() // reason, artist, song
                    for (track in userTopTracks) {
                        val artistName = track.artist?.name ?: continue
                        val similar = fetchSimilarTracks(artistName, track.name, limit = 2)
                        for (s in similar) {
                            val sArtist = s.artist?.name ?: continue
                            tracksRaw.add(Triple("Similar to \"${track.name}\"", sArtist, s.name))
                        }
                    }

                    val artistsRaw = mutableListOf<Triple<String, String, String>>() // reason, artist, ""
                    for (artist in topArtists) {
                        val similar = fetchSimilarArtists(artist.name, limit = 2)
                        for (simArtist in similar) {
                            artistsRaw.add(Triple("Similar to ${artist.name}", simArtist.name, ""))
                        }
                    }

                    val albumsRaw = mutableListOf<Triple<String, String, String>>() // reason, artist, album
                    for (artist in topArtists) {
                        val albums = fetchArtistTopAlbums(artist.name, limit = 2)
                        for (album in albums) {
                            albumsRaw.add(Triple("Album by ${artist.name}", artist.name, album.name))
                        }
                    }

                    coroutineScope {
                        val enrichedTracks = tracksRaw.distinctBy { "${it.second.lowercase()} - ${it.third.lowercase()}" }.take(6).map { (reason, artist, song) ->
                            async {
                                val itunes = ItunesClient.searchSong(song, artist)
                                RecommendedTrackItem(
                                    title = itunes?.trackName ?: song,
                                    artist = itunes?.artistName ?: artist,
                                    album = itunes?.collectionName ?: "",
                                    reason = reason,
                                    category = RecCategory.TRACKS,
                                    itunesMatch = itunes
                                )
                            }
                        }

                        val enrichedArtists = artistsRaw.distinctBy { it.second.lowercase() }.take(4).map { (reason, artist, _) ->
                            async {
                                val itunes = ItunesClient.searchArtist(artist)
                                RecommendedTrackItem(
                                    title = itunes?.artistName ?: artist,
                                    artist = itunes?.collectionName ?: "Artist",
                                    album = "",
                                    reason = reason,
                                    category = RecCategory.ARTISTS,
                                    itunesMatch = itunes
                                )
                            }
                        }

                        val enrichedAlbums = albumsRaw.distinctBy { "${it.second.lowercase()} - ${it.third.lowercase()}" }.take(4).map { (reason, artist, album) ->
                            async {
                                val itunes = ItunesClient.searchAlbum(album, artist)
                                RecommendedTrackItem(
                                    title = itunes?.collectionName ?: album,
                                    artist = itunes?.artistName ?: artist,
                                    album = itunes?.collectionName ?: album,
                                    reason = reason,
                                    category = RecCategory.ALBUMS,
                                    itunesMatch = itunes
                                )
                            }
                        }

                        val tList = enrichedTracks.awaitAll()
                        val aList = enrichedArtists.awaitAll()
                        val albList = enrichedAlbums.awaitAll()

                        // Interleave into a mixed feed
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
                    val topArtists = fetchTopArtists(username, page = page, limit = 6)
                    val artistsRaw = mutableListOf<Pair<String, String>>() // reason, artist
                    for (artist in topArtists) {
                        val similar = fetchSimilarArtists(artist.name, limit = 3)
                        for (simArtist in similar) {
                            artistsRaw.add("Similar to ${artist.name}" to simArtist.name)
                        }
                    }

                    val distinct = artistsRaw.distinctBy { it.second.lowercase() }.take(16)
                    coroutineScope {
                        distinct.map { (reason, artist) ->
                            async {
                                val itunes = ItunesClient.searchArtist(artist)
                                RecommendedTrackItem(
                                    title = itunes?.artistName ?: artist,
                                    artist = itunes?.collectionName ?: "Artist",
                                    album = "",
                                    reason = reason,
                                    category = RecCategory.ARTISTS,
                                    itunesMatch = itunes
                                )
                            }
                        }.awaitAll()
                    }
                }

                RecCategory.ALBUMS -> {
                    val topArtists = fetchTopArtists(username, page = page, limit = 5)
                    val albumsRaw = mutableListOf<Triple<String, String, String>>() // reason, artist, album
                    for (artist in topArtists) {
                        val similar = fetchSimilarArtists(artist.name, limit = 2)
                        for (simArtist in similar) {
                            val albums = fetchArtistTopAlbums(simArtist.name, limit = 3)
                            for (album in albums) {
                                albumsRaw.add(Triple("Similar to ${artist.name}", simArtist.name, album.name))
                            }
                        }
                    }

                    val distinct = albumsRaw.distinctBy { "${it.second.lowercase()} - ${it.third.lowercase()}" }.take(16)
                    coroutineScope {
                        distinct.map { (reason, artist, album) ->
                            async {
                                val itunes = ItunesClient.searchAlbum(album, artist)
                                RecommendedTrackItem(
                                    title = itunes?.collectionName ?: album,
                                    artist = itunes?.artistName ?: artist,
                                    album = itunes?.collectionName ?: album,
                                    reason = reason,
                                    category = RecCategory.ALBUMS,
                                    itunesMatch = itunes
                                )
                            }
                        }.awaitAll()
                    }
                }

                RecCategory.TRACKS -> {
                    val userTopTracks = fetchUserTopTracks(username, page = page, limit = 8)
                    val tracksRaw = mutableListOf<Triple<String, String, String>>() // reason, artist, song
                    for (track in userTopTracks) {
                        val artistName = track.artist?.name ?: continue
                        val similar = fetchSimilarTracks(artistName, track.name, limit = 3)
                        for (s in similar) {
                            val sArtist = s.artist?.name ?: continue
                            tracksRaw.add(Triple("Similar to \"${track.name}\"", sArtist, s.name))
                        }
                    }

                    val distinct = tracksRaw.distinctBy { "${it.second.lowercase()} - ${it.third.lowercase()}" }.take(16)
                    coroutineScope {
                        distinct.map { (reason, artist, song) ->
                            async {
                                val itunes = ItunesClient.searchSong(song, artist)
                                RecommendedTrackItem(
                                    title = itunes?.trackName ?: song,
                                    artist = itunes?.artistName ?: artist,
                                    album = itunes?.collectionName ?: "",
                                    reason = reason,
                                    category = RecCategory.TRACKS,
                                    itunesMatch = itunes
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

    private fun fetchTopArtists(user: String, page: Int = 1, limit: Int = 5): List<ArtistEntry> {
        return try {
            val url = "https://ws.audioscrobbler.com/2.0/?method=user.getTopArtists&user=${URLEncoder.encode(user, "UTF-8")}&api_key=$API_KEY&format=json&page=$page&limit=$limit&period=1month"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                gson.fromJson(body, TopArtistsResponse::class.java).topartists?.artist ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchUserTopTracks(user: String, page: Int = 1, limit: Int = 5): List<TrackEntry> {
        return try {
            val url = "https://ws.audioscrobbler.com/2.0/?method=user.getTopTracks&user=${URLEncoder.encode(user, "UTF-8")}&api_key=$API_KEY&format=json&page=$page&limit=$limit&period=1month"
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                gson.fromJson(body, UserTopTracksResponse::class.java).toptracks?.track ?: emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun fetchSimilarTracks(artist: String, track: String, limit: Int = 3): List<TrackEntry> {
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

    private fun fetchSimilarArtists(artist: String, limit: Int = 2): List<ArtistEntry> {
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

    private fun fetchArtistTopTracks(artist: String, limit: Int = 2): List<TrackEntry> {
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

    private fun fetchArtistTopAlbums(artist: String, limit: Int = 2): List<AlbumEntry> {
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
