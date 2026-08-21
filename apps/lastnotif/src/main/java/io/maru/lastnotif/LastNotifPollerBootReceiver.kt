package io.maru.lastnotif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LastNotifPollerBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = LastNotifPrefs(context)
            CoroutineScope(Dispatchers.IO).launch {
                if (prefs.serviceRunning.first()) {
                    LastNotifPollerService.start(context)
                    LastNotifPollerAlarmScheduler.schedule(context)
                }
            }
        }
    }
}
