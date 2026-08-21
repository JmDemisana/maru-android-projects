package io.maru.lastnotif

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

object LastNotifApiClient {
    private const val TAG = "LastNotifApiClient"
    private const val BASE = "https://maruchansquigle.vercel.app/api/auth"
    private val client = OkHttpClient()
    private val gson = Gson()

    data class NowPlayingResult(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val isPlaying: Boolean = false
    ) {
        fun trackKey() = "$artist - $title"
    }

    fun getNowPlaying(username: String): NowPlayingResult? {
        return try {
            val url = "$BASE?route=lastfm/now-playing&username=${encode(username)}&fast=1"
            val json = fetchJson(url) ?: return null
            val root = gson.fromJson(json, Map::class.java)
            val track = root["track"] as? Map<*, *> ?: return null

            NowPlayingResult(
                title = track["title"] as? String ?: "",
                artist = track["artist"] as? String ?: "",
                album = track["album"] as? String ?: "",
                isPlaying = track["nowPlaying"] as? Boolean ?: false
            )
        } catch (e: Exception) {
            Log.w(TAG, "getNowPlaying error: ${e.message}")
            null
        }
    }

    private const val LASTFM_BASE = "https://ws.audioscrobbler.com/2.0/"
    private const val API_KEY = LastFmScrobbler.API_KEY
    private const val NAMIREC_URL = "https://maruchansquigle.vercel.app/api/namitalk-chat"

    // --- User Info & Profile ---
    data class UserProfile(
        val username: String = "",
        val realName: String = "",
        val playcount: Long = 0L,
        val registeredTime: Long = 0L,
        val avatarUrl: String? = null,
        val url: String = ""
    )

    data class ScrobbleItem(
        val title: String,
        val artist: String,
        val album: String,
        val timestamp: Long?,
        val isNowPlaying: Boolean,
        val artworkUrl: String? = null
    )

    data class TopItem(
        val rank: Int,
        val name: String,
        val subtext: String, // e.g. Artist name or play count
        val playcount: Long = 0L,
        val artworkUrl: String? = null
    )

    // --- NamiRec Data Structures ---
    data class NamiRecTrackInput(
        val typed: String,
        val trackName: String?,
        val artistName: String?,
        val collectionName: String?,
        val primaryGenreName: String? = null,
        val releaseDate: String? = null
    )

    data class NamiMonthlyReview(
        val title: String = "",
        val summary: String = "",
        val vibeTags: List<String> = emptyList(),
        val similarGroup: NamiGroupReview? = null,
        val outlierGroup: NamiGroupReview? = null
    )

    data class NamiGroupReview(
        val title: String = "",
        val comment: String = "",
        val songIndexes: List<Int> = emptyList()
    )

