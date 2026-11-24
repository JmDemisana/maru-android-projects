package io.maru.manime

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "manime_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

internal data class AniListSession(
    val token: String = "",
    val username: String = "",
    val avatarUrl: String? = null,
    val contentOptions: AniListContentOptions? = null
)

data class SavedAniListAccount(
    val id: Int,
    val username: String,
    val avatarUrl: String?,
    val scoreFormat: String = "POINT_100",
    val token: String,
    val contentOptions: AniListContentOptions? = null
)

internal data class AniListTrackingAccounts(
    val active: SavedAniListAccount? = null,
    val nsfw: SavedAniListAccount? = null,
    private val saved: List<SavedAniListAccount> = emptyList()
) {
    fun forTitle(isNsfw: Boolean): SavedAniListAccount? {
        if (active == null) return null
        return if (isNsfw) nsfw ?: active else active
    }

    fun forUpdate(update: PendingAniListUpdate): SavedAniListAccount? {
        val session = active ?: return null
        val id = update.accountId
        return if (id != null) {
            session.takeIf { it.id == id } ?: saved.firstOrNull { it.id == id }
        } else {
            session.takeIf { update.belongsTo(it, includeUnassigned = true) }
        }
    }
}

class MAnimePrefs(private val context: Context) {
    companion object {
        val KEY_ANILIST_TOKEN       = stringPreferencesKey("anilist_token")
        val KEY_ANILIST_USERNAME    = stringPreferencesKey("anilist_username")
        val KEY_ANILIST_AVATAR      = stringPreferencesKey("anilist_avatar")
        val KEY_ANILIST_SCORE_FMT   = stringPreferencesKey("anilist_score_format")
        val KEY_ANILIST_ACCOUNTS    = stringPreferencesKey("anilist_saved_accounts_json")
        private val KEY_ANILIST_CONTENT_OPTIONS = stringPreferencesKey("anilist_content_options")
        private val KEY_HIDE_NSFW_CONTENT = booleanPreferencesKey("hide_nsfw_content")
        private val KEY_NSFW_ANILIST_ACCOUNT_ID = intPreferencesKey("nsfw_anilist_account_id")
        val KEY_EXT_TYPE            = stringPreferencesKey("extension_type") // CLOUDSTREAM | ANIYOMI | STREMIO
        val KEY_STREMIO_URL         = stringPreferencesKey("stremio_url")
        val KEY_STREMIO_ADDONS      = stringSetPreferencesKey("stremio_addon_urls")
        val KEY_CS_REPOS            = stringSetPreferencesKey("cloudstream_repos")
        val KEY_CS_INSTALLED        = stringSetPreferencesKey("cloudstream_installed")
        val KEY_REPORT_PROGRESS     = booleanPreferencesKey("report_progress")
        val KEY_REMEMBER_POSITION   = booleanPreferencesKey("remember_position")
        val KEY_TORRENT_MAX_SPEED   = intPreferencesKey("torrent_max_dl_kbps") // 0 = unlimited
        val KEY_PREFERRED_AUDIO     = stringPreferencesKey("preferred_audio") // "SUB" | "DUB"
        val KEY_PREFERRED_QUALITY   = stringPreferencesKey("preferred_quality") // "1080p" | "720p" | "4K"
        val KEY_AUTO_SKIP_INTRO     = booleanPreferencesKey("auto_skip_intro")
        val KEY_SWIPE_GESTURES      = booleanPreferencesKey("player_swipe_gestures")
        val KEY_SUBTITLE_BLACK_FILL = booleanPreferencesKey("subtitle_black_fill")
        val KEY_SUBTITLE_SIZE       = stringPreferencesKey("subtitle_size") // "SMALL" | "MEDIUM" | "LARGE" | "EXTRA_LARGE"
        val KEY_PERSISTENT_DURATION_LINE = booleanPreferencesKey("persistent_duration_line")
        val KEY_MY_LISTS_SORT_OPTION = stringPreferencesKey("my_lists_sort_option")

        const val EXT_CLOUDSTREAM = "CLOUDSTREAM"
        const val EXT_ANIYOMI     = "ANIYOMI"
        const val EXT_STREMIO     = "STREMIO"

        const val DEFAULT_STREMIO_URL = "http://127.0.0.1:11470"
        val DEFAULT_STREMIO_ADDONS = setOf(
            "https://torrentio.strem.fun/manifest.json",
            "https://animekitsu.strem.fun/manifest.json"
        )
        val DEFAULT_CS_REPOS = setOf(
            "https://raw.githubusercontent.com/recloudstream/cloudstream-extensions-multilingual/builds/repo.json",
            "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"
        )
    }

