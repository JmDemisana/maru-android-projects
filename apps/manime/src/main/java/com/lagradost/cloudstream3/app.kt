package com.lagradost.cloudstream3

import com.google.gson.Gson
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

typealias NiceResponse = com.lagradost.nicehttp.NiceResponse

object app {
    var okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        timeout: Long = 20,
        allowRedirects: Boolean = true,
        cacheTime: Int = 0
    ): NiceResponse = withContext(Dispatchers.IO) {
        val httpUrlBuilder = url.toHttpUrlOrNull()?.newBuilder() ?: throw IllegalArgumentException("Invalid url: $url")
        params.forEach { (k, v) -> httpUrlBuilder.addQueryParameter(k, v) }

        val reqBuilder = Request.Builder().url(httpUrlBuilder.build())
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        referer?.let { reqBuilder.addHeader("Referer", it) }
        if (cookies.isNotEmpty()) {
            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            reqBuilder.addHeader("Cookie", cookieHeader)
        }

        val resp = okHttpClient.newCall(reqBuilder.build()).execute()
        NiceResponse(resp)
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        json: Any? = null,
        requestBody: RequestBody? = null,
        timeout: Long = 20,
        allowRedirects: Boolean = true
    ): NiceResponse = withContext(Dispatchers.IO) {
        val httpUrlBuilder = url.toHttpUrlOrNull()?.newBuilder() ?: throw IllegalArgumentException("Invalid url: $url")
        params.forEach { (k, v) -> httpUrlBuilder.addQueryParameter(k, v) }

        val body = when {
            requestBody != null -> requestBody
            json != null -> Gson().toJson(json).toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            else -> {
                val formBody = FormBody.Builder()
                data.forEach { (k, v) -> formBody.add(k, v) }
                formBody.build()
            }
        }

        val reqBuilder = Request.Builder().url(httpUrlBuilder.build()).post(body)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }
        referer?.let { reqBuilder.addHeader("Referer", it) }
        if (cookies.isNotEmpty()) {
            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            reqBuilder.addHeader("Cookie", cookieHeader)
        }

        val resp = okHttpClient.newCall(reqBuilder.build()).execute()
        NiceResponse(resp)
    }
}
