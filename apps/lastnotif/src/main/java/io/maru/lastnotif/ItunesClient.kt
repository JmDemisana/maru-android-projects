package io.maru.lastnotif

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ItunesClient {
    private const val TAG = "ItunesClient"
    private const val MIN_REQUEST_INTERVAL_MS = 250L // Rate-limit safety: 4 reqs/sec max

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val memoryCache = ConcurrentHashMap<String, ItunesSongMatch>()
    private val rateLimitLock = Mutex()
    private var lastRequestTime = 0L

    data class ItunesSongMatch(
        val trackId: Long,
        val trackName: String,
        val artistName: String,
        val collectionName: String,
        val artworkUrl: String?,
        val appleMusicUrl: String?,
        val previewUrl: String?
    )

    private data class ItunesSearchResponse(
        @SerializedName("resultCount") val resultCount: Int,
        @SerializedName("results") val results: List<ItunesTrackEntry>?
    )

    private data class ItunesTrackEntry(
        @SerializedName("trackId") val trackId: Long?,
        @SerializedName("trackName") val trackName: String?,
        @SerializedName("artistName") val artistName: String?,
        @SerializedName("collectionName") val collectionName: String?,
        @SerializedName("artworkUrl100") val artworkUrl100: String?,
        @SerializedName("trackViewUrl") val trackViewUrl: String?,
        @SerializedName("previewUrl") val previewUrl: String?
    )

    private data class ItunesArtistEntry(
        @SerializedName("artistId") val artistId: Long?,
        @SerializedName("artistName") val artistName: String?,
        @SerializedName("primaryGenreName") val primaryGenreName: String?,
        @SerializedName("artistLinkUrl") val artistLinkUrl: String?
    )

    private data class ItunesArtistSearchResponse(
        @SerializedName("resultCount") val resultCount: Int,
        @SerializedName("results") val results: List<ItunesArtistEntry>?
    )

    suspend fun searchSong(
        trackName: String,
        artistName: String = "",
        country: String = "PH",
        lang: String = "en_us"
    ): ItunesSongMatch? = withContext(Dispatchers.IO) {
        val cacheKey = "song::${artistName.trim().lowercase()}::${trackName.trim().lowercase()}"
        memoryCache[cacheKey]?.let { return@withContext it }

        try {
            rateLimitLock.withLock {
                val now = System.currentTimeMillis()
                val elapsed = now - lastRequestTime
                if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                    delay(MIN_REQUEST_INTERVAL_MS - elapsed)
                }
                lastRequestTime = System.currentTimeMillis()
            }
            val query = if (artistName.isNotEmpty()) "$artistName $trackName" else trackName
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=1&country=$country&lang=$lang"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LastNotif/1.0 (Android)")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val searchRes = gson.fromJson(body, ItunesSearchResponse::class.java)
                val entry = searchRes.results?.firstOrNull() ?: return@withContext null

                val highResArt = entry.artworkUrl100?.let { art ->
                    art.replace("100x100bb.jpg", "600x600bb.jpg")
                        .replace("100x100bb.png", "600x600bb.png")
                }

                val match = ItunesSongMatch(
                    trackId = entry.trackId ?: 0L,
                    trackName = entry.trackName ?: trackName,
                    artistName = entry.artistName ?: artistName,
                    collectionName = entry.collectionName ?: "",
                    artworkUrl = highResArt,
                    appleMusicUrl = entry.trackViewUrl,
                    previewUrl = entry.previewUrl
                )
                memoryCache[cacheKey] = match
                match
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed searching iTunes for song: $artistName - $trackName", e)
            null
        }
    }

    suspend fun searchAlbum(
        albumName: String,
        artistName: String = "",
        country: String = "PH",
        lang: String = "en_us"
    ): ItunesSongMatch? = withContext(Dispatchers.IO) {
        val cacheKey = "album::${artistName.trim().lowercase()}::${albumName.trim().lowercase()}"
        memoryCache[cacheKey]?.let { return@withContext it }

        try {
            rateLimitLock.withLock {
                val now = System.currentTimeMillis()
                val elapsed = now - lastRequestTime
                if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                    delay(MIN_REQUEST_INTERVAL_MS - elapsed)
                }
                lastRequestTime = System.currentTimeMillis()
            }
            val query = if (artistName.isNotEmpty()) "$artistName $albumName" else albumName
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedQuery&entity=album&limit=1&country=$country&lang=$lang"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LastNotif/1.0 (Android)")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val searchRes = gson.fromJson(body, ItunesSearchResponse::class.java)
                val entry = searchRes.results?.firstOrNull() ?: return@withContext null

                val highResArt = entry.artworkUrl100?.let { art ->
                    art.replace("100x100bb.jpg", "600x600bb.jpg")
                        .replace("100x100bb.png", "600x600bb.png")
                }

                val match = ItunesSongMatch(
                    trackId = entry.trackId ?: 0L,
                    trackName = entry.collectionName ?: albumName,
                    artistName = entry.artistName ?: artistName,
                    collectionName = entry.collectionName ?: albumName,
                    artworkUrl = highResArt,
                    appleMusicUrl = entry.trackViewUrl,
                    previewUrl = null
                )
                memoryCache[cacheKey] = match
                match
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed searching iTunes for album: $artistName - $albumName", e)
            null
        }
    }

    suspend fun searchArtist(
        artistName: String,
        country: String = "PH",
        lang: String = "en_us"
    ): ItunesSongMatch? = withContext(Dispatchers.IO) {
        val cacheKey = "artist::${artistName.trim().lowercase()}"
        memoryCache[cacheKey]?.let { return@withContext it }

        try {
            // Step 1: Query top release/album to get accurate artist cover art
            val albumArtMatch = searchAlbum(artistName, "") ?: searchSong("", artistName)
            
            rateLimitLock.withLock {
                val now = System.currentTimeMillis()
                val elapsed = now - lastRequestTime
                if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                    delay(MIN_REQUEST_INTERVAL_MS - elapsed)
                }
                lastRequestTime = System.currentTimeMillis()
            }
            val encodedQuery = URLEncoder.encode(artistName, "UTF-8")
            val url = "https://itunes.apple.com/search?term=$encodedQuery&entity=musicArtist&limit=1&country=$country&lang=$lang"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LastNotif/1.0 (Android)")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext albumArtMatch
                val body = response.body?.string() ?: return@withContext albumArtMatch
                val artistRes = gson.fromJson(body, ItunesArtistSearchResponse::class.java)
                val entry = artistRes.results?.firstOrNull()

                val match = ItunesSongMatch(
                    trackId = entry?.artistId ?: (albumArtMatch?.trackId ?: 0L),
                    trackName = entry?.artistName ?: artistName,
                    artistName = entry?.artistName ?: artistName,
                    collectionName = entry?.primaryGenreName ?: (albumArtMatch?.collectionName ?: "Artist"),
                    artworkUrl = albumArtMatch?.artworkUrl,
                    appleMusicUrl = entry?.artistLinkUrl ?: albumArtMatch?.appleMusicUrl,
                    previewUrl = null
                )
                memoryCache[cacheKey] = match
                match
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed searching iTunes for artist: $artistName", e)
            null
        }
    }

    fun openInPreferredPlayer(
        context: Context,
        platform: String,
        trackName: String,
        artistName: String,
        appleMusicUrl: String?
    ) {
        try {
            val intent = when (platform) {
                "Spotify" -> {
                    val encoded = URLEncoder.encode("$artistName $trackName", "UTF-8")
                    Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded")).apply {
                        setPackage("com.spotify.music")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                "YouTube Music" -> {
                    val encoded = URLEncoder.encode("$artistName $trackName", "UTF-8")
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encoded")).apply {
                        setPackage("com.google.android.apps.youtube.music")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                "Tidal" -> {
                    val encoded = URLEncoder.encode("$artistName $trackName", "UTF-8")
                    Intent(Intent.ACTION_VIEW, Uri.parse("tidal://search?query=$encoded")).apply {
                        setPackage("com.aspiro.tidal")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                else -> { // Apple Music
                    if (!appleMusicUrl.isNullOrBlank()) {
                        Intent(Intent.ACTION_VIEW, Uri.parse(appleMusicUrl)).apply {
                            setPackage("com.apple.android.music")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    } else {
                        val encoded = URLEncoder.encode("$artistName $trackName", "UTF-8")
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://music.apple.com/search?term=$encoded")).apply {
                            setPackage("com.apple.android.music")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                }
            }

            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                val fallbackUri = when (platform) {
                    "Spotify" -> Uri.parse("https://open.spotify.com/search/${URLEncoder.encode("$artistName $trackName", "UTF-8")}")
                    "YouTube Music" -> Uri.parse("https://music.youtube.com/search?q=${URLEncoder.encode("$artistName $trackName", "UTF-8")}")
                    "Tidal" -> Uri.parse("https://listen.tidal.com/search?q=${URLEncoder.encode("$artistName $trackName", "UTF-8")}")
                    else -> Uri.parse(appleMusicUrl ?: "https://music.apple.com/search?term=${URLEncoder.encode("$artistName $trackName", "UTF-8")}")
                }
                context.startActivity(Intent(Intent.ACTION_VIEW, fallbackUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        } catch (err: Exception) {
            Log.e(TAG, "Failed launching $platform for $artistName - $trackName", err)
        }
    }
}
