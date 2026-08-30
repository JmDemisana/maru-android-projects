package com.maru.marucast.gaming

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.maru.marucast.gaming.data.PcClient
import com.maru.marucast.gaming.ui.GamingScreen

class MainActivity : ComponentActivity() {
    private val pcClient = PcClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GamingScreen(client = pcClient)
        }
    }
}
