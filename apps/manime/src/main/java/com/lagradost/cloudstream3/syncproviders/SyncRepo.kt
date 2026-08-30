package com.lagradost.cloudstream3.syncproviders

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.providers.AniListApi
import com.lagradost.cloudstream3.syncproviders.providers.KitsuApi
import com.lagradost.cloudstream3.syncproviders.providers.MALApi
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi

open class AccountManager(open val api: SyncAPI? = null) {
    companion object {
        @JvmStatic val malApi: MALApi = MALApi()
        @JvmStatic val aniListApi: AniListApi = AniListApi()
        @JvmStatic val kitsuApi: KitsuApi = KitsuApi()
        @JvmStatic val openSubtitlesApi: OpenSubtitlesApi = OpenSubtitlesApi()
    }
}

data class SyncSearchResult(
    val name: String,
    val apiName: String,
    val syncId: String,
    val url: String,
    val posterUrl: String? = null,
    val type: TvType = TvType.Anime
)

open class SyncRepo(open val syncApi: SyncAPI) {
    constructor() : this(object : SyncAPI { override val name = "SyncRepo"; override val idPrefix = "syncrepo" })
    constructor(name: String) : this(object : SyncAPI { override val name = name; override val idPrefix = name.lowercase() })

    open suspend fun search(query: String): List<SyncSearchResult>? = syncApi.search(query)

    companion object {
        val syncApis: Array<Any> = emptyArray()
    }
}

interface SyncAPI {
    val name: String
    val idPrefix: String
    val requireLogin: Boolean get() = false
    suspend fun search(query: String): List<SyncSearchResult>? = null
}
