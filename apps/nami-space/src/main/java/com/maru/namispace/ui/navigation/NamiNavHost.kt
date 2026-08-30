package com.maru.namispace.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.maru.namispace.engine.BackgroundMusic
import com.maru.namispace.engine.GameManager
import com.maru.namispace.ui.screens.HomeScreen

@Composable
fun NamiNavHost(
    modifier: Modifier = Modifier,
    gameManager: GameManager,
) {
    val session by gameManager.state.collectAsState()

    BackgroundMusic(muted = session.bgmMuted)

    HomeScreen(gameManager = gameManager)
}
