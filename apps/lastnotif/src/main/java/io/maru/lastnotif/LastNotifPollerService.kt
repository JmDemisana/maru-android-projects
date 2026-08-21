package io.maru.lastnotif

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

class LastNotifPollerService : LifecycleService() {

    data class ActiveTrackState(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val isPlaying: Boolean = false,
        val lyricLine: String = "",
        val pollingMethod: String = "Idle",
        val timestamp: Long = System.currentTimeMillis()
    )

    companion object {
        const val TAG = "LastNotifPoller"
        const val ACTION_START = "io.maru.lastnotif.ACTION_START"
        const val ACTION_STOP = "io.maru.lastnotif.ACTION_STOP"

        @Volatile
        private var sRunning = false
        fun isRunning() = sRunning

        private val _liveTrack = MutableStateFlow<ActiveTrackState?>(null)
        val liveTrack: StateFlow<ActiveTrackState?> = _liveTrack.asStateFlow()

        fun start(ctx: Context) {
            try {
                Log.d(TAG, "Starting service")
                val i = Intent(ctx, LastNotifPollerService::class.java).apply { action = ACTION_START }
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground service", e)
            }
        }

        fun stop(ctx: Context) {
            try {
                Log.d(TAG, "Stopping service")
                val i = Intent(ctx, LastNotifPollerService::class.java).apply { action = ACTION_STOP }
                ctx.stopService(i)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
            }
        }
    }

    private lateinit var prefs: LastNotifPrefs
    private lateinit var notifMgr: LastNotifNotificationManager
    private var pollJob: Job? = null

    // Shared lyrics state
    private var cachedLyricsForTrackKey = ""
    private var cachedLyricLines: List<LastNotifApiClient.LyricLine>? = null
    private var cachedSyncStartedAtMs = 0L
    private var lastLyricIndex = -1

    // Scrobble state
    private var currentScrobbleTrack: LastNotifMediaMonitor.TrackInfo? = null
    private var scrobbleStartTime = 0L
    private var scrobbleSubmitted = false

    private var lastWrittenState: JSONObject? = null

