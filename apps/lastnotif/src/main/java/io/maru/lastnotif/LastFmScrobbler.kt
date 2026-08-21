package io.maru.lastnotif

import android.util.Log
import com.google.gson.Gson
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

object LastFmScrobbler {
    private const val TAG = "LastFmScrobbler"
    const val API_KEY = "5b573acce360566bf0ca66ab4a020e77"
    const val SECRET = "e4c8eca5ba52e4f1fa25c5a95d48b486"
    private const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"

    private val client = OkHttpClient()
    private val gson = Gson()

    data class SessionInfo(val key: String, val username: String)

    fun getMobileSession(token: String): SessionInfo? {
        val params = mutableMapOf(
            "api_key" to API_KEY,
            "method" to "auth.getSession",
            "token" to token.trim()
        )
        val sig = generateSignature(params)
        params["api_sig"] = sig
        params["format"] = "json"

        return try {
            val urlBuilder = StringBuilder(BASE_URL).append("?")
            params.forEach { (k, v) -> urlBuilder.append("$k=$v&") }
            val request = Request.Builder().url(urlBuilder.toString()).build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                val root = gson.fromJson(body, Map::class.java)
                val session = root["session"] as? Map<*, *>
                val key = session?.get("key") as? String
                val name = session?.get("name") as? String
                if (!key.isNullOrBlank() && !name.isNullOrBlank()) SessionInfo(key.trim(), name.trim()) else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMobileSession error", e)
            null
        }
    }

    fun updateNowPlaying(artist: String, track: String, album: String?, sessionKey: String) {
        if (artist.isBlank() || track.isBlank() || sessionKey.isBlank()) return
        val params = mutableMapOf(
            "api_key" to API_KEY,
            "method" to "track.updateNowPlaying",
            "artist" to artist.trim(),
            "track" to track.trim(),
            "sk" to sessionKey.trim()
        )
        if (!album.isNullOrBlank()) {
            params["album"] = album.trim()
        }
        val sig = generateSignature(params)
        params["api_sig"] = sig
        params["format"] = "json"

        val formBody = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()

        val request = Request.Builder().url(BASE_URL).post(formBody).build()
        try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "updateNowPlaying status: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateNowPlaying error", e)
        }
    }

    fun scrobble(artist: String, track: String, album: String?, timestamp: Long, sessionKey: String) {
        if (artist.isBlank() || track.isBlank() || sessionKey.isBlank()) return
        val params = mutableMapOf(
            "api_key" to API_KEY,
            "method" to "track.scrobble",
            "artist" to artist.trim(),
            "track" to track.trim(),
            "timestamp" to timestamp.toString(),
            "sk" to sessionKey.trim()
        )
        if (!album.isNullOrBlank()) {
            params["album"] = album.trim()
        }
        val sig = generateSignature(params)
        params["api_sig"] = sig
        params["format"] = "json"

        val formBody = FormBody.Builder().apply {
            params.forEach { (k, v) -> add(k, v) }
        }.build()

        val request = Request.Builder().url(BASE_URL).post(formBody).build()
        try {
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "scrobble status: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "scrobble error", e)
        }
    }

    private fun generateSignature(params: Map<String, String>): String {
        val sortedKeys = params.keys.sorted()
        val signature = StringBuilder()
        for (key in sortedKeys) {
            signature.append(key).append(params[key])
        }
        signature.append(SECRET)
        return md5(signature.toString())
    }

    private fun md5(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(s.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
