package com.maru.study

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class HomeworkTask(
    val id: String,
    val title: String = "",
    val subject: String = "",
    val dueAtMillis: Long? = null,
    val reminder: Boolean = true,
    val done: Boolean = false,
    val notes: String = "",
)

class HomeworkStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("maru_study_homework", Context.MODE_PRIVATE)

    fun load(): List<HomeworkTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(parse(item))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(tasks: List<HomeworkTask>) {
        val array = JSONArray()
        tasks.forEach { task ->
            array.put(
                JSONObject()
                    .put("id", task.id)
                    .put("title", task.title)
                    .put("subject", task.subject)
                    .put("dueAtMillis", task.dueAtMillis ?: JSONObject.NULL)
                    .put("reminder", task.reminder)
                    .put("done", task.done)
                    .put("notes", task.notes)
            )
        }
        prefs.edit().putString(KEY_TASKS, array.toString()).apply()
    }

    private fun parse(item: JSONObject): HomeworkTask = HomeworkTask(
        id = item.optString("id").ifBlank { "task-${System.currentTimeMillis()}" },
        title = item.optString("title"),
        subject = item.optString("subject"),
        dueAtMillis = if (item.isNull("dueAtMillis")) null else item.optLong("dueAtMillis"),
        reminder = item.optBoolean("reminder", true),
        done = item.optBoolean("done", false),
        notes = item.optString("notes"),
    )

    private companion object {
        const val KEY_TASKS = "tasks"
    }
}

fun homeworkStatus(task: HomeworkTask): String = when {
    task.done -> "Done"
    task.dueAtMillis == null -> "No deadline"
    task.dueAtMillis < System.currentTimeMillis() -> "Overdue"
    else -> "Due ${dueLabel(task.dueAtMillis)}"
}