    // --- Flows ---
    val anilistToken: Flow<String> = context.dataStore.data.map { it[KEY_ANILIST_TOKEN] ?: "" }
    internal val anilistSession: Flow<AniListSession> = context.dataStore.data.map {
        AniListSession(
            token = it[KEY_ANILIST_TOKEN].orEmpty(),
            username = it[KEY_ANILIST_USERNAME].orEmpty(),
            avatarUrl = it[KEY_ANILIST_AVATAR]?.takeIf(String::isNotBlank),
            contentOptions = it.readAniListContentOptions()
        )
    }
    val savedAnilistAccounts: Flow<List<SavedAniListAccount>> = context.dataStore.data.map { prefs ->
        val parsed = parseAccountsJson(prefs[KEY_ANILIST_ACCOUNTS]).toMutableList()
        val activeToken = prefs[KEY_ANILIST_TOKEN].orEmpty()
        val activeUsername = prefs[KEY_ANILIST_USERNAME].orEmpty()
        // Automatically include legacy single-account session if not yet in saved accounts list
        if (activeToken.isNotBlank() && activeUsername.isNotBlank() &&
            parsed.none { it.username.equals(activeUsername, ignoreCase = true) }
        ) {
            parsed.add(
                0,
                SavedAniListAccount(
                    id = 0,
                    username = activeUsername,
                    avatarUrl = prefs[KEY_ANILIST_AVATAR]?.takeIf(String::isNotBlank),
                    scoreFormat = prefs[KEY_ANILIST_SCORE_FMT] ?: "POINT_100",
                    token = activeToken,
                    contentOptions = prefs.readAniListContentOptions()
                )
            )
        }
        parsed
    }
    val nsfwAnilistAccountId: Flow<Int?> = context.dataStore.data.map { it[KEY_NSFW_ANILIST_ACCOUNT_ID] }
        .distinctUntilChanged()
    internal val trackingAccounts: Flow<AniListTrackingAccounts> = context.dataStore.data.map { prefs ->
        val saved = parseAccountsJson(prefs[KEY_ANILIST_ACCOUNTS])
        val token = prefs[KEY_ANILIST_TOKEN].orEmpty()
        val username = prefs[KEY_ANILIST_USERNAME].orEmpty()
        val active = token.takeIf(String::isNotBlank)?.let {
            saved.firstOrNull { account -> account.token == token } ?: SavedAniListAccount(
                id = 0,
                username = username,
                avatarUrl = prefs[KEY_ANILIST_AVATAR],
                scoreFormat = prefs[KEY_ANILIST_SCORE_FMT] ?: "POINT_100",
                token = token
            )
        }
        AniListTrackingAccounts(active, saved.firstOrNull { it.id == prefs[KEY_NSFW_ANILIST_ACCOUNT_ID] }, saved)
    }.distinctUntilChanged()

