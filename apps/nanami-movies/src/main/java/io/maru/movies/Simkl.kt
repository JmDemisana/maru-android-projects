package io.maru.movies

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
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

internal class SimklPkceSession(
    val authorizeUrl: String,
    val clientId: String,
    val state: String,
    val codeVerifier: String,
    val redirectUri: String = Simkl.REDIRECT_URI,
)

data class SimklWatchlistEntry(
    val imdbId: String,
    val isSeries: Boolean,
    val simklUrl: String?,
)

internal data class SimklAccount(
    val id: String,
    val label: String,
    val username: String? = null,
    val avatarUrl: String? = null,
)

internal data class SimklViewerProfile(
    val username: String,
    val avatarUrl: String?,
    val simklAccountId: String?,
)

internal class SimklTitleUnavailable : IOException("SIMKL couldn't match this title or episode.")

internal data class SimklLibrarySnapshot(
    val planned: Set<TitleRef>,
    val ratings: Map<TitleRef, Int>,
    val completed: List<WatchProgress>,
    val statuses: Map<TitleRef, MovieWatchStatus>,
)

internal fun generatePkceVerifier(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(48)
    random.nextBytes(bytes)
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun computePkceChallengeS256(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

class Simkl(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("simkl", Context.MODE_PRIVATE)
    private val vault = SimklTokenVault()
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .callTimeout(14, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private var activeSignIn: SimklSignIn? = null

    val clientId: String
        get() = prefs.getString("client_id", null)?.trim()?.takeIf(String::isNotEmpty)
            ?: prefs.getString("discovered_client_id", null)?.trim()?.takeIf(String::isNotEmpty)
            ?: BuildConfig.SIMKL_CLIENT_ID.trim().takeIf(String::isNotEmpty)
            ?: MASTER_CLIENT_ID

    val isConnected: Boolean
        get() = storedToken() != null

    internal val connectionKey: String?
        get() = prefs.getString("connection_key", null)

    internal fun activeAccount(): SimklAccount? {
        val key = connectionKey ?: return null
        if (!isConnected) return null
        val username = prefs.getString("username/$key", null)
        val label = username?.let { "@$it" } ?: prefs.getString("label/$key", null) ?: "SIMKL Account"
        return SimklAccount(
            id = key,
            label = label,
            username = username,
            avatarUrl = prefs.getString("avatar/$key", null),
        )
    }

    internal fun accounts(): List<SimklAccount> {
        val ids = prefs.getStringSet("accounts", emptySet()).orEmpty() + listOfNotNull(connectionKey)
        return ids.distinct().mapNotNull { id ->
            if (id != connectionKey && !prefs.contains("token/$id")) return@mapNotNull null
            val username = prefs.getString("username/$id", null)
            val label = username?.let { "@$it" } ?: prefs.getString("label/$id", null) ?: "SIMKL Account"
            SimklAccount(
                id = id,
                label = label,
                username = username,
                avatarUrl = prefs.getString("avatar/$id", null),
            )
        }.sortedBy { it.label.lowercase() }
    }

    internal fun setCustomClientId(value: String) {
        val cleaned = value.trim()
        val edit = prefs.edit()
        if (cleaned.isEmpty()) edit.remove("client_id") else edit.putString("client_id", cleaned)
        edit.apply()
    }

    internal suspend fun resolveEffectiveClientId(explicit: String = ""): String = withContext(Dispatchers.IO) {
        explicit.trim().takeIf(String::isNotEmpty)?.let { return@withContext it }
        clientId.takeIf(String::isNotEmpty)?.let { return@withContext it }
        for (endpoint in TOKEN_ENDPOINTS) {
            val discovered = runCatching {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/json")
                    .header("User-Agent", "NanamiMovies/${BuildConfig.VERSION_NAME}")
                    .build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use null
                    val body = JSONObject(res.body?.string().orEmpty())
                    body.text("client_id")?.takeIf { CLIENT_ID_PATTERN.matches(it) }
                }
            }.getOrNull()
            if (!discovered.isNullOrBlank()) {
                prefs.edit().putString("discovered_client_id", discovered).apply()
                return@withContext discovered
            }
        }
        ""
    }

    internal suspend fun createPkceSession(
        explicitClientId: String = "",
        forAppDeepLink: Boolean = true,
    ): SimklPkceSession {
        val resolvedClient = resolveEffectiveClientId(explicitClientId).ifBlank { MASTER_CLIENT_ID }
        val verifier = generatePkceVerifier()
        val challenge = computePkceChallengeS256(verifier)
        val rawUuid = UUID.randomUUID().toString()
        val state = if (forAppDeepLink) "app_$rawUuid" else rawUuid
        val authorizeUrl = Uri.parse("https://simkl.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", resolvedClient)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", "media:read media:write")
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
        prefs.edit()
            .putString("pending_pkce_state", state)
            .putString("pending_pkce_verifier", verifier)
            .putString("pending_pkce_client", resolvedClient)
            .commit()
        return SimklPkceSession(
            authorizeUrl = authorizeUrl,
            clientId = resolvedClient,
            state = state,
            codeVerifier = verifier,
            redirectUri = REDIRECT_URI,
        )
    }

    internal fun isAppAuthCallback(uri: Uri?): Boolean =
        uri != null && uri.scheme == APP_CALLBACK_SCHEME && uri.host == APP_CALLBACK_HOST

    internal suspend fun completeAppCallback(uri: Uri): SimklAccount {
        val oauthError = uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error")
        if (!oauthError.isNullOrBlank()) {
            clearPendingPkce()
            throw IOException(oauthError)
        }
        val code = uri.getQueryParameter("code")?.trim().orEmpty()
        val returnedState = uri.getQueryParameter("state")?.trim().orEmpty()
        val expectedState = prefs.getString("pending_pkce_state", null).orEmpty()
        val verifier = prefs.getString("pending_pkce_verifier", null).orEmpty()
        val savedClient = prefs.getString("pending_pkce_client", null).orEmpty().ifBlank { clientId }

        if (code.isBlank() || verifier.isBlank() || (expectedState.isNotBlank() && returnedState != expectedState)) {
            clearPendingPkce()
            throw IOException("SIMKL sign-in session expired. Tap Connect SIMKL to try again.")
        }
        clearPendingPkce()
        val session = SimklPkceSession(
            authorizeUrl = "",
            clientId = savedClient,
            state = returnedState,
            codeVerifier = verifier,
            redirectUri = REDIRECT_URI,
        )
        return completePkceSignIn(session, code)
    }

    private fun clearPendingPkce() {
        prefs.edit()
            .remove("pending_pkce_state")
            .remove("pending_pkce_verifier")
            .remove("pending_pkce_client")
            .apply()
    }

    internal suspend fun completePkceSignIn(
        session: SimklPkceSession,
        code: String,
        labelHint: String = "",
    ): SimklAccount = mutex.withLock {
        val token = exchangePkceCode(session, code.trim())
        val viewer = runCatching { fetchViewerProfile(token.access, session.clientId) }.getOrNull()
        storeAuthenticatedAccount(token, session.clientId, viewer, labelHint)
    }

    internal suspend fun connectWithToken(
        accessToken: String,
        labelHint: String = "",
    ): SimklAccount = mutex.withLock {
        val cleaned = accessToken.trim()
        require(cleaned.isNotEmpty()) { "Missing SIMKL access token." }
        val activeClient = resolveEffectiveClientId().ifBlank { MASTER_CLIENT_ID }
        val token = Token(
            access = cleaned,
            refresh = "",
            expiresAt = Long.MAX_VALUE,
            scope = "media:read media:write",
        )
        val viewer = runCatching { fetchViewerProfile(token.access, activeClient) }.getOrNull()
        storeAuthenticatedAccount(token, activeClient, viewer, labelHint)
    }

    internal suspend fun refreshViewerProfile(): SimklAccount? = mutex.withLock {
        val key = connectionKey ?: return@withLock null
        val token = storedToken() ?: return@withLock null
        val activeClient = clientId.takeIf(String::isNotBlank) ?: return@withLock activeAccount()
        val viewer = runCatching { fetchViewerProfile(token.access, activeClient) }.getOrNull() ?: return@withLock activeAccount()
        prefs.edit()
            .putString("username/$key", viewer.username)
            .putString("label/$key", "@${viewer.username}")
            .putString("avatar/$key", viewer.avatarUrl)
            .apply()
        activeAccount()
    }

    internal suspend fun selectAccount(id: String?) = mutex.withLock {
        if (id == connectionKey) return@withLock
        val token = id?.let { requireNotNull(prefs.getString("token/$it", null)) { "This account needs to sign in again." } }
        val edit = prefs.edit()
        saveCurrent(edit)
        edit.putString("token", token).putString("connection_key", id)
            .putString("library", id?.let { prefs.getString("library/$it", null) })
        if (id != null) {
            prefs.getString("client/$id", null)?.takeIf(String::isNotBlank)?.let {
                edit.putString("client_id", it)
            }
        }
        edit.apply()
    }

    internal suspend fun removeAccount(id: String) = mutex.withLock {
        if (id == connectionKey) {
            disconnectLocked()
            return@withLock
        }
        prefs.edit()
            .remove("token/$id")
            .remove("client/$id")
            .remove("library/$id")
            .remove("label/$id")
            .remove("username/$id")
            .remove("avatar/$id")
            .remove("simkl_uid/$id")
            .putStringSet("accounts", prefs.getStringSet("accounts", emptySet()).orEmpty() - id)
            .apply()
    }

    suspend fun beginSignIn(client: String = clientId, label: String = ""): SimklSignIn = mutex.withLock {
        val currentClient = resolveEffectiveClientId(client)
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
            label = label.trim().take(40),
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
                    if (token.scope.isNotBlank()) {
                        require("media:write" in token.scope.split(' ', ',')) {
                            "SIMKL did not grant permission to sync your list."
                        }
                    }
                    val viewer = runCatching { fetchViewerProfile(token.access, signIn.clientId) }.getOrNull()
                    storeAuthenticatedAccount(token, signIn.clientId, viewer, signIn.label)
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

    private suspend fun exchangePkceCode(session: SimklPkceSession, code: String): Token = withContext(Dispatchers.IO) {
        // 1. Direct Simkl AUTH V2 PKCE exchange first (single-hop, preserves one-time authorization code)
        val directReply = runCatching {
            oauth(
                "token",
                session.clientId,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to session.redirectUri,
                "code_verifier" to session.codeVerifier,
            )
        }.getOrNull()

        if (directReply != null) {
            if (directReply.isSuccessful && directReply.error == null) {
                return@withContext parseToken(directReply.body)
            }
            // If Simkl explicitly rejected the grant/client, surface the exact reason immediately
            if (directReply.code in 400..499) {
                return@withContext parseToken(directReply.requireSuccess())
            }
        }

        // 2. Site bridge (/api/simkl-token) fallback when direct API is unreachable
        val bridgeBody = JSONObject()
            .put("code", code)
            .put("clientId", session.clientId)
            .put("redirectUri", session.redirectUri)
            .put("codeVerifier", session.codeVerifier)
            .toString()
            .toRequestBody(JSON_TYPE)

        for (endpoint in TOKEN_ENDPOINTS) {
            val token = runCatching {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(bridgeBody)
                    .header("Accept", "application/json")
                    .header("User-Agent", "NanamiMovies/${BuildConfig.VERSION_NAME}")
                    .build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use null
                    val body = JSONObject(res.body?.string().orEmpty())
                    if (body.text("access_token") == null) return@use null
                    parseToken(body)
                }
            }.getOrNull()
            if (token != null) return@withContext token
        }

        throw IOException("Couldn't reach SIMKL to finish sign-in. Check your connection and try again.")
    }

    private suspend fun fetchViewerProfile(accessToken: String, activeClientId: String): SimklViewerProfile? =
        withContext(Dispatchers.IO) {
            var reply = request(
                path = "users/settings",
                id = activeClientId,
                body = "".toRequestBody(JSON_TYPE),
                access = accessToken,
            )
            if (!reply.isSuccessful) {
                reply = request(
                    path = "users/settings",
                    id = activeClientId,
                    body = null,
                    access = accessToken,
                )
            }
            if (!reply.isSuccessful) return@withContext null
            val userObj = reply.body.optJSONObject("user")
            val accountObj = reply.body.optJSONObject("account")
            val username = userObj?.text("name") ?: return@withContext null
            val rawAvatar = userObj.text("avatar")
            val avatarUrl = when {
                rawAvatar == null -> null
                rawAvatar.startsWith("https://") -> rawAvatar
                rawAvatar.startsWith("//") -> "https:$rawAvatar"
                else -> "https://simkl.in/avatars/${rawAvatar.trimStart('/')}_m.jpg"
            }
            SimklViewerProfile(
                username = username,
                avatarUrl = avatarUrl,
                simklAccountId = accountObj?.opt("id")?.toString()?.takeIf(String::isNotBlank),
            )
        }

    private suspend fun storeAuthenticatedAccount(
        token: Token,
        usedClientId: String,
        viewer: SimklViewerProfile?,
        labelHint: String,
    ): SimklAccount {
        val encrypted = withContext(Dispatchers.IO) { vault.encrypt(token.toJson().toString()) }
        val existingIds = prefs.getStringSet("accounts", emptySet()).orEmpty() + listOfNotNull(connectionKey)
        val matchedId = existingIds.firstOrNull { id ->
            val savedUid = prefs.getString("simkl_uid/$id", null)
            val savedUser = prefs.getString("username/$id", null)
            (viewer?.simklAccountId != null && savedUid == viewer.simklAccountId) ||
                (viewer?.username != null && savedUser?.equals(viewer.username, ignoreCase = true) == true)
        }
        val id = matchedId ?: UUID.randomUUID().toString()
        val username = viewer?.username
        val displayLabel = when {
            !username.isNullOrBlank() -> "@$username"
            labelHint.isNotBlank() -> labelHint.trim().take(40)
            else -> "SIMKL Account ${accounts().size + 1}"
        }
        val edit = prefs.edit()
        saveCurrent(edit, id)
        if (matchedId == null || matchedId != connectionKey) {
            edit.putString("library", prefs.getString("library/$id", null))
        }
        edit.putString("token", encrypted)
            .putString("token/$id", encrypted)
            .putString("label/$id", displayLabel)
            .putString("client/$id", usedClientId)
            .putString("client_id", usedClientId)
            .putString("connection_key", id)
        if (!username.isNullOrBlank()) edit.putString("username/$id", username)
        if (!viewer?.avatarUrl.isNullOrBlank()) edit.putString("avatar/$id", viewer?.avatarUrl)
        if (!viewer?.simklAccountId.isNullOrBlank()) edit.putString("simkl_uid/$id", viewer?.simklAccountId)
        edit.apply()
        return SimklAccount(id = id, label = displayLabel, username = username, avatarUrl = viewer?.avatarUrl)
    }

    internal suspend fun librarySnapshot(): SimklLibrarySnapshot = mutex.withLock {
        val library = syncLibrary()
        SimklLibrarySnapshot(
            planned = library.filter { it.status == "plantowatch" }.map { it.title }.toSet(),
            ratings = library.mapNotNull { entry -> entry.rating?.let { entry.title to it } }.toMap(),
            completed = library.filter { it.status == "completed" || !it.media.isSeries && it.lastWatched > 0 }.map {
                WatchProgress(it.title, null, null, 0L, 0L, it.lastWatched, completed = true)
            },
            statuses = library.mapNotNull { row -> MovieWatchStatus.parse(row.status)?.let { row.title to it } }.toMap(),
        )
    }

    internal suspend fun track(title: TitleRef, status: MovieWatchStatus, account: String) = mutex.withLock {
        check(account == connectionKey) { "SIMKL account changed." }
        val type = if (title.kind == MovieKind.SERIES) "shows" else "movies"
        val before = syncLibrary().firstOrNull { it.title == title }
        if (before?.status == status.code) return@withLock
        val item = imdbItem(title.imdbId).put("to", status.code)
        val result = authorized("sync/add-to-list", JSONObject().put(type, JSONArray().put(item)))
        checkFound(result)
        val updated = result.optJSONObject("added")?.optJSONArray(type)?.optJSONObject(0)
            ?: throw IOException("SIMKL did not confirm the status change.")
        val remoteId = updated.optJSONObject("ids")?.text("simkl") ?: before?.simklId
        putCachedEntry(type, title.imdbId, libraryEntry(title.imdbId, title.kind == MovieKind.SERIES, remoteId,
            updated.text("to") ?: status.code, before?.hasPreservedActivity ?: false, before?.rating, before?.lastWatched ?: 0L))
    }

    internal suspend fun rate(title: TitleRef, score: Int, account: String) = mutex.withLock {
        check(account == connectionKey) { "SIMKL account changed." }
        require(score in 0..10)
        val type = if (title.kind == MovieKind.SERIES) "shows" else "movies"
        val item = imdbItem(title.imdbId)
        if (score > 0) item.put("rating", score)
        val path = if (score == 0) "sync/ratings/remove" else "sync/ratings"
        val result = authorized(path, JSONObject().put(type, JSONArray().put(item)))
        checkFound(result)
    }

    suspend fun setWatchlisted(imdbId: String, isSeries: Boolean, listed: Boolean, account: String? = null): Boolean = mutex.withLock {
        check(account == null || account == connectionKey) { "SIMKL account changed." }
        val item = imdbItem(imdbId)
        val type = if (isSeries) "shows" else "movies"
        val entry = syncLibrary().firstOrNull { it.media.imdbId == imdbId && it.media.isSeries == isSeries }
        if (listed) {
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
            if (entry == null) return@withLock false
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
        val activeClient = clientId
        activeSignIn = null
        forgetCurrent()
        if (previous != null && previous.refresh.isNotBlank() && activeClient.isNotBlank()) {
            try {
                oauth("revoke", activeClient, "token" to previous.refresh, "token_type_hint" to "refresh_token")
            } catch (_: IOException) {
            }
        }
    }

    private fun saveCurrent(edit: SharedPreferences.Editor, newId: String? = null) {
        val ids = prefs.getStringSet("accounts", emptySet()).orEmpty().toMutableSet()
        connectionKey?.let { id ->
            ids.add(id)
            edit.putString("token/$id", prefs.getString("token", null))
                .putString("client/$id", clientId)
                .putString("library/$id", prefs.getString("library", null))
        }
        if (newId != null) ids.add(newId)
        edit.putStringSet("accounts", ids)
    }

    private fun forgetCurrent() {
        val edit = prefs.edit().remove("token").remove("library").remove("connection_key")
        connectionKey?.let { id ->
            edit.remove("token/$id")
                .remove("client/$id")
                .remove("library/$id")
                .remove("label/$id")
                .remove("username/$id")
                .remove("avatar/$id")
                .remove("simkl_uid/$id")
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
            val membershipFields = listOf("plantowatch", "watching", "hold", "completed", "dropped", "last_watched_at")
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
            val rated = currentGroup?.text("rated_at") != lastGroup?.text("rated_at")
            if (since != null && rated) {
                val ratingsDelta = readLibrary(type, authorized("sync/ratings/$type", query = mapOf("date_from" to since)))
                val updates = ratingsDelta.associateBy { it.media.imdbId }
                for (index in merged.indices) {
                    val entry = merged[index]
                    if (entry.media.isSeries == isSeries) updates[entry.media.imdbId]?.let { merged[index] = it }
                }
                val existing = merged.filter { it.media.isSeries == isSeries }.mapTo(mutableSetOf()) { it.media.imdbId }
                merged += ratingsDelta.filter { it.media.imdbId !in existing }
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
            val lastWatched = row.text("last_watched_at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L
            libraryEntry(imdbId, type == "shows", ids.text("simkl"), status, hasActivity,
                rating = row.optInt("user_rating").takeIf { it in 1..10 }, lastWatched = lastWatched)
        }

    private fun parseCachedEntry(row: JSONObject, isSeries: Boolean): LibraryEntry? {
        val imdbId = row.text("imdbId") ?: return null
        val status = row.text("status") ?: return null
        return libraryEntry(imdbId, isSeries, row.text("simklId"), status, row.optBoolean("watched"),
            row.optInt("rating").takeIf { it in 1..10 }, row.optLong("lastWatched"))
    }

    private fun libraryEntry(
        imdbId: String, isSeries: Boolean, simklId: String?, status: String, hasActivity: Boolean,
        rating: Int? = null, lastWatched: Long = 0,
    ): LibraryEntry {
        val url = simklId?.takeIf { it.all(Char::isDigit) }?.let { "https://simkl.com/${if (isSeries) "tv" else "movies"}/$it" }
        return LibraryEntry(SimklWatchlistEntry(imdbId, isSeries, url), simklId, status, hasActivity, rating, lastWatched)
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
            Token(data.getString("access"), data.optString("refresh", ""), data.optLong("expiresAt", Long.MAX_VALUE), data.optString("scope", "media:read media:write"))
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

    private fun parseToken(body: JSONObject): Token {
        val expiresIn = body.optLong("expires_in", 0L)
        val expiresAt = if (expiresIn > 0L) System.currentTimeMillis() + expiresIn * 1_000L else Long.MAX_VALUE
        return Token(
            access = body.getString("access_token"),
            refresh = body.optString("refresh_token", ""),
            expiresAt = expiresAt,
            scope = body.optString("scope", "media:read media:write"),
        )
    }

    private suspend fun refresh(token: Token): Token = withContext(Dispatchers.IO + NonCancellable) {
        if (token.refresh.isBlank()) {
            forgetCurrent()
            throw IOException("Your Simkl connection expired. Connect your account again.")
        }
        val response = oauth("token", clientId, "grant_type" to "refresh_token", "refresh_token" to token.refresh)
        if (response.error in setOf("invalid_grant", "invalid_client")) {
            forgetCurrent()
            throw IOException("Your Simkl connection expired. Connect your account again.")
        }
        val updated = parseToken(response.requireSuccess())
        val encrypted = vault.encrypt(updated.toJson().toString())
        val edit = prefs.edit().putString("token", encrypted)
        connectionKey?.let { edit.putString("token/$it", encrypted) }
        edit.apply()
        updated
    }

    private suspend fun authorized(path: String, body: JSONObject? = null, query: Map<String, String> = emptyMap()): JSONObject {
        val token = storedToken() ?: throw IOException("Connect your Simkl account first.")
        val ready = if (token.refresh.isNotBlank() && token.expiresAt <= System.currentTimeMillis() + 60_000L) refresh(token) else token
        val requestBody = body?.toString()?.toRequestBody(JSON_TYPE)
        val response = request(path, clientId, requestBody, ready.access, query)
        if (response.code == 401 && ready === token && ready.refresh.isNotBlank()) {
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
            .header("simkl-api-key", id)
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
        val rating: Int?, val lastWatched: Long,
    ) {
        val title get() = TitleRef(if (media.isSeries) MovieKind.SERIES else MovieKind.MOVIE, media.imdbId)
        fun toJson() = JSONObject().put("imdbId", media.imdbId).put("simklId", simklId)
            .put("status", status).put("watched", hasPreservedActivity).put("rating", rating).put("lastWatched", lastWatched)
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
                error == "invalid_client" || error == "client_id_failed" || code == 412 ->
                    "Simkl needs a valid AUTH V2 Client ID. Check your developer settings."
                code == 401 -> "Simkl could not verify your account. Try reconnecting."
                code == 403 -> "Simkl did not allow this action. Check your account permissions."
                code == 429 -> "Simkl's rate limit was reached. Try again later."
                else -> body.text("error_description") ?: body.text("message") ?: "Simkl request failed (HTTP $code)."
            }
            throw IOException(message)
        }
    }

    companion object {
        const val MASTER_CLIENT_ID = "ac2e17ef7382fa9f3113b74345d22eadb36de496d3f2b5b52ebd1d030abce6fe"
        const val REDIRECT_URI = "https://maruchan.pages.dev/simkl-integration"
        const val APP_CALLBACK_SCHEME = "nanamimovies"
        const val APP_CALLBACK_HOST = "simkl-auth"
        private val TOKEN_ENDPOINTS = listOf(
            "https://maruchan.pages.dev/api/simkl-token",
            "https://maru-api-wine.vercel.app/api/simkl-token",
        )
        private val CLIENT_ID_PATTERN = Regex("[A-Za-z0-9_-]{8,128}")
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val IMDB_ID = Regex("tt[0-9]+")
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