    fun cleanLastFmArtwork(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.contains("2a96cbd8b46e442fc41c2b86b821562f", ignoreCase = true)) return null
        if (url.contains("default_album", ignoreCase = true)) return null
        if (url.contains("noimage", ignoreCase = true)) return null
        return url
    }

    fun getUserInfo(username: String): UserProfile? {
        if (username.isBlank()) return null
        return try {
            val url = "$LASTFM_BASE?method=user.getinfo&user=${encode(username)}&api_key=$API_KEY&format=json"
            val json = fetchJson(url) ?: return null
            val root = gson.fromJson(json, Map::class.java)
            val userMap = root["user"] as? Map<*, *> ?: return null

            val name = userMap["name"] as? String ?: username
            val realName = userMap["realname"] as? String ?: ""
            val playcount = (userMap["playcount"] as? String)?.toLongOrNull() ?: 0L
            val regObj = userMap["registered"] as? Map<*, *>
            val regTime = (regObj?.get("unixtime") as? String)?.toLongOrNull() ?: 0L
            val images = userMap["image"] as? List<Map<*, *>>
            val avatar = images?.lastOrNull()?.get("#text") as? String
            val urlStr = userMap["url"] as? String ?: ""

            UserProfile(name, realName, playcount, regTime, cleanLastFmArtwork(avatar), urlStr)
        } catch (e: Exception) {
            Log.w(TAG, "getUserInfo error", e)
            null
        }
    }

    fun getRecentTracks(username: String, limit: Int = 20): List<ScrobbleItem> {
        if (username.isBlank()) return emptyList()
        return try {
            val url = "$LASTFM_BASE?method=user.getrecenttracks&user=${encode(username)}&api_key=$API_KEY&format=json&limit=$limit"
            val json = fetchJson(url) ?: return emptyList()
            val root = gson.fromJson(json, Map::class.java)
            val recentObj = root["recenttracks"] as? Map<*, *> ?: return emptyList()
            val trackRaw = recentObj["track"]
            val tracksList = when (trackRaw) {
                is List<*> -> trackRaw.filterIsInstance<Map<*, *>>()
                is Map<*, *> -> listOf(trackRaw)
                else -> emptyList()
            }

            tracksList.mapNotNull { t ->
                val name = t["name"] as? String ?: return@mapNotNull null
                val artistObj = t["artist"] as? Map<*, *>
                val artistName = artistObj?.get("#text") as? String ?: ""
                val albumObj = t["album"] as? Map<*, *>
                val albumName = albumObj?.get("#text") as? String ?: ""
                val attrObj = t["@attr"] as? Map<*, *>
                val isNowPlaying = attrObj?.get("nowplaying") == "true"
                val dateObj = t["date"] as? Map<*, *>
                val uts = (dateObj?.get("uts") as? String)?.toLongOrNull()
                val images = t["image"] as? List<Map<*, *>>
                val artwork = images?.lastOrNull()?.get("#text") as? String

                ScrobbleItem(name, artistName, albumName, uts, isNowPlaying, cleanLastFmArtwork(artwork))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRecentTracks error", e)
            emptyList()
        }
    }

    fun getTopTracks(username: String, period: String = "1month", limit: Int = 10): List<TopItem> {
        if (username.isBlank()) return emptyList()
        return try {
            val url = "$LASTFM_BASE?method=user.gettoptracks&user=${encode(username)}&period=$period&limit=$limit&api_key=$API_KEY&format=json"
            val json = fetchJson(url) ?: return emptyList()
            val root = gson.fromJson(json, Map::class.java)
            val topObj = root["toptracks"] as? Map<*, *> ?: return emptyList()
            val trackList = (topObj["track"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

            trackList.mapIndexed { idx, t ->
                val name = t["name"] as? String ?: ""
                val artistObj = t["artist"] as? Map<*, *>
                val artistName = artistObj?.get("name") as? String ?: ""
                val playcount = (t["playcount"] as? String)?.toLongOrNull() ?: 0L
                val images = t["image"] as? List<Map<*, *>>
                val artwork = images?.lastOrNull()?.get("#text") as? String
                val attrObj = t["@attr"] as? Map<*, *>
                val rank = (attrObj?.get("rank") as? String)?.toIntOrNull() ?: (idx + 1)

                TopItem(rank, name, artistName, playcount, cleanLastFmArtwork(artwork))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getTopTracks error", e)
            emptyList()
        }
    }

    fun getTopArtists(username: String, period: String = "1month", limit: Int = 10): List<TopItem> {
        if (username.isBlank()) return emptyList()
        return try {
            val url = "$LASTFM_BASE?method=user.gettopartists&user=${encode(username)}&period=$period&limit=$limit&api_key=$API_KEY&format=json"
            val json = fetchJson(url) ?: return emptyList()
            val root = gson.fromJson(json, Map::class.java)
            val topObj = root["topartists"] as? Map<*, *> ?: return emptyList()
            val list = (topObj["artist"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

            list.mapIndexed { idx, a ->
                val name = a["name"] as? String ?: ""
                val playcount = (a["playcount"] as? String)?.toLongOrNull() ?: 0L
                val attrObj = a["@attr"] as? Map<*, *>
                val rank = (attrObj?.get("rank") as? String)?.toIntOrNull() ?: (idx + 1)
                TopItem(rank, name, "$playcount scrobbles", playcount, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "getTopArtists error", e)
            emptyList()
        }
    }

    fun getTopAlbums(username: String, period: String = "1month", limit: Int = 10): List<TopItem> {
        if (username.isBlank()) return emptyList()
        return try {
            val url = "$LASTFM_BASE?method=user.gettopalbums&user=${encode(username)}&period=$period&limit=$limit&api_key=$API_KEY&format=json"
            val json = fetchJson(url) ?: return emptyList()
            val root = gson.fromJson(json, Map::class.java)
            val topObj = root["topalbums"] as? Map<*, *> ?: return emptyList()
            val list = (topObj["album"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

            list.mapIndexed { idx, alb ->
                val name = alb["name"] as? String ?: ""
                val artistObj = alb["artist"] as? Map<*, *>
                val artistName = artistObj?.get("name") as? String ?: ""
                val playcount = (alb["playcount"] as? String)?.toLongOrNull() ?: 0L
                val images = alb["image"] as? List<Map<*, *>>
                val artwork = images?.lastOrNull()?.get("#text") as? String
                val attrObj = alb["@attr"] as? Map<*, *>
                val rank = (attrObj?.get("rank") as? String)?.toIntOrNull() ?: (idx + 1)
                TopItem(rank, name, artistName, playcount, cleanLastFmArtwork(artwork))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getTopAlbums error", e)
            emptyList()
        }
    }

    fun getSimilarTracks(artist: String, track: String, limit: Int = 10): List<Pair<String, String>> {
        if (artist.isBlank() || track.isBlank()) return emptyList()
        return try {
            val url = "$LASTFM_BASE?method=track.getsimilar&artist=${encode(artist)}&track=${encode(track)}&limit=$limit&api_key=$API_KEY&format=json"
            val json = fetchJson(url) ?: return emptyList()
            val root = gson.fromJson(json, Map::class.java)
            val simObj = root["similartracks"] as? Map<*, *> ?: return emptyList()
            val list = (simObj["track"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

            list.mapNotNull { t ->
                val name = t["name"] as? String ?: return@mapNotNull null
                val artistObj = t["artist"] as? Map<*, *>
                val artistName = artistObj?.get("name") as? String ?: ""
                name to artistName
            }
        } catch (e: Exception) {
            Log.w(TAG, "getSimilarTracks error", e)
            emptyList()
        }
    }

    fun searchTracks(query: String, limit: Int = 20): List<Pair<String, String>> {
        if (query.isBlank()) return emptyList()
        return try {
            val url = "$LASTFM_BASE?method=track.search&track=${encode(query)}&limit=$limit&api_key=$API_KEY&format=json"
            val json = fetchJson(url) ?: return emptyList()
            val root = gson.fromJson(json, Map::class.java)
            val resultsObj = root["results"] as? Map<*, *> ?: return emptyList()
            val trackmatches = resultsObj["trackmatches"] as? Map<*, *> ?: return emptyList()
            val list = (trackmatches["track"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: emptyList()

            list.mapNotNull { t ->
                val name = t["name"] as? String ?: return@mapNotNull null
                val artistName = t["artist"] as? String ?: ""
                name to artistName
            }
        } catch (e: Exception) {
            Log.w(TAG, "searchTracks error", e)
            emptyList()
        }
    }

    // --- Generate NamiRec Monthly Recap ---
    fun generateMonthlyRecap(
        displayName: String,
        monthLabel: String,
        tracks: List<NamiRecTrackInput>,
        language: String = "en"
    ): NamiMonthlyReview? {
        return try {
            val bodyMap = mapOf(
                "mode" to "monthly-songs",
                "language" to language,
                "displayName" to displayName,
                "monthLabel" to monthLabel,
                "message" to "Create my $monthLabel songs recap.",
                "monthlySongs" to tracks.take(10)
            )

            val jsonBody = gson.toJson(bodyMap)
            val request = Request.Builder()
                .url(NAMIREC_URL)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "NamiRec returned HTTP ${response.code}")
                    return generateFallbackReview(displayName, monthLabel, tracks)
                }
                val respBody = response.body?.string() ?: return generateFallbackReview(displayName, monthLabel, tracks)
                val parsed = gson.fromJson(respBody, Map::class.java)
                val reviewMap = parsed["review"] as? Map<*, *> ?: return generateFallbackReview(displayName, monthLabel, tracks)

                val title = reviewMap["title"] as? String ?: "$monthLabel Recap"
                val summary = reviewMap["summary"] as? String ?: ""
                val vibeTags = (reviewMap["vibeTags"] as? List<*>)?.filterIsInstance<String>() ?: listOf("Music", "Vocaloid", "Energetic")

                val simGroupObj = reviewMap["similarGroup"] as? Map<*, *>
                val similarGroup = simGroupObj?.let {
                    NamiGroupReview(
                        title = it["title"] as? String ?: "Main Flow",
                        comment = it["comment"] as? String ?: "",
                        songIndexes = (it["songIndexes"] as? List<*>)?.mapNotNull { n -> (n as? Number)?.toInt() } ?: listOf(0, 1, 2, 3, 4)
                    )
                }

                val outGroupObj = reviewMap["outlierGroup"] as? Map<*, *>
                val outlierGroup = outGroupObj?.let {
                    NamiGroupReview(
                        title = it["title"] as? String ?: "Surprise Outliers",
                        comment = it["comment"] as? String ?: "",
                        songIndexes = (it["songIndexes"] as? List<*>)?.mapNotNull { n -> (n as? Number)?.toInt() } ?: listOf(5, 6, 7, 8, 9)
                    )
                }

                NamiMonthlyReview(title, summary, vibeTags, similarGroup, outlierGroup)
            }
        } catch (e: Exception) {
            Log.w(TAG, "generateMonthlyRecap error, using fallback", e)
            generateFallbackReview(displayName, monthLabel, tracks)
        }
    }

    private fun generateFallbackReview(
        displayName: String,
        monthLabel: String,
        tracks: List<NamiRecTrackInput>
    ): NamiMonthlyReview {
        val name = displayName.ifEmpty { "Senpai" }
        val topArtist = tracks.firstOrNull()?.artistName ?: "various artists"
        val topTrack = tracks.firstOrNull()?.trackName ?: "favorite tracks"

        return NamiMonthlyReview(
            title = "$monthLabel Sonic Voyage",
            summary = "Ehh, $name's listening taste for $monthLabel is filled with energy! You spent so much time on $topTrack by $topArtist. Not bad at all... I guess I kinda like your taste! (⁄ ⁄>⁄ ▽ ⁄<⁄ ⁄)",
            vibeTags = listOf("High-Energy", "Melodic", "Signature Taste", "Vocaloid", "Repeat Mode"),
            similarGroup = NamiGroupReview(
                title = "The Power Rotation",
                comment = "These tracks set the rhythm for your month, keeping your energy peak from start to finish!",
                songIndexes = listOf(0, 1, 2, 3, 4).filter { it < tracks.size }
            ),
            outlierGroup = NamiGroupReview(
                title = "Atmospheric Sidequests",
                comment = "A wonderful detour from your usual heavy rotation. Surprising and refreshing selections!",
                songIndexes = listOf(5, 6, 7, 8, 9).filter { it < tracks.size }
            )
        )
    }

    private fun fetchJson(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "HTTP ${response.code} from $url")
                return null
            }
            return response.body?.string()
        }
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
