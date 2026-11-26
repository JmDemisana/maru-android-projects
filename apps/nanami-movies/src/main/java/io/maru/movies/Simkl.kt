package io.maru.movies

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class SimklSignIn internal constructor(
    val userCode: String,
    val verificationUrl: String,
    internal val clientId: String,
    internal val deviceCode: String,
    internal val deadline: Long,
    internal val intervalSeconds: Long,
    internal val label: String,
)

data class SimklWatchlistEntry(
    val imdbId: String,
    val isSeries: Boolean,
    val simklUrl: String?,
)

internal data class SimklAccount(val id: String, val label: String)
internal class SimklTitleUnavailable : IOException("SIMKL couldn't match this title or episode.")

class Simkl(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simkl", Context.MODE_PRIVATE)
    private val vault = SimklTokenVault()
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private var activeSignIn: SimklSignIn? = null

    val clientId: String
        get() = prefs.getString("client_id", null) ?: BuildConfig.SIMKL_CLIENT_ID.trim()

    val isConnected: Boolean
        get() = storedToken() != null

    internal val connectionKey: String?
        get() = prefs.getString("connection_key", null)

    internal fun accounts(): List<SimklAccount> {
        val ids = prefs.getStringSet("accounts", emptySet()).orEmpty() + listOfNotNull(connectionKey)
        return ids.mapNotNull { id ->
            if (id != connectionKey && !prefs.contains("token/$id")) return@mapNotNull null
            SimklAccount(id, prefs.getString("label/$id", null) ?: "SIMKL account")
        }.sortedBy { it.label.lowercase() }
    }

    internal suspend fun selectAccount(id: String?) = mutex.withLock {
        if (id == connectionKey) return@withLock
        val token = id?.let { requireNotNull(prefs.getString("token/$it", null)) { "This account needs to sign in again." } }
        val edit = prefs.edit()
        saveCurrent(edit)
        edit.putString("token", token).putString("connection_key", id)
            .putString("library", id?.let { prefs.getString("library/$it", null) })
        if (id != null) edit.putString("client_id", prefs.getString("client/$id", null))
        edit.apply()
    }

    suspend fun beginSignIn(client: String = clientId, label: String = ""): SimklSignIn = mutex.withLock {
        val currentClient = client.trim()
        require(currentClient.isNotBlank()) { "Enter a Simkl AUTH V2 client ID first." }
        val payload = oauth("device", currentClient, "scope" to "media:read media:write").requireSuccess()
        val verificationUrl = payload.text("verification_uri_complete") ?: payload.getString("verification_uri")
        val url = verificationUrl.toHttpUrl()
        check(url.isHttps && url.host in setOf("simkl.com", "www.simkl.com")) {
            "Simkl returned an invalid sign-in address."
        }
        SimklSignIn(
            userCode = payload.getString("user_code"),
            verificationUrl = verificationUrl,
            clientId = currentClient,
            deviceCode = payload.getString("device_code"),
            deadline = SystemClock.elapsedRealtime() + payload.getLong("expires_in") * 1_000L,
            intervalSeconds = payload.optLong("interval", 5L).coerceAtLeast(5),
            label = label.trim().take(40).ifBlank { "SIMKL account ${accounts().size + 1}" },
        ).also { activeSignIn = it }
    }

    suspend fun awaitSignIn(signIn: SimklSignIn) {
        var pollInterval = signIn.intervalSeconds
        while (SystemClock.elapsedRealtime() < signIn.deadline) {
            delay(minOf(pollInterval * 1_000L, (signIn.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)))
            if (SystemClock.elapsedRealtime() >= signIn.deadline) break
            val result = mutex.withLock {
                check(activeSignIn === signIn) { "This Simkl sign-in was cancelled." }
                val response = oauth(
                    "token", signIn.clientId,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to signIn.deviceCode,
                )
                if (response.isSuccessful) {
                    val token = parseToken(response.requireSuccess())
                    require("media:write" in token.scope.split(' ')) { "Simkl did not grant permission to sync your list." }
                    val encrypted = withContext(Dispatchers.IO) { vault.encrypt(token.toJson().toString()) }
                    val id = UUID.randomUUID().toString()
                    val edit = prefs.edit()
                    saveCurrent(edit, id)
                    edit.remove("library").putString("token", encrypted).putString("label/$id", signIn.label)
                        .putString("client_id", signIn.clientId).putString("connection_key", id).apply()
                    activeSignIn = null
                }
                response
            }
            if (result.isSuccessful) return
            when (result.error) {
                "authorization_pending" -> Unit
                "slow_down" -> pollInterval += 5L
                "expired_token" -> break
                else -> result.requireSuccess()
            }
        }
        throw IOException("Simkl sign-in expired. Start again for a new code.")
    }

    suspend fun disconnect() = mutex.withLock { disconnectLocked() }

    suspend fun watchlist(): List<SimklWatchlistEntry> = mutex.withLock {
        syncLibrary().filter { it.status == "plantowatch" }.map { it.media }
    }

    suspend fun setWatchlisted(imdbId: String, isSeries: Boolean, listed: Boolean, account: String? = null): Boolean = mutex.withLock {
        check(account == null || account == connectionKey) { "SIMKL account changed." }
        val item = imdbItem(imdbId)
        val type = if (isSeries) "shows" else "movies"
        val entry = syncLibrary().firstOrNull { it.media.imdbId == imdbId && it.media.isSeries == isSeries }
        if (listed) {
            // An existing Watchlist state belongs to the user. Adding again could move watched titles back to planned.
            if (entry != null) return@withLock entry.status == "plantowatch"
            item.put("to", "plantowatch")
            val result = authorized("sync/add-to-list", JSONObject().put(type, JSONArray().put(item)))
            checkFound(result)
            val added = result.optJSONObject("added")?.optJSONArray(type)?.optJSONObject(0)
                ?: throw IOException("Simkl did not confirm the watchlist update.")
            val status = added.text("to") ?: "plantowatch"
            putCachedEntry(type, imdbId, libraryEntry(imdbId, isSeries,
                added.optJSONObject("ids")?.text("simkl"), status, false))
            status == "plantowatch"
        } else {
            // Simkl's removal endpoint deletes the whole entry, including any watch history it holds.
            if (entry?.status != "plantowatch") return@withLock false
            if (entry.hasPreservedActivity) return@withLock true
            checkFound(authorized("sync/history/remove", JSONObject().put(type, JSONArray().put(item))))
            putCachedEntry(type, imdbId, null)
            false
        }
    }

    suspend fun markWatched(
        imdbId: String, season: Int? = null, episode: Int? = null, watched: Boolean = true, account: String? = null,
    ) = mutex.withLock {
        check(account == null || account == connectionKey) { "SIMKL account changed." }
        require((season == null && episode == null) || (season != null && season >= 0 && episode != null && episode > 0))
        val item = imdbItem(imdbId)
        if (season != null) {
            val episodes = JSONArray().put(JSONObject().put("number", episode))
            item.put("seasons", JSONArray().put(JSONObject().put("number", season).put("episodes", episodes)))
        }
        val type = if (season == null) "movies" else "shows"
        val path = if (watched) "sync/history" else "sync/history/remove"
        checkFound(authorized(path, JSONObject().put(type, JSONArray().put(item))))
    }

    private suspend fun disconnectLocked() {
        val previous = storedToken()
        activeSignIn = null
        forgetCurrent()
        if (previous != null) {
            try {
                oauth("revoke", clientId, "token" to previous.refresh, "token_type_hint" to "refresh_token")
            } catch (_: IOException) {
                // Local sign-out still works offline.
            }
        }
    }

    private fun saveCurrent(edit: SharedPreferences.Editor, newId: String? = null) {
        val ids = prefs.getStringSet("accounts", emptySet()).orEmpty().toMutableSet()
        connectionKey?.let { id ->
            ids.add(id)
            edit.putString("token/$id", prefs.getString("token", null)).putString("client/$id", clientId)
                .putString("library/$id", prefs.getString("library", null))
        }
        if (newId != null) ids.add(newId)
        edit.putStringSet("accounts", ids)
    }

    private fun forgetCurrent() {
        val edit = prefs.edit().remove("token").remove("library").remove("connection_key")
        connectionKey?.let { id ->
            edit.remove("token/$id").remove("client/$id").remove("library/$id").remove("label/$id")
                .putStringSet("accounts", prefs.getStringSet("accounts", emptySet()).orEmpty() - id)
        }
        edit.apply()
    }

    private suspend fun syncLibrary(): List<LibraryEntry> {
        val previous = prefs.getString("library", null)?.let(::JSONObject)
        val previousActivities = previous?.optJSONObject("activities")
        val current = authorized("sync/activities")
        val since = previousActivities?.text("all")
        val merged = mutableListOf<LibraryEntry>()
        for ((type, activityType) in listOf("movies" to "movies", "shows" to "tv_shows")) {
            val isSeries = type == "shows"
            val known = previous?.optJSONArray(type).objects().mapNotNull { parseCachedEntry(it, isSeries) }
            val currentGroup = current.optJSONObject(activityType)
            val lastGroup = previousActivities?.optJSONObject(activityType)
            val membershipFields = listOf("plantowatch", "watching", "hold", "completed", "dropped")
            val removed = currentGroup?.text("removed_from_list") != lastGroup?.text("removed_from_list")
            val changed = membershipFields.any { currentGroup?.text(it) != lastGroup?.text(it) }
            val path = "sync/all-items/$type"
            when {
                since == null -> merged += readLibrary(type, authorized(path))
                removed || changed -> {
                    val byId = known.associateByTo(linkedMapOf()) { it.media.imdbId }
                    val delta = authorized(path, query = mapOf("date_from" to since))
                    readLibrary(type, delta).forEach { byId[it.media.imdbId] = it }
                    if (removed) {
                        val existing = authorized(path, query = mapOf("extended" to "simkl_ids_only"))
                            .optJSONArray(type).objects().mapNotNull { row ->
                                mediaObject(row, type)?.optJSONObject("ids")?.text("simkl")
                            }.toSet()
                        byId.values.removeAll { it.simklId != null && it.simklId !in existing }
                    }
                    merged += byId.values
                }
                else -> merged += known
            }
        }
        val snapshot = JSONObject().put("activities", current)
            .put("movies", JSONArray(merged.filterNot { it.media.isSeries }.map(LibraryEntry::toJson)))
            .put("shows", JSONArray(merged.filter { it.media.isSeries }.map(LibraryEntry::toJson)))
        prefs.edit().putString("library", snapshot.toString()).apply()
        return merged
    }

    private fun putCachedEntry(type: String, imdbId: String, entry: LibraryEntry?) {
        val snapshot = prefs.getString("library", null)?.let(::JSONObject) ?: return
        val matchesOtherTitle = snapshot.optJSONArray(type).objects().filter { it.text("imdbId") != imdbId }
        val rows = JSONArray(matchesOtherTitle)
        if (entry != null) rows.put(entry.toJson())
        prefs.edit().putString("library", snapshot.put(type, rows).toString()).apply()
    }

    private fun readLibrary(type: String, payload: JSONObject): List<LibraryEntry> =
        payload.optJSONArray(type).objects().mapNotNull { row ->
            val media = mediaObject(row, type) ?: return@mapNotNull null
            val ids = media.optJSONObject("ids") ?: return@mapNotNull null
            val anime = row.text("anime_type") != null || media.text("anime_type") != null ||
                row.optJSONObject("anime") != null || listOf("mal", "anidb", "anilist").any { ids.text(it) != null }
            if (anime) return@mapNotNull null
            val imdbId = ids.text("imdb")?.takeIf(IMDB_ID::matches) ?: return@mapNotNull null
            val status = row.text("status") ?: return@mapNotNull null
            val hasActivity = row.text("last_watched_at") != null || row.optInt("watched_episodes_count") > 0 ||
                row.text("user_rating") != null
            libraryEntry(imdbId, type == "shows", ids.text("simkl"), status, hasActivity)
        }

    private fun parseCachedEntry(row: JSONObject, isSeries: Boolean): LibraryEntry? {
        val imdbId = row.text("imdbId") ?: return null
        val status = row.text("status") ?: return null
        return libraryEntry(imdbId, isSeries, row.text("simklId"), status, row.optBoolean("watched"))
    }

    private fun libraryEntry(imdbId: String, isSeries: Boolean, simklId: String?, status: String, hasActivity: Boolean): LibraryEntry {
        val url = simklId?.takeIf { it.all(Char::isDigit) }?.let { "https://simkl.com/${if (isSeries) "tv" else "movies"}/$it" }
        return LibraryEntry(SimklWatchlistEntry(imdbId, isSeries, url), simklId, status, hasActivity)
    }

    private fun mediaObject(row: JSONObject, type: String): JSONObject? =
        row.optJSONObject(if (type == "shows") "show" else "movie") ?: row.takeIf { it.has("ids") }

    private fun imdbItem(imdbId: String): JSONObject {
        require(IMDB_ID.matches(imdbId)) { "This title has no valid IMDb ID for Simkl." }
        return JSONObject().put("ids", JSONObject().put("imdb", imdbId))
    }

    private fun checkFound(result: JSONObject) {
        val notFound = result.optJSONObject("not_found") ?: return
        if (listOf("movies", "shows", "episodes").any { (notFound.optJSONArray(it)?.length() ?: 0) > 0 }) {
            throw SimklTitleUnavailable()
        }
    }

    private fun storedToken(): Token? {
        val encrypted = prefs.getString("token", null) ?: return null
        return try {
            val data = JSONObject(vault.decrypt(encrypted))
            Token(data.getString("access"), data.getString("refresh"), data.getLong("expiresAt"), data.getString("scope"))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: JSONException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun parseToken(body: JSONObject) = Token(
        access = body.getString("access_token"),
        refresh = body.getString("refresh_token"),
        expiresAt = System.currentTimeMillis() + body.getLong("expires_in") * 1_000L,
        scope = body.getString("scope"),
    )

    private suspend fun refresh(token: Token): Token = withContext(Dispatchers.IO + NonCancellable) {
        val response = oauth("token", clientId, "grant_type" to "refresh_token", "refresh_token" to token.refresh)
        if (response.error in setOf("invalid_grant", "invalid_client")) {
            forgetCurrent()
            throw IOException("Your Simkl connection expired. Connect your account again.")
        }
        val updated = parseToken(response.requireSuccess())
        prefs.edit().putString("token", vault.encrypt(updated.toJson().toString())).apply()
        updated
    }

    private suspend fun authorized(path: String, body: JSONObject? = null, query: Map<String, String> = emptyMap()): JSONObject {
        val token = storedToken() ?: throw IOException("Connect your Simkl account first.")
        val ready = if (token.expiresAt <= System.currentTimeMillis() + 60_000L) refresh(token) else token
        val requestBody = body?.toString()?.toRequestBody(JSON_TYPE)
        val response = request(path, clientId, requestBody, ready.access, query)
        if (response.code == 401 && ready === token) {
            return request(path, clientId, requestBody, refresh(ready).access, query).requireSuccess()
        }
        return response.requireSuccess()
    }

    private suspend fun oauth(path: String, id: String, vararg fields: Pair<String, String>): Reply {
        val form = FormBody.Builder().add("client_id", id)
        fields.forEach { (key, value) -> form.add(key, value) }
        return request("oauth2/$path", id, form.build())
    }

    private suspend fun request(
        path: String, id: String, body: RequestBody?, access: String? = null, query: Map<String, String> = emptyMap(),
    ): Reply = withContext(Dispatchers.IO) {
        val url = "https://api.simkl.com/$path".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", id)
            .addQueryParameter("app-name", "nanami-movies")
            .addQueryParameter("app-version", BuildConfig.VERSION_NAME)
        query.forEach { (key, value) -> url.addQueryParameter(key, value) }
        val builder = Request.Builder().url(url.build())
            .header("User-Agent", "NanamiMovies/${BuildConfig.VERSION_NAME}")
            .header("Accept", "application/json")
        if (access != null) builder.header("Authorization", "Bearer $access")
        if (body != null) builder.post(body)
        http.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val payload = if (raw.isBlank() && (path == "oauth2/revoke" || !response.isSuccessful)) JSONObject() else try {
                JSONObject(raw)
            } catch (_: JSONException) {
                if (response.isSuccessful) throw IOException("Simkl returned an invalid response.")
                JSONObject()
            }
            Reply(response.code, payload)
        }
    }

    private data class LibraryEntry(
        val media: SimklWatchlistEntry, val simklId: String?, val status: String, val hasPreservedActivity: Boolean,
    ) {
        fun toJson() = JSONObject().put("imdbId", media.imdbId).put("simklId", simklId)
            .put("status", status).put("watched", hasPreservedActivity)
    }

    private class Token(val access: String, val refresh: String, val expiresAt: Long, val scope: String) {
        fun toJson() = JSONObject().put("access", access).put("refresh", refresh).put("expiresAt", expiresAt).put("scope", scope)
    }

    private data class Reply(val code: Int, val body: JSONObject) {
        val isSuccessful: Boolean get() = code in 200..299
        val error: String? get() = body.text("error")

        fun requireSuccess(): JSONObject {
            if (isSuccessful && error == null) return body
            val message = when {
                error == "invalid_client" -> "Simkl needs a valid AUTH V2 client ID. Check your developer settings."
                code == 401 -> "Simkl could not verify your account. Try reconnecting."
                code == 403 -> "Simkl did not allow this action. Check your account permissions."
                code == 429 -> "Simkl's rate limit was reached. Try again later."
                else -> "Simkl request failed (HTTP $code)."
            }
            throw IOException(message)
        }
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        val IMDB_ID = Regex("tt[0-9]+")
    }
}

private class SimklTokenVault {
    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "nanami-movies-simkl-token"
        const val IV_BYTES = 12
    }
}

private fun JSONObject.text(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)
