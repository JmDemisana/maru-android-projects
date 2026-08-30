package com.lagradost.nicehttp

import com.google.gson.Gson
import com.lagradost.cloudstream3.app
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class Requests(val customClient: OkHttpClient? = null) {
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        timeout: Long = 20,
        allowRedirects: Boolean = true,
        cacheTime: Int = 0
    ): NiceResponse = app.get(url, headers, referer, params, cookies, timeout, allowRedirects, cacheTime)

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
    ): NiceResponse = app.post(url, headers, referer, params, cookies, data, json, requestBody, timeout, allowRedirects)
}

open class NiceResponse(val raw: Response) {
    val text: String by lazy { raw.body?.string() ?: "" }
    val body: String get() = text
    val code: Int get() = raw.code
    val isSuccessful: Boolean get() = raw.isSuccessful
    val url: String get() = raw.request.url.toString()
    val headers: Headers get() = raw.headers
    val okhttpResponse: Response get() = raw
    val document: Document by lazy { Jsoup.parse(text, url) }

    inline fun <reified T> parsed(): T? {
        return try {
            Gson().fromJson(text, T::class.java)
        } catch (_: Exception) {
            null
        }
    }

    inline fun <reified T> parsedSafe(): T? {
        return try {
            Gson().fromJson(text, T::class.java)
        } catch (_: Exception) {
            null
        }
    }
}
