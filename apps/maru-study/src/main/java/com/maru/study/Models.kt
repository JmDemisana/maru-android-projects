package com.maru.study

import androidx.compose.ui.graphics.Color
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------
// Schedule model
// ---------------------------------------------------------------------------

enum class StudyDay(val label: String, val short: String, val dayOfWeek: DayOfWeek) {
    MONDAY("Monday", "MON", DayOfWeek.MONDAY),
    TUESDAY("Tuesday", "TUE", DayOfWeek.TUESDAY),
    WEDNESDAY("Wednesday", "WED", DayOfWeek.WEDNESDAY),
    THURSDAY("Thursday", "THU", DayOfWeek.THURSDAY),
    FRIDAY("Friday", "FRI", DayOfWeek.FRIDAY),
    SATURDAY("Saturday", "SAT", DayOfWeek.SATURDAY);

    companion object {
        fun fromDate(date: LocalDate): StudyDay? =
            entries.firstOrNull { it.dayOfWeek == date.dayOfWeek }
    }
}

enum class EntryAccent(val label: String, val color: Color) {
    ROSE("Rose", Color(0xFFFF6B87)),
    AMBER("Amber", Color(0xFFFFB347)),
    SKY("Sky", Color(0xFF70B9FF)),
    MINT("Mint", Color(0xFF74E1B3)),
    LILAC("Lilac", Color(0xFFB998FF)),
    PEACH("Peach", Color(0xFFFF9D7A)),
}

data class ScheduleEntry(
    val id: String,
    val day: StudyDay,
    val startMinute: Int,
    val endMinute: Int,
    val courseCode: String = "",
    val title: String = "",
    val section: String = "",
    val instructor: String = "",
    val room: String = "",
    val notes: String = "",
    val accent: EntryAccent = EntryAccent.SKY,
) {
    val durationMinutes: Int get() = (endMinute - startMinute).coerceAtLeast(0)
    val displayTitle: String get() = title.ifBlank { courseCode.ifBlank { "Untitled class" } }
}

data class ScheduleDocument(
    val entries: List<ScheduleEntry> = emptyList(),
) {
    fun sortedEntries(): List<ScheduleEntry> =
        entries.sortedWith(compareBy({ it.day.ordinal }, { it.startMinute }, { it.endMinute }))

    fun entriesFor(day: StudyDay): List<ScheduleEntry> =
        entries.filter { it.day == day }.sortedBy { it.startMinute }

    fun activeDays(): List<StudyDay> =
        StudyDay.entries.filter { day -> entries.any { it.day == day } }

    fun totalWeeklyMinutes(): Int = entries.sumOf { it.durationMinutes }

    fun busiestDay(): StudyDay? =
        StudyDay.entries
            .associateWith { day -> entriesFor(day).sumOf { it.durationMinutes } }
            .filterValues { it > 0 }
            .maxByOrNull { it.value }
            ?.key

    fun subjects(): List<String> =
        entries
            .map { it.displayTitle }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
}

fun defaultDocument(): ScheduleDocument = ScheduleDocument(
    entries = listOf(
        ScheduleEntry("default-mon-bs434a", StudyDay.MONDAY, 8 * 60, 12 * 60, "BS 434 A", "Technopreneurship", "ECE 4D", "Young", "NEB 8", accent = EntryAccent.AMBER),
        ScheduleEntry("default-mon-ece414a", StudyDay.MONDAY, 13 * 60, 16 * 60 + 45, "ECE 414 A", "Feedback and Control Systems", "ECE 4A", "Abeto", "LAB 2", accent = EntryAccent.MINT),
        ScheduleEntry("default-tue-ece412b", StudyDay.TUESDAY, 13 * 60, 16 * 60 + 45, "ECE 412 B", "Antenna Systems", "ECE 4A", "Benitez", "NEB 8", accent = EntryAccent.SKY),
        ScheduleEntry("default-wed-bes423", StudyDay.WEDNESDAY, 8 * 60, 12 * 60, "BES 423", "Engineering Economics and Accounting", "ECE 4D", "Young", "NEB 6", accent = EntryAccent.ROSE),
        ScheduleEntry("default-wed-ece412", StudyDay.WEDNESDAY, 13 * 60, 15 * 60 + 30, "ECE 412", "Program Enhancement 4", "ECE 4B", "Diamante", "NEB 7", accent = EntryAccent.LILAC),
        ScheduleEntry("default-thu-ece413", StudyDay.THURSDAY, 8 * 60, 12 * 60, "ECE 413", "Methods of Research", "ECE 4B", "Deldo", "31", accent = EntryAccent.AMBER),
        ScheduleEntry("default-thu-ece432b", StudyDay.THURSDAY, 15 * 60 + 30, 18 * 60, "ECE 432 B", "Modern Comm's Syst's and Technologies", "ECE 4D", "Benitez", "NEB 6", accent = EntryAccent.SKY),
        ScheduleEntry("default-fri-ece414a", StudyDay.FRIDAY, 8 * 60, 12 * 60, "ECE 414 A", "Feedback and Control Systems", "ECE 4B", "Abeto", "NEB 8", accent = EntryAccent.MINT),
        ScheduleEntry("default-fri-ece412b", StudyDay.FRIDAY, 15 * 60, 16 * 60 + 45, "ECE 412 B", "Antenna Systems", "ECE 4A", "Benitez", "32", accent = EntryAccent.SKY),
    ).sortedWith(compareBy({ it.day.ordinal }, { it.startMinute })),
)

// ---------------------------------------------------------------------------
// Time helpers
// ---------------------------------------------------------------------------

const val DAY_START_MINUTE = 7 * 60
const val DAY_END_MINUTE = 21 * 60

