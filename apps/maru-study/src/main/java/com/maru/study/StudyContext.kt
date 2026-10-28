package com.maru.study

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the schedule + homework + grade summary that Nami can request from
 * the chat WebView. Kept compact so it costs very little context per turn.
 */
fun buildStudyContext(
    document: ScheduleDocument,
    homework: List<HomeworkTask>,
    grades: GradeSolverState,
    exams: List<ExamEntry>,
): String {
    val today = LocalDate.now()
    val dateText = today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US))
    val dayFormat = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    val lines = buildList {
        add("Today is $dateText.")

        add("")
        add("CLASS SCHEDULE:")
        if (document.entries.isEmpty()) {
            add("- No classes saved yet.")
        } else {
            document.sortedEntries().forEach { entry ->
                val bits = buildList {
                    add("${entry.day.label} ${formatMinute(entry.startMinute)} - ${formatMinute(entry.endMinute)}")
                    val name = entry.displayTitle
                    if (name.isNotBlank()) add(name)
                    if (entry.section.isNotBlank()) add(entry.section)
                    if (entry.instructor.isNotBlank()) add(entry.instructor)
                    if (entry.room.isNotBlank()) add(entry.room)
                    add("id ${entry.id}")
                }
                add("- " + bits.joinToString(" · "))
            }
        }

        add("")
        add("EXAM SCHEDULE:")
        if (exams.isEmpty()) {
            add("- No exams saved yet.")
        } else {
            exams.sortedExams().forEach { exam ->
                val bits = buildList {
                    add(examDateLabel(exam.date))
                    val title = exam.title.ifBlank { "Exam" }
                    add(title)
                    val time = examTimeRange(exam.start, exam.end)
                    if (time.isNotBlank()) add(time)
                    if (exam.room.isNotBlank()) add(exam.room)
                    if (exam.notes.isNotBlank()) add(exam.notes)
                    add("id ${exam.id}")
                }
                add("- " + bits.joinToString(" · "))
            }
        }

        add("")
        add("PENDING HOMEWORK:")
        val pending = homework.filterNot { it.done }
        if (pending.isEmpty()) {
            add("- Nothing pending.")
        } else {
            pending.sortedWith(compareBy(nullsLast()) { it.dueAtMillis }).forEach { task ->
                val due = task.dueAtMillis?.let { millis ->
                    Instant.ofEpochMilli(millis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .format(dayFormat)
                }
                val bits = buildList {
                    add(task.title.ifBlank { "Homework" })
                    if (task.subject.isNotBlank()) add(task.subject)
                    if (due != null) add("due $due")
                    if (task.done) add("done")
                    add("id ${task.id}")
                }
                add("- " + bits.joinToString(" · "))
            }
        }

        add("")
        add("GRADE SOLVER (required endterm, lower is easier):")
        val gradeLines = buildList {
            if (grades.course.isNotBlank() || grades.prelim.isNotBlank() || grades.midterm.isNotBlank()) {
                add(gradeLine(grades.course.ifBlank { "Current subject" }, grades.prelim, grades.midterm))
            }
            grades.bulkSubjects
                .filter { it.course.isNotBlank() || it.prelim.isNotBlank() || it.midterm.isNotBlank() }
                .forEach { add(gradeLine(it.course.ifBlank { "Saved subject" }, it.prelim, it.midterm)) }
        }
        if (gradeLines.isEmpty()) {
            add("- No grades saved yet.")
        } else {
            addAll(gradeLines)
        }

        add("")
        add("Homework tasks that are marked done are not listed.")
    }

    return lines.joinToString("\n")
}

private fun gradeLine(course: String, prelim: String, midterm: String): String {
    val required = computeRequiredEndterm(prelim, midterm)
    return "- $course · prelim ${gradeValue(prelim)} · midterm ${gradeValue(midterm)} · " +
        "needs ${formatRequiredEndterm(required)} on the endterm"
}

private fun gradeValue(raw: String): String =
    if (raw.isBlank()) "--" else raw.trim()
