package io.maru.lastnotif

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log

object LastNotifMediaMonitor {
    private const val TAG = "LastNotifMediaMonitor"

    data class TrackInfo(
        val title: String,
        val artist: String,
        val album: String,
        val duration: Long,
        val isPlaying: Boolean,
        val packageName: String
    ) {
        fun trackKey() = "$artist - $title"
    }

    fun isNotificationAccessGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat?.contains(context.packageName) == true
    }

    fun hasPostNotificationsPermission(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun getActiveTrack(context: Context): TrackInfo? {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return null
        if (!isNotificationAccessGranted(context)) return null

        return try {
            val cn = ComponentName(context, LastNotifMediaListenerService::class.java)
            val controllers = manager.getActiveSessions(cn)
            if (controllers.isNullOrEmpty()) return null

            // 1. First look for an actively PLAYING controller with valid track metadata
            for (controller in controllers) {
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                    val info = getTrackInfoFromController(controller)
                    if (info != null) return info
                }
            }

            // 2. Fallback to any controller with valid metadata (e.g. paused)
            for (controller in controllers) {
                val info = getTrackInfoFromController(controller)
                if (info != null) return info
            }

            null
        } catch (se: SecurityException) {
            Log.w(TAG, "Notification access permission revoked", se)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Exception in getActiveTrack", e)
            null
        }
    }

    private fun getTrackInfoFromController(controller: MediaController): TrackInfo? {
        val metadata = controller.metadata ?: return null
        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING

        var title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        var artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (title.isNullOrBlank()) {
            title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        }
        if (artist.isNullOrBlank()) {
            artist = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        }
        if (artist.isNullOrBlank()) {
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        }

        val cleanTitle = title?.trim() ?: ""
        val cleanArtist = artist?.trim() ?: ""
        val cleanAlbum = album.trim()

        return if (cleanTitle.isNotEmpty() || cleanArtist.isNotEmpty()) {
            TrackInfo(
                title = cleanTitle,
                artist = cleanArtist,
                album = cleanAlbum,
                duration = if (duration > 0) duration else 0L,
                isPlaying = isPlaying,
                packageName = controller.packageName ?: ""
            )
        } else null
    }
}
