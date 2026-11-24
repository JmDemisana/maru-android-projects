package io.maru.manime

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

data class MediaTag(
    val name: String,
    val rank: Int = 0,
    val isAdult: Boolean = false,
    val isMediaSpoiler: Boolean = false
)

data class ContentAdvisory(
    val ageRating: String,          // e.g. "18+", "16+", "14+", "PG"
    val warnings: List<String>      // e.g. ["Violence", "Drugs"]
) {
    val warningsText: String
        get() = warnings.joinToString(", ")

    val isMature: Boolean
        get() = ageRating == "18+" || ageRating == "16+"
}

/**
 * Computes Crunchyroll-style age rating and content descriptors (e.g. "18+  Violence, Drugs")
 * from AniList genres, community content tags, and adult flags without leaking plot spoilers.
 */
internal fun deriveContentAdvisory(
    isAdult: Boolean,
    genres: List<String>,
    tags: List<MediaTag>
): ContentAdvisory {
    val genreSet = genres.mapTo(mutableSetOf()) { it.trim().lowercase() }
    val activeTags = tags.filter { it.rank >= 42 || it.isAdult }
    val tagNames = activeTags.associate { it.name.trim().lowercase() to it.rank }

    fun hasTag(vararg candidates: String, minRank: Int = 45): Boolean =
        candidates.any { key -> (tagNames[key.lowercase()] ?: 0) >= minRank }

    val warnings = linkedSetOf<String>()
    var severity = 0 // 0 = PG, 1 = 14+, 2 = 16+, 3 = 18+

    if (isAdult || activeTags.any { it.isAdult && it.rank >= 50 }) {
        severity = maxOf(severity, 3)
    }

    // 1. Gore & Graphic / Fantasy Violence
    if (hasTag("gore", "body horror", "torture", "mutilation", "cannibalism", minRank = 48)) {
        warnings += "Violence"
        warnings += "Gore"
        val goreRank = maxOf(
            tagNames["gore"] ?: 0,
            tagNames["torture"] ?: 0,
            tagNames["mutilation"] ?: 0,
            tagNames["cannibalism"] ?: 0
        )
        severity = maxOf(severity, if (goreRank >= 72) 3 else 2)
    } else if (
        "action" in genreSet ||
        hasTag(
            "violence", "swordplay", "guns", "military", "war", "terrorism",
            "assassins", "death game", "battle royale", "yakuza", "gangs", "crime", "martial arts",
            minRank = 50
        )
    ) {
        warnings += "Violence"
        val heavyViolence = hasTag("death game", "terrorism", "yakuza", "gangs", "crime", "war", minRank = 60)
        severity = maxOf(severity, if (heavyViolence) 2 else 1)
    }

    // 2. Drugs, Alcohol, Smoking & Gambling
    if (hasTag("drugs", minRank = 42)) {
        warnings += "Drugs"
        severity = maxOf(severity, 3)
    }
    if (hasTag("alcohol", "smoking", minRank = 50)) {
        warnings += "Alcohol & Tobacco"
        severity = maxOf(severity, 1)
    }
    if (hasTag("gambling", minRank = 55)) {
        warnings += "Gambling"
        severity = maxOf(severity, 2)
    }

    // 3. Nudity & Sexual Content / Suggestive Themes
    if (isAdult || hasTag("nudity", "sexual content", "prostitution", "netorare", minRank = 48)) {
        if (hasTag("nudity", minRank = 48)) warnings += "Nudity"
        warnings += "Sexual Content"
        severity = maxOf(severity, 3)
    } else if (
        "ecchi" in genreSet ||
        hasTag("large breasts", "ensembles", "erotic piercings", "virginity", "voyeurism", minRank = 55)
    ) {
        warnings += "Sexual Themes"
        severity = maxOf(severity, 2)
    }

    // 4. Self-Harm, Abuse & Psychological / Horror Themes
    if (hasTag("suicide", "self-harm", minRank = 50)) {
        warnings += "Self-Harm"
        severity = maxOf(severity, 3)
    }
    if (hasTag("slavery", "human trafficking", "bullying", "revenge", "denpa", minRank = 58)) {
        warnings += "Mature Themes"
        severity = maxOf(severity, 2)
    }
    if ("horror" in genreSet || hasTag("cosmic horror", "ghost", "zombies", "demons", minRank = 60)) {
        warnings += "Frightening Scenes"
        severity = maxOf(severity, if ("horror" in genreSet) 2 else 1)
    } else if ("psychological" in genreSet || "thriller" in genreSet) {
        warnings += "Mature Themes"
        severity = maxOf(severity, 2)
    }

    val rating = when (severity) {
        3 -> "18+"
        2 -> "16+"
        1 -> "14+"
        else -> "PG"
    }
    return ContentAdvisory(
        ageRating = rating,
        warnings = warnings.take(4).toList()
    )
}

internal fun latestReleasedEpisode(status: String?, episodes: Int?, nextEpisode: Int?): Int? = when {
    status.equals("NOT_YET_RELEASED", true) -> 0
    status.equals("FINISHED", true) -> episodes?.takeIf { it > 0 }
    else -> nextEpisode?.takeIf { it > 0 }?.let { it - 1 }
}

internal fun singaporeDate(now: Instant = Instant.now()): String =
    now.atZone(ZoneId.of("Asia/Singapore")).toLocalDate().toString()

internal fun nativeScoreToRaw(score: Double, format: String): Int {
    require(score.isFinite())
    val raw = when (format) {
        "POINT_10", "POINT_10_DECIMAL" -> score * 10
        "POINT_5" -> score * 20
        "POINT_3" -> when (score.toInt()) { 1 -> 35.0; 2 -> 60.0; 3 -> 85.0; else -> 0.0 }
        else -> score
    }
    return raw.roundToInt().coerceIn(0, 100)
}
