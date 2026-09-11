package com.calendaralarm.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.calendaralarm.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val supported = intent.action in setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
        if (!supported) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // AlarmManager entries are gone after reboot or exact-access revocation; time
                // changes also require rebuilding the wall-clock schedule from Calendar Provider.
                WorkScheduler.ensureScheduled(context.applicationContext)
                runCatching { AppContainer.reconciler.reconcile("Device / permission change", forceRestore = true) }
                    .onFailure { WorkScheduler.enqueueImmediate(context.applicationContext) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
