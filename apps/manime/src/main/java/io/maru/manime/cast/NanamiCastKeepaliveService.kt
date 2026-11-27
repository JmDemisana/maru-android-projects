package io.maru.manime.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Holds a partial CPU wake lock and high-performance Wi-Fi lock in the foreground
 * while casting a downloaded video from the phone's local HTTP vault to a TV.
 */
class NanamiCastKeepaliveService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_KEEPALIVE -> {
                onStopRequested?.invoke()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_KEEPALIVE, null -> {
                val appTitle = intent?.getStringExtra(EXTRA_APP_TITLE).orEmpty().ifBlank { "Nanami" }
                val mediaTitle = intent?.getStringExtra(EXTRA_MEDIA_TITLE).orEmpty().ifBlank { "Downloaded video" }
                val deviceName = intent?.getStringExtra(EXTRA_DEVICE_NAME).orEmpty().ifBlank { "TV" }
                acquireLocks()
                startForeground(NOTIFICATION_ID, buildNotification(appTitle, mediaTitle, deviceName))
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nanami:CastKeepalive")?.apply {
                setReferenceCounted(false)
                acquire(6L * 60L * 60L * 1000L)
            }
        }
        if (wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(lockMode, "nanami:CastWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Chromecast Local Relay",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Keeps downloaded video casting smoothly to your TV"
                },
            )
        }
    }

    private fun buildNotification(appTitle: String, mediaTitle: String, deviceName: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(packageName)
        val contentIntent = PendingIntent.getActivity(this, 0, launchIntent, flags)
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NanamiCastKeepaliveService::class.java).setAction(ACTION_STOP_KEEPALIVE),
            flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Casting to $deviceName")
            .setContentText("$mediaTitle • $appTitle")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop Casting", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "NanamiCastKeepalive"
        private const val CHANNEL_ID = "nanami_cast_keepalive"
        private const val NOTIFICATION_ID = 8041
        private const val ACTION_START_KEEPALIVE = "io.maru.nanami.action.START_CAST_KEEPALIVE"
        private const val ACTION_STOP_KEEPALIVE = "io.maru.nanami.action.STOP_CAST_KEEPALIVE"
        private const val EXTRA_APP_TITLE = "extra_app_title"
        private const val EXTRA_MEDIA_TITLE = "extra_media_title"
        private const val EXTRA_DEVICE_NAME = "extra_device_name"

        @Volatile
        internal var onStopRequested: (() -> Unit)? = null

        fun start(context: Context, appTitle: String, mediaTitle: String, deviceName: String) {
            val intent = Intent(context, NanamiCastKeepaliveService::class.java).apply {
                action = ACTION_START_KEEPALIVE
                putExtra(EXTRA_APP_TITLE, appTitle)
                putExtra(EXTRA_MEDIA_TITLE, mediaTitle)
                putExtra(EXTRA_DEVICE_NAME, deviceName)
            }
            try {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start cast keepalive service", e)
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(Intent(context, NanamiCastKeepaliveService::class.java))
            }
        }
    }
}
