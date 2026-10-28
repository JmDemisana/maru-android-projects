package com.maru.study

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class StudySnapshot(
    val document: ScheduleDocument,
    val homework: List<HomeworkTask>,
    val grades: GradeSolverState,
    val exams: List<ExamEntry>,
)

/**
 * A batch of changes Nami asked for. Nothing touches storage until the user
 * confirms it in the native sheet.
 */
class StudyChangeProposal(
    val lines: List<String>,
    val title: String = "Nami wants to change your data",
    private val applyBlock: (StudySnapshot) -> StudySnapshot,
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    fun apply(snapshot: StudySnapshot): StudySnapshot = applyBlock(snapshot)

    fun withTitle(next: String): StudyChangeProposal =
        StudyChangeProposal(lines, next, applyBlock)

    fun withTitleAndLines(nextTitle: String, nextLines: List<String>): StudyChangeProposal =
        StudyChangeProposal(nextLines, nextTitle, applyBlock)
}

private data class ScheduleEdit(
    val add: List<ScheduleEntry>,
    val update: List<ScheduleEntry>,
    val deleteIds: List<String>,
    val replace: List<ScheduleEntry>?,
)

private data class HomeworkEdit(
    val add: List<HomeworkTask>,
    val update: List<HomeworkTask>,
    val deleteIds: List<String>,
)

private data class ExamEdit(
    val add: List<ExamEntry>,
    val update: List<ExamEntry>,
    val deleteIds: List<String>,
    val replace: List<ExamEntry>?,
)

fun parseStudyChange(raw: String, snapshot: StudySnapshot): StudyChangeProposal? {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null

    val scheduleBlock = root.optJSONObject("schedule")
    val homeworkBlock = root.optJSONObject("homework")
    val gradesBlock = root.optJSONObject("grades")
    val examsBlock = root.optJSONObject("exams")

    val schedule = parseScheduleEdit(scheduleBlock, snapshot.document.entries)
    val homework = parseHomeworkEdit(homeworkBlock, snapshot.homework)
    val grades = parseGradesEdit(gradesBlock, snapshot.grades)
    val exams = parseExamEdit(examsBlock, snapshot.exams)

    if (schedule == null && homework == null && grades == null && exams == null) return null

    val lines = buildList {
        schedule?.let { edit ->
            edit.replace?.let { entries ->
                add("Replace entire schedule · ${classCountLabel(entries.size)}")
                entries.sortedWith(compareBy({ it.day.ordinal }, { it.startMinute }))
                    .forEach { entry -> add("  · ${entryLabel(entry)}") }
            }
            edit.deleteIds.forEach { id ->
                val entry = snapshot.document.entries.firstOrNull { it.id == id }
                add("Remove class · ${entry?.let(::entryLabel) ?: id}")
            }
            edit.update.forEach { updated ->
                val before = snapshot.document.entries.firstOrNull { it.id == updated.id }
                add("Change class · ${before?.let(::entryLabel) ?: updated.id} → ${entryLabel(updated)}")
            }
            edit.add.forEach { entry -> add("Add class · ${entryLabel(entry)}") }
        }

        homework?.let { edit ->
            edit.deleteIds.forEach { id ->
                val task = snapshot.homework.firstOrNull { it.id == id }
                add("Remove homework · ${task?.title?.ifBlank { "Homework" } ?: id}")
            }
            edit.update.forEach { updated ->
                add("Change homework · ${homeworkLabel(updated)}")
            }
            edit.add.forEach { task -> add("Add homework · ${homeworkLabel(task)}") }
        }

        exams?.let { edit ->
            edit.replace?.let { entries ->
                add("Replace entire exam schedule · ${examCountLabel(entries.size)}")
                entries.sortedExams().forEach { exam -> add("  · ${examLabel(exam)}") }
            }
            edit.deleteIds.forEach { id ->
                val exam = snapshot.exams.firstOrNull { it.id == id }
                add("Remove exam · ${exam?.let(::examLabel) ?: id}")
            }
            edit.update.forEach { updated ->
                val before = snapshot.exams.firstOrNull { it.id == updated.id }
                add("Change exam · ${before?.let(::examLabel) ?: updated.id} → ${examLabel(updated)}")
            }
            edit.add.forEach { exam -> add("Add exam · ${examLabel(exam)}") }
        }

        grades?.let { edit ->
            edit.single?.let { single ->
                add("Set grade solver · ${single.course.ifBlank { "current subject" }} · ${gradeSummary(single)}")
            }
            if (edit.bulk.isNotEmpty()) {
                add("Replace saved grades · ${edit.bulk.size} subject(s)")
                edit.bulk.forEach { subject ->
                    add("  · ${subject.course.ifBlank { "Subject" }} · ${gradeSummary(subject)}")
                }
            }
        }
    }

    if (lines.isEmpty()) return null

    return StudyChangeProposal(lines) { current ->
        current.copy(
            document = schedule?.let { edit ->
                if (edit.replace != null) {
                    ScheduleDocument(
                        entries = edit.replace.sortedWith(
                            compareBy({ it.day.ordinal }, { it.startMinute }),
                        ),
                    )
                } else {
                    var entries = current.document.entries.filterNot { it.id in edit.deleteIds }
                    entries = entries.map { entry ->
                        edit.update.firstOrNull { it.id == entry.id } ?: entry
                    }
                    ScheduleDocument(entries = entries + edit.add)
                }
            } ?: current.document,
            homework = homework?.let { edit ->
                var tasks = current.homework.filterNot { it.id in edit.deleteIds }
                tasks = tasks.map { task ->
                    edit.update.firstOrNull { it.id == task.id } ?: task
                }
                tasks + edit.add
            } ?: current.homework,
            exams = exams?.let { edit ->
                if (edit.replace != null) {
                    edit.replace.sortedExams()
                } else {
                    var entries = current.exams.filterNot { it.id in edit.deleteIds }
                    entries = entries.map { exam ->
                        edit.update.firstOrNull { it.id == exam.id } ?: exam
                    }
                    (entries + edit.add).sortedExams()
                }
            } ?: current.exams,
            grades = grades?.let { edit ->
                var state = current.grades
                edit.single?.let { single ->
                    state = state.copy(
                        course = single.course,
                        prelim = single.prelim,
                        midterm = single.midterm,
                    )
                }
                if (edit.bulk.isNotEmpty()) {
                    state = state.copy(
                        bulkMode = true,
                        bulkSubjects = edit.bulk,
                        nextId = edit.bulk.size + 1,
                    )
                }
                state
            } ?: current.grades,
        )
    }
}

