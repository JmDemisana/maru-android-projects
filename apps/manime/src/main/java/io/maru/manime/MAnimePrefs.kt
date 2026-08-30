package io.maru.manime

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "manime_prefs")

class MAnimePrefs(private val context: Context) {
    companion object {
        val KEY_ANILIST_TOKEN       = stringPreferencesKey("anilist_token")
        val KEY_ANILIST_USERNAME    = stringPreferencesKey("anilist_username")
        val KEY_ANILIST_AVATAR      = stringPreferencesKey("anilist_avatar")
        val KEY_EXT_TYPE            = stringPreferencesKey("extension_type") // CLOUDSTREAM | ANIYOMI | STREMIO
        val KEY_STREMIO_URL         = stringPreferencesKey("stremio_url")
        val KEY_STREMIO_ADDONS      = stringSetPreferencesKey("stremio_addon_urls")
        val KEY_CS_REPOS            = stringSetPreferencesKey("cloudstream_repos")
        val KEY_CS_INSTALLED        = stringSetPreferencesKey("cloudstream_installed") // jar filenames
        val KEY_REPORT_PROGRESS     = booleanPreferencesKey("report_progress")
        val KEY_REMEMBER_POSITION   = booleanPreferencesKey("remember_position")
        val KEY_TORRENT_MAX_SPEED   = intPreferencesKey("torrent_max_dl_kbps") // 0 = unlimited

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
    val anilistUsername: Flow<String> = context.dataStore.data.map { it[KEY_ANILIST_USERNAME] ?: "" }
    val anilistAvatar: Flow<String> = context.dataStore.data.map { it[KEY_ANILIST_AVATAR] ?: "" }
    val extensionType: Flow<String> = context.dataStore.data.map { it[KEY_EXT_TYPE] ?: EXT_STREMIO }
    val stremioUrl: Flow<String> = context.dataStore.data.map { it[KEY_STREMIO_URL] ?: DEFAULT_STREMIO_URL }
    val stremioAddons: Flow<Set<String>> = context.dataStore.data.map { it[KEY_STREMIO_ADDONS] ?: DEFAULT_STREMIO_ADDONS }
    val cloudstreamRepos: Flow<Set<String>> = context.dataStore.data.map { it[KEY_CS_REPOS] ?: DEFAULT_CS_REPOS }
    val cloudstreamInstalled: Flow<Set<String>> = context.dataStore.data.map { it[KEY_CS_INSTALLED] ?: emptySet() }
    val reportProgress: Flow<Boolean> = context.dataStore.data.map { it[KEY_REPORT_PROGRESS] ?: true }
    val rememberPosition: Flow<Boolean> = context.dataStore.data.map { it[KEY_REMEMBER_POSITION] ?: true }
    val torrentMaxSpeed: Flow<Int> = context.dataStore.data.map { it[KEY_TORRENT_MAX_SPEED] ?: 0 }

    // --- Suspend setters ---
    suspend fun setAnilistToken(value: String) = context.dataStore.edit { it[KEY_ANILIST_TOKEN] = value }
    suspend fun setAnilistUsername(value: String) = context.dataStore.edit { it[KEY_ANILIST_USERNAME] = value }
    suspend fun setAnilistAvatar(value: String) = context.dataStore.edit { it[KEY_ANILIST_AVATAR] = value }
    suspend fun setExtensionType(value: String) = context.dataStore.edit { it[KEY_EXT_TYPE] = value }
    suspend fun setStremioUrl(value: String) = context.dataStore.edit { it[KEY_STREMIO_URL] = value }
    suspend fun setStremioAddons(value: Set<String>) = context.dataStore.edit { it[KEY_STREMIO_ADDONS] = value }
    suspend fun setCloudstreamRepos(value: Set<String>) = context.dataStore.edit { it[KEY_CS_REPOS] = value }
    suspend fun setCloudstreamInstalled(value: Set<String>) = context.dataStore.edit { it[KEY_CS_INSTALLED] = value }
    suspend fun setReportProgress(value: Boolean) = context.dataStore.edit { it[KEY_REPORT_PROGRESS] = value }
    suspend fun setRememberPosition(value: Boolean) = context.dataStore.edit { it[KEY_REMEMBER_POSITION] = value }
    suspend fun setTorrentMaxSpeed(value: Int) = context.dataStore.edit { it[KEY_TORRENT_MAX_SPEED] = value }

    // Episode watch position cache: "mediaId:ep" -> seconds
    private fun posKey(mediaId: Int, ep: Int) = longPreferencesKey("pos_${mediaId}_$ep")
    suspend fun savePosition(mediaId: Int, ep: Int, seconds: Long) =
        context.dataStore.edit { it[posKey(mediaId, ep)] = seconds }
    fun getPositionFlow(mediaId: Int, ep: Int): Flow<Long> =
        context.dataStore.data.map { it[posKey(mediaId, ep)] ?: 0L }
}
