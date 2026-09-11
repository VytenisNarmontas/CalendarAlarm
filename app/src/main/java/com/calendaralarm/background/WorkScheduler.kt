package com.calendaralarm.background

import android.content.Context
import android.provider.CalendarContract
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.calendaralarm.platform.PermissionChecks
import java.util.concurrent.TimeUnit

object WorkScheduler {
    private const val PERIODIC_WORK = "calendar_alarm_periodic_reconcile"
    private const val CONTENT_WORK = "calendar_alarm_content_observer"
    private const val IMMEDIATE_WORK = "calendar_alarm_immediate_reconcile"

    fun ensureScheduled(context: Context) {
        val manager = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<ReconcileWorker>(
            15,
            TimeUnit.MINUTES,
            5,
            TimeUnit.MINUTES,
        ).build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        ensureContentObserver(context)
    }

    fun ensureContentObserver(context: Context) {
        if (!PermissionChecks.hasCalendar(context)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            CONTENT_WORK,
            ExistingWorkPolicy.KEEP,
            contentRequest(),
        )
    }

    fun enqueueImmediate(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ReconcileWorker>().build(),
        )
    }

    fun appendContentObserver(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CONTENT_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            contentRequest(),
        )
    }

    private fun contentRequest() = OneTimeWorkRequestBuilder<CalendarContentWorker>()
        .setConstraints(
            Constraints.Builder()
                // Providers may notify at the authority root (including attendee changes).
                .addContentUriTrigger(CalendarContract.CONTENT_URI, true)
                .setTriggerContentUpdateDelay(5, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(30, TimeUnit.SECONDS)
                .build(),
        )
        .build()
}
