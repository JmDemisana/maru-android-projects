package io.maru.lastnotif;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Foreground service that polls the Maru site's Last.fm endpoints.
 *
 * Polling interval:
 *  - Normal mode  (no lyrics): every 5 seconds (enough to catch song changes fast)
 *  - Lyrics mode             : every 1 second  (we need frequent lyric position updates)
 *
 * What it does each tick:
 *  1. Calls now-playing endpoint
 *  2. If song changed + notifySongUpdate → fire song notification, reset lyric state
 *  3. If interval alert enabled + enough time elapsed → fire interval notification
 *  4. If lyrics enabled + now playing → find current lyric line, fire if changed
 */
public class LastNotifPollerService extends Service {

    public static final String TAG = "LastNotifPoller";
    public static final String ACTION_START = "io.maru.lastnotif.ACTION_START";
    public static final String ACTION_STOP  = "io.maru.lastnotif.ACTION_STOP";

    private static volatile boolean sRunning = false;

    public static boolean isRunning() { return sRunning; }

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> pollFuture;

    private LastNotifStorage        storage;
    private LastNotifNotificationManager notifMgr;

    // Lyric state — cached per track to avoid re-fetching every second
    private String cachedLyricsForTrackKey = "";
    private List<LastNotifSiteClient.LyricLine> cachedLyricLines = null;
    private long   cachedSyncStartedAtMs = 0L;
    private int    lastLyricIndex = -1;


    // Cache for writeActiveTrack
    private String lastWrittenTitle = null;
    private String lastWrittenArtist = null;
    private String lastWrittenAlbum = null;
    private Boolean lastWrittenIsPlaying = null;
    private String lastWrittenLyricLine = null;
    private String lastWrittenPollingMethod = null;

