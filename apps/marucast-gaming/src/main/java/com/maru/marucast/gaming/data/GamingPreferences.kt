package com.maru.marucast.gaming.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class GamingPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("maru_gaming_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    var lastHost: String
        get() = prefs.getString("last_host", "127.0.0.1:48792") ?: "127.0.0.1:48792"
        set(value) = prefs.edit().putString("last_host", value).apply()

    var lastScanDir: String
        get() = prefs.getString("last_scan_dir", "D:\\Games\\Visual Novels") ?: "D:\\Games\\Visual Novels"
        set(value) = prefs.edit().putString("last_scan_dir", value).apply()

    var scaleModeIndex: Int
        get() = prefs.getInt("scale_mode_index", 0) // Default 0: Stretch (No Bars)
        set(value) = prefs.edit().putInt("scale_mode_index", value).apply()

    var uiScaleFactor: Float
        get() = prefs.getFloat("ui_scale_factor", 1.75f) // Default 1.75x readable DPI
        set(value) = prefs.edit().putFloat("ui_scale_factor", value).apply()

    var streamFpsTarget: Int
        get() = prefs.getInt("stream_fps_target", 24) // 20-30 FPS default for VN/UI
        set(value) = prefs.edit().putInt("stream_fps_target", value).apply()

    var trackpadMode: Boolean
        get() = prefs.getBoolean("trackpad_mode", false)
        set(value) = prefs.edit().putBoolean("trackpad_mode", value).apply()

    fun getRecentHosts(): List<String> {
        val json = prefs.getString("recent_hosts", null) ?: return listOf(
            "127.0.0.1:48792",
            "192.168.8.145:48792"
        )
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            listOf("127.0.0.1:48792")
        }
    }

    fun addRecentHost(host: String) {
        val current = getRecentHosts().toMutableList()
        current.remove(host)
        current.add(0, host)
        val trimmed = current.take(5)
        prefs.edit().putString("recent_hosts", gson.toJson(trimmed)).apply()
    }

    fun getFolderBookmarks(): List<FolderBookmark> {
        val json = prefs.getString("folder_bookmarks", null)
        if (json.isNullOrEmpty()) {
            return listOf(
                FolderBookmark("Visual Novels", "D:\\Games\\Visual Novels", isDefault = true),
                FolderBookmark("Steam Apps", "C:\\Program Files (x86)\\Steam\\steamapps\\common", isDefault = true),
                FolderBookmark("Chrome Apps", "C:\\Program Files\\Google\\Chrome\\Application", isDefault = true),
                FolderBookmark("D: Games Hub", "D:\\Games", isDefault = true)
            )
        }
        return try {
            val type = object : TypeToken<List<FolderBookmark>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFolderBookmarks(bookmarks: List<FolderBookmark>) {
        prefs.edit().putString("folder_bookmarks", gson.toJson(bookmarks)).apply()
    }

    fun addBookmark(name: String, path: String) {
        val current = getFolderBookmarks().toMutableList()
        if (current.none { it.path.equals(path, ignoreCase = true) }) {
            current.add(FolderBookmark(name, path, isDefault = false))
            saveFolderBookmarks(current)
        }
    }

    fun removeBookmark(path: String) {
        val current = getFolderBookmarks().toMutableList()
        current.removeAll { it.path.equals(path, ignoreCase = true) }
        saveFolderBookmarks(current)
    }

    fun getPinnedGamePaths(): Set<String> {
        return prefs.getStringSet("pinned_games", emptySet()) ?: emptySet()
    }

    fun togglePinGame(path: String) {
        val current = getPinnedGamePaths().toMutableSet()
        if (current.contains(path)) {
            current.remove(path)
        } else {
            current.add(path)
        }
        prefs.edit().putStringSet("pinned_games", current).apply()
    }

    fun getGameConfig(path: String): GameConfig? {
        val json = prefs.getString("game_cfg_$path", null) ?: return null
        return try {
            gson.fromJson(json, GameConfig::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun saveGameConfig(config: GameConfig) {
        prefs.edit().putString("game_cfg_${config.path}", gson.toJson(config)).apply()
    }
}
