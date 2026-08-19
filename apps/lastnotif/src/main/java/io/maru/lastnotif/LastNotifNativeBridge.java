package io.maru.lastnotif;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

/**
 * JS ↔ Java bridge injected into the settings WebView as "NativeBridge".
 *
 * Callable from JS:
 *   NativeBridge.getSettings()       → JSON string of all stored settings
 *   NativeBridge.saveSettings(json)  → saves the given settings JSON
 *   NativeBridge.startPoller()       → starts the foreground service
 *   NativeBridge.stopPoller()        → stops the foreground service
 *   NativeBridge.isPollerRunning()   → true/false string
 */
public class LastNotifNativeBridge {

    private static final String TAG = "LastNotifBridge";

    private final Context ctx;
    private final android.app.Activity activity;
    private final LastNotifStorage storage;

    public LastNotifNativeBridge(Context context) {
        this.ctx      = context.getApplicationContext();
        this.activity = (context instanceof android.app.Activity)
            ? (android.app.Activity) context : null;
        this.storage  = new LastNotifStorage(ctx);
    }

    @JavascriptInterface
    public String getSettings() {
        return storage.toJson();
    }

    @JavascriptInterface
    public void saveSettings(String json) {
        if (json == null || json.length() > 5000) {
            return; // Prevent excessive payload size
        }
        try {
            JSONObject obj = new JSONObject(json);

            if (obj.has("username") && obj.opt("username") instanceof String) {
                String val = obj.getString("username");
                if (val.length() <= 100) {
                    storage.setUsername(val);
                }
            }

            if (obj.has("notifySongUpdate") && obj.opt("notifySongUpdate") instanceof Boolean) {
                storage.setNotifySongUpdate(obj.getBoolean("notifySongUpdate"));
            }

            if (obj.has("intervalEnabled") && obj.opt("intervalEnabled") instanceof Boolean) {
                storage.setIntervalEnabled(obj.getBoolean("intervalEnabled"));
            }

            if (obj.has("intervalMinutes") && obj.opt("intervalMinutes") instanceof Integer) {
                int val = obj.getInt("intervalMinutes");
                if (val >= 1 && val <= 1440) {
                    storage.setIntervalMinutes(val);
                }
            }

            if (obj.has("notifMainFormat") && obj.opt("notifMainFormat") instanceof String) {
                String val = obj.getString("notifMainFormat");
                if (val.length() <= 200) {
                    storage.setNotifMainFormat(val);
                }
            }

            if (obj.has("notifSubFormat") && obj.opt("notifSubFormat") instanceof String) {
                String val = obj.getString("notifSubFormat");
                if (val.length() <= 200) {
                    storage.setNotifSubFormat(val);
                }
            }

            if (obj.has("lyricsEnabled") && obj.opt("lyricsEnabled") instanceof Boolean) {
                storage.setLyricsEnabled(obj.getBoolean("lyricsEnabled"));
            }

            if (obj.has("trackSource") && obj.opt("trackSource") instanceof String) {
                String val = obj.getString("trackSource");
                if ("device".equals(val) || "lastfm".equals(val) || "mixed".equals(val)) {
                    storage.setTrackSource(val);
                }
            }

        } catch (Exception e) {
            // Ignore malformed JSON — settings stay as-is
        }
    }

    @JavascriptInterface
    public void startPoller() {
        try {
            LastNotifPollerService.start(ctx);
            LastNotifPollerAlarmScheduler.schedule(ctx);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start poller from bridge.", e);
        }
    }

    @JavascriptInterface
    public void stopPoller() {
        try {
            LastNotifPollerService.stop(ctx);
            LastNotifPollerAlarmScheduler.cancel(ctx);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop poller from bridge.", e);
        }
    }

    @JavascriptInterface
    public String isPollerRunning() {
        if (LastNotifPollerService.isRunning()) {
            return "true";
        }
        return String.valueOf(storage.isServiceRunning());
    }

    @JavascriptInterface
    public String getActiveTrack() {
        try {
            java.io.File file = new java.io.File(ctx.getCacheDir(), "active_track.json");
            if (file.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                return sb.toString();
            }
        } catch (Exception e) {
            // Ignore
        }
        return "{}";
    }

    @JavascriptInterface
    public void openNotificationAccessSettings() {
        try {
            android.content.Intent intent = new android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open notification access settings.", e);
        }
    }

    @JavascriptInterface
    public boolean hasNotificationAccess() {
        return LastNotifMediaMonitor.isNotificationAccessGranted(ctx);
    }

    @JavascriptInterface
    public boolean hasNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(
            ctx,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    @JavascriptInterface
    public void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        try {
            if (activity != null) {
                androidx.core.app.ActivityCompat.requestPermissions(
                    activity,
                    new String[]{ android.Manifest.permission.POST_NOTIFICATIONS },
                    1001
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to request notification permission.", e);
        }
    }
}
