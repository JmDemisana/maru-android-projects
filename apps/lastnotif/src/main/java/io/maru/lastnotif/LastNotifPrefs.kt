package io.maru.lastnotif

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lastnotif_prefs")

class LastNotifPrefs(private val context: Context) {

    companion object {
        val KEY_USERNAME = stringPreferencesKey("username")
        val KEY_NOTIFY_SONG_UPDATE = booleanPreferencesKey("notify_song_update")
        val KEY_INTERVAL_ENABLED = booleanPreferencesKey("notify_interval_enabled")
        val KEY_INTERVAL_MINUTES = intPreferencesKey("notify_interval_minutes")
        val KEY_NOTIF_MAIN_FORMAT = stringPreferencesKey("notif_main_format")
        val KEY_NOTIF_SUB_FORMAT = stringPreferencesKey("notif_sub_format")
        val KEY_TRACK_SOURCE = stringPreferencesKey("track_source")
        val KEY_SERVICE_RUNNING = booleanPreferencesKey("service_running")
        val KEY_LAST_TRACK_KEY = stringPreferencesKey("last_track_key")
        val KEY_LAST_INTERVAL_AT = longPreferencesKey("last_interval_notif_at")
        
        // --- New Requirements ---
        val KEY_SCROBBLE_ENABLED = booleanPreferencesKey("scrobble_enabled")
        val KEY_RECEIVER_ENABLED = booleanPreferencesKey("receiver_enabled")
        val KEY_LOCAL_ENABLED = booleanPreferencesKey("local_enabled")
        val KEY_SCROBBLE_APPS = stringSetPreferencesKey("scrobble_apps")
        val KEY_LOCAL_APPS = stringSetPreferencesKey("local_apps")
        val KEY_SCROBBLE_PERCENTAGE = intPreferencesKey("scrobble_percentage")
        val KEY_RECEIVER_USERNAME = stringPreferencesKey("receiver_username")
        
        val KEY_LASTFM_SESSION_KEY = stringPreferencesKey("lastfm_session_key")
        val KEY_LASTFM_USERNAME = stringPreferencesKey("lastfm_username")

        val KEY_LAST_ALERT_TITLE = stringPreferencesKey("last_alert_title")
        val KEY_LAST_ALERT_SUB = stringPreferencesKey("last_alert_sub")
        val KEY_LAST_ALERT_SOURCE = stringPreferencesKey("last_alert_source")
        val KEY_LAST_ALERT_TIME = longPreferencesKey("last_alert_time")

        val KEY_PREFERRED_PLATFORM = stringPreferencesKey("preferred_platform")
        val KEY_DIRECT_SONG_LAUNCH = booleanPreferencesKey("direct_song_launch")
    }

    val preferredPlatform: Flow<String> = context.dataStore.data.map { it[KEY_PREFERRED_PLATFORM] ?: "Apple Music" }
    val directSongLaunch: Flow<Boolean> = context.dataStore.data.map { it[KEY_DIRECT_SONG_LAUNCH] ?: false }

