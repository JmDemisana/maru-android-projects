package io.maru.lastnotif.cast

import android.graphics.Bitmap
import android.media.session.MediaController
import android.os.SystemClock

object CastMediaState {
    var title: String? = null
    var artist: String? = null
    var durationMs: Long = 0L
    var positionMs: Long = 0L
    var lastPositionUpdateTimeMs: Long = 0L
    var playbackSpeed: Float = 1.0f
    var isPlaying: Boolean = false
    var appLabel: String? = null
    var artworkBitmap: Bitmap? = null
    var activeController: MediaController? = null
    
    var onMetadataChanged: (() -> Unit)? = null

    fun triggerUpdate() {
        onMetadataChanged?.invoke()
    }

    fun getEstimatedPosition(): Long {
        if (!isPlaying || lastPositionUpdateTimeMs <= 0) {
            return positionMs
        }
        val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTimeMs
        val extrapolated = positionMs + (elapsed * playbackSpeed).toLong()
        return if (durationMs > 0) extrapolated.coerceIn(0, durationMs) else extrapolated
    }
}
