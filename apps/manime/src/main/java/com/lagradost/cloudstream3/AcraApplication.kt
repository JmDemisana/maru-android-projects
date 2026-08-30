package com.lagradost.cloudstream3

import android.app.Application
import android.content.Context

class AcraApplication : Application() {
    companion object {
        var context: Context? = null
        fun getKey(key: String, def: String? = null): String? = def
        fun setKey(key: String, value: String?) {}
    }
}
