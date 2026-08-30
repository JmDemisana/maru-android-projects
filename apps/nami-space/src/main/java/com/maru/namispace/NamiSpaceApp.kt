package com.maru.namispace

import android.app.Application
import android.util.Log

class NamiSpaceApp : Application() {
    companion object {
        init {
            try {
                System.loadLibrary("ggml-base")
                System.loadLibrary("ggml")
                System.loadLibrary("ggml-cpu-android_armv8.0_1")
                System.loadLibrary("llama")
                System.loadLibrary("llama-common")
                System.loadLibrary("llama-completion-impl")
                Log.i("NamiSpaceApp", "Native LLaMA libraries and CPU backend loaded successfully")
            } catch (e: Throwable) {
                Log.e("NamiSpaceApp", "Failed to load native libraries", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
    }
}
