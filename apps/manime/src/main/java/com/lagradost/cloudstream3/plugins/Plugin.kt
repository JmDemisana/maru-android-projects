package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudstreamPlugin

abstract class BasePlugin {
    val registeredApis = mutableListOf<MainAPI>()
    val registeredExtractors = mutableListOf<ExtractorApi>()

    open fun load(context: Context) {}

    open fun registerMainAPI(api: MainAPI) {
        registeredApis.add(api)
    }

    open fun registerExtractorAPI(api: ExtractorApi) {
        registeredExtractors.add(api)
    }
}

abstract class Plugin : BasePlugin()
