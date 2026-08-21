package io.maru.lastnotif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LastNotifPollerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = LastNotifPrefs(context)
        CoroutineScope(Dispatchers.IO).launch {
            if (prefs.serviceRunning.first()) {
                LastNotifPollerService.start(context)
                LastNotifPollerAlarmScheduler.schedule(context)
            }
        }
    }
}
