package com.maru.marucast.gaming.data

import com.google.gson.annotations.SerializedName

data class PcStatus(
    val status: String,
    val service: String,
    val version: String,
    val hostname: String,
    @SerializedName("active_session") val activeSession: ActiveSession?
)

data class ExecutableItem(
    val name: String,
    val path: String,
    @SerializedName("is_dir") val isDir: Boolean,
    @SerializedName("size_bytes") val sizeBytes: Long,
    @SerializedName("likely_game") val likelyGame: Boolean
)

data class ActiveSession(
    @SerializedName("process_id") val processId: Long,
    val path: String,
    val name: String,
    @SerializedName("is_japanese_locale") val isJapaneseLocale: Boolean,
    @SerializedName("is_admin") val isAdmin: Boolean,
    @SerializedName("is_offscreen") val isOffscreen: Boolean
)

data class LaunchRequest(
    val path: String,
    @SerializedName("japanese_locale") val japaneseLocale: Boolean,
    @SerializedName("as_admin") val asAdmin: Boolean,
    val offscreen: Boolean = true,
    @SerializedName("scale_factor") val scaleFactor: Float = 1.75f
)

data class InputRequest(
    @SerializedName("event_type") val eventType: String, // "down", "up", "click", "move", "key"
    @SerializedName("x_ratio") val xRatio: Float = 0f,
    @SerializedName("y_ratio") val yRatio: Float = 0f,
    val button: String = "left",
    val key: String = ""
)

data class GameConfig(
    val path: String,
    val name: String,
    val japaneseLocale: Boolean = true,
    val asAdmin: Boolean = false,
    val scaleFactor: Float = 1.75f,
    val isPinned: Boolean = false,
    val lastPlayedTimestamp: Long = 0L
)

data class FolderBookmark(
    val name: String,
    val path: String,
    val isDefault: Boolean = false
)

data class RunningWindowItem(
    val hwnd: Long,
    val pid: Long,
    val title: String,
    val width: Int,
    val height: Int
)

data class HookWindowRequest(
    val hwnd: Long,
    val offscreen: Boolean = true
)
