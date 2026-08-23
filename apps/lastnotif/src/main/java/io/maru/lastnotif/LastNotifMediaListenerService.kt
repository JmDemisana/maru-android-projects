package io.maru.lastnotif

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.util.Log
import io.maru.lastnotif.cast.CastMediaState

/**
 * Service to register the app as a notification listener.
 * This binds the app to Android's media session callbacks and synchronizes
 * active playback state to CastMediaState for Marucast broadcasting.
 */
class LastNotifMediaListenerService : NotificationListenerService() {
    private val TAG = "LastNotifMediaListener"
    private lateinit var mediaSessionManager: MediaSessionManager
    
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateMetadata(CastMediaState.activeController)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updatePlaybackState(state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        updateActiveSessions()
        
        mediaSessionManager.addOnActiveSessionsChangedListener(
            { controllers ->
                Log.d(TAG, "Active sessions changed, count: ${controllers?.size ?: 0}")
                updateActiveSessions()
            },
            ComponentName(this, LastNotifMediaListenerService::class.java)
        )
    }

    private fun updateActiveSessions() {
        try {
            val controllers = mediaSessionManager.getActiveSessions(
                ComponentName(this, LastNotifMediaListenerService::class.java)
            )
            val firstPlaying = controllers.find { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            val controller = firstPlaying ?: controllers.firstOrNull()
            
            if (controller != CastMediaState.activeController) {
                CastMediaState.activeController?.unregisterCallback(controllerCallback)
                CastMediaState.activeController = controller
                controller?.registerCallback(controllerCallback)
                updateMetadata(controller)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: Notification Access might have been revoked.", e)
        } catch (e: Exception) {
            Log.w(TAG, "Error updating active sessions", e)
        }
    }

    private fun updateMetadata(controller: MediaController?) {
        if (controller == null) {
            CastMediaState.title = null
            CastMediaState.artist = null
            CastMediaState.durationMs = 0L
            CastMediaState.positionMs = 0L
            CastMediaState.isPlaying = false
            CastMediaState.appLabel = null
            CastMediaState.artworkBitmap = null
            CastMediaState.triggerUpdate()
            return
        }

        val metadata = controller.metadata
        val playbackState = controller.playbackState

        CastMediaState.title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        CastMediaState.artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        CastMediaState.durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        CastMediaState.appLabel = getAppNameFromPackage(controller.packageName)

        var art: Bitmap? = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        if (art == null) {
            art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }
        CastMediaState.artworkBitmap = art

        updatePlaybackState(playbackState)
    }

    private fun updatePlaybackState(state: PlaybackState?) {
        if (state == null) {
            CastMediaState.isPlaying = false
            CastMediaState.positionMs = 0L
            CastMediaState.lastPositionUpdateTimeMs = 0L
            CastMediaState.playbackSpeed = 0f
        } else {
            CastMediaState.isPlaying = state.state == PlaybackState.STATE_PLAYING
            CastMediaState.positionMs = state.position
            CastMediaState.lastPositionUpdateTimeMs = state.lastPositionUpdateTime
            CastMediaState.playbackSpeed = state.playbackSpeed
        }
        CastMediaState.triggerUpdate()
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName.split(".").lastOrNull() ?: packageName
        }
    }
}
