package com.maru.marucast.gaming.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class NativeStreamRecorder(private val context: Context) {
    private var mediaCodec: MediaCodec? = null
    private var mediaMuxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var trackIndex = -1
    private var isMuxerStarted = false
    private var tempFile: File? = null
    private var width = 1920
    private var height = 1080
    private var frameIndex = 0L
    var isRecording = false
        private set

    fun startRecording(targetWidth: Int, targetHeight: Int, fps: Int = 30): Boolean {
        return try {
            width = if (targetWidth % 2 == 0) targetWidth else targetWidth - 1
            height = if (targetHeight % 2 == 0) targetHeight else targetHeight - 1
            if (width <= 0) width = 1920
            if (height <= 0) height = 1080

            val mime = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            mediaCodec = MediaCodec.createEncoderByType(mime)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mediaCodec?.createInputSurface()
            mediaCodec?.start()

            tempFile = File(context.cacheDir, "marucast_rec_${System.currentTimeMillis()}.mp4")
            mediaMuxer = MediaMuxer(tempFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            trackIndex = -1
            isMuxerStarted = false
            frameIndex = 0L
            isRecording = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            stopSync()
            false
        }
    }

    fun recordFrame(bitmap: Bitmap) {
        if (!isRecording || inputSurface == null || mediaCodec == null) return
        try {
            val surface = inputSurface ?: return
            val canvas = surface.lockHardwareCanvas()
            val src = Rect(0, 0, bitmap.width, bitmap.height)
            val dst = Rect(0, 0, width, height)
            canvas.drawBitmap(bitmap, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            surface.unlockCanvasAndPost(canvas)
            drainEncoder(false)
            frameIndex++
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val codec = mediaCodec ?: return
        val muxer = mediaMuxer ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        if (endOfStream) {
            try {
                codec.signalEndOfInputStream()
            } catch (_: Exception) {}
        }

        while (true) {
            val encoderStatus = codec.dequeueOutputBuffer(bufferInfo, 10000)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (isMuxerStarted) break
                val newFormat = codec.outputFormat
                trackIndex = muxer.addTrack(newFormat)
                muxer.start()
                isMuxerStarted = true
            } else if (encoderStatus >= 0) {
                val encodedData = codec.getOutputBuffer(encoderStatus) ?: continue
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }
                if (bufferInfo.size != 0 && isMuxerStarted) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                }
                codec.releaseOutputBuffer(encoderStatus, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun stopSync() {
        isRecording = false
        try {
            drainEncoder(true)
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (_: Exception) {}
        mediaCodec = null

        try {
            if (isMuxerStarted) {
                mediaMuxer?.stop()
            }
            mediaMuxer?.release()
        } catch (_: Exception) {}
        mediaMuxer = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {}
        inputSurface = null
    }

    suspend fun stopRecording(sessionName: String?): String? = withContext(Dispatchers.IO) {
        if (!isRecording) return@withContext null
        stopSync()

        try {
            val savedFile = tempFile
            if (savedFile != null && savedFile.exists() && savedFile.length() > 0) {
                val cleanName = (sessionName ?: "VisualNovel").replace(" ", "_")
                val filename = "Marucast_${cleanName}_${System.currentTimeMillis()}.mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Marucast")
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        savedFile.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    savedFile.delete()
                    return@withContext filename
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        null
    }
}
