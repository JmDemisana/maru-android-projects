package com.maru.namispace.ai

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.maru.namispace.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Direct Gemini REST API client using OkHttp.
 * No Firebase SDK dependency needed — just a simple HTTP call.
 */
class GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val apiKey = BuildConfig.GEMINI_API_KEY
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"

    private val systemInstruction = mapOf(
        "parts" to listOf(mapOf("text" to SystemPrompt.core))
    )

    suspend fun chat(
        message: String,
        history: List<ChatTurn> = emptyList(),
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val contents = buildList {
                history.takeLast(20).forEach { turn ->
                    add(mapOf(
                        "role" to if (turn.isUser) "user" else "model",
                        "parts" to listOf(mapOf("text" to turn.text)),
                    ))
                }
                add(mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to message)),
                ))
            }

            val body = mapOf(
                "contents" to contents,
                "systemInstruction" to systemInstruction,
                "generationConfig" to mapOf(
                    "temperature" to 0.8,
                    "topP" to 0.95,
                    "topK" to 40,
                    "maxOutputTokens" to 2048,
                ),
            )

            val json = gson.toJson(body)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = json.toRequestBody(mediaType)

            val request = Request.Builder()
                .url("$baseUrl?key=$apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("API error ${response.code}: $responseBody")
                )
            }

            val jsonResponse = JsonParser.parseString(responseBody).asJsonObject
            val candidates = jsonResponse.getAsJsonArray("candidates")
            if (candidates == null || candidates.size() == 0) {
                return@withContext Result.failure(Exception("No response from Nami"))
            }

            val text = candidates[0].asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts")[0]
                .asJsonObject
                .get("text")
                .asString

            if (text.isBlank()) {
                Result.failure(Exception("Nami couldn't find the right words..."))
            } else {
                Result.success(text)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    data class ChatTurn(
        val text: String,
        val isUser: Boolean,
    )
}
