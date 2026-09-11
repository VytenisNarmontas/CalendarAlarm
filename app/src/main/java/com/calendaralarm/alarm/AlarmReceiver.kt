package com.calendaralarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.calendaralarm.AppContainer
import com.calendaralarm.core.AlarmState
import com.calendaralarm.platform.PermissionChecks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val key = intent.getStringExtra(EXTRA_ALARM_KEY) ?: return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
              AppContainer.alarmMutex.withLock {
                val record = AppContainer.alarmStore.find(key) ?: return@withLock
                val settings = AppContainer.settingsRepository.settings.first()
                if (!settings.enabled || record.offsetMinutes !in settings.enabledOffsets || !PermissionChecks.hasCalendar(context)) {
                    AppContainer.alarmScheduler.cancel(record)
                    AppContainer.alarmStore.delete(listOf(key))
                    AppContainer.refreshRecords()
                    return@withLock
                }
                if (record.state == AlarmState.DISMISSED || record.state == AlarmState.RINGING) return@withLock
                if (record.effectiveTriggerAtMillis > System.currentTimeMillis() + 1_000L) return@withLock
                val expectedTrigger = intent.getLongExtra(EXTRA_TRIGGER, record.effectiveTriggerAtMillis)
                if (expectedTrigger != record.effectiveTriggerAtMillis) return@withLock
                val ringing = record.copy(
                    state = AlarmState.RINGING,
                    snoozedUntilMillis = null,
                    platformScheduled = false,
                )
                AppContainer.alarmStore.upsert(ringing)
                AppContainer.refreshRecords()
                AlarmPlaybackService.start(context.applicationContext, ringing)
              }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.calendaralarm.action.FIRE"
        const val EXTRA_ALARM_KEY = "alarm_key"
        const val EXTRA_TRIGGER = "expected_trigger"
    }
}
