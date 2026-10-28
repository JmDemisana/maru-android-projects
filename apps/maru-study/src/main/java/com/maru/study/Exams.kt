package com.maru.study

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ExamEntry(
    val id: String,
    val title: String = "",
    val date: String = "",
    val start: String = "",
    val end: String = "",
    val room: String = "",
    val notes: String = "",
)

class ExamStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("maru_study_exams", Context.MODE_PRIVATE)

    fun load(): List<ExamEntry> {
        val raw = prefs.getString(KEY_EXAMS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        ExamEntry(
                            id = item.optString("id").ifBlank { "exam-$index" },
                            title = item.optString("title"),
                            date = item.optString("date"),
                            start = item.optString("start"),
                            end = item.optString("end"),
                            room = item.optString("room"),
                            notes = item.optString("notes"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(exams: List<ExamEntry>) {
        val array = JSONArray()
        exams.forEach { exam ->
            array.put(
                JSONObject()
                    .put("id", exam.id)
                    .put("title", exam.title)
                    .put("date", exam.date)
                    .put("start", exam.start)
                    .put("end", exam.end)
                    .put("room", exam.room)
                    .put("notes", exam.notes),
            )
        }
        prefs.edit().putString(KEY_EXAMS, array.toString()).apply()
    }

    private companion object {
        const val KEY_EXAMS = "exam-schedule-v1"
    }
}

fun List<ExamEntry>.sortedExams(): List<ExamEntry> = sortedWith(
    compareBy(
        { it.date.ifBlank { "9999-99-99" } },
        { it.start.ifBlank { "99:99" } },
    ),
)

fun parseExamDate(raw: String): LocalDate? =
    runCatching { LocalDate.parse(raw.trim()) }.getOrNull()

fun examDateLabel(raw: String): String =
    parseExamDate(raw)?.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
        ?: raw.trim().ifBlank { "Date TBD" }

fun parseExamMinute(raw: String): Int? {
    val value = raw.trim()
    if (value.isBlank()) return null
    val parts = value.split(":")
    val hour = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
    val minute = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}

fun examTimeLabel(raw: String): String {
    val minute = parseExamMinute(raw) ?: return raw.trim()
    return formatMinute(minute)
}

fun examTimeRange(start: String, end: String): String {
    val startMinute = parseExamMinute(start)
    val endMinute = parseExamMinute(end)
    return when {
        startMinute == null && endMinute == null -> ""
        startMinute != null && endMinute == null -> formatMinute(startMinute)
        startMinute == null && endMinute != null -> "until ${formatMinute(endMinute)}"
        else -> "${formatMinute(startMinute!!)} - ${formatMinute(endMinute!!)}"
    }
}

fun examCountLabel(count: Int): String = if (count == 1) "1 exam" else "$count exams"
