package com.calendaralarm.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.calendaralarm.R
import com.calendaralarm.core.AlarmRecord
import com.calendaralarm.platform.PermissionChecks
import java.util.Date

object AlarmNotificationFactory {
    const val CHANNEL_ID = "calendar_alarm_alerts"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Calendar alarms",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Loud alarms for upcoming calendar events"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 500)
            // The service plays the default alarm sound on a loop. Channel sound is disabled
            // to avoid a second, non-looping copy from the notification framework.
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, record: AlarmRecord): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            AlarmIdentity.requestCode("screen:${record.key}"),
            Intent(context, AlarmActivity::class.java).apply {
                data = "calendaralarm://active/${Uri.encode(record.key)}".toUri()
                putExtra(AlarmActivity.EXTRA_ALARM_KEY, record.key)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozeIntent = actionPendingIntent(context, record.key, AlarmActionReceiver.ACTION_SNOOZE)
        val dismissIntent = actionPendingIntent(context, record.key, AlarmActionReceiver.ACTION_DISMISS)
        val startsAt = DateFormat.getTimeFormat(context).format(Date(record.occurrenceStartMillis))

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(record.eventTitle)
            .setContentText("Starts at $startsAt")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "${record.eventTitle}\nStarts at $startsAt",
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .addAction(0, "Snooze 10 minutes", snoozeIntent)
            .addAction(0, "Dismiss", dismissIntent)
            .apply {
                if (PermissionChecks.canUseFullScreenIntent(context)) {
                    setFullScreenIntent(contentIntent, true)
                }
            }
            .build()
    }

    fun notificationId(key: String): Int = AlarmIdentity.requestCode("notification:$key")

    private fun actionPendingIntent(context: Context, key: String, action: String): PendingIntent {
        val intent = Intent(context, AlarmActionReceiver::class.java).apply {
            this.action = action
            data = "calendaralarm://action/${action.substringAfterLast('.')}/${Uri.encode(key)}".toUri()
            putExtra(AlarmActionReceiver.EXTRA_ALARM_KEY, key)
        }
        return PendingIntent.getBroadcast(
            context,
            AlarmIdentity.requestCode("$action:$key"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
