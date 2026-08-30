package com.maru.marucast.gaming.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class PcClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getStatus(ipPort: String): Result<PcStatus> = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ipPort/api/status"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val status = gson.fromJson(body, PcStatus::class.java)
            Result.success(status)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDrives(ipPort: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ipPort/api/drives"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val listType = object : TypeToken<List<String>>() {}.type
            val drives: List<String> = gson.fromJson(body, listType)
            Result.success(drives)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun scanDirectory(ipPort: String, dir: String): Result<List<ExecutableItem>> = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ipPort/api/scan?dir=${java.net.URLEncoder.encode(dir, "UTF-8")}"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val listType = object : TypeToken<List<ExecutableItem>>() {}.type
            val items: List<ExecutableItem> = gson.fromJson(body, listType)
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun launchApp(
        ipPort: String,
        path: String,
        japaneseLocale: Boolean,
        asAdmin: Boolean,
        offscreen: Boolean = true,
        scaleFactor: Float = 1.75f
    ): Result<ActiveSession> = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ipPort/api/launch"
            val payload = LaunchRequest(path, japaneseLocale, asAdmin, offscreen, scaleFactor)
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)
            val request = Request.Builder().url(url).post(body).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: "HTTP ${response.code}"
                return@withContext Result.failure(Exception(err))
            }
            val resBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val session = gson.fromJson(resBody, ActiveSession::class.java)
            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun stopApp(ipPort: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ipPort/api/stop"
            val body = "{}".toRequestBody(jsonMediaType)
            val request = Request.Builder().url(url).post(body).build()
            val response = client.newCall(request).execute()
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendInput(
        ipPort: String,
        eventType: String,
        xRatio: Float = 0f,
        yRatio: Float = 0f,
        button: String = "left",
        key: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ipPort/api/input"
            val payload = InputRequest(eventType, xRatio, yRatio, button, key)
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)
            val request = Request.Builder().url(url).post(body).build()
            val response = client.newCall(request).execute()
            Result.success(response.isSuccessful)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRunningWindows(ipPort: String): Result<List<RunningWindowItem>> = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ipPort/api/windows"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
            val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val listType = object : TypeToken<List<RunningWindowItem>>() {}.type
            val windows: List<RunningWindowItem> = gson.fromJson(body, listType)
            Result.success(windows)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun hookWindow(ipPort: String, hwnd: Long, offscreen: Boolean = true): Result<ActiveSession> = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ipPort/api/hook_window"
            val payload = HookWindowRequest(hwnd, offscreen)
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)
            val request = Request.Builder().url(url).post(body).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: "HTTP ${response.code}"
                return@withContext Result.failure(Exception(err))
            }
            val resBody = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
            val session = gson.fromJson(resBody, ActiveSession::class.java)
            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getScreenshotBytes(ipPort: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ipPort/api/screenshot"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.bytes()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
