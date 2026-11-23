package io.maru.manime

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

internal fun latestReleasedEpisode(status: String?, episodes: Int?, nextEpisode: Int?): Int? = when {
    status.equals("NOT_YET_RELEASED", true) -> 0
    status.equals("RELEASING", true) -> nextEpisode?.let { (it - 1).coerceAtLeast(0) }
    else -> episodes
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
