package io.maru.movies

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Publishes the current player to Android media controls while its playback screen owns it. */
@OptIn(UnstableApi::class)
class MoviesPlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        activeSession?.let(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = activeSession

    override fun onDestroy() {
        if (instance === this) instance = null
        sessions.forEach(::removeSession)
        super.onDestroy()
    }

    companion object {
        private var activeSession: MediaSession? = null
        private var instance: MoviesPlaybackService? = null

        internal fun attach(context: Context, session: MediaSession): Boolean {
            activeSession?.let { instance?.removeSession(it) }
            activeSession = session
            instance?.addSession(session)
            return runCatching {
                context.startService(Intent(context, MoviesPlaybackService::class.java)) != null
            }.getOrDefault(false)
        }

        internal fun detach(context: Context, session: MediaSession) {
            if (activeSession !== session) return
            instance?.removeSession(session)
            activeSession = null
            context.stopService(Intent(context, MoviesPlaybackService::class.java))
        }
    }
}