fun formatMinute(minute: Int): String {
    val normalized = ((minute % (24 * 60)) + 24 * 60) % (24 * 60)
    val hour24 = normalized / 60
    val mins = normalized % 60
    val period = if (hour24 >= 12) "PM" else "AM"
    val hour12 = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    return String.format(Locale.US, "%d:%02d %s", hour12, mins, period)
}

fun formatTimeRange(startMinute: Int, endMinute: Int): String =
    "${formatMinute(startMinute)} to ${formatMinute(endMinute)}"

fun formatHourLabel(hour24: Int): String {
    val period = if (hour24 >= 12) "PM" else "AM"
    val hour = when {
        hour24 == 0 -> 12
        hour24 > 12 -> hour24 - 12
        else -> hour24
    }
    return "$hour $period"
}

fun classCountLabel(count: Int): String = if (count == 1) "1 class" else "$count classes"

fun formatDuration(minutes: Int): String {
    if (minutes <= 0) return "0m"
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours == 0 -> "${mins}m"
        mins == 0 -> "${hours}h"
        else -> "${hours}h ${mins}m"
    }
}

fun currentMinuteOfDay(now: LocalTime = LocalTime.now()): Int = now.hour * 60 + now.minute

fun todayStudyDay(today: LocalDate = LocalDate.now()): StudyDay? = StudyDay.fromDate(today)

// ---------------------------------------------------------------------------
// Upcoming class
// ---------------------------------------------------------------------------

data class UpcomingClass(
    val entry: ScheduleEntry,
    val dayOffset: Int,
    val startsInMinutes: Int,
    val ongoing: Boolean,
)

fun findUpcomingClass(
    document: ScheduleDocument,
    now: LocalTime = LocalTime.now(),
    today: LocalDate = LocalDate.now(),
): UpcomingClass? {
    val nowMinute = currentMinuteOfDay(now)
    for (offset in 0..7) {
        val date = today.plusDays(offset.toLong())
        val day = StudyDay.fromDate(date) ?: continue
        val candidates = document.entriesFor(day)
        for (entry in candidates) {
            if (offset == 0) {
                if (entry.endMinute > nowMinute) {
                    return UpcomingClass(
                        entry = entry,
                        dayOffset = 0,
                        startsInMinutes = entry.startMinute - nowMinute,
                        ongoing = entry.startMinute <= nowMinute,
                    )
                }
            } else {
                return UpcomingClass(
                    entry = entry,
                    dayOffset = offset,
                    startsInMinutes = entry.startMinute + offset * 24 * 60 - nowMinute,
                    ongoing = false,
                )
            }
        }
    }
    return null
}

fun upcomingWhenLabel(upcoming: UpcomingClass): String = when {
    upcoming.ongoing -> "HAPPENING NOW"
    upcoming.dayOffset == 0 -> "IN ${humanGap(upcoming.startsInMinutes).uppercase()}"
    upcoming.dayOffset == 1 -> "TOMORROW"
    else -> upcoming.entry.day.label.uppercase()
}

fun upcomingDetailLabel(upcoming: UpcomingClass): String = when {
    upcoming.ongoing -> "Ongoing until ${formatMinute(upcoming.entry.endMinute)}"
    upcoming.dayOffset == 0 -> "Starts at ${formatMinute(upcoming.entry.startMinute)}"
    upcoming.dayOffset == 1 -> "Tomorrow at ${formatMinute(upcoming.entry.startMinute)}"
    else -> "${upcoming.entry.day.label} at ${formatMinute(upcoming.entry.startMinute)}"
}

fun humanGap(minutes: Int): String {
    if (minutes < 1) return "now"
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        mins == 0 -> "${hours}h"
        else -> "${hours}h ${mins}m"
    }
}

// ---------------------------------------------------------------------------
// TUP Grade Solver maths.
// ---------------------------------------------------------------------------

fun parseGrade(value: String): Double? {
    if (value.isBlank()) return 5.0
    return value.trim().toDoubleOrNull()
}

fun roundToOne(value: Double): Double = (value * 10).roundToLong() / 10.0

fun computeRequiredEndterm(prelim: String, midterm: String): Double? {
    val prelimValue = parseGrade(prelim) ?: return null
    val midtermValue = parseGrade(midterm) ?: return null
    val required = (4.85 - 0.3 * prelimValue - 0.3 * midtermValue) / 0.4
    return minOf(roundToOne(required), 10.0)
}

fun formatRequiredEndterm(value: Double?): String = when {
    value == null -> "--"
    value < 0 -> "You Already Passed!"
    else -> String.format(Locale.US, "%.1f", value)
}

data class GradeDifficulty(val label: String, val color: Color)

fun difficultyFor(value: Double?): GradeDifficulty? = when {
    value == null -> null
    value <= 0.0 -> GradeDifficulty("Already Passed", Color(0xFF34D399))
    value <= 4.0 -> GradeDifficulty("Comfortable", Color(0xFF4ADE80))
    value <= 6.5 -> GradeDifficulty("Manageable", Color(0xFFFBBF24))
    value <= 8.5 -> GradeDifficulty("Demanding", Color(0xFFFB923C))
    else -> GradeDifficulty("Very Risky / Critical", Color(0xFFF87171))
}

fun normalizeGradeInput(raw: String): String {
    val filtered = raw.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    val singleDot = if (firstDot == -1) {
        filtered
    } else {
        filtered.substring(0, firstDot + 1) + filtered.substring(firstDot + 1).replace(".", "")
    }
    if (singleDot.isEmpty()) return ""
    val value = singleDot.toDoubleOrNull() ?: return singleDot
    return if (value > 10.0) "10" else singleDot
}


