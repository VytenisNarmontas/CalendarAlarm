package com.calendaralarm

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.calendaralarm.alarm.AlarmNotificationFactory
import com.calendaralarm.background.WorkScheduler
import com.calendaralarm.platform.PermissionChecks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CalendarAlarmApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observerRegistered = false
    private var observerJob: Job? = null

    private val calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            observerJob?.cancel()
            observerJob = scope.launch {
                delay(750)
                // Cancel only the debounce, never a reconciliation already modifying alarms.
                AppContainer.requestScan("Phone calendar changed")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppContainer.initialize(this)
        AlarmNotificationFactory.createChannel(this)
        WorkScheduler.ensureScheduled(this)
        ensureCalendarObserver()
        AppContainer.requestScan("App started")
    }

    fun ensureCalendarObserver() {
        if (observerRegistered || !PermissionChecks.hasCalendar(this)) return
        runCatching {
            contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI,
                true,
                calendarObserver,
            )
            observerRegistered = true
            WorkScheduler.ensureContentObserver(this)
        }
    }
}
