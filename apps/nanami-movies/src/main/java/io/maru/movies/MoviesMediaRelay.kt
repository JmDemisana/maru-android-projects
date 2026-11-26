package io.maru.movies

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.maru.manime.torrent.parseByteRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Presents one immutable URL to the clip transformer or the user's Cast receiver. */
@OptIn(UnstableApi::class)
internal class MoviesMediaRelay private constructor(
    private val socket: ServerSocket,
    private val factory: DataSource.Factory,
    private val media: Uri,
    private val length: Long,
    val mime: String,
    extension: String,
) : AutoCloseable {
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val permits = Semaphore(6)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val path = "/${UUID.randomUUID()}/video.$extension"
    val url = "http://${socket.inetAddress.hostAddress}:${socket.localPort}$path"

    init {
        scope.launch {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                if (!permits.tryAcquire()) {
                    client.close()
                    continue
                }
                clients.add(client)
                if (socket.isClosed) {
                    clients.remove(client)
                    client.close()
                    permits.release()
                    break
                }
                scope.launch {
                    try {
                        client.use { connection ->
                            connection.soTimeout = 5_000
                            respond(connection.getInputStream().buffered(), connection.getOutputStream().buffered())
                        }
                    } catch (_: Exception) {
                        // Receivers routinely close an active request early when seeking.
                    } finally {
                        clients.remove(client)
                        permits.release()
                    }
                }
            }
        }
    }

    private fun respond(input: InputStream, output: OutputStream) {
        val request = input.readLineLimited()?.trim()?.split(' ') ?: return
        if (request.size != 3) return
        val (method, target) = request
        var rangeHeader: String? = null
        for (index in 0..40) {
            val line = input.readLineLimited() ?: return
            if (line.isEmpty()) break
            if (index == 40) return
            if (line.substringBefore(':').equals("Range", true)) rangeHeader = line.substringAfter(':').trim()
        }
        if (target != path) {
            headers(output, "404 Not Found", "Content-Length: 0")
            return
        }
        if (method == "OPTIONS") {
            headers(output, "204 No Content", "Content-Length: 0", "Access-Control-Allow-Methods: GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers: Range, Content-Type")
            return
        }
        if (method != "GET" && method != "HEAD") {
            headers(output, "405 Method Not Allowed", "Content-Length: 0")
            return
        }
        val range = parseByteRange(rangeHeader, length)
        if (range == null) {
            headers(output, "416 Range Not Satisfiable", "Content-Range: bytes */$length", "Content-Length: 0")
            return
        }
        val fields = mutableListOf("Content-Type: $mime", "Content-Length: ${range.length}", "Accept-Ranges: bytes")
        val status = if (rangeHeader == null) "200 OK" else "206 Partial Content".also {
            fields += "Content-Range: bytes ${range.start}-${range.end}/$length"
        }
        if (method == "HEAD") {
            headers(output, status, *fields.toTypedArray())
            return
        }
        val source = factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(media).setPosition(range.start).setLength(range.length).build())
            headers(output, status, *fields.toTypedArray())
            val buffer = ByteArray(64 * 1024)
            var remaining = range.length
            while (remaining > 0 && !socket.isClosed) {
                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.flush()
        } finally {
            source.close()
        }
    }

    private fun headers(output: OutputStream, status: String, vararg fields: String) {
        val response = (listOf("HTTP/1.1 $status", "Connection: close", "Access-Control-Allow-Origin: *",
            "Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges") + fields).joinToString("\r\n", postfix = "\r\n\r\n")
        output.write(response.toByteArray(Charsets.US_ASCII))
        output.flush()
    }

    private fun InputStream.readLineLimited(): String? {
        val line = StringBuilder()
        while (line.length < 4_096) {
            val byte = read()
            if (byte == -1) return null
            if (byte == 10) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
        return null
    }

    override fun close() {
        runCatching { socket.close() }
        clients.forEach { runCatching { it.close() } }
        scope.cancel()
    }

    companion object {
        @Suppress("DEPRECATION")
        private fun lanAddress(context: Context): Inet4Address? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            return cm.allNetworks.asSequence().filter { network ->
                val capabilities = cm.getNetworkCapabilities(network)
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                    capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            }.flatMap { cm.getLinkProperties(it)?.linkAddresses.orEmpty().asSequence() }
                .map { it.address }.filterIsInstance<Inet4Address>().firstOrNull { !it.isLoopbackAddress }
        }

        suspend fun start(context: Context, factory: DataSource.Factory, media: Uri, lan: Boolean): MoviesMediaRelay =
            withContext(Dispatchers.IO) {
                val address = if (lan) requireNotNull(lanAddress(context)) { "Connect your phone and TV to the same Wi-Fi." }
                    else InetAddress.getByName("127.0.0.1")
                val source = factory.createDataSource()
                val length = try { source.open(DataSpec(media)) } finally { source.close() }
                check(length > 0) { "The stream doesn't have a known length." }
                val extension = media.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
                    ?.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "mp4"
                val server = ServerSocket()
                try {
                    server.bind(InetSocketAddress(address, 0))
                    MoviesMediaRelay(server, factory, media, length, mediaMime(extension), extension)
                } catch (error: Exception) {
                    server.close()
                    throw error
                }
            }

        fun mediaMime(extension: String): String = when (extension.lowercase()) {
            "m3u", "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "avi" -> "video/x-msvideo"
            else -> "video/mp4"
        }
    }
}
