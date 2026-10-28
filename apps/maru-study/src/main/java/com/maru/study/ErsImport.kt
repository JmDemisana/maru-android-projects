package com.maru.study

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

enum class ErsSyncMode(val label: String, val key: String) {
    BOTH("Both", "both"),
    SCHEDULE("Schedule", "schedule"),
    GRADES("Grades", "grades");

    val includesSchedule: Boolean get() = this != GRADES
    val includesGrades: Boolean get() = this != SCHEDULE

    companion object {
        fun fromKey(key: String, fallback: ErsSyncMode = BOTH): ErsSyncMode =
            entries.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) } ?: fallback
    }
}

data class ErsSubjectRecord(
    val code: String = "",
    val title: String = "",
    val faculty: String = "",
    val units: String = "",
    val section: String = "",
    val schedule: String = "",
    val prelim: String = "",
    val midterm: String = "",
    val endterm: String = "",
    val average: String = "",
    val status: String = "",
) {
    val displayName: String get() = code.ifBlank { title.ifBlank { "Subject" } }
}

data class ErsGradeTerm(
    val schoolYear: String = "",
    val term: String = "",
    val gpa: String = "",
    val subjects: List<ErsSubjectRecord> = emptyList(),
) {
    val label: String
        get() = buildList {
            if (schoolYear.isNotBlank()) add("SY ${formatErsSchoolYear(schoolYear)}")
            if (term.isNotBlank()) add(term)
        }.joinToString(" · ").ifBlank { "Term" }
}

fun formatErsSchoolYear(raw: String): String {
    val value = raw.trim()
    if (value.length == 4 && value.all { it.isDigit() }) {
        return "20${value.substring(0, 2)}-20${value.substring(2, 4)}"
    }
    return value
}

data class ErsImportResult(
    val mode: ErsSyncMode = ErsSyncMode.BOTH,
    val scheduleTerm: String = "",
    val gradesTerm: String = "",
    val schedule: List<ErsSubjectRecord> = emptyList(),
    val grades: List<ErsSubjectRecord> = emptyList(),
    val gradeHistory: List<ErsGradeTerm> = emptyList(),
) {
    val displayTerm: String get() = scheduleTerm.ifBlank { gradesTerm }.trim()

    fun isEmpty(): Boolean =
        (mode.includesSchedule && schedule.isEmpty()) &&
            (mode.includesGrades && grades.isEmpty())
}

class ErsImportException(message: String) : Exception(message)

/**
 * Turns the JSON handed back by the hidden ERS WebView into schedule entries
 * and grade solver rows.
 */
fun parseErsPayload(raw: String, fallbackMode: ErsSyncMode): ErsImportResult {
    val root = runCatching { JSONObject(raw) }.getOrElse {
        throw ErsImportException("ERS sent back a page Maru Study could not read.")
    }

    if (!root.optBoolean("ok", false)) {
        val message = root.optString("message").trim()
        throw ErsImportException(message.ifBlank { "ERS import failed. Try again." })
    }

    return ErsImportResult(
        mode = ErsSyncMode.fromKey(root.optString("mode"), fallbackMode),
        scheduleTerm = root.optString("scheduleTerm").trim(),
        gradesTerm = root.optString("gradesTerm").trim(),
        schedule = readErsRecords(root.optJSONArray("schedule")),
        grades = readErsRecords(root.optJSONArray("grades")),
        gradeHistory = readErsTerms(root.optJSONArray("gradeTerms")),
    )
}

/**
 * Builds the same change proposal the Nami flow uses, so the user still sees
 * one native confirmation sheet before anything is written to storage.
 */