    // ─── Lifecycle ────────────────────────────────────────────────────────────


    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        Log.i(TAG, "Poller service onCreate");
        storage  = new LastNotifStorage(this);
        storage.setServiceRunning(true);
        notifMgr = new LastNotifNotificationManager(this);
        executor = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "null";
        Log.i(TAG, "Poller service onStartCommand action=" + action);

        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                int fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                }
                startForeground(
                    LastNotifNotificationManager.ID_KEEPALIVE,
                    notifMgr.buildKeepaliveNotification(),
                    fgType
                );
            } else {
                startForeground(
                    LastNotifNotificationManager.ID_KEEPALIVE,
                    notifMgr.buildKeepaliveNotification()
                );
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Unable to start foreground service.", e);
            storage.setServiceRunning(false);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        schedulePoll();

        // Reschedule the alarm keepalive
        LastNotifPollerAlarmScheduler.schedule(this);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Poller service onDestroy");
        sRunning = false;
        if (storage != null) {
            storage.setServiceRunning(false);
        }
        try {
            java.io.File file = new java.io.File(getCacheDir(), "active_track.json");
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete active_track.json", e);
        }
        if (pollFuture != null) pollFuture.cancel(false);
        if (executor != null)   executor.shutdown();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ─── Polling ──────────────────────────────────────────────────────────────

    private void schedulePoll() {
        if (pollFuture != null) pollFuture.cancel(false);

        boolean lyricsEnabled = storage.isLyricsEnabled();
        long intervalSec = lyricsEnabled ? 1L : 5L;

        pollFuture = executor.scheduleWithFixedDelay(
            this::tick, 0, intervalSec, TimeUnit.SECONDS
        );
    }

    private static class PolledTrack {
        String title = "";
        String artist = "";
        String album = "";
        boolean isPlaying = false;
        boolean useLocal = false;
        String pollingMethod = "";
        boolean shouldAbort = false;
    }

    private PolledTrack fetchTrack(String source, String username) {
        PolledTrack pt = new PolledTrack();
        LastNotifMediaMonitor.TrackInfo localTrack = null;
        if ("device".equals(source) || "mixed".equals(source)) {
            localTrack = LastNotifMediaMonitor.getActiveTrack(this);
        }

        if ("device".equals(source)) {
            if (localTrack != null) {
                pt.title = localTrack.title;
                pt.artist = localTrack.artist;
                pt.album = localTrack.album;
                pt.isPlaying = localTrack.isPlaying;
                pt.useLocal = true;
                pt.pollingMethod = "Device";
                Log.d(TAG, "Device track active: " + pt.title + " - " + pt.artist + " (isPlaying=" + pt.isPlaying + ")");
            } else {
                Log.w(TAG, "Device source selected but no track details or notification permission.");
                pt.shouldAbort = true;
                pt.pollingMethod = "Device";
            }
        } else if ("mixed".equals(source) && localTrack != null && localTrack.isPlaying) {
            pt.title = localTrack.title;
            pt.artist = localTrack.artist;
            pt.album = localTrack.album;
            pt.isPlaying = localTrack.isPlaying;
            pt.useLocal = true;
            pt.pollingMethod = "Mixed→Device";
            Log.d(TAG, "Mixed source: choosing active device track: " + pt.title + " - " + pt.artist);
        } else {
            // Last.fm or mixed fallback
            if (username.isEmpty()) {
                Log.d(TAG, "Last.fm polling skipped: username is empty.");
                pt.shouldAbort = true;
                pt.pollingMethod = source;
                return pt;
            }
            LastNotifSiteClient.NowPlayingResult np =
                LastNotifSiteClient.getNowPlaying(username);
            if (np == null) {
                Log.w(TAG, "Last.fm fetch returned null.");
                pt.shouldAbort = true;
                return pt;
            }
            pt.title = np.title;
            pt.artist = np.artist;
            pt.album = np.album;
            pt.isPlaying = np.isPlaying;
            pt.pollingMethod = "mixed".equals(source) ? "Mixed→Last.fm" : "Last.fm";
            Log.d(TAG, "Last.fm source active: " + pt.title + " - " + pt.artist + " (isPlaying=" + pt.isPlaying + ")");
        }
        return pt;
    }

    private void handleTrackChange(PolledTrack pt, String trackKey) {
        String lastKey = storage.getLastTrackKey();
        boolean trackChanged = pt.isPlaying && !trackKey.equals(lastKey);

        if (trackChanged) {
            Log.i(TAG, "Track changed detected: " + trackKey + " (previous: " + lastKey + ")");
            storage.setLastTrackKey(trackKey);

            // Reset lyric cache for new track
            cachedLyricsForTrackKey = "";
            cachedLyricLines = null;
            lastLyricIndex = -1;

            if (storage.isNotifySongUpdate() || (storage.isLyricsEnabled() && !pt.useLocal)) {
                Log.i(TAG, "Posting song change notification alert.");
                notifMgr.postSongAlert(
                    pt.title, pt.artist, pt.album,
                    storage.getNotifMainFormat(),
                    storage.getNotifSubFormat(),
                    pt.pollingMethod
                );
            }
        }
    }

    private void handleIntervalAlert(PolledTrack pt) {
        if (storage.isIntervalEnabled() && pt.isPlaying) {
            long now       = System.currentTimeMillis();
            long lastFired = storage.getLastIntervalNotifAt();
            long intervalMs = storage.getIntervalMinutes() * 60_000L;

            if (now - lastFired >= intervalMs) {
                Log.i(TAG, "Posting interval alert notification.");
                storage.setLastIntervalNotifAt(now);
                notifMgr.postSongAlert(
                    pt.title, pt.artist, pt.album,
                    storage.getNotifMainFormat(),
                    storage.getNotifSubFormat(),
                    pt.pollingMethod
                );
            }
        }
    }

    private String handleLyrics(PolledTrack pt, String trackKey, String username) {
        if (!storage.isLyricsEnabled() || !pt.isPlaying || pt.useLocal) {
            return "";
        }

        // Fetch/refresh lyrics if track changed
        if (!trackKey.equals(cachedLyricsForTrackKey)) {
            cachedLyricsForTrackKey = trackKey;
            cachedLyricLines = null;
            lastLyricIndex = -1;

            LastNotifSiteClient.LyricsResult lr =
                LastNotifSiteClient.getLyrics(username);

            if (lr != null && !lr.lines.isEmpty()) {
                cachedLyricLines       = lr.lines;
                cachedSyncStartedAtMs  = lr.syncStartedAtMs;
            } else {
                return null; // Return null to indicate abort
            }
        }

        if (cachedLyricLines == null || cachedLyricLines.isEmpty()) {
            return null; // Return null to indicate abort
        }

        // Compute current position using the server-anchored sync time
        long posMs = System.currentTimeMillis() - cachedSyncStartedAtMs;
        if (posMs < 0) posMs = 0;

        int activeIndex = findLyricIndex(cachedLyricLines, posMs);
        String currentLyric = "";

        if (activeIndex >= 0) {
            currentLyric = cachedLyricLines.get(activeIndex).text;
            if (activeIndex != lastLyricIndex) {
                lastLyricIndex = activeIndex;
                if (!currentLyric.isEmpty()) {
                    notifMgr.postLyricAlert(currentLyric, pt.title, pt.artist);
                }
            }
        }
        return currentLyric;
    }

    private void tick() {
        String username = storage.getUsername().trim();
        String source = storage.getTrackSource(); // "device", "lastfm", "mixed"

        Log.d(TAG, "Poller service tick. Source=" + source + ", Username=" + username);

        PolledTrack pt = fetchTrack(source, username);

        if (pt.shouldAbort) {
            // Check if we need to write empty track (null np check doesn't write)
            // Original code:
            // if device && !localTrack -> writeActiveTrack("", "", "", false, "", "Device"); return;
            // if !device && username.isEmpty() -> writeActiveTrack("", "", "", false, "", source); return;
            // if !device && !username.isEmpty() && np == null -> return;

            if ("device".equals(source) && pt.pollingMethod.equals("Device")) {
                writeActiveTrack("", "", "", false, "", "Device");
            } else if (!"device".equals(source) && username.isEmpty()) {
                writeActiveTrack("", "", "", false, "", source);
            }
            return;
        }

        String trackKey = pt.artist + " - " + pt.title;

        handleTrackChange(pt, trackKey);
        handleIntervalAlert(pt);

        String currentLyric = "";
        if (storage.isLyricsEnabled() && pt.isPlaying && !pt.useLocal) {
            currentLyric = handleLyrics(pt, trackKey, username);
            if (currentLyric == null) {
                 // Lyrics aborted tick
                 writeActiveTrack(pt.title, pt.artist, pt.album, pt.isPlaying, "", pt.pollingMethod);
                 return;
            }
        }

        writeActiveTrack(pt.title, pt.artist, pt.album, pt.isPlaying, currentLyric, pt.pollingMethod);
    }
/** Returns the index of the lyric line active at posMs, or -1 if before first line */
    private static int findLyricIndex(
            List<LastNotifSiteClient.LyricLine> lines, long posMs) {
        int low = 0;
        int high = lines.size() - 1;
        int bestIdx = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midVal = lines.get(mid).timestampMs;

            if (midVal <= posMs) {
                bestIdx = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return bestIdx;
    }

    private void writeActiveTrack(String title, String artist, String album, boolean isPlaying, String lyricLine, String pollingMethod) {
        String safeTitle = title != null ? title : "";
        String safeArtist = artist != null ? artist : "";
        String safeAlbum = album != null ? album : "";
        String safeLyricLine = lyricLine != null ? lyricLine : "";
        String safePollingMethod = pollingMethod != null ? pollingMethod : "";

        if (safeTitle.equals(lastWrittenTitle) &&
            safeArtist.equals(lastWrittenArtist) &&
            safeAlbum.equals(lastWrittenAlbum) &&
            lastWrittenIsPlaying != null && isPlaying == lastWrittenIsPlaying &&
            safeLyricLine.equals(lastWrittenLyricLine) &&
            safePollingMethod.equals(lastWrittenPollingMethod)) {
            return; // No changes, avoid disk I/O
        }

        lastWrittenTitle = safeTitle;
        lastWrittenArtist = safeArtist;
        lastWrittenAlbum = safeAlbum;
        lastWrittenIsPlaying = isPlaying;
        lastWrittenLyricLine = safeLyricLine;
        lastWrittenPollingMethod = safePollingMethod;

        try {
            executor.execute(() -> {
                try {
                    java.io.File file = new java.io.File(getCacheDir(), "active_track.json");
                    org.json.JSONObject json = new org.json.JSONObject();
                    json.put("title", safeTitle);
                    json.put("artist", safeArtist);
                    json.put("album", safeAlbum);
                    json.put("isPlaying", isPlaying);
                    json.put("lyricLine", safeLyricLine);
                    json.put("pollingMethod", safePollingMethod);
                    json.put("timestamp", System.currentTimeMillis());

                    java.io.FileWriter writer = new java.io.FileWriter(file);
                    writer.write(json.toString());
                    writer.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error writing active track json", e);
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "Active track write skipped because service executor is unavailable.", e);
        }
    }

    // ─── Static helpers ───────────────────────────────────────────────────────

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, LastNotifPollerService.class);
            i.setAction(ACTION_START);
            ctx.startForegroundService(i);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start foreground service", e);
        }
    }

    public static void stop(Context ctx) {
        try {
            Intent i = new Intent(ctx, LastNotifPollerService.class);
            i.setAction(ACTION_STOP);
            ctx.stopService(i);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop foreground service", e);
        }
    }
}