    suspend fun setNsfwAnilistAccountId(accountId: Int?) = context.dataStore.edit { prefs ->
        if (accountId == null) prefs.remove(KEY_NSFW_ANILIST_ACCOUNT_ID)
        else if (accountId > 0 && parseAccountsJson(prefs[KEY_ANILIST_ACCOUNTS]).any { it.id == accountId }) {
            prefs[KEY_NSFW_ANILIST_ACCOUNT_ID] = accountId
        }
    }
    val anilistScoreFormat: Flow<String> = context.dataStore.data.map { it[KEY_ANILIST_SCORE_FMT] ?: "POINT_100" }
    val extensionType: Flow<String> = context.dataStore.data.map { it[KEY_EXT_TYPE] ?: EXT_STREMIO }
    val stremioUrl: Flow<String> = context.dataStore.data.map { it[KEY_STREMIO_URL] ?: DEFAULT_STREMIO_URL }
    val stremioAddons: Flow<Set<String>> = context.dataStore.data.map { it[KEY_STREMIO_ADDONS] ?: DEFAULT_STREMIO_ADDONS }
    val cloudstreamRepos: Flow<Set<String>> = context.dataStore.data.map { it[KEY_CS_REPOS] ?: DEFAULT_CS_REPOS }
    val cloudstreamInstalled: Flow<Set<String>> = context.dataStore.data.map { it[KEY_CS_INSTALLED] ?: emptySet() }
    val reportProgress: Flow<Boolean> = context.dataStore.data.map { it[KEY_REPORT_PROGRESS] ?: true }
    val rememberPosition: Flow<Boolean> = context.dataStore.data.map { it[KEY_REMEMBER_POSITION] ?: true }
    val torrentMaxSpeed: Flow<Int> = context.dataStore.data.map { it[KEY_TORRENT_MAX_SPEED] ?: 0 }
    val preferredAudio: Flow<String> = context.dataStore.data.map { it[KEY_PREFERRED_AUDIO] ?: "SUB" }
    val preferredQuality: Flow<String> = context.dataStore.data.map { it[KEY_PREFERRED_QUALITY] ?: "1080p" }
    val autoSkipIntro: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_SKIP_INTRO] ?: false }
    val swipeGesturesEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_SWIPE_GESTURES] ?: true }
    val subtitleBlackFill: Flow<Boolean> = context.dataStore.data.map { it[KEY_SUBTITLE_BLACK_FILL] ?: true }
    val subtitleSize: Flow<String> = context.dataStore.data.map { it[KEY_SUBTITLE_SIZE] ?: "MEDIUM" }
    val persistentDurationLine: Flow<Boolean> = context.dataStore.data.map { it[KEY_PERSISTENT_DURATION_LINE] ?: false }
    val myListsSortOption: Flow<String> = context.dataStore.data.map { it[KEY_MY_LISTS_SORT_OPTION] ?: "RECENTLY_ADDED" }
    val hideNsfwContent: Flow<Boolean> = context.dataStore.data.map { it[KEY_HIDE_NSFW_CONTENT] ?: true }

    // --- Suspend setters ---
    suspend fun setAnilistSession(token: String, viewer: AniListUser) = context.dataStore.edit { prefs ->
        prefs[KEY_ANILIST_TOKEN] = token
        prefs.writeAniListViewer(viewer)
        val list = parseAccountsJson(prefs[KEY_ANILIST_ACCOUNTS]).toMutableList()
        val entry = SavedAniListAccount(
            id = viewer.id,
            username = viewer.name,
            avatarUrl = viewer.avatarUrl,
            scoreFormat = viewer.scoreFormat,
            token = token,
            contentOptions = viewer.contentOptions
        )
        val existingIdx = list.indexOfFirst {
            (viewer.id > 0 && it.id == viewer.id) || it.username.equals(viewer.name, ignoreCase = true)
        }
        if (existingIdx >= 0) list[existingIdx] = entry else list.add(entry)
        prefs[KEY_ANILIST_ACCOUNTS] = encodeAccountsJson(list)
    }

    suspend fun updateAnilistViewer(token: String, viewer: AniListUser) = context.dataStore.edit { prefs ->
        if (prefs[KEY_ANILIST_TOKEN] == token) prefs.writeAniListViewer(viewer)
        val list = parseAccountsJson(prefs[KEY_ANILIST_ACCOUNTS]).toMutableList()
        val idx = list.indexOfFirst {
            it.token == token || it.username.equals(viewer.name, ignoreCase = true)
        }
        val updated = SavedAniListAccount(viewer.id, viewer.name, viewer.avatarUrl, viewer.scoreFormat, token, viewer.contentOptions)
        if (idx >= 0) list[idx] = updated else if (prefs[KEY_ANILIST_TOKEN] == token) list.add(updated)
        if (list.isNotEmpty()) prefs[KEY_ANILIST_ACCOUNTS] = encodeAccountsJson(list)
    }

    suspend fun switchAnilistAccount(account: SavedAniListAccount) = context.dataStore.edit { prefs ->
        prefs[KEY_ANILIST_TOKEN] = account.token
        prefs[KEY_ANILIST_USERNAME] = account.username
        prefs[KEY_ANILIST_SCORE_FMT] = account.scoreFormat
        prefs.writeAniListContentOptions(account.contentOptions)
        if (account.avatarUrl.isNullOrBlank()) prefs.remove(KEY_ANILIST_AVATAR)
        else prefs[KEY_ANILIST_AVATAR] = account.avatarUrl
    }

    suspend fun removeAnilistAccount(username: String) = context.dataStore.edit { prefs ->
        val remaining = parseAccountsJson(prefs[KEY_ANILIST_ACCOUNTS])
            .filterNot { it.username.equals(username, ignoreCase = true) }
        prefs[KEY_ANILIST_ACCOUNTS] = encodeAccountsJson(remaining)
        prefs.clearRemovedNsfwAccount(remaining)

        val currentActive = prefs[KEY_ANILIST_USERNAME].orEmpty()
        if (currentActive.equals(username, ignoreCase = true)) {
            val next = remaining.firstOrNull()
            if (next != null) {
                prefs[KEY_ANILIST_TOKEN] = next.token
                prefs[KEY_ANILIST_USERNAME] = next.username
                prefs[KEY_ANILIST_SCORE_FMT] = next.scoreFormat
                prefs.writeAniListContentOptions(next.contentOptions)
                if (next.avatarUrl.isNullOrBlank()) prefs.remove(KEY_ANILIST_AVATAR)
                else prefs[KEY_ANILIST_AVATAR] = next.avatarUrl
            } else {
                prefs.clearActiveSessionFields()
            }
        }
    }

    /**
     * Switches into Guest Mode without deleting saved accounts unless [removeSavedAccount] is true.
     */
    suspend fun clearAnilistSession(removeSavedAccount: Boolean = true) = context.dataStore.edit { prefs ->
        val activeName = prefs[KEY_ANILIST_USERNAME].orEmpty()
        if (removeSavedAccount && activeName.isNotBlank()) {
            val remaining = parseAccountsJson(prefs[KEY_ANILIST_ACCOUNTS])
                .filterNot { it.username.equals(activeName, ignoreCase = true) }
            prefs[KEY_ANILIST_ACCOUNTS] = encodeAccountsJson(remaining)
            prefs.clearRemovedNsfwAccount(remaining)
            val fallback = remaining.firstOrNull()
            if (fallback != null) {
                prefs[KEY_ANILIST_TOKEN] = fallback.token
                prefs[KEY_ANILIST_USERNAME] = fallback.username
                prefs[KEY_ANILIST_SCORE_FMT] = fallback.scoreFormat
                prefs.writeAniListContentOptions(fallback.contentOptions)
                if (fallback.avatarUrl.isNullOrBlank()) prefs.remove(KEY_ANILIST_AVATAR)
                else prefs[KEY_ANILIST_AVATAR] = fallback.avatarUrl
                return@edit
            }
        }
        prefs.clearActiveSessionFields()
    }

    suspend fun useGuestMode() = context.dataStore.edit { prefs ->
        prefs.clearActiveSessionFields()
    }

    private fun MutablePreferences.clearRemovedNsfwAccount(remaining: List<SavedAniListAccount>) {
        val id = this[KEY_NSFW_ANILIST_ACCOUNT_ID] ?: return
        if (remaining.none { it.id == id }) remove(KEY_NSFW_ANILIST_ACCOUNT_ID)
    }

    private fun MutablePreferences.clearActiveSessionFields() {
        remove(KEY_ANILIST_TOKEN)
        remove(KEY_ANILIST_USERNAME)
        remove(KEY_ANILIST_AVATAR)
        remove(KEY_ANILIST_SCORE_FMT)
        remove(KEY_ANILIST_CONTENT_OPTIONS)
    }

    private fun MutablePreferences.writeAniListViewer(viewer: AniListUser) {
        this[KEY_ANILIST_USERNAME] = viewer.name
        this[KEY_ANILIST_SCORE_FMT] = viewer.scoreFormat
        writeAniListContentOptions(viewer.contentOptions)
        val avatar = viewer.avatarUrl
        if (avatar.isNullOrBlank()) remove(KEY_ANILIST_AVATAR) else this[KEY_ANILIST_AVATAR] = avatar
    }

    private fun Preferences.readAniListContentOptions(): AniListContentOptions? =
        this[KEY_ANILIST_CONTENT_OPTIONS]?.let { runCatching { parseAniListContentOptions(JSONObject(it)) }.getOrNull() }

    private fun MutablePreferences.writeAniListContentOptions(options: AniListContentOptions?) {
        if (options == null) remove(KEY_ANILIST_CONTENT_OPTIONS)
        else this[KEY_ANILIST_CONTENT_OPTIONS] = options.toJson().toString()
    }

    suspend fun setExtensionType(value: String) = context.dataStore.edit { it[KEY_EXT_TYPE] = value }
    suspend fun setStremioUrl(value: String) = context.dataStore.edit { it[KEY_STREMIO_URL] = value }
    suspend fun setStremioAddons(value: Set<String>) = context.dataStore.edit { it[KEY_STREMIO_ADDONS] = value }
    suspend fun setCloudstreamRepos(value: Set<String>) = context.dataStore.edit { it[KEY_CS_REPOS] = value }
    suspend fun setCloudstreamInstalled(value: Set<String>) = context.dataStore.edit { it[KEY_CS_INSTALLED] = value }
    suspend fun setReportProgress(value: Boolean) = context.dataStore.edit { it[KEY_REPORT_PROGRESS] = value }
    suspend fun setRememberPosition(value: Boolean) = context.dataStore.edit { it[KEY_REMEMBER_POSITION] = value }
    suspend fun setTorrentMaxSpeed(value: Int) = context.dataStore.edit { it[KEY_TORRENT_MAX_SPEED] = value }
    suspend fun setPreferredAudio(value: String) = context.dataStore.edit { it[KEY_PREFERRED_AUDIO] = value }
    suspend fun setPreferredQuality(value: String) = context.dataStore.edit { it[KEY_PREFERRED_QUALITY] = value }
    suspend fun setAutoSkipIntro(value: Boolean) = context.dataStore.edit { it[KEY_AUTO_SKIP_INTRO] = value }
    suspend fun setSwipeGesturesEnabled(value: Boolean) = context.dataStore.edit { it[KEY_SWIPE_GESTURES] = value }
    suspend fun setSubtitleBlackFill(value: Boolean) = context.dataStore.edit { it[KEY_SUBTITLE_BLACK_FILL] = value }
    suspend fun setSubtitleSize(value: String) = context.dataStore.edit { it[KEY_SUBTITLE_SIZE] = value }
    suspend fun setPersistentDurationLine(value: Boolean) = context.dataStore.edit { it[KEY_PERSISTENT_DURATION_LINE] = value }
    suspend fun setMyListsSortOption(value: String) = context.dataStore.edit { it[KEY_MY_LISTS_SORT_OPTION] = value }
    suspend fun setHideNsfwContent(value: Boolean) = context.dataStore.edit { it[KEY_HIDE_NSFW_CONTENT] = value }

    // Episode watch position cache: "mediaId:ep" -> seconds
    private fun posKey(mediaId: Int, ep: Int) = longPreferencesKey("pos_${mediaId}_$ep")
    suspend fun savePosition(mediaId: Int, ep: Int, seconds: Long) =
        context.dataStore.edit { it[posKey(mediaId, ep)] = seconds }
    fun getPositionFlow(mediaId: Int, ep: Int): Flow<Long> =
        context.dataStore.data.map { it[posKey(mediaId, ep)] ?: 0L }
}