fun ErsImportResult.toProposal(snapshot: StudySnapshot): StudyChangeProposal? {
    val entries = if (mode.includesSchedule) scheduleEntries() else emptyList()
    val gradeRows = if (mode.includesGrades) {
        grades.filter { it.code.isNotBlank() || it.title.isNotBlank() }
    } else {
        emptyList()
    }

    if (entries.isEmpty() && gradeRows.isEmpty()) return null

    val root = JSONObject()

    if (entries.isNotEmpty()) {
        root.put(
            "schedule",
            JSONObject().put(
                "replace",
                JSONArray().apply {
                    entries.forEach { entry ->
                        put(
                            JSONObject().apply {
                                put("day", entry.day.label)
                                put("start", formatMinute(entry.startMinute))
                                put("end", formatMinute(entry.endMinute))
                                put("courseCode", entry.courseCode)
                                put("title", entry.title)
                                put("section", entry.section)
                                put("instructor", entry.instructor)
                                put("room", entry.room)
                                put("accent", entry.accent.name)
                            },
                        )
                    }
                },
            ),
        )
    }

    if (gradeRows.isNotEmpty()) {
        root.put(
            "grades",
            JSONObject().put(
                "bulk",
                JSONArray().apply {
                    gradeRows.forEach { record ->
                        put(
                            JSONObject().apply {
                                put("course", record.code)
                                put("prelim", record.prelim)
                                put("midterm", record.midterm)
                            },
                        )
                    }
                },
            ),
        )
    }

    val base = parseStudyChange(root.toString(), snapshot) ?: return null

    val parts = buildList {
        add("TUP ERS")
        if (displayTerm.isNotBlank()) add(displayTerm)
        if (entries.isNotEmpty()) add("schedule")
        if (gradeRows.isNotEmpty()) add("grades")
    }

    return base.withTitleAndLines(
        nextTitle = "Import from TUP ERS?",
        nextLines = listOf(parts.joinToString(" · ")) + base.lines,
    )
}

data class ErsMeeting(
    val day: StudyDay,
    val startMinute: Int,
    val endMinute: Int,
    val room: String,
    val section: String,
) {
    val timeLabel: String get() = "${formatMinute(startMinute)} to ${formatMinute(endMinute)}"
}

fun ErsImportResult.withSelection(
    includeSchedule: Boolean,
    includeGrades: Boolean,
): ErsImportResult = copy(
    mode = when {
        includeSchedule && includeGrades -> ErsSyncMode.BOTH
        includeSchedule -> ErsSyncMode.SCHEDULE
        includeGrades -> ErsSyncMode.GRADES
        else -> ErsSyncMode.GRADES
    },
)

fun ErsSubjectRecord.meetings(): List<ErsMeeting> =
    parseErsSessions(schedule).flatMap { session ->
        session.days.map { day ->
            ErsMeeting(
                day = day,
                startMinute = session.startMinute,
                endMinute = session.endMinute,
                room = session.room,
                section = session.section,
            )
        }
    }

fun ErsImportResult.scheduleEntries(): List<ScheduleEntry> =
    schedule.flatMapIndexed { subjectIndex, record ->
        val accent = EntryAccent.entries[subjectIndex % EntryAccent.entries.size]
        val sessions = parseErsSessions(record.schedule)
        sessions.flatMapIndexed { sessionIndex, session ->
            session.days.map { day ->
                ScheduleEntry(
                    id = "ers-$subjectIndex-$sessionIndex-${day.name.lowercase(Locale.US)}-${session.startMinute}",
                    day = day,
                    startMinute = session.startMinute,
                    endMinute = session.endMinute,
                    courseCode = record.code,
                    title = record.title,
                    section = session.section,
                    instructor = record.faculty,
                    room = session.room,
                    accent = accent,
                )
            }
        }
    }

private data class ErsSession(
    val days: List<StudyDay>,
    val startMinute: Int,
    val endMinute: Int,
    val room: String,
    val section: String,
)

private val ersTimeRange = Regex(
    """(\d{1,2}:\d{2}\s*(?:AM|PM))\s*-\s*(\d{1,2}:\d{2}\s*(?:AM|PM))""",
    RegexOption.IGNORE_CASE,
)
private val ersTrailingWord = Regex("""([A-Za-z]+)\s*$""")
private val ersExtraSpaces = Regex("""\s+""")

/**
 * ERS crams every meeting of a subject into one cell, for example:
 * `BSECE-V-4A-V - BSECE-V-4A-V - M 01:00PM-04:45PM ECE LAB 2/TH 01:00PM-04:45PM 31`
 */
