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

internal fun isExplicitAdultAnime(isAdult: Boolean, genres: List<String>): Boolean =
    isAdult || genres.any { it.trim().equals("Hentai", ignoreCase = true) }

/**
 * Computes Netflix / Crunchyroll-style TV maturity rating and content descriptors
 * shown during the opening seconds of video playback.
 *
 * Note: AniList marks community tags like "Nudity" (magical girl silhouette transformations)
 * or "Large Breasts" with `tag.isAdult = true` even on PG/14+ shows like Sailor Moon!
 * Never treat `tag.isAdult` alone as 18+; only `media.isAdult == true` or genuinely extreme
 * R-18+ content escalates to "18+".
 */
internal fun deriveContentAdvisory(
    isAdult: Boolean,
    genres: List<String>,
    tags: List<MediaTag>
): ContentAdvisory {
    val genreSet = genres.mapTo(mutableSetOf()) { it.trim().lowercase() }
    val tagNames = tags
        .filter { !it.isMediaSpoiler && it.rank >= 50 }
        .associate { it.name.trim().lowercase() to it.rank }

    fun hasTag(vararg candidates: String, minRank: Int = 55): Boolean =
        candidates.any { key -> (tagNames[key.lowercase()] ?: 0) >= minRank }

    val isMagicalGirl = "mahou shoujo" in genreSet || hasTag("mahou shoujo", "henshin", minRank = 50)
    val isEcchiGenre = "ecchi" in genreSet || "hentai" in genreSet

    val warnings = linkedSetOf<String>()
    var severity = 0 // 0 = PG, 1 = 14+, 2 = 16+, 3 = 18+

    if (isExplicitAdultAnime(isAdult, genres)) {
        severity = 3
    }

    // 1. Gore, Graphic Violence, or Fantasy / Action Violence
    val goreRank = maxOf(
        tagNames["gore"] ?: 0,
        tagNames["torture"] ?: 0,
        tagNames["mutilation"] ?: 0,
        tagNames["cannibalism"] ?: 0,
        tagNames["body horror"] ?: 0
    )
    if (goreRank >= 62) {
        warnings += "Violence"
        warnings += "Gore"
        val extremeMatureGore = goreRank >= 84 && (
            "horror" in genreSet || "psychological" in genreSet || "thriller" in genreSet ||
                hasTag("seinen", minRank = 60)
        )
        severity = maxOf(severity, if (extremeMatureGore) 3 else 2)
    } else if (
        "action" in genreSet ||
        hasTag(
            "swordplay", "guns", "military", "war", "terrorism",
            "assassins", "death game", "battle royale", "yakuza", "gangs", "crime", "martial arts",
            minRank = 58
        )
    ) {
        val isFantasyAction = isMagicalGirl || (
            ("fantasy" in genreSet || hasTag("magic", "super power", minRank = 55)) &&
                !hasTag("crime", "yakuza", "gangs", "terrorism", "death game", "war", minRank = 62)
        )
        warnings += if (isFantasyAction) "Fantasy Violence" else "Violence"
        val grittyViolence = hasTag("death game", "terrorism", "yakuza", "gangs", "crime", minRank = 68)
        severity = maxOf(severity, if (grittyViolence) 2 else 1)
    }

    // 2. Sexual Content / Suggestive Themes (ignoring magical girl transformation silhouettes!)
    if (isExplicitAdultAnime(isAdult, genres)) {
        if (hasTag("nudity", minRank = 50)) warnings += "Nudity"
        warnings += "Sexual Content"
        severity = 3
    } else if (isEcchiGenre && hasTag("nudity", "sexual content", "prostitution", "netorare", minRank = 78)) {
        warnings += "Nudity"
        warnings += "Sexual Content"
        severity = maxOf(severity, 3)
    } else if (isEcchiGenre) {
        if (!isMagicalGirl && hasTag("nudity", minRank = 65)) warnings += "Nudity"
        warnings += "Sexual Themes"
        severity = maxOf(severity, 2)
    } else if (!isMagicalGirl && hasTag("sexual content", "prostitution", "voyeurism", minRank = 70)) {
        warnings += "Sexual Themes"
        severity = maxOf(severity, 2)
    }

    // 3. Substances & Gambling
    if (hasTag("drugs", minRank = 68)) {
        warnings += "Drugs"
        val extremeCrimeDrugs = (tagNames["drugs"] ?: 0) >= 84 &&
            ("thriller" in genreSet || "psychological" in genreSet || hasTag("crime", minRank = 65))
        severity = maxOf(severity, if (extremeCrimeDrugs) 3 else 2)
    }
    if (hasTag("alcohol", "smoking", minRank = 65)) {
        warnings += "Alcohol & Tobacco"
        severity = maxOf(severity, 1)
    }
    if (hasTag("gambling", minRank = 65)) {
        warnings += "Gambling"
        severity = maxOf(severity, 2)
    }

    // 4. Mature / Psychological / Frightening Themes
    if (hasTag("suicide", "self-harm", minRank = 70)) {
        warnings += "Self-Harm"
        severity = maxOf(severity, 2)
    }
    if ("horror" in genreSet || hasTag("cosmic horror", "zombies", minRank = 68)) {
        warnings += "Frightening Scenes"
        severity = maxOf(severity, if ("horror" in genreSet) 2 else 1)
    } else if ("psychological" in genreSet || "thriller" in genreSet || hasTag("slavery", "human trafficking", "bullying", minRank = 68)) {
        warnings += "Mature Themes"
        severity = maxOf(severity, if (isMagicalGirl) 1 else 2)
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
