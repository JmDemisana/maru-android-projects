package io.maru.manime.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import kotlinx.coroutines.*
import java.io.File
import java.io.FileInputStream
import java.net.ServerSocket
import java.net.Socket

class TorrentStreamService : Service() {

    companion object {
        const val ACTION_START_TORRENT = "io.maru.manime.action.START_TORRENT"
        const val ACTION_STOP_TORRENT = "io.maru.manime.action.STOP_TORRENT"
        const val EXTRA_MAGNET = "extra_magnet"

        const val NOTIFICATION_ID = 9002
        const val CHANNEL_ID = "manime_torrent_channel"
        const val STREAM_PORT = 19876

        var currentStreamUrl: String? = null
            private set
        var isStreaming: Boolean = false
            private set

        fun start(context: Context, magnetUri: String) {
            val intent = Intent(context, TorrentStreamService::class.java).apply {
                action = ACTION_START_TORRENT
                putExtra(EXTRA_MAGNET, magnetUri)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TorrentStreamService::class.java).apply {
                action = ACTION_STOP_TORRENT
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sessionManager: SessionManager? = null
    private var serverSocket: ServerSocket? = null
    private var targetFile: File? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing torrent engine..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TORRENT -> {
                val magnet = intent.getStringExtra(EXTRA_MAGNET)
                if (!magnet.isNullOrEmpty()) {
                    startTorrentEngine(magnet)
                }
            }
            ACTION_STOP_TORRENT -> {
                stopTorrentEngine()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTorrentEngine(magnetUri: String) {
        serviceScope.launch {
            try {
                updateNotification("Starting session...")
                val saveDir = File(cacheDir, "torrent_stream").apply { if (!exists()) mkdirs() }

                sessionManager = SessionManager().apply {
                    start()
                }

                updateNotification("Fetching torrent metadata...")
                val rawData = sessionManager?.fetchMagnet(magnetUri, 30, saveDir)
                if (rawData != null) {
                    val tInfo = TorrentInfo.bdecode(rawData)
                    sessionManager?.download(tInfo, saveDir)
                    targetFile = File(saveDir, tInfo.files().filePath(0))
                }

                // Start local HTTP server to feed ExoPlayer
                startLocalHttpServer()

                currentStreamUrl = "http://127.0.0.1:$STREAM_PORT/stream"
                isStreaming = true
                updateNotification("Streaming P2P video on localhost")
            } catch (e: Exception) {
                android.util.Log.e("TorrentService", "Error starting torrent", e)
                updateNotification("Torrent stream error: ${e.localizedMessage}")
            }
        }
    }

    private fun startLocalHttpServer() {
        try {
            serverSocket?.close()
            serverSocket = ServerSocket(STREAM_PORT)
            serviceScope.launch {
                while (isActive && serverSocket?.isClosed == false) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        handleClientSocket(socket)
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TorrentService", "HTTP Server Error", e)
        }
    }

    private fun handleClientSocket(socket: Socket) {
        serviceScope.launch {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val reader = input.bufferedReader()
                val requestLine = reader.readLine() ?: return@launch

                val file = targetFile
                if (file != null && file.exists()) {
                    val length = file.length()
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: video/mp4\r\n" +
                            "Content-Length: $length\r\n" +
                            "Accept-Ranges: bytes\r\n" +
                            "Connection: close\r\n\r\n"
                    output.write(header.toByteArray())

                    val fileIn = FileInputStream(file)
                    val buffer = ByteArray(32 * 1024)
                    var read: Int
                    while (fileIn.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    fileIn.close()
                } else {
                    val notFound = "HTTP/1.1 404 Not Found\r\n\r\n"
                    output.write(notFound.toByteArray())
                }
                output.flush()
                socket.close()
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun stopTorrentEngine() {
        isStreaming = false
        currentStreamUrl = null
        try {
            serverSocket?.close()
            sessionManager?.stop()
        } catch (e: Exception) {}
        serviceScope.cancel()
    }

    override fun onDestroy() {
        stopTorrentEngine()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Torrent Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MAnime Torrent Engine")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
