package com.maru.study

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------
// Reminder preferences
// ---------------------------------------------------------------------------

data class ReminderSettings(
    val enabled: Boolean = true,
    val leadMinutes: Int = 15,
)

class ReminderStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("maru_study_reminders", Context.MODE_PRIVATE)

    fun load(): ReminderSettings = ReminderSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        leadMinutes = prefs.getInt(KEY_LEAD, 15),
    )

    fun save(settings: ReminderSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_LEAD, settings.leadMinutes)
            .apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_LEAD = "lead"
    }
}

// ---------------------------------------------------------------------------
// Scheduling
// ---------------------------------------------------------------------------

object MaruReminders {

    const val CHANNEL_CLASS = "class_reminders"
    const val CHANNEL_HOMEWORK = "homework_reminders"

    private const val DAYS_AHEAD = 14
    private const val CLASS_ID_BASE = 100_000

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CLASS, "Class reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders before a scheduled class."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_HOMEWORK, "Homework reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders for homework deadlines."
            }
        )
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun rescheduleAll(context: Context) {
        ensureChannels(context)
        scheduleClasses(
            context = context,
            document = StudyStore(context).load(),
            settings = ReminderStore(context).load(),
        )
        HomeworkStore(context).load().forEach { task ->
            if (!task.done) scheduleHomework(context, task)
        }
    }

    fun scheduleClasses(
        context: Context,
        document: ScheduleDocument,
        settings: ReminderSettings,
    ) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val today = LocalDate.now()
        val nowMillis = System.currentTimeMillis()

        // Clear the window we own, then rebuild it.
        for (offset in 0..DAYS_AHEAD) {
            document.entries.forEach { entry ->
                alarms.cancel(classPendingIntent(context, entry, offset))
            }
        }

        if (!settings.enabled) return

        for (offset in 0..DAYS_AHEAD) {
            val date = today.plusDays(offset.toLong())
            val day = StudyDay.fromDate(date) ?: continue
            document.entriesFor(day).forEach { entry ->
                val startMillis = date
                    .atTime(LocalTime.of(entry.startMinute / 60, entry.startMinute % 60))
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
                val triggerAt = startMillis - settings.leadMinutes * 60_000L
                if (triggerAt <= nowMillis + 5_000L) return@forEach

                val intent = Intent(context, ReminderReceiver::class.java).apply {
                    putExtra(EXTRA_ID, CLASS_ID_BASE + entry.id.hashCode() + offset)
                    putExtra(EXTRA_CHANNEL, CHANNEL_CLASS)
                    putExtra(EXTRA_TITLE, "${entry.displayTitle} in ${settings.leadMinutes} min")
                    putExtra(
                        EXTRA_TEXT,
                        buildString {
                            append(formatTimeRange(entry.startMinute, entry.endMinute))
                            if (entry.room.isNotBlank()) append("  ·  ${entry.room}")
                        },
                    )
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    CLASS_ID_BASE + entry.id.hashCode() + offset,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }
    }

    fun scheduleHomework(context: Context, task: HomeworkTask) {
        val due = task.dueAtMillis ?: return
        val triggerAt = due - HOMEWORK_LEAD_MS
        if (triggerAt <= System.currentTimeMillis() + 5_000L) return

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_ID, taskId(task))
            putExtra(EXTRA_CHANNEL, CHANNEL_HOMEWORK)
            putExtra(EXTRA_TITLE, "Homework due soon")
            putExtra(
                EXTRA_TEXT,
                buildString {
                    append(task.title)
                    if (task.subject.isNotBlank()) append("  ·  ${task.subject}")
                    append("  ·  ").append(dueLabel(task.dueAtMillis))
                },
            )
        }
        val pending = PendingIntent.getBroadcast(
            context,
            taskId(task),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(AlarmManager::class.java)
            ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
    }

    fun cancelHomework(context: Context, task: HomeworkTask) {
        val pending = PendingIntent.getBroadcast(
            context,
            taskId(task),
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        context.getSystemService(AlarmManager::class.java)?.cancel(pending)
    }

    fun notify(
        context: Context,
        id: Int,
        channel: String,
        title: String,
        text: String,
    ) {
        if (!canNotify(context)) return
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            id,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    private fun classPendingIntent(
        context: Context,
        entry: ScheduleEntry,
        offset: Int,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        CLASS_ID_BASE + entry.id.hashCode() + offset,
        Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun taskId(task: HomeworkTask): Int = HOMEWORK_ID_BASE + task.id.hashCode()

    private const val HOMEWORK_ID_BASE = 500_000
    private const val HOMEWORK_LEAD_MS = 30 * 60_000L
}

const val EXTRA_ID = "reminder_id"
const val EXTRA_CHANNEL = "reminder_channel"
const val EXTRA_TITLE = "reminder_title"
const val EXTRA_TEXT = "reminder_text"

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 1)
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: MaruReminders.CHANNEL_CLASS
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Maru Study"
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        MaruReminders.notify(context, id, channel, title, text)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            MaruReminders.rescheduleAll(context)
        }
    }
}

fun dueLabel(millis: Long?): String {
    if (millis == null) return "No deadline"
    val dateTime = java.time.Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
    return dateTime.format(
        DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)
    )
}
