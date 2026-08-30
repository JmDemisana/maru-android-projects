package com.maru.namispace.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads and verifies the on-device quantized model for Nami's local brain.
 */
class NamiModelDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progress: Float, val bytesRead: Long, val totalBytes: Long) : DownloadState()
        object Completed : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    val modelDir: File
        get() = File(context.filesDir, NamiOnDeviceLlm.MODEL_DIR).apply { if (!exists()) mkdirs() }

    val targetFile: File
        get() = File(modelDir, NamiOnDeviceLlm.MODEL_FILENAME)

    suspend fun downloadModel(
        modelUrl: String,
        hfToken: String? = null,
        onProgress: (DownloadState) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder().url(modelUrl)
            if (!hfToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer ${hfToken.trim()}")
            }
            val request = reqBuilder.build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val err = "Download failed with HTTP ${response.code}"
                onProgress(DownloadState.Error(err))
                return@withContext Result.failure(Exception(err))
            }

            val body = response.body ?: run {
                val err = "Empty response body"
                onProgress(DownloadState.Error(err))
                return@withContext Result.failure(Exception(err))
            }

            val totalBytes = body.contentLength()
            val tempFile = File(modelDir, "${NamiOnDeviceLlm.MODEL_FILENAME}.tmp")

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Long = 0
                    var read: Int

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        val progress = if (totalBytes > 0) bytesRead.toFloat() / totalBytes else 0f
                        onProgress(DownloadState.Downloading(progress, bytesRead, totalBytes))
                    }
                    output.flush()
                }
            }

            // Rename / copy temp to final
            try {
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (e: Exception) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            onProgress(DownloadState.Completed)
            Result.success(targetFile)
        } catch (e: Exception) {
            onProgress(DownloadState.Error(e.localizedMessage ?: "Download error"))
            Result.failure(e)
        }
    }
}
