package io.maru.manime.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PlayerForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 9001
        const val CHANNEL_ID = "manime_player_channel"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_EPISODE = "extra_episode"

        private val _playerOpen = MutableStateFlow(false)
        internal val playerOpen = _playerOpen.asStateFlow()

        fun start(context: Context, title: String, episode: Int) {
            _playerOpen.value = true
            val intent = Intent(context, PlayerForegroundService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_EPISODE, episode)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                Log.w("PlayerForegroundService", "Could not show playback notification", e)
            }
        }

        fun stop(context: Context) {
            _playerOpen.value = false
            val intent = Intent(context, PlayerForegroundService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Anime Playback"
        val episode = intent?.getIntExtra(EXTRA_EPISODE, 1) ?: 1

        val notification = buildNotification(title, episode)
        startForeground(NOTIFICATION_ID, notification)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Anime Player",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows now playing anime info in the notification shade"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, episode: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Episode $episode • Playing in Nanami Anime")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
