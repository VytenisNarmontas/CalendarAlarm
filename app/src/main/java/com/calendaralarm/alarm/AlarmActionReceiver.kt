package com.calendaralarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.calendaralarm.AppContainer
import com.calendaralarm.core.AlarmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SNOOZE && intent.action != ACTION_DISMISS) return
        val key = intent.getStringExtra(EXTRA_ALARM_KEY) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
              AppContainer.alarmMutex.withLock {
                val record = AppContainer.alarmStore.find(key) ?: return@withLock
                if (record.state != AlarmState.RINGING) return@withLock
                AppContainer.alarmScheduler.cancel(record)
                val updated = if (intent.action == ACTION_SNOOZE) {
                    val snoozed = record.copy(
                        state = AlarmState.SNOOZED,
                        snoozedUntilMillis = System.currentTimeMillis() + SNOOZE_MILLIS,
                        platformScheduled = false,
                    )
                    AppContainer.alarmStore.upsert(snoozed)
                    snoozed.copy(
                        platformScheduled = AppContainer.alarmScheduler.schedule(snoozed),
                    )
                } else {
                    record.copy(
                        state = AlarmState.DISMISSED,
                        snoozedUntilMillis = null,
                        platformScheduled = false,
                    )
                }
                AppContainer.alarmStore.upsert(updated)
                AppContainer.refreshRecords()
                AlarmPlaybackService.close(context.applicationContext, key)
                context.sendBroadcast(
                    Intent(AlarmActivity.ACTION_ALARM_CLOSED)
                        .setPackage(context.packageName)
                        .putExtra(EXTRA_ALARM_KEY, key),
                )
              }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SNOOZE = "com.calendaralarm.action.SNOOZE"
        const val ACTION_DISMISS = "com.calendaralarm.action.DISMISS"
        const val EXTRA_ALARM_KEY = "alarm_key"
        const val SNOOZE_MINUTES = 10
        private const val SNOOZE_MILLIS = SNOOZE_MINUTES * 60_000L
    }
}