// ---------------------------------------------------------------------------
// Schedule
// ---------------------------------------------------------------------------

private fun parseScheduleEdit(block: JSONObject?, existing: List<ScheduleEntry>): ScheduleEdit? {
    if (block == null) return null

    val deletes = block.optJSONArray("delete").toStringList()
    val replace = block.optJSONArray("replace")?.toObjects()?.mapIndexedNotNull { index, item ->
        if (!item.has("day") && !item.has("title") && !item.has("courseCode")) {
            null
        } else {
            entryFromJson(item, "ai-${System.currentTimeMillis()}-r$index")
        }
    }
    val adds = block.optJSONArray("add").toObjects().mapIndexedNotNull { index, item ->
        if (!item.has("day") && !item.has("title") && !item.has("courseCode")) {
            null
        } else {
            entryFromJson(item, "ai-${System.currentTimeMillis()}-$index")
        }
    }
    val updates = block.optJSONArray("update").toObjects().mapNotNull { item ->
        val id = item.optString("id")
        val base = existing.firstOrNull { it.id == id } ?: return@mapNotNull null
        mergeEntry(base, item)
    }

    if (deletes.isEmpty() && adds.isEmpty() && updates.isEmpty() && replace == null) return null
    return ScheduleEdit(add = adds, update = updates, deleteIds = deletes, replace = replace)
}

private fun entryFromJson(item: JSONObject, id: String): ScheduleEntry {
    val day = parseDay(item.optString("day")) ?: StudyDay.MONDAY
    val start = parseTimeToMinute(item.optString("start")) ?: DAY_START_MINUTE
    val end = parseTimeToMinute(item.optString("end")) ?: (start + 90)
    val accent = runCatching {
        EntryAccent.valueOf(item.optString("accent").trim().uppercase(Locale.US))
    }.getOrDefault(EntryAccent.SKY)

    return ScheduleEntry(
        id = item.optString("id").ifBlank { id },
        day = day,
        startMinute = start,
        endMinute = end.coerceAtLeast(start + 15),
        courseCode = item.optString("courseCode"),
        title = item.optString("title"),
        section = item.optString("section"),
        instructor = item.optString("instructor"),
        room = item.optString("room"),
        notes = item.optString("notes"),
        accent = accent,
    )
}

