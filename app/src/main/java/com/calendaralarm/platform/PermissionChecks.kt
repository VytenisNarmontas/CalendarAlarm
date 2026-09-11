package com.calendaralarm.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

data class PermissionSnapshot(
    val calendarGranted: Boolean,
    val notificationsGranted: Boolean,
    val exactAlarmsGranted: Boolean,
    val fullScreenGranted: Boolean,
) {
    val operational: Boolean
        get() = calendarGranted && notificationsGranted && exactAlarmsGranted && fullScreenGranted
}

object PermissionChecks {
    fun snapshot(context: Context) = PermissionSnapshot(
        calendarGranted = hasCalendar(context),
        notificationsGranted = hasNotifications(context),
        exactAlarmsGranted = canScheduleExactAlarms(context),
        fullScreenGranted = canUseFullScreenIntent(context),
    )

    fun hasCalendar(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasNotifications(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
        (context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(com.calendaralarm.alarm.AlarmNotificationFactory.CHANNEL_ID)?.importance
            ?: NotificationManager.IMPORTANCE_HIGH) >= NotificationManager.IMPORTANCE_HIGH &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED)

    fun canScheduleExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun canUseFullScreenIntent(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}