    val username: Flow<String> = context.dataStore.data.map { it[KEY_USERNAME] ?: "" }
    val notifySongUpdate: Flow<Boolean> = context.dataStore.data.map { it[KEY_NOTIFY_SONG_UPDATE] ?: true }
    val intervalEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_INTERVAL_ENABLED] ?: false }
    val intervalMinutes: Flow<Int> = context.dataStore.data.map { it[KEY_INTERVAL_MINUTES] ?: 5 }
    val notifMainFormat: Flow<String> = context.dataStore.data.map { it[KEY_NOTIF_MAIN_FORMAT] ?: "{song_name}" }
    val notifSubFormat: Flow<String> = context.dataStore.data.map { it[KEY_NOTIF_SUB_FORMAT] ?: "{artist}" }
    val trackSource: Flow<String> = context.dataStore.data.map { it[KEY_TRACK_SOURCE] ?: "mixed" }
    val serviceRunning: Flow<Boolean> = context.dataStore.data.map { it[KEY_SERVICE_RUNNING] ?: false }
    
    val scrobbleEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_SCROBBLE_ENABLED] ?: false }
    val receiverEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_RECEIVER_ENABLED] ?: false }
    val localEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_LOCAL_ENABLED] ?: false }
    val scrobbleApps: Flow<Set<String>> = context.dataStore.data.map { it[KEY_SCROBBLE_APPS] ?: emptySet() }
    val localApps: Flow<Set<String>> = context.dataStore.data.map { it[KEY_LOCAL_APPS] ?: emptySet() }
    val scrobblePercentage: Flow<Int> = context.dataStore.data.map { it[KEY_SCROBBLE_PERCENTAGE] ?: 50 }
    val receiverUsername: Flow<String> = context.dataStore.data.map { it[KEY_RECEIVER_USERNAME] ?: "" }

    val lastfmSessionKey: Flow<String> = context.dataStore.data.map { it[KEY_LASTFM_SESSION_KEY] ?: "" }
    val lastfmUsername: Flow<String> = context.dataStore.data.map { it[KEY_LASTFM_USERNAME] ?: "" }

    val lastAlertTitle: Flow<String> = context.dataStore.data.map { it[KEY_LAST_ALERT_TITLE] ?: "" }
    val lastAlertSub: Flow<String> = context.dataStore.data.map { it[KEY_LAST_ALERT_SUB] ?: "" }
    val lastAlertSource: Flow<String> = context.dataStore.data.map { it[KEY_LAST_ALERT_SOURCE] ?: "" }
    val lastAlertTime: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_ALERT_TIME] ?: 0L }

    suspend fun setUsername(value: String) { context.dataStore.edit { it[KEY_USERNAME] = value } }
    suspend fun setNotifySongUpdate(value: Boolean) { context.dataStore.edit { it[KEY_NOTIFY_SONG_UPDATE] = value } }
    suspend fun setIntervalEnabled(value: Boolean) { context.dataStore.edit { it[KEY_INTERVAL_ENABLED] = value } }
    suspend fun setIntervalMinutes(value: Int) { context.dataStore.edit { it[KEY_INTERVAL_MINUTES] = value } }
    suspend fun setNotifMainFormat(value: String) { context.dataStore.edit { it[KEY_NOTIF_MAIN_FORMAT] = value } }
    suspend fun setNotifSubFormat(value: String) { context.dataStore.edit { it[KEY_NOTIF_SUB_FORMAT] = value } }
    suspend fun setTrackSource(value: String) { context.dataStore.edit { it[KEY_TRACK_SOURCE] = value } }
    suspend fun setServiceRunning(value: Boolean) { context.dataStore.edit { it[KEY_SERVICE_RUNNING] = value } }
    
    suspend fun setScrobbleEnabled(value: Boolean) { context.dataStore.edit { it[KEY_SCROBBLE_ENABLED] = value } }
    suspend fun setReceiverEnabled(value: Boolean) { context.dataStore.edit { it[KEY_RECEIVER_ENABLED] = value } }
    suspend fun setLocalEnabled(value: Boolean) { context.dataStore.edit { it[KEY_LOCAL_ENABLED] = value } }
    suspend fun setScrobbleApps(value: Set<String>) { context.dataStore.edit { it[KEY_SCROBBLE_APPS] = value } }
    suspend fun setLocalApps(value: Set<String>) { context.dataStore.edit { it[KEY_LOCAL_APPS] = value } }
    suspend fun setScrobblePercentage(value: Int) { context.dataStore.edit { it[KEY_SCROBBLE_PERCENTAGE] = value } }
    suspend fun setReceiverUsername(value: String) { context.dataStore.edit { it[KEY_RECEIVER_USERNAME] = value } }

    suspend fun setLastfmSessionKey(value: String) { context.dataStore.edit { it[KEY_LASTFM_SESSION_KEY] = value } }
    suspend fun setLastfmUsername(value: String) { context.dataStore.edit { it[KEY_LASTFM_USERNAME] = value } }

    suspend fun setPreferredPlatform(value: String) { context.dataStore.edit { it[KEY_PREFERRED_PLATFORM] = value } }
    suspend fun setDirectSongLaunch(value: Boolean) { context.dataStore.edit { it[KEY_DIRECT_SONG_LAUNCH] = value } }

    suspend fun setLastAlert(title: String, sub: String, source: String) {
        context.dataStore.edit {
            it[KEY_LAST_ALERT_TITLE] = title
            it[KEY_LAST_ALERT_SUB] = sub
            it[KEY_LAST_ALERT_SOURCE] = source
            it[KEY_LAST_ALERT_TIME] = System.currentTimeMillis()
        }
    }

    // Internal state
    val lastTrackKey: Flow<String> = context.dataStore.data.map { it[KEY_LAST_TRACK_KEY] ?: "" }
    suspend fun setLastTrackKey(value: String) { context.dataStore.edit { it[KEY_LAST_TRACK_KEY] = value } }
    
    val lastIntervalAt: Flow<Long> = context.dataStore.data.map { it[KEY_LAST_INTERVAL_AT] ?: 0L }
    suspend fun setLastIntervalAt(value: Long) { context.dataStore.edit { it[KEY_LAST_INTERVAL_AT] = value } }
}
