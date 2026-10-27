package com.maru.study

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class StudyStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("maru_study_v2", Context.MODE_PRIVATE)

    fun load(): ScheduleDocument {
        val raw = prefs.getString(KEY_DOCUMENT, null) ?: return defaultDocument()
        return runCatching { parseDocument(JSONObject(raw)) }.getOrDefault(defaultDocument())
    }

    fun save(document: ScheduleDocument) {
        prefs.edit().putString(KEY_DOCUMENT, serializeDocument(document).toString()).apply()
    }

    private fun parseDocument(root: JSONObject): ScheduleDocument {
        val entriesJson = root.optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until entriesJson.length()) {
                val item = entriesJson.optJSONObject(index) ?: continue
                add(parseEntry(item))
            }
        }
        return ScheduleDocument(entries = entries)
    }

    private fun parseEntry(item: JSONObject): ScheduleEntry {
        val day = runCatching {
            StudyDay.valueOf(item.optString("day", StudyDay.MONDAY.name).uppercase())
        }.getOrDefault(StudyDay.MONDAY)
        val accent = runCatching {
            EntryAccent.valueOf(item.optString("accent", EntryAccent.SKY.name).uppercase())
        }.getOrDefault(EntryAccent.SKY)
        val start = item.optInt("startMinute", DAY_START_MINUTE)
        val end = item.optInt("endMinute", start + 90)
        return ScheduleEntry(
            id = item.optString("id").ifBlank { "entry-${System.currentTimeMillis()}-$start" },
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

    private fun serializeDocument(document: ScheduleDocument): JSONObject {
        val entries = JSONArray()
        document.sortedEntries().forEach { entry ->
            entries.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("day", entry.day.name)
                    .put("startMinute", entry.startMinute)
                    .put("endMinute", entry.endMinute)
                    .put("courseCode", entry.courseCode)
                    .put("title", entry.title)
                    .put("section", entry.section)
                    .put("instructor", entry.instructor)
                    .put("room", entry.room)
                    .put("notes", entry.notes)
                    .put("accent", entry.accent.name)
            )
        }

        return JSONObject()
            .put("version", 3)
            .put("entries", entries)
    }

    private companion object {
        const val KEY_DOCUMENT = "schedule-document-v2"
    }
}
