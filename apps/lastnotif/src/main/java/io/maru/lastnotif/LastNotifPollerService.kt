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
                delay(3000L)
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

        val localTrack = LastNotifMediaMonitor.getActiveTrack(this, localApps)
        val scrobbleTrack = if (scrobbleEnabled && sessionKey.isNotEmpty()) {
            LastNotifMediaMonitor.getActiveTrack(this, scrobbleApps)
        } else null

        var displayTrack: PolledTrack? = null

        // 1. LOCAL PILLAR
        if (localEnabled && localTrack != null) {
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
        if (displayTrack == null && localEnabled && localTrack != null) {
            displayTrack = PolledTrack(localTrack.title, localTrack.artist, localTrack.album, isPlaying = false, "Local")
        }

        // 3. SCROBBLE PILLAR (Runs in background if enabled and active)
        if (scrobbleEnabled && sessionKey.isNotEmpty() && scrobbleTrack != null && scrobbleTrack.isPlaying) {
            handleScrobbling(scrobbleTrack, sessionKey, scrobblePercent)
        }

        // --- Handle Notifications & Live Track for Display Track ---
        if (displayTrack != null) {
            val trackKey = displayTrack.trackKey()
            handleTrackChange(displayTrack, trackKey)
            handleIntervalAlert(displayTrack)

            writeActiveTrack(
                displayTrack.title, displayTrack.artist, displayTrack.album,
                displayTrack.isPlaying, displayTrack.pollingMethod
            )
        } else {
            writeActiveTrack("", "", "", false, "Idle")
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

    private fun writeActiveTrack(title: String, artist: String, album: String, isPlaying: Boolean, pollingMethod: String) {
        val now = System.currentTimeMillis()
        _liveTrack.value = if (title.isNotEmpty() || artist.isNotEmpty()) {
            ActiveTrackState(title, artist, album, isPlaying, pollingMethod, now)
        } else null

        val state = JSONObject().apply {
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("isPlaying", isPlaying)
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
