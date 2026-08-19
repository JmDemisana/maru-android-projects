package io.maru.lastnotif;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Schedules a repeating inexact alarm every ~5 minutes to wake up and
 * restart the poller service if it has been killed by the OS.
 */
public class LastNotifPollerAlarmScheduler {

    private static final long INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes

    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        PendingIntent pi = buildPendingIntent(context);

        // Cancel existing before re-scheduling
        am.cancel(pi);

        long triggerAtMillis = SystemClock.elapsedRealtime() + INTERVAL_MS;

        // Inexact allow-while-idle alarm: no exact-alarm permission needed, still wakes in Doze.
        try {
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pi
            );
        } catch (SecurityException se) {
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAtMillis,
                pi
            );
        }
    }

    public static void cancel(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(buildPendingIntent(context));
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, LastNotifPollerAlarmReceiver.class);
        return PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
    }
}