internal fun parseAccountsJson(raw: String?): List<SavedAniListAccount> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val username = o.optString("username", "").trim()
                val token = o.optString("token", "").trim()
                if (username.isEmpty() || token.isEmpty()) continue
                add(
                    SavedAniListAccount(
                        id = o.optInt("id", 0),
                        username = username,
                        avatarUrl = o.optString("avatarUrl", "").trim().takeIf { it.isNotEmpty() },
                        scoreFormat = o.optString("scoreFormat", "POINT_100").ifBlank { "POINT_100" },
                        token = token,
                        contentOptions = o.optJSONObject("contentOptions")?.let(::parseAniListContentOptions)
                    )
                )
            }
        }.distinctBy { it.username.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }
}

internal fun encodeAccountsJson(accounts: List<SavedAniListAccount>): String {
    val arr = JSONArray()
    for (acc in accounts.distinctBy { it.username.lowercase() }) {
        arr.put(JSONObject().apply {
            put("id", acc.id)
            put("username", acc.username)
            put("avatarUrl", acc.avatarUrl ?: "")
            put("scoreFormat", acc.scoreFormat)
            put("token", acc.token)
            acc.contentOptions?.let { put("contentOptions", it.toJson()) }
        })
    }
    return arr.toString()
}

private fun AniListContentOptions.toJson() = JSONObject().apply {
    put("displayAdultContent", displayAdultContent)
    put("includeAdultContentInSearch", includeAdultContentInSearch)
    put("displayAdultActivities", displayAdultActivities)
}
