package com.maru.namispace.ai

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
import java.io.File

/**
 * Foreground Service that maintains the on-device local neural AI engine alive in memory.
 * Prevents Android OS from killing the local neural runtime.
 */
class NamiNeuralService : Service() {

    companion object {
        private const val TAG = "NamiNeuralService"
        private const val CHANNEL_ID = "nami_neural_channel"
        private const val NOTIF_ID = 7788

        fun start(context: Context) {
            val intent = Intent(context, NamiNeuralService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var serverProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, createNotification())
        startEngine()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nami Neural Brain",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Nanami's local on-device neural AI active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nanami Shiro")
            .setContentText("Local Neural Brain active (100% Offline)")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun startEngine() {
        Thread {
            try {
                val binPath = "/data/local/tmp/llama/llama-b10672/llama-server"
                val modelPath = "/data/local/tmp/llama/qwen2.5-3b-instruct-q4_k_m.gguf"
                val fallbackModel = "/data/local/tmp/llama/qwen2.5-1.5b-instruct-q4_k_m.gguf"
                val targetModel = if (File(modelPath).exists()) modelPath else fallbackModel

                Log.i(TAG, "Starting on-device llama-server with $targetModel...")
                val pb = ProcessBuilder(
                    binPath,
                    "-m", targetModel,
                    "--host", "127.0.0.1",
                    "--port", "8088",
                    "-c", "2048",
                    "-t", "4",
                    "--no-webui"
                )
                pb.environment()["LD_LIBRARY_PATH"] = "/data/local/tmp/llama/llama-b10672"
                pb.redirectErrorStream(true)
                serverProcess = pb.start()
                Log.i(TAG, "llama-server started successfully in background service!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch llama-server in service", e)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        serverProcess?.destroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
