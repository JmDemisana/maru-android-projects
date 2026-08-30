package com.maru.namispace.engine

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.maru.namispace.R

@Composable
fun BackgroundMusic(muted: Boolean = false) {
    val context = LocalContext.current
    val player = remember {
        try {
            MediaPlayer.create(context, R.raw.mochi_bounce)?.apply {
                isLooping = true
                setVolume(if (muted) 0f else 0.4f, if (muted) 0f else 0.4f)
            }
        } catch (_: Exception) {
            null
        }
    }

    LaunchedEffect(muted) {
        try {
            player?.setVolume(if (muted) 0f else 0.4f, if (muted) 0f else 0.4f)
        } catch (_: Exception) {
        }
    }

    DisposableEffect(Unit) {
        try {
            player?.start()
        } catch (_: Exception) {
        }
        onDispose {
            try {
                player?.stop()
                player?.release()
            } catch (_: Exception) {
            }
        }
    }
}
