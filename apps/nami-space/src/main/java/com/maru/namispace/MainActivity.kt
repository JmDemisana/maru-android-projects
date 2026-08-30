package com.maru.namispace

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.maru.namispace.engine.GameManager
import com.maru.namispace.ui.navigation.NamiNavHost
import com.maru.namispace.ui.theme.NamiSpaceTheme

class MainActivity : ComponentActivity() {

    // Keep a reference so we can call lifecycle methods
    private lateinit var gameManager: GameManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        gameManager = GameManager(this)
        setContent {
            NamiSpaceTheme {
                NamiNavHost(
                    modifier = Modifier.fillMaxSize(),
                    gameManager = gameManager,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::gameManager.isInitialized) {
            gameManager.onAppResume()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::gameManager.isInitialized) {
            gameManager.onAppPause()
        }
    }
}
