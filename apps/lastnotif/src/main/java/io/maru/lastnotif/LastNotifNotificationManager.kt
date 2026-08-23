package io.maru.lastnotif

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LastNotifNotificationManager(private val context: Context) {
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    private val prefs = LastNotifPrefs(context)

    companion object {
        private const val TAG = "LastNotifNotifications"
        const val CHANNEL_ALERTS = "lastnotif_alerts"
        const val CHANNEL_KEEPALIVE = "lastnotif_keepalive"
        const val ID_ALERT = 13001
        const val ID_KEEPALIVE = 13000

        fun applyFormat(fmt: String, title: String, artist: String, album: String, pollingMethod: String): String {
            if (fmt.isBlank()) return title
            val resolvedSource = when {
                pollingMethod.isNotEmpty() -> pollingMethod
                else -> "Media Player"
            }
            return fmt
                .replace("{song_name}", title, ignoreCase = true)
                .replace("{title}", title, ignoreCase = true)
                .replace("{track}", title, ignoreCase = true)
                .replace("{song}", title, ignoreCase = true)
                .replace("{artist}", artist, ignoreCase = true)
                .replace("{album}", album, ignoreCase = true)
                .replace("{source}", resolvedSource, ignoreCase = true)
                .replace("{media_player}", resolvedSource, ignoreCase = true)
                .replace("{player}", resolvedSource, ignoreCase = true)
                .replace("{polling_method}", resolvedSource, ignoreCase = true)
        }
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        if (nm == null) {
            Log.w(TAG, "NotificationManager unavailable; channels not created.")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val alerts = NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.notif_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.notif_channel_alerts_desc)
                enableVibration(true)
                setShowBadge(true)
            }

            val keepalive = NotificationChannel(
                CHANNEL_KEEPALIVE,
                context.getString(R.string.notif_channel_keepalive),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = context.getString(R.string.notif_channel_keepalive_desc)
                enableVibration(false)
                setShowBadge(false)
                setSound(null, null)
            }

            try {
                nm.createNotificationChannel(alerts)
                nm.createNotificationChannel(keepalive)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create notification channels.", e)
            }
        }
    }

    fun buildKeepaliveNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_launcher_lastnotif_monochrome)
            .setContentTitle(context.getString(R.string.notif_keepalive_title))
            .setContentText(context.getString(R.string.notif_keepalive_text))
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun postSongAlert(title: String, artist: String, album: String,
                      mainFmt: String, subFmt: String, pollingMethod: String) {
        val mainText = applyFormat(mainFmt, title, artist, album, pollingMethod)
        val subText = applyFormat(subFmt, title, artist, album, pollingMethod)
        Log.d(TAG, "Posting song alert: $mainText / $subText")
        post(mainText, subText, pollingMethod)
    }

    fun postTestAlert() {
        post("LastNotif Connected", "Smart band notification sync is working!", "Test")
    }

    fun sendAlert(title: String, body: String) {
        post(title, body, "Test")
    }

    private fun post(main: String, sub: String, source: String = "Alert") {
        val nm = nm ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                prefs.setLastAlert(main, sub, source)
            } catch (_: Exception) {}
        }

        val tapIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_lastnotif_monochrome)
            .setContentTitle(main)
            .setContentText(sub)
            .setContentIntent(tapIntent)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup("LASTNOTIF_ALERTS")
            .build()

        nm.notify(ID_ALERT, n)
    }
}