private fun parseErsSessions(raw: String): List<ErsSession> {
    val text = raw.trim()
    if (text.isEmpty()) return emptyList()

    val matches = ersTimeRange.findAll(text).toList()
    if (matches.isEmpty()) return emptyList()

    val dayStarts = IntArray(matches.size)
    val dayTokens = arrayOfNulls<String>(matches.size)

    matches.forEachIndexed { index, match ->
        val chunkStart = if (index == 0) 0 else matches[index - 1].range.last + 1
        val before = text.substring(chunkStart, match.range.first)
        val word = ersTrailingWord.find(before)
        dayStarts[index] = if (word == null) match.range.first else chunkStart + word.range.first
        dayTokens[index] = word?.value?.trim().orEmpty()
    }

    val section = text.substring(0, dayStarts[0])
        .split(" - ")
        .firstOrNull()
        ?.trim()
        ?.takeIf { it.length in 2..24 && it.contains("-") }
        .orEmpty()

    val sessions = mutableListOf<ErsSession>()

    matches.forEachIndexed { index, match ->
        val days = parseErsDayToken(dayTokens[index].orEmpty())
        if (days.isEmpty()) return@forEachIndexed

        val start = parseTimeToMinute(match.groupValues[1]) ?: return@forEachIndexed
        val end = parseTimeToMinute(match.groupValues[2]) ?: return@forEachIndexed
        if (end <= start) return@forEachIndexed

        val roomEnd = if (index + 1 < matches.size) dayStarts[index + 1] else text.length
        val from = match.range.last + 1
        val room = if (roomEnd > from) {
            text.substring(from, roomEnd)
                .trim()
                .trim('/', '-', ' ')
                .replace(ersExtraSpaces, " ")
        } else {
            ""
        }

        sessions += ErsSession(
            days = days,
            startMinute = start,
            endMinute = end,
            room = room,
            section = section,
        )
    }

    return sessions
}

private fun parseErsDayToken(token: String): List<StudyDay> {
    val value = token.trim().uppercase(Locale.US)
    if (value.isEmpty()) return emptyList()

    val days = mutableListOf<StudyDay>()
    var index = 0

    while (index < value.length) {
        when {
            value.startsWith("TH", index) -> {
                days += StudyDay.THURSDAY
                index += 2
            }
            value.startsWith("SAT", index) -> {
                days += StudyDay.SATURDAY
                index += 3
            }
            value.startsWith("SUN", index) -> index += 3
            value.startsWith("SU", index) -> index += 2
            value[index] == 'M' -> {
                days += StudyDay.MONDAY
                index++
            }
            value[index] == 'T' -> {
                days += StudyDay.TUESDAY
                index++
            }
            value[index] == 'W' -> {
                days += StudyDay.WEDNESDAY
                index++
            }
            value[index] == 'F' -> {
                days += StudyDay.FRIDAY
                index++
            }
            value[index] == 'S' -> {
                days += StudyDay.SATURDAY
                index++
            }
            else -> index++
        }
    }

    return days.distinct()
}

private fun readErsTerms(array: JSONArray?): List<ErsGradeTerm> {
    if (array == null) return emptyList()

    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val subjects = readErsRecords(item.optJSONArray("subjects"))
            if (subjects.isEmpty()) continue
            add(
                ErsGradeTerm(
                    schoolYear = item.optString("schoolYear").trim(),
                    term = item.optString("term").trim(),
                    gpa = item.optString("gpa").trim(),
                    subjects = subjects,
                ),
            )
        }
    }
}

private fun readErsRecords(array: JSONArray?): List<ErsSubjectRecord> {    if (array == null) return emptyList()

    return buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val record = ErsSubjectRecord(
                code = item.optString("code").trim(),
                title = item.optString("title").trim(),
                faculty = item.optString("faculty").trim(),
                units = item.optString("units").trim(),
                section = item.optString("section").trim(),
                schedule = item.optString("schedule").trim(),
                prelim = item.optString("prelim").trim(),
                midterm = item.optString("midterm").trim(),
                endterm = item.optString("endterm").trim(),
                average = item.optString("average").trim(),
                status = item.optString("status").trim(),
            )
            if (record.code.isBlank() && record.title.isBlank()) continue
            add(record)
        }
    }
}
