package io.maru.lastnotif;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Simple SharedPreferences wrapper for all LastNotif settings.
 * No encryption needed — just username + toggle state.
 */
public class LastNotifStorage {

    private static final String PREFS_NAME = "lastnotif_prefs";

    // Keys
    public static final String KEY_USERNAME             = "username";
    public static final String KEY_NOTIFY_SONG_UPDATE   = "notify_song_update";
    public static final String KEY_INTERVAL_ENABLED     = "notify_interval_enabled";
    public static final String KEY_INTERVAL_MINUTES     = "notify_interval_minutes";
    public static final String KEY_NOTIF_MAIN_FORMAT    = "notif_main_format";
    public static final String KEY_NOTIF_SUB_FORMAT     = "notif_sub_format";
    public static final String KEY_LYRICS_ENABLED       = "lyrics_enabled";
    public static final String KEY_LAST_TRACK_KEY       = "last_track_key";
    public static final String KEY_LAST_INTERVAL_AT     = "last_interval_notif_at";
    public static final String KEY_SERVICE_RUNNING      = "service_running";
    public static final String KEY_TRACK_SOURCE        = "track_source";

    private final Context context;

    @SuppressWarnings("deprecation")
    public LastNotifStorage(Context context) {
        this.context = context.getApplicationContext();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isServiceRunning() {
        return prefs().getBoolean(KEY_SERVICE_RUNNING, false);
    }

    public void setServiceRunning(boolean value) {
        prefs().edit().putBoolean(KEY_SERVICE_RUNNING, value).apply();
    }

    // --- Track Source ---

    public String getTrackSource() {
        return prefs().getString(KEY_TRACK_SOURCE, "mixed");
    }

    public void setTrackSource(String source) {
        prefs().edit().putString(KEY_TRACK_SOURCE, source).apply();
    }

    // --- Username ---

    public String getUsername() {
        return prefs().getString(KEY_USERNAME, "");
    }

    public void setUsername(String username) {
        prefs().edit().putString(KEY_USERNAME, username).apply();
    }

    // --- Notify on song update ---

    public boolean isNotifySongUpdate() {
        return prefs().getBoolean(KEY_NOTIFY_SONG_UPDATE, true);
    }

    public void setNotifySongUpdate(boolean value) {
        prefs().edit().putBoolean(KEY_NOTIFY_SONG_UPDATE, value).apply();
    }

    // --- Interval ---

    public boolean isIntervalEnabled() {
        return prefs().getBoolean(KEY_INTERVAL_ENABLED, false);
    }

    public void setIntervalEnabled(boolean value) {
        prefs().edit().putBoolean(KEY_INTERVAL_ENABLED, value).apply();
    }

    public int getIntervalMinutes() {
        return prefs().getInt(KEY_INTERVAL_MINUTES, 5);
    }

    public void setIntervalMinutes(int minutes) {
        prefs().edit().putInt(KEY_INTERVAL_MINUTES, Math.max(1, minutes)).apply();
    }

    // --- Notification format ---

    public String getNotifMainFormat() {
        return prefs().getString(KEY_NOTIF_MAIN_FORMAT, "{song_name}");
    }

    public void setNotifMainFormat(String fmt) {
        prefs().edit().putString(KEY_NOTIF_MAIN_FORMAT, fmt).apply();
    }

    public String getNotifSubFormat() {
        return prefs().getString(KEY_NOTIF_SUB_FORMAT, "{artist}");
    }

    public void setNotifSubFormat(String fmt) {
        prefs().edit().putString(KEY_NOTIF_SUB_FORMAT, fmt).apply();
    }

    // --- Lyrics ---

    public boolean isLyricsEnabled() {
        return prefs().getBoolean(KEY_LYRICS_ENABLED, false);
    }

    public void setLyricsEnabled(boolean value) {
        prefs().edit().putBoolean(KEY_LYRICS_ENABLED, value).apply();
    }

    // --- Internal state ---

    public String getLastTrackKey() {
        return prefs().getString(KEY_LAST_TRACK_KEY, "");
    }

    public void setLastTrackKey(String key) {
        prefs().edit().putString(KEY_LAST_TRACK_KEY, key).apply();
    }

    public long getLastIntervalNotifAt() {
        return prefs().getLong(KEY_LAST_INTERVAL_AT, 0L);
    }

    public void setLastIntervalNotifAt(long millis) {
        prefs().edit().putLong(KEY_LAST_INTERVAL_AT, millis).apply();
    }

    // --- Convenience: get all as JSON (for JS bridge) ---

    public String toJson() {
        try {
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("username", getUsername());
            obj.put("notifySongUpdate", isNotifySongUpdate());
            obj.put("intervalEnabled", isIntervalEnabled());
            obj.put("intervalMinutes", getIntervalMinutes());
            obj.put("notifMainFormat", getNotifMainFormat());
            obj.put("notifSubFormat", getNotifSubFormat());
            obj.put("lyricsEnabled", isLyricsEnabled());
            obj.put("trackSource", getTrackSource());
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
