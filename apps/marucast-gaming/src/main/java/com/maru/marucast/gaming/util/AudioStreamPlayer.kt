package com.maru.marucast.gaming.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioStreamPlayer(private val serverUrl: String) {
    private var audioTrack: AudioTrack? = null
    private var streamThread: Thread? = null
    @Volatile
    private var running = false

    fun start() {
        if (running) return
        running = true

        streamThread = Thread {
            try {
                val url = URL("http://$serverUrl/api/audio")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 0
                conn.requestMethod = "GET"
                conn.connect()

                val input = BufferedInputStream(conn.inputStream, 65536)

                // Read header: sample_rate (u32 LE) + channels (u16 LE)
                val header = ByteArray(6)
                var headerRead = 0
                while (headerRead < 6) {
                    val n = input.read(header, headerRead, 6 - headerRead)
                    if (n == -1) break
                    headerRead += n
                }

                if (headerRead < 6) {
                    Log.e(TAG, "Audio header too short: $headerRead bytes")
                    return@Thread
                }

                val sampleRate = ByteBuffer.wrap(header, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val channels = ByteBuffer.wrap(header, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()

                Log.d(TAG, "Audio stream: ${sampleRate}Hz, ${channels}ch")

                val channelConfig = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelConfig,
                    AudioFormat.ENCODING_PCM_FLOAT
                ).coerceAtLeast(3840) // at least 40ms

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack?.play()

                // Read PCM float data in chunks
                val chunkSize = sampleRate * channels / 10 // ~100ms chunks
                val byteChunk = ByteArray(chunkSize * 4) // 4 bytes per float sample
                val floatChunk = FloatArray(chunkSize)

                while (running) {
                    var totalRead = 0
                    while (totalRead < byteChunk.size) {
                        val n = input.read(byteChunk, totalRead, byteChunk.size - totalRead)
                        if (n == -1) break
                        totalRead += n
                    }
                    if (totalRead == 0) break

                    // Convert bytes to floats
                    val samplesRead = totalRead / 4
                    ByteBuffer.wrap(byteChunk, 0, totalRead)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .asFloatBuffer()
                        .get(floatChunk, 0, samplesRead)

                    audioTrack?.write(floatChunk, 0, samplesRead, 1) // WRITE_NON_BLOCK = 1
                }

                conn.disconnect()
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Audio stream error: ${e.message}")
                }
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (_: Exception) {}
                audioTrack = null
            }
        }
        streamThread?.name = "AudioStreamPlayer"
        streamThread?.isDaemon = true
        streamThread?.start()
    }

    fun stop() {
        running = false
        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        streamThread?.join(1000)
        streamThread = null
        audioTrack = null
    }

    companion object {
        private const val TAG = "AudioStreamPlayer"
    }
}
