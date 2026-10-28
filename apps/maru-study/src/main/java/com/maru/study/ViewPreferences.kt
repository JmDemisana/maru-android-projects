package com.maru.study

import android.content.Context

/**
 * Small UI preferences that should survive an app restart, such as the
 * schedule layout the user last picked.
 */
internal class ViewPreferences(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("maru_study_ui", Context.MODE_PRIVATE)

    fun scheduleView(): ScheduleViewMode {
        val stored = prefs.getString(KEY_SCHEDULE_VIEW, null) ?: return ScheduleViewMode.GRID
        return runCatching { ScheduleViewMode.valueOf(stored) }.getOrDefault(ScheduleViewMode.GRID)
    }

    fun setScheduleView(mode: ScheduleViewMode) {
        prefs.edit().putString(KEY_SCHEDULE_VIEW, mode.name).apply()
    }

    private companion object {
        const val KEY_SCHEDULE_VIEW = "schedule_view"
    }
}
