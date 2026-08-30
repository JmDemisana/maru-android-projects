package com.lagradost.cloudstream3.utils

enum class Qualities(val value: Int) {
    Unknown(0),
    P144(144),
    P240(240),
    P360(360),
    P480(480),
    P720(720),
    P1080(1080),
    P1440(1440),
    P2160(2160);

    companion object {
        fun getStringByInt(qual: Int?): String = when (qual) {
            2160 -> "4K"
            1440 -> "1440p"
            1080 -> "1080p"
            720 -> "720p"
            480 -> "480p"
            360 -> "360p"
            240 -> "240p"
            else -> "HD"
        }
    }
}

enum class ExtractorLinkType {
    M3U8,
    DASH,
    VIDEO,
    TORRENT,
    MAGNET
}

data class SubtitleFile(
    val lang: String,
    val url: String
)

data class ExtractorLink(
    val source: String,
    val name: String,
    val url: String,
    val referer: String = "",
    val quality: Int = Qualities.P1080.value,
    val type: ExtractorLinkType = ExtractorLinkType.VIDEO,
    val headers: Map<String, String> = emptyMap(),
    val extractorData: String? = null
)

abstract class ExtractorApi {
    open val name: String = "Extractor"
    open val mainUrl: String = ""
    open val requiresReferer: Boolean = false

    open suspend fun getUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        getUrl(url, referer, callback)
    }

    open suspend fun getUrl(
        url: String,
        referer: String? = null,
        callback: (ExtractorLink) -> Unit
    ) {}
}