private fun mergeEntry(base: ScheduleEntry, item: JSONObject): ScheduleEntry {
    val day = if (item.has("day")) parseDay(item.optString("day")) ?: base.day else base.day
    val start = if (item.has("start")) {
        parseTimeToMinute(item.optString("start")) ?: base.startMinute
    } else {
        base.startMinute
    }
    val end = if (item.has("end")) {
        parseTimeToMinute(item.optString("end")) ?: base.endMinute
    } else {
        base.endMinute
    }
    val accent = if (item.has("accent")) {
        runCatching {
            EntryAccent.valueOf(item.optString("accent").trim().uppercase(Locale.US))
        }.getOrDefault(base.accent)
    } else {
        base.accent
    }

    return base.copy(
        day = day,
        startMinute = start,
        endMinute = end.coerceAtLeast(start + 15),
        courseCode = item.stringOr("courseCode", base.courseCode),
        title = item.stringOr("title", base.title),
        section = item.stringOr("section", base.section),
        instructor = item.stringOr("instructor", base.instructor),
        room = item.stringOr("room", base.room),
        notes = item.stringOr("notes", base.notes),
        accent = accent,
    )
}

private fun entryLabel(entry: ScheduleEntry): String {
    val time = "${formatMinute(entry.startMinute)} - ${formatMinute(entry.endMinute)}"
    return "${entry.day.label} $time · ${entry.displayTitle}"
}

// ---------------------------------------------------------------------------
// Homework
// ---------------------------------------------------------------------------

private fun parseHomeworkEdit(block: JSONObject?, existing: List<HomeworkTask>): HomeworkEdit? {
    if (block == null) return null

    val deletes = block.optJSONArray("delete").toStringList()
    val adds = block.optJSONArray("add").toObjects().mapIndexedNotNull { index, item ->
        if (item.optString("title").isBlank()) return@mapIndexedNotNull null
        taskFromJson(item, "ai-hw-${System.currentTimeMillis()}-$index")
    }
    val updates = block.optJSONArray("update").toObjects().mapNotNull { item ->
        val id = item.optString("id")
        val base = existing.firstOrNull { it.id == id } ?: return@mapNotNull null
        mergeTask(base, item)
    }

    if (deletes.isEmpty() && adds.isEmpty() && updates.isEmpty()) return null
    return HomeworkEdit(add = adds, update = updates, deleteIds = deletes)
}

private fun taskFromJson(item: JSONObject, id: String): HomeworkTask = HomeworkTask(
    id = item.optString("id").ifBlank { id },
    title = item.optString("title"),
    subject = item.optString("subject"),
    dueAtMillis = parseDue(item.optString("due")),
    reminder = if (item.has("reminder")) item.optBoolean("reminder", true) else true,
    done = item.optBoolean("done", false),
    notes = item.optString("notes"),
)

private fun mergeTask(base: HomeworkTask, item: JSONObject): HomeworkTask = base.copy(
    title = item.stringOr("title", base.title),
    subject = item.stringOr("subject", base.subject),
    dueAtMillis = if (item.has("due")) parseDue(item.optString("due")) else base.dueAtMillis,
    reminder = if (item.has("reminder")) item.optBoolean("reminder", base.reminder) else base.reminder,
    done = if (item.has("done")) item.optBoolean("done", base.done) else base.done,
    notes = item.stringOr("notes", base.notes),
)

private fun homeworkLabel(task: HomeworkTask): String {
    val due = task.dueAtMillis?.let { millis ->
        java.time.Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    }
    return buildList {
        add(task.title.ifBlank { "Homework" })
        if (task.subject.isNotBlank()) add(task.subject)
        if (due != null) add("due $due")
        if (task.done) add("done")
    }.joinToString(" · ")
}

// ---------------------------------------------------------------------------
// Exams
// ---------------------------------------------------------------------------

private fun parseExamEdit(block: JSONObject?, existing: List<ExamEntry>): ExamEdit? {
    if (block == null) return null

    val deletes = block.optJSONArray("delete").toStringList()
    val replace = block.optJSONArray("replace")?.toObjects()?.mapIndexedNotNull { index, item ->
        if (item.optString("title").isBlank()) {
            null
        } else {
            examFromJson(item, "ai-exam-${System.currentTimeMillis()}-r$index")
        }
    }
    val adds = block.optJSONArray("add").toObjects().mapIndexedNotNull { index, item ->
        if (item.optString("title").isBlank()) {
            null
        } else {
            examFromJson(item, "ai-exam-${System.currentTimeMillis()}-$index")
        }
    }
    val updates = block.optJSONArray("update").toObjects().mapNotNull { item ->
        val id = item.optString("id")
        val base = existing.firstOrNull { it.id == id } ?: return@mapNotNull null
        mergeExam(base, item)
    }

    if (deletes.isEmpty() && adds.isEmpty() && updates.isEmpty() && replace == null) return null
    return ExamEdit(add = adds, update = updates, deleteIds = deletes, replace = replace)
}