    override fun onCreate() {
        super.onCreate()
        sRunning = true
        prefs = LastNotifPrefs(this)
        notifMgr = LastNotifNotificationManager(this)
        lifecycleScope.launch { prefs.setServiceRunning(true) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        Log.i(TAG, "Service onStartCommand action=$action")

        if (ACTION_STOP == action) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = notifMgr.buildKeepaliveNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                }
                startForeground(LastNotifNotificationManager.ID_KEEPALIVE, notification, fgType)
            } else {
                startForeground(LastNotifNotificationManager.ID_KEEPALIVE, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in startForeground", e)
        }

        startPolling()
        LastNotifPollerAlarmScheduler.schedule(this)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        sRunning = false
        lifecycleScope.launch { prefs.setServiceRunning(false) }
        _liveTrack.value = null
        File(cacheDir, "active_track.json").delete()
        pollJob?.cancel()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in poll tick", e)
                }
                val lyricsEnabled = prefs.lyricsEnabled.first()
                delay(if (lyricsEnabled) 1000L else 4000L)
            }
        }
    }

    private suspend fun tick() {
        val scrobbleEnabled = prefs.scrobbleEnabled.first()
        val receiverEnabled = prefs.receiverEnabled.first()
        val localEnabled = prefs.localEnabled.first()

        val scrobbleApps = prefs.scrobbleApps.first()
        val localApps = prefs.localApps.first()
        val receiverUser = prefs.receiverUsername.first().ifEmpty { prefs.lastfmUsername.first() }.trim()
        val sessionKey = prefs.lastfmSessionKey.first()
        val scrobblePercent = prefs.scrobblePercentage.first()

        val localTrack = LastNotifMediaMonitor.getActiveTrack(this)
        val isLocalAllowed = localTrack != null && (localApps.isEmpty() || localApps.contains(localTrack.packageName))

        var displayTrack: PolledTrack? = null

        // 1. LOCAL PILLAR
        if (localEnabled && isLocalAllowed) {
            if (localTrack.isPlaying) {
                displayTrack = PolledTrack(localTrack.title, localTrack.artist, localTrack.album, isPlaying = true, "Local")
            }
        }

        // 2. RECEIVER PILLAR (If local isn't currently playing)
        if (receiverEnabled && receiverUser.isNotEmpty() && (displayTrack == null || !displayTrack.isPlaying)) {
            val np = LastNotifApiClient.getNowPlaying(receiverUser)
            if (np != null && np.isPlaying) {
                displayTrack = PolledTrack(np.title, np.artist, np.album, isPlaying = true, "Receiver")
            }
        }

        // Fallback: If no playing track was found, but local track exists (paused), show it
        if (displayTrack == null && localEnabled && isLocalAllowed) {
            displayTrack = PolledTrack(localTrack.title, localTrack.artist, localTrack.album, isPlaying = false, "Local")
        }

        // 3. SCROBBLE PILLAR (Runs in background if enabled and active)
        if (scrobbleEnabled && sessionKey.isNotEmpty() && localTrack != null && localTrack.isPlaying) {
            if (scrobbleApps.isEmpty() || scrobbleApps.contains(localTrack.packageName)) {
                handleScrobbling(localTrack, sessionKey, scrobblePercent)
            }
        }

        // --- Handle Notifications & Live Track for Display Track ---
        if (displayTrack != null) {
            val trackKey = displayTrack.trackKey()
            handleTrackChange(displayTrack, trackKey)
            handleIntervalAlert(displayTrack)

            var currentLyric = ""
            if (prefs.lyricsEnabled.first() && displayTrack.isPlaying) {
                val lyricsUser = if (displayTrack.pollingMethod == "Receiver") receiverUser else receiverUser.ifEmpty { prefs.lastfmUsername.first().trim() }
                if (lyricsUser.isNotEmpty()) {
                    currentLyric = handleLyrics(displayTrack, trackKey, lyricsUser) ?: ""
                }
            }

            writeActiveTrack(
                displayTrack.title, displayTrack.artist, displayTrack.album,
                displayTrack.isPlaying, currentLyric, displayTrack.pollingMethod
            )
        } else {
            writeActiveTrack("", "", "", false, "", "Idle")
        }
    }

    private data class PolledTrack(
        val title: String,
        val artist: String,
        val album: String,
        val isPlaying: Boolean,
        val pollingMethod: String
    ) {
        fun trackKey() = "$artist - $title"
    }

    private suspend fun handleTrackChange(pt: PolledTrack, trackKey: String) {
        val lastKey = prefs.lastTrackKey.first()
        val notifyOnChange = prefs.notifySongUpdate.first()
        if (pt.isPlaying && trackKey != lastKey) {
            prefs.setLastTrackKey(trackKey)
            cachedLyricsForTrackKey = ""
            cachedLyricLines = null
            lastLyricIndex = -1

            if (notifyOnChange) {
                notifMgr.postSongAlert(
                    pt.title, pt.artist, pt.album,
                    prefs.notifMainFormat.first(),
                    prefs.notifSubFormat.first(),
                    pt.pollingMethod
                )
            }
        }
    }

    private suspend fun handleIntervalAlert(pt: PolledTrack) {
        if (prefs.intervalEnabled.first() && pt.isPlaying) {
            val now = System.currentTimeMillis()
            val lastFired = prefs.lastIntervalAt.first()
            val intervalMs = prefs.intervalMinutes.first() * 60_000L

            if (now - lastFired >= intervalMs) {
                prefs.setLastIntervalAt(now)
                notifMgr.postSongAlert(
                    pt.title, pt.artist, pt.album,
                    prefs.notifMainFormat.first(),
                    prefs.notifSubFormat.first(),
                    pt.pollingMethod
                )
            }
        }
    }

    private fun handleLyrics(pt: PolledTrack, trackKey: String, username: String): String? {
        if (trackKey != cachedLyricsForTrackKey) {
            cachedLyricsForTrackKey = trackKey
            val lr = LastNotifApiClient.getLyrics(username)
            if (lr != null) {
                cachedLyricLines = lr.lines
                cachedSyncStartedAtMs = lr.syncStartedAtMs
                lastLyricIndex = -1
            } else {
                cachedLyricLines = null
                return null
            }
        }

        val lines = cachedLyricLines ?: return null
        val posMs = System.currentTimeMillis() - cachedSyncStartedAtMs
        val activeIndex = findLyricIndex(lines, posMs)

        if (activeIndex >= 0 && activeIndex != lastLyricIndex) {
            lastLyricIndex = activeIndex
            val text = lines[activeIndex].text
            if (text.isNotEmpty()) {
                notifMgr.postLyricAlert(text, pt.title, pt.artist)
            }
            return text
        }
        return if (lastLyricIndex >= 0) lines[lastLyricIndex].text else ""
    }

    private fun findLyricIndex(lines: List<LastNotifApiClient.LyricLine>, posMs: Long): Int {
        var low = 0
        var high = lines.size - 1
        var bestIdx = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timestampMs <= posMs) {
                bestIdx = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return bestIdx
    }

    private fun handleScrobbling(pt: LastNotifMediaMonitor.TrackInfo, sessionKey: String, percentage: Int) {
        val current = currentScrobbleTrack
        if (current == null || pt.trackKey() != current.trackKey()) {
            if (current != null && !scrobbleSubmitted) {
                val playedTime = System.currentTimeMillis() - scrobbleStartTime
                val targetMs = if (current.duration > 0) (current.duration * (percentage / 100.0)).toLong() else 30_000L
                if (playedTime >= targetMs) {
                    LastFmScrobbler.scrobble(current.artist, current.title, current.album, scrobbleStartTime / 1000, sessionKey)
                }
            }
            currentScrobbleTrack = pt
            scrobbleStartTime = System.currentTimeMillis()
            scrobbleSubmitted = false
            LastFmScrobbler.updateNowPlaying(pt.artist, pt.title, pt.album, sessionKey)
        } else {
            // Update duration if it became available after initial playback start
            if (current.duration <= 0 && pt.duration > 0) {
                currentScrobbleTrack = pt
            }
            if (!scrobbleSubmitted) {
                val playedTime = System.currentTimeMillis() - scrobbleStartTime
                val duration = if (pt.duration > 0) pt.duration else current.duration
                val targetMs = if (duration > 0) (duration * (percentage / 100.0)).toLong() else 30_000L
                if (playedTime >= targetMs) {
                    LastFmScrobbler.scrobble(pt.artist, pt.title, pt.album, scrobbleStartTime / 1000, sessionKey)
                    scrobbleSubmitted = true
                }
            }
        }
    }

    private fun writeActiveTrack(title: String, artist: String, album: String, isPlaying: Boolean, lyricLine: String, pollingMethod: String) {
        val now = System.currentTimeMillis()
        _liveTrack.value = if (title.isNotEmpty() || artist.isNotEmpty()) {
            ActiveTrackState(title, artist, album, isPlaying, lyricLine, pollingMethod, now)
        } else null

        val state = JSONObject().apply {
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("isPlaying", isPlaying)
            put("lyricLine", lyricLine)
            put("pollingMethod", pollingMethod)
            put("timestamp", now)
        }
        if (lastWrittenState?.toString() == state.toString()) return
        lastWrittenState = state
        lifecycleScope.launch(Dispatchers.IO) {
            try { FileWriter(File(cacheDir, "active_track.json")).use { it.write(state.toString()) } }
            catch (e: Exception) { Log.e(TAG, "Error writing state", e) }
        }
    }
}
