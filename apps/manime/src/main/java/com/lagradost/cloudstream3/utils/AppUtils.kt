package com.lagradost.cloudstream3.utils

import com.google.gson.Gson

object AppUtils {
    val gson = Gson()

    fun toJson(any: Any?): String = gson.toJson(any)

    inline fun <reified T> parseJson(json: String): T = gson.fromJson(json, T::class.java)

    inline fun <reified T> tryParseJson(json: String?): T? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, T::class.java)
        } catch (_: Exception) {
            null
        }
    }
}