private fun examFromJson(item: JSONObject, id: String): ExamEntry = ExamEntry(
    id = item.optString("id").ifBlank { id },
    title = item.optString("title"),
    date = normalizeExamDate(item.optString("date")),
    start = normalizeExamTime(item.optString("start")),
    end = normalizeExamTime(item.optString("end")),
    room = item.optString("room"),
    notes = item.optString("notes"),
)

private fun mergeExam(base: ExamEntry, item: JSONObject): ExamEntry = base.copy(
    title = item.stringOr("title", base.title),
    date = if (item.has("date")) normalizeExamDate(item.optString("date")) else base.date,
    start = if (item.has("start")) normalizeExamTime(item.optString("start")) else base.start,
    end = if (item.has("end")) normalizeExamTime(item.optString("end")) else base.end,
    room = item.stringOr("room", base.room),
    notes = item.stringOr("notes", base.notes),
)

private fun examLabel(exam: ExamEntry): String = buildList {
    add(exam.title.ifBlank { "Exam" })
    add(examDateLabel(exam.date))
    val time = examTimeRange(exam.start, exam.end)
    if (time.isNotBlank()) add(time)
    if (exam.room.isNotBlank()) add(exam.room)
}.joinToString(" · ")

private fun normalizeExamDate(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) return ""
    parseExamDate(value)?.let { return it.toString() }
    val withYear = runCatching {
        LocalDate.parse(
            value,
            DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US),
        )
    }.getOrNull()
    return withYear?.toString() ?: value
}

private fun normalizeExamTime(raw: String): String {
    val value = raw.trim()
    if (value.isBlank()) return ""
    val minute = parseTimeToMinute(value) ?: return value
    return "%02d:%02d".format(Locale.US, minute / 60, minute % 60)
}

// ---------------------------------------------------------------------------
// Grades
// ---------------------------------------------------------------------------

private data class GradesEdit(
    val single: BulkGradeSubject?,
    val bulk: List<BulkGradeSubject>,
)

private fun parseGradesEdit(block: JSONObject?, current: GradeSolverState): GradesEdit? {
    if (block == null) return null

    val single = block.optJSONObject("single")?.let { item ->
        BulkGradeSubject(
            id = 0,
            course = item.stringOr("course", current.course),
            prelim = item.stringOr("prelim", current.prelim),
            midterm = item.stringOr("midterm", current.midterm),
        )
    }

    val bulk = block.optJSONArray("bulk").toObjects().mapIndexed { index, item ->
        BulkGradeSubject(
            id = index + 1,
            course = item.optString("course"),
            prelim = item.optString("prelim"),
            midterm = item.optString("midterm"),
        )
    }

    if (single == null && bulk.isEmpty()) return null
    return GradesEdit(single = single, bulk = bulk)
}

private fun gradeSummary(subject: BulkGradeSubject): String {
    val required = computeRequiredEndterm(subject.prelim, subject.midterm)
    return "prelim ${subject.prelim.ifBlank { "--" }}, midterm ${subject.midterm.ifBlank { "--" }}, " +
        "needs ${formatRequiredEndterm(required)}"
}

// ---------------------------------------------------------------------------
// Tiny JSON helpers
// ---------------------------------------------------------------------------

private fun JSONObject.stringOr(key: String, fallback: String): String =
    if (has(key) && !isNull(key)) optString(key) else fallback

private fun JSONArray?.toObjects(): List<JSONObject> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { add(it) }
        }
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }
}

private fun parseDay(raw: String): StudyDay? {
    val value = raw.trim().uppercase(Locale.US)
    if (value.isBlank()) return null
    val short = value.take(3)
    return StudyDay.entries.firstOrNull { it.short == short } ?:
        StudyDay.entries.firstOrNull { it.label.uppercase(Locale.US).startsWith(short) }
}

internal fun parseTimeToMinute(raw: String): Int? {
    val value = raw.trim().uppercase(Locale.US).replace(".", "")
    if (value.isBlank()) return null

    val period = when {
        value.endsWith("AM") -> "AM"
        value.endsWith("PM") -> "PM"
        else -> ""
    }
    val core = if (period.isEmpty()) value else value.dropLast(2).trim()
    val parts = core.split(":")
    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0

    var hour24 = hour
    if (period == "AM" && hour == 12) hour24 = 0
    if (period == "PM" && hour < 12) hour24 = hour + 12

    return hour24.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59)
}

private fun parseDue(raw: String): Long? {
    val value = raw.trim()
    if (value.isBlank()) return null

    val zone = ZoneId.systemDefault()
    runCatching { LocalDateTime.parse(value) }.getOrNull()?.let { dateTime ->
        return dateTime.atZone(zone).toInstant().toEpochMilli()
    }
    runCatching { LocalDate.parse(value) }.getOrNull()?.let { date ->
        return date.atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
    }
    return null
}
