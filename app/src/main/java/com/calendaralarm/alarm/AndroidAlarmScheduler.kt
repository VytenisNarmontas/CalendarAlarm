package com.calendaralarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.calendaralarm.MainActivity
import com.calendaralarm.core.AlarmRecord
import com.calendaralarm.core.AlarmState
import com.calendaralarm.platform.PermissionChecks

class AndroidAlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun schedule(record: AlarmRecord, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!PermissionChecks.canScheduleExactAlarms(context)) return false
        val fireAt = when (record.state) {
            AlarmState.SNOOZED -> record.snoozedUntilMillis ?: return false
            AlarmState.SCHEDULED -> record.triggerAtMillis
            AlarmState.RINGING,
            AlarmState.DISMISSED,
            -> return false
        }
        if (fireAt <= nowMillis) return false

        val operation = firePendingIntent(record, snoozed = record.state == AlarmState.SNOOZED)
        val showIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(fireAt, showIntent), operation)
        return true
    }

    fun cancel(record: AlarmRecord) {
        alarmManager.cancel(firePendingIntent(record, snoozed = false))
        alarmManager.cancel(firePendingIntent(record, snoozed = true))
    }

    private fun firePendingIntent(record: AlarmRecord, snoozed: Boolean): PendingIntent {
        val kind = if (snoozed) "snooze" else "event"
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            data = "calendaralarm://$kind/${Uri.encode(record.key)}".toUri()
            putExtra(AlarmReceiver.EXTRA_ALARM_KEY, record.key)
            putExtra(AlarmReceiver.EXTRA_TRIGGER, record.effectiveTriggerAtMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            AlarmIdentity.requestCode("$kind:${record.key}"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
