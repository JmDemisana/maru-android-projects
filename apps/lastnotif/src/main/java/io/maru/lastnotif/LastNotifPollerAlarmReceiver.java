package io.maru.lastnotif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives the 5-minute keepalive alarm and restarts the poller service
 * if it is not already running.
 */
public class LastNotifPollerAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LastNotifStorage storage = new LastNotifStorage(context);
        if (storage.isServiceRunning()) {
            LastNotifPollerService.start(context);
            LastNotifPollerAlarmScheduler.schedule(context);
        }
    }
}
