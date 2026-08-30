package com.maru.namispace.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("namispace_prefs", Context.MODE_PRIVATE)

    private val gson = Gson()

    var totalMessages: Int
        get() = prefs.getInt("total_messages", 0)
        set(value) = prefs.edit().putInt("total_messages", value).apply()

    var affectionLevel: Int
        get() = prefs.getInt("affection_level", 0)
        set(value) = prefs.edit().putInt("affection_level", value).apply()

    var hunger: Int
        get() = prefs.getInt("hunger", 70)
        set(value) = prefs.edit().putInt("hunger", value).apply()

    var energy: Int
        get() = prefs.getInt("energy", 80)
        set(value) = prefs.edit().putInt("energy", value).apply()

    var coins: Int
        get() = prefs.getInt("coins", 0)
        set(value) = prefs.edit().putInt("coins", value).apply()

    var totalCoinsEarned: Int
        get() = prefs.getInt("total_coins_earned", 0)
        set(value) = prefs.edit().putInt("total_coins_earned", value).apply()

    var inventory: Map<String, Int>
        get() {
            val json = prefs.getString("inventory", null) ?: return emptyMap()
            val type = object : TypeToken<Map<String, Int>>() {}.type
            return try { gson.fromJson(json, type) } catch (_: Exception) { emptyMap() }
        }
        set(value) = prefs.edit().putString("inventory", gson.toJson(value)).apply()

    var lastDialogue: String
        get() = prefs.getString("last_dialogue", "") ?: ""
        set(value) = prefs.edit().putString("last_dialogue", value).apply()

    var lastAiDialogueTimestamp: Long
        get() = prefs.getLong("last_ai_dialogue_ts", 0L)
        set(value) = prefs.edit().putLong("last_ai_dialogue_ts", value).apply()

    var daysSinceInstall: Int
        get() = prefs.getInt("days_since_install", 0)
        set(value) = prefs.edit().putInt("days_since_install", value).apply()

    var topicsDiscussed: Set<String>
        get() = prefs.getStringSet("topics_discussed", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("topics_discussed", value).apply()

    var storiesUnlocked: Set<String>
        get() = prefs.getStringSet("stories_unlocked", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("stories_unlocked", value).apply()

    var installTimestamp: Long
        get() = prefs.getLong("install_timestamp", System.currentTimeMillis())
        set(value) = prefs.edit().putLong("install_timestamp", value).apply()

    var recentLineIds: List<String>
        get() {
            val json = prefs.getString("recent_line_ids", null) ?: return emptyList()
            val type = object : TypeToken<List<String>>() {}.type
            return try { gson.fromJson(json, type) } catch (_: Exception) { emptyList() }
        }
        set(value) = prefs.edit().putString("recent_line_ids", gson.toJson(value.takeLast(25))).apply()

    var bgmMuted: Boolean
        get() = prefs.getBoolean("bgm_muted", false)
        set(value) = prefs.edit().putBoolean("bgm_muted", value).apply()

    var lastActiveTimestamp: Long
        get() = prefs.getLong("last_active_ts", System.currentTimeMillis())
        set(value) = prefs.edit().putLong("last_active_ts", value).apply()

    var saidGoodbye: Boolean
        get() = prefs.getBoolean("said_goodbye", false)
        set(value) = prefs.edit().putBoolean("said_goodbye", value).apply()

    var locationMode: String
        get() = prefs.getString("location_mode", "home") ?: "home"
        set(value) = prefs.edit().putString("location_mode", value).apply()

    var currentActivityId: String
        get() = prefs.getString("current_activity_id", "studying") ?: "studying"
        set(value) = prefs.edit().putString("current_activity_id", value).apply()

    var outingDistanceMeters: Float
        get() = prefs.getFloat("outing_dist_meters", 0f)
        set(value) = prefs.edit().putFloat("outing_dist_meters", value).apply()

    var collectedSouvenirs: Set<String>
        get() = prefs.getStringSet("collected_souvenirs", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("collected_souvenirs", value).apply()

    var suggestedReplies: List<String>
        get() {
            val json = prefs.getString("suggested_replies", null) ?: return listOf("🎵 Favorite song?", "🥑 Want a snack?", "💤 Let's rest", "what is a qubit?")
            val type = object : TypeToken<List<String>>() {}.type
            return try { gson.fromJson(json, type) } catch (_: Exception) { listOf("🎵 Favorite song?", "🥑 Want a snack?", "💤 Let's rest", "what is a qubit?") }
        }
        set(value) = prefs.edit().putString("suggested_replies", gson.toJson(value)).apply()
}
