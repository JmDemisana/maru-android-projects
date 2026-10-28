package com.maru.study

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BulkGradeSubject(
    val id: Int,
    val course: String = "",
    val prelim: String = "",
    val midterm: String = "",
)

data class GradeSolverState(
    val bulkMode: Boolean = false,
    val course: String = "",
    val prelim: String = "",
    val midterm: String = "",
    val nextId: Int = 2,
    val bulkSubjects: List<BulkGradeSubject> = listOf(BulkGradeSubject(id = 1)),
)

/**
 * Keeps the grade solver exactly where Maru left it. Previously the inputs
 * lived only in composition state, so switching tabs cleared them.
 */
class GradeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("maru_study_grades", Context.MODE_PRIVATE)

    fun load(): GradeSolverState {
        val raw = prefs.getString(KEY_STATE, null) ?: return GradeSolverState()
        return runCatching { parse(JSONObject(raw)) }.getOrDefault(GradeSolverState())
    }

    fun save(state: GradeSolverState) {
        prefs.edit().putString(KEY_STATE, serialize(state).toString()).apply()
    }

    private fun parse(root: JSONObject): GradeSolverState {
        val subjectsJson = root.optJSONArray("bulkSubjects") ?: JSONArray()
        val subjects = buildList {
            for (index in 0 until subjectsJson.length()) {
                val item = subjectsJson.optJSONObject(index) ?: continue
                add(
                    BulkGradeSubject(
                        id = item.optInt("id", index + 1),
                        course = item.optString("course"),
                        prelim = item.optString("prelim"),
                        midterm = item.optString("midterm"),
                    ),
                )
            }
        }.ifEmpty { listOf(BulkGradeSubject(id = 1)) }

        return GradeSolverState(
            bulkMode = root.optBoolean("bulkMode", false),
            course = root.optString("course"),
            prelim = root.optString("prelim"),
            midterm = root.optString("midterm"),
            nextId = root.optInt("nextId", (subjects.maxOfOrNull { it.id } ?: 1) + 1),
            bulkSubjects = subjects,
        )
    }

    private fun serialize(state: GradeSolverState): JSONObject {
        val subjects = JSONArray()
        state.bulkSubjects.forEach { subject ->
            subjects.put(
                JSONObject()
                    .put("id", subject.id)
                    .put("course", subject.course)
                    .put("prelim", subject.prelim)
                    .put("midterm", subject.midterm),
            )
        }

        return JSONObject()
            .put("version", 1)
            .put("bulkMode", state.bulkMode)
            .put("course", state.course)
            .put("prelim", state.prelim)
            .put("midterm", state.midterm)
            .put("nextId", state.nextId)
            .put("bulkSubjects", subjects)
    }

    private companion object {
        const val KEY_STATE = "grade-solver-state-v1"
    }
}
