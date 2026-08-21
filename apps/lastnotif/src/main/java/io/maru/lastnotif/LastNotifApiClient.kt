package io.maru.lastnotif

import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.regex.Pattern

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

    data class LyricLine(
        val timestampMs: Long,
        val text: String
    )

    data class LyricsResult(
        val lines: List<LyricLine>,
        val syncStartedAtMs: Long
    )

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

    fun getLyrics(username: String): LyricsResult? {
        return try {
            val url = "$BASE?route=lastfm/lyrics&username=${encode(username)}"
            val json = fetchJson(url) ?: return null
            val root = gson.fromJson(json, Map::class.java)
            val lyricsObj = root["lyrics"] as? Map<*, *> ?: return null
            val synced = lyricsObj["synced"] as? String ?: ""
            if (synced.isEmpty()) return null

            val syncStartedAtMs = (root["syncStartedAtMs"] as? Double)?.toLong() 
                ?: System.currentTimeMillis()

            val lines = parseLrc(synced)
            if (lines.isEmpty()) return null

            LyricsResult(lines, syncStartedAtMs)
        } catch (e: Exception) {
            Log.w(TAG, "getLyrics error: ${e.message}")
            null
        }
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        val p = Pattern.compile("\\[(\\d+):(\\d+\\.\\d+)\\](.*)")
        
        lrc.lines().forEach { line ->
            val m = p.matcher(line.trim())
            if (m.matches()) {
                try {
                    val minutes = m.group(1)?.toLong() ?: 0L
                    val seconds = m.group(2)?.toDouble() ?: 0.0
                    val tsMs = (minutes * 60_000L) + (seconds * 1000L).toLong()
                    val text = m.group(3)?.trim() ?: ""
                    result.add(LyricLine(tsMs, text))
                } catch (ignored: Exception) {}
            }
        }
        return result
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
