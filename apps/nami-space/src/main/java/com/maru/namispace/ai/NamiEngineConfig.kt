package com.maru.namispace.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Dynamic Brain & Personality Configuration for instant hotfixing without APK rebuilds.
 * Enables live prompt, lore, parameter, and search updates in 50ms via ADB.
 */
data class NamiEngineConfig(
    val systemPromptTemplate: String = "",
    val ambientPromptTemplate: String = "",
    val temperature: Float = 0.75f,
    val maxTokens: Int = 160,
    val searchEnabled: Boolean = true,
    val primaryModelPath: String = "qwen2.5-3b-instruct-q4_k_m.gguf",
    val quirks: Map<String, String> = mapOf("9+10" to "21, baka!", "microwavable" to "cute compliment"),
    val version: Int = 1
)

object NamiConfigManager {
    private const val TAG = "NamiConfigManager"
    private const val CONFIG_FILE = "config/nami_engine.json"
    private val gson = Gson()

    private val _config = MutableStateFlow(NamiEngineConfig())
    val config = _config.asStateFlow()

    private var isReceiverRegistered = false

    fun init(context: Context) {
        loadConfig(context)
        registerHotReloadReceiver(context)
    }

    fun loadConfig(context: Context) {
        try {
            val file = File(context.filesDir, CONFIG_FILE)
            if (file.exists()) {
                val json = file.readText()
                val loaded = gson.fromJson(json, NamiEngineConfig::class.java)
                if (loaded != null) {
                    _config.value = loaded
                    Log.i(TAG, "Hot-reloaded Nami engine config v${loaded.version} from ${file.absolutePath}")
                    return
                }
            }
            // If not found, save default
            saveDefault(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading dynamic config, using default", e)
        }
    }

    private fun saveDefault(context: Context) {
        try {
            val file = File(context.filesDir, CONFIG_FILE)
            file.parentFile?.mkdirs()
            val defaultConfig = NamiEngineConfig(
                systemPromptTemplate = SystemPrompt.core,
                version = 1
            )
            file.writeText(gson.toJson(defaultConfig))
            _config.value = defaultConfig
            Log.i(TAG, "Created default dynamic config at ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save default config", e)
        }
    }

    private fun registerHotReloadReceiver(context: Context) {
        if (isReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                Log.i(TAG, "Received HOT_RELOAD broadcast! Reloading dynamic brain config...")
                ctx?.let { loadConfig(it) }
            }
        }
        val filter = IntentFilter("com.maru.namispace.HOT_RELOAD")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        isReceiverRegistered = true
    }
}
