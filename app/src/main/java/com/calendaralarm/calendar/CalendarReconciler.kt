package com.calendaralarm.calendar

import android.content.Context
import com.calendaralarm.alarm.AlarmPlaybackService
import com.calendaralarm.alarm.AndroidAlarmScheduler
import com.calendaralarm.core.AlarmPlanner
import com.calendaralarm.core.ReconciliationEngine
import com.calendaralarm.core.ScanProgress
import com.calendaralarm.core.ScanReport
import com.calendaralarm.data.AlarmStore
import com.calendaralarm.data.SettingsRepository
import com.calendaralarm.platform.PermissionChecks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ReconcileSummary(val scheduled: Int, val cancelled: Int, val stored: Int)

class CalendarReconciler(
    private val context: Context,
    private val calendarSource: CalendarSource,
    private val store: AlarmStore,
    private val settingsRepository: SettingsRepository,
    private val alarmScheduler: AndroidAlarmScheduler,
    private val onRecordsChanged: suspend () -> Unit,
    private val mutex: Mutex = Mutex(),
) {
    private val mutableProgress = MutableStateFlow(ScanProgress())
    val progress = mutableProgress.asStateFlow()

    fun reportActionError(message: String) {
        mutableProgress.value = ScanProgress(actionError = message)
    }

    // Shared with delivery/actions: an old snooze action cannot resurrect a deleted event.
    // Requests wait for this lock; none are dropped because a scan is in flight.
    suspend fun reconcile(reason: String = "Automatic check", forceRestore: Boolean = false): ReconcileSummary =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                mutableProgress.value = ScanProgress("Reading Google calendars stored on this phone…")
                try {
                    val settings = settingsRepository.settings.first()
                    val calendarGranted = PermissionChecks.hasCalendar(context)
                    val before = store.all()
                    // Turning off must work even if the provider is unavailable.
                    if (!settings.enabled || !calendarGranted) {
                        before.forEach(alarmScheduler::cancel)
                        store.deleteAll()
                        AlarmPlaybackService.stopAll(context)
                        onRecordsChanged()
                    } else {
                        // A reminder switch also takes effect even if the calendar read fails.
                        val disabled = before.filter { it.offsetMinutes !in settings.enabledOffsets }
                        disabled.forEach {
                            alarmScheduler.cancel(it)
                            AlarmPlaybackService.close(context, it.key)
                        }
                        store.delete(disabled.map { it.key })
                        onRecordsChanged()
                    }
                    if (!calendarGranted) {
                        settingsRepository.recordScan(now, "Calendar access is missing. Allow it below to read events.")
                        return@withLock ReconcileSummary(0, before.size, 0)
                    }
                    // A failed or null query is NOT an empty calendar. Keep the saved schedule.
                    val snapshot = calendarSource.queryInstances(now)
                    mutableProgress.value = ScanProgress("Comparing events and updating exact alarms…")
                    if (forceRestore || !PermissionChecks.canScheduleExactAlarms(context)) {
                        store.markAllPlatformUnscheduled()
                    }
                    val offsets = if (settings.enabled) settings.enabledOffsets else emptySet()
                    val planning = AlarmPlanner.plan(snapshot.instances, offsets, now)
                    val existing = store.all()
                    val plan = ReconciliationEngine.plan(
                        planning.desiredAlarms, planning.eligibleOccurrenceKeys, offsets, existing, now,
                    )
                    val newKeys = plan.toSchedule.mapTo(mutableSetOf()) { it.key }
                    plan.toCancel.forEach { record ->
                        alarmScheduler.cancel(record)
                        if (record.key !in newKeys) AlarmPlaybackService.close(context, record.key)
                    }
                    store.delete(plan.deleteKeys)
                    store.upsertAll(plan.toUpsert)
                    // Persist intent first, then acknowledge each platform registration individually.
                    // Process death or partial failure can be repaired by the next scan.
                    store.upsertAll(plan.toSchedule.map { it.copy(platformScheduled = false) })
                    var scheduled = 0
                    plan.toSchedule.forEach { record ->
                        val registered = alarmScheduler.schedule(record)
                        store.upsert(record.copy(platformScheduled = registered))
                        if (registered) scheduled++
                    }
                    val previousKeys = before.mapTo(mutableSetOf()) { it.key }
                    val after = store.all()
                    val remainingKeys = after.mapTo(mutableSetOf()) { it.key }
                    val changedKeys = (plan.toSchedule + plan.toUpsert).mapTo(mutableSetOf()) { it.key }
                    val report = ScanReport(
                        calendarNames = snapshot.calendarNames,
                        eventCount = snapshot.instances.distinctBy { it.occurrenceKey }.count {
                            it.occurrenceStartMillis > now && it.occurrenceKey in planning.eligibleOccurrenceKeys
                        },
                        added = (remainingKeys - previousKeys).size,
                        updated = (changedKeys intersect previousKeys).size,
                        removed = (previousKeys - remainingKeys).size,
                        unchanged = ((remainingKeys intersect previousKeys) - changedKeys).size,
                        reason = reason,
                    )
                    settingsRepository.recordScan(System.currentTimeMillis(), null, report)
                    ReconcileSummary(scheduled, report.removed, after.size)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    settingsRepository.recordScan(System.currentTimeMillis(), error.message ?: "Calendar scan failed. Please try again.")
                    throw error
                } finally {
                    onRecordsChanged()
                    mutableProgress.value = ScanProgress()
                }
            }
        }
}
