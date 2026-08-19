package io.maru.lastnotif;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the poller service after device reboot or app update.
 */
public class LastNotifPollerBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // Only start if the service was running
            LastNotifStorage storage = new LastNotifStorage(context);
            if (storage.isServiceRunning()) {
                LastNotifPollerService.start(context);
                LastNotifPollerAlarmScheduler.schedule(context);
            }
        }
    }
}
