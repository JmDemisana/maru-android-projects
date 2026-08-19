package io.maru.helper;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.webkit.JavascriptInterface;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

/**
 * JavascriptInterface bridge between the Nami agent WebView and MainActivity.
 * All methods are called from the WebView's JS thread; those that touch UI/activities
 * must be dispatched to the main thread via the activity reference.
 */
public class NamiNativeBridge {

    private static final String PREFS_FILE   = "nami_secure_prefs";
    private static final String KEY_API_KEYS = "nami_api_keys";

    private final MainActivity activity;

    public NamiNativeBridge(MainActivity activity) {
        this.activity = activity;
    }

    /* ── API key storage ─────────────────────────────────────────────── */

    @JavascriptInterface
    public String getApiKey() {
        try {
            MasterKey masterKey = new MasterKey.Builder(activity)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            var prefs = EncryptedSharedPreferences.create(
                    activity,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
            return prefs.getString(KEY_API_KEYS, "");
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public void saveApiKey(String key) {
        if (key == null) key = "";
        final String finalKey = key.trim();
        activity.runOnUiThread(() -> {
            try {
                MasterKey masterKey = new MasterKey.Builder(activity)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                var prefs = EncryptedSharedPreferences.create(
                        activity,
                        PREFS_FILE,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
                prefs.edit().putString(KEY_API_KEYS, finalKey).apply();
            } catch (Exception ignored) {}
        });
    }

    /* ── applet launcher ─────────────────────────────────────────────── */

    @JavascriptInterface
    public boolean openApplet(String id, String name, String path) {
        if (id == null || name == null || path == null) return false;
        final String fId = id, fName = name, fPath = path;
        activity.runOnUiThread(() -> activity.openApplet(fId, fName, fPath));
        return true;
    }

    /* ── installed app launcher ──────────────────────────────────────── */

    @JavascriptInterface
    public boolean openInstalledApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        try {
            PackageManager pm = activity.getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            if (launchIntent == null) return false;
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            final Intent finalIntent = launchIntent;
            activity.runOnUiThread(() -> activity.startActivity(finalIntent));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /* ── companion status (informational, real WS is on JS side) ─────── */

    @JavascriptInterface
    public String getCompanionStatus() {
        // The companion WebSocket is managed entirely in JS for flexibility.
        // This method is a stub for future native relay if needed.
        try {
            return new JSONObject()
                    .put("nativeRelayAvailable", false)
                    .toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /* ── no-op stubs for bridge completeness ─────────────────────────── */

    @JavascriptInterface
    public void connectCompanion(String host, int port) {
        // Managed in JS WebSocket layer; stub kept for API compatibility.
    }

    @JavascriptInterface
    public void disconnectCompanion() {
        // Managed in JS WebSocket layer; stub kept for API compatibility.
    }

    @JavascriptInterface
    public void sendCompanionMessage(String msg) {
        // Managed in JS WebSocket layer; stub kept for API compatibility.
    }
}
