package com.maru.study

import android.content.Context

data class CachedErsSync(
    val result: ErsImportResult,
    val syncedAt: Long,
)

/**
 * Keeps the last successful ERS read on this phone so the schedule and grade
 * timeline can still be reviewed without a connection. It is only a preview
 * copy: applying it to Maru Study still goes through the normal confirmation.
 */
class ErsSyncCache(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("maru_study_ers_sync", Context.MODE_PRIVATE)

    fun save(payload: String) {
        prefs.edit()
            .putString(KEY_PAYLOAD, payload)
            .putLong(KEY_SYNCED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(): CachedErsSync? {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val syncedAt = prefs.getLong(KEY_SYNCED_AT, 0L)
        val result = runCatching { parseErsPayload(payload, ErsSyncMode.BOTH) }.getOrNull()
        if (result == null || result.isEmpty()) return null
        return CachedErsSync(result = result, syncedAt = syncedAt)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_PAYLOAD = "payload"
        const val KEY_SYNCED_AT = "synced_at"
    }
}
