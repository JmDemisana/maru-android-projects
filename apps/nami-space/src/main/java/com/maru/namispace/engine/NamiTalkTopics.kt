package com.maru.namispace.engine

/** Canon topic words mirrored from the NamiTalk routes used by the site and Messenger. */
object NamiTalkTopics {
    private val aliases = mapOf(
        "rain" to "rain", "weather" to "rain", "bear-chan" to "bear-chan", "bearchan" to "bear-chan",
        "hana" to "hana", "mochi" to "mochi", "eevee" to "mochi", "avocado" to "avocado",
        "gumi" to "gumi", "vocaloid" to "gumi", "bach" to "bach", "mozart" to "mozart",
        "planetarian" to "planetarian", "fluorite" to "fluorite", "senpai" to "senpai",
        "seven seas" to "seven seas", "nanami" to "nami", "nami" to "nami",
    )

    fun findIn(text: String): Set<String> {
        val normalized = text.lowercase()
        return aliases.filterKeys { it in normalized }.values.toSet()
    }

    val starterPrompts = listOf("Tell me about Mochi", "What do you think about rain?", "Let's talk about GUMI")
}
