package io.maru.manime

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "manime_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

internal data class AniListSession(
    val token: String = "",
    val username: String = "",
    val avatarUrl: String? = null
)

class MAnimePrefs(private val context: Context) {
    companion object {
        val KEY_ANILIST_TOKEN       = stringPreferencesKey("anilist_token")
        val KEY_ANILIST_USERNAME    = stringPreferencesKey("anilist_username")
        val KEY_ANILIST_AVATAR      = stringPreferencesKey("anilist_avatar")
        val KEY_ANILIST_SCORE_FMT   = stringPreferencesKey("anilist_score_format")
        val KEY_EXT_TYPE            = stringPreferencesKey("extension_type") // CLOUDSTREAM | ANIYOMI | STREMIO
        val KEY_STREMIO_URL         = stringPreferencesKey("stremio_url")
        val KEY_STREMIO_ADDONS      = stringSetPreferencesKey("stremio_addon_urls")
        val KEY_CS_REPOS            = stringSetPreferencesKey("cloudstream_repos")
        val KEY_CS_INSTALLED        = stringSetPreferencesKey("cloudstream_installed") // jar filenames
        val KEY_REPORT_PROGRESS     = booleanPreferencesKey("report_progress")
        val KEY_REMEMBER_POSITION   = booleanPreferencesKey("remember_position")
        val KEY_TORRENT_MAX_SPEED   = intPreferencesKey("torrent_max_dl_kbps") // 0 = unlimited
        val KEY_PREFERRED_AUDIO     = stringPreferencesKey("preferred_audio") // "SUB" | "DUB"
        val KEY_PREFERRED_QUALITY   = stringPreferencesKey("preferred_quality") // "1080p" | "720p" | "4K"
        val KEY_AUTO_SKIP_INTRO     = booleanPreferencesKey("auto_skip_intro")

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
            avatarUrl = it[KEY_ANILIST_AVATAR]?.takeIf(String::isNotBlank)
        )
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

    // --- Suspend setters ---
    suspend fun setAnilistSession(token: String, viewer: AniListUser) = context.dataStore.edit {
        it[KEY_ANILIST_TOKEN] = token
        it.writeAniListViewer(viewer)
    }
    suspend fun updateAnilistViewer(token: String, viewer: AniListUser) = context.dataStore.edit {
        if (it[KEY_ANILIST_TOKEN] == token) it.writeAniListViewer(viewer)
    }
    suspend fun clearAnilistSession() = context.dataStore.edit {
        it.remove(KEY_ANILIST_TOKEN)
        it.remove(KEY_ANILIST_USERNAME)
        it.remove(KEY_ANILIST_AVATAR)
        it.remove(KEY_ANILIST_SCORE_FMT)
    }
    private fun MutablePreferences.writeAniListViewer(viewer: AniListUser) {
        this[KEY_ANILIST_USERNAME] = viewer.name
        this[KEY_ANILIST_SCORE_FMT] = viewer.scoreFormat
        val avatar = viewer.avatarUrl
        if (avatar.isNullOrBlank()) remove(KEY_ANILIST_AVATAR) else this[KEY_ANILIST_AVATAR] = avatar
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

    // Episode watch position cache: "mediaId:ep" -> seconds
    private fun posKey(mediaId: Int, ep: Int) = longPreferencesKey("pos_${mediaId}_$ep")
    suspend fun savePosition(mediaId: Int, ep: Int, seconds: Long) =
        context.dataStore.edit { it[posKey(mediaId, ep)] = seconds }
    fun getPositionFlow(mediaId: Int, ep: Int): Flow<Long> =
        context.dataStore.data.map { it[posKey(mediaId, ep)] ?: 0L }
}
