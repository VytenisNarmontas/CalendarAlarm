package com.calendaralarm

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.app.KeyguardManager
import android.app.NotificationManager
import android.os.PowerManager
import com.calendaralarm.alarm.AlarmActionReceiver
import com.calendaralarm.alarm.AlarmNotificationFactory
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.calendaralarm.calendar.CalendarProviderSource
import com.calendaralarm.calendar.CalendarReconciler
import com.calendaralarm.calendar.CalendarSource
import com.calendaralarm.core.AlarmState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only synthetic Calendar Provider data. The shipped app has NO write permission. */
@RunWith(AndroidJUnit4::class)
class CalendarAlarmRegressionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver
    private var calendarId = -1L
    private var scenario: ActivityScenario<MainActivity>? = null
    private val account = "calendar-alarm-test@example.invalid"

    @Before fun setup() = runBlocking {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CALENDAR)
        AppContainer.alarmMutex.withLock {
            AppContainer.settingsRepository.setEnabled(true)
            AppContainer.settingsRepository.setOneHourEnabled(true)
            AppContainer.settingsRepository.setTenMinutesEnabled(true)
            AppContainer.alarmStore.all().forEach(AppContainer.alarmScheduler::cancel)
            AppContainer.alarmStore.deleteAll()
        }
        calendarId = ContentUris.parseId(requireNotNull(resolver.insert(syncUri(CalendarContract.Calendars.CONTENT_URI), ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, account)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google")
            put(CalendarContract.Calendars.NAME, "CalendarAlarm regression")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Verification Google calendar")
            put(CalendarContract.Calendars.OWNER_ACCOUNT, account)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        })))
    }

    @After fun cleanup() = runBlocking {
        scenario?.close()
        if (calendarId != -1L) resolver.delete(syncUri(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)), null, null)
        AppContainer.reconciler.reconcile("Test cleanup")
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    @Test fun allThreeSwitchesPersistAndActuallyChangePlatformSchedule() {
        event()
        openMain()
        awaitCount(2)
        toggle("1 hour before")
        awaitCount(1)
        assertEquals(setOf(10), AppContainer.records.value.map { it.offsetMinutes }.toSet())
        compose.onNodeWithTag("1 hour before").assertIsOff()
        toggle("10 minutes before")
        awaitCount(0)
        toggle("1 hour before")
        awaitCount(1)
        toggle("10 minutes before")
        awaitCount(2)
        toggle("Calendar alarms enabled")
        awaitCount(0)
        assertFalse(settings().enabled)
        scenario!!.recreate()
        compose.onNodeWithTag("Calendar alarms enabled").assertIsOff()
        toggle("Calendar alarms enabled")
        awaitCount(2)
        toggle("1 hour before")
        awaitCount(1)
        scenario!!.recreate()
        compose.onNodeWithTag("1 hour before").assertIsOff()
        compose.onNodeWithTag("10 minutes before").assertIsOn()
        assertFalse(settings().oneHourEnabled)
    }

    @Test fun settingChangesDuringBlockedScanAreNotDropped() {
        event()
        openMain()
        awaitCount(2)
        runBlocking { AppContainer.alarmMutex.lock() }
        try {
            AppContainer.requestScan("Queued scan regression")
            toggle("1 hour before")
            toggle("10 minutes before")
        } finally { AppContainer.alarmMutex.unlock() }
        awaitCount(0)
        assertFalse(settings().oneHourEnabled)
        assertFalse(settings().tenMinutesEnabled)
    }

    @Test fun rescanShowsSourceSuccessAndNoChangesAndObserverSeesMoveAndDelete() {
        val event = event()
        openMain()
        awaitCount(2)
        val originalKeys = AppContainer.records.value.map { it.key }.toSet()
        val previousCheck = settings().lastScanMillis!!
        compose.onNodeWithTag("rescan").performScrollTo().performClick()
        compose.waitUntil(15_000) { (settings().lastScanMillis ?: 0L) > previousCheck && !AppContainer.reconciler.progress.value.busy }
        compose.onNodeWithTag("scan_changes").assertTextContains("No alarm changes needed", substring = true)
        assertEquals(listOf("Verification Google calendar"), settings().lastScanReport!!.calendarNames)
        assertEquals(1, settings().lastScanReport!!.eventCount)
        val newStart = System.currentTimeMillis() + 4 * 60 * 60_000L
        resolver.update(event, ContentValues().apply {
            put(CalendarContract.Events.DTSTART, newStart)
            put(CalendarContract.Events.DTEND, newStart + 60 * 60_000L)
        }, null, null)
        // No manual rescan: the live Calendar Provider observer must detect this.
        compose.waitUntil(15_000) { AppContainer.records.value.size == 2 && AppContainer.records.value.none { it.key in originalKeys } }
        runBlocking {
            AppContainer.alarmMutex.withLock {
                val record = AppContainer.alarmStore.all().first().copy(state = AlarmState.SNOOZED, snoozedUntilMillis = System.currentTimeMillis() + 600_000L)
                AppContainer.alarmStore.upsert(record.copy(platformScheduled = AppContainer.alarmScheduler.schedule(record)))
                AppContainer.refreshRecords()
            }
        }
        resolver.delete(event, null, null)
        awaitCount(0)
        assertTrue(AppContainer.records.value.isEmpty())
    }

    @Test fun realProviderExpandsRecurrenceAndExcludesDeclinedAllDayAndLocalCalendars() {
        val start = System.currentTimeMillis() + 2 * 60 * 60_000L
        event(start, recurrence = true)
        event(start) // simultaneous occurrence with different event identity
        event(start, declined = true)
        event(start, allDay = true)
        runBlocking { AppContainer.reconciler.reconcile("Provider regression") }
        assertEquals(8, AppContainer.alarmStore.all().size) // three recurring + one ordinary, two offsets
        assertEquals(4, settings().lastScanReport!!.eventCount)
        val ids = CalendarProviderSource(context).queryInstances(System.currentTimeMillis()).instances
        assertEquals(3, ids.groupBy { it.eventId }.values.maxOf { it.size })
        resolver.update(syncUri(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)), ContentValues().apply {
            put(CalendarContract.Calendars.VISIBLE, 0)
        }, null, null)
        runBlocking { AppContainer.reconciler.reconcile("Hidden calendar") }
        assertTrue(AppContainer.alarmStore.all().isEmpty())
    }

    @Test fun failedProviderDoesNotEraseScheduleOrAdvanceLastSuccess() {
        event()
        openMain()
        awaitCount(2)
        val failing = CalendarReconciler(context, CalendarSource { error("Test provider unavailable") },
            AppContainer.alarmStore, AppContainer.settingsRepository, AppContainer.alarmScheduler,
            AppContainer::refreshRecords, AppContainer.alarmMutex)
        val before = settings().lastScanMillis
        val keys = AppContainer.records.value.map { it.key }
        runBlocking { try { failing.reconcile("Failure regression"); fail("Must report failure") } catch (_: IllegalStateException) { } }
        assertEquals(keys, AppContainer.records.value.map { it.key })
        assertEquals(before, settings().lastScanMillis)
        assertEquals("Test provider unavailable", settings().lastScanError)
        runBlocking { AppContainer.reconciler.reconcile("Retry regression") }
        assertNull(settings().lastScanError)
        assertEquals(2, AppContainer.records.value.size)
    }

    @Test fun exactAlarmWakesLockedEmulatorAndSnoozeAndDismissWork() {
        // Uses a real exact alarm, not a fake broadcast or an Activity launched by the test.
        val event = event(System.currentTimeMillis() + 10 * 60_000L + 10_000L)
        openMain()
        awaitCount(1)
        shell("input keyevent 223") // sleep, with keyguard enabled by the verification script
        compose.waitUntil(20_000) { AppContainer.records.value.any { it.state == AlarmState.RINGING } }
        compose.onNodeWithText("Snooze 10 minutes").assertExists()
        assertTrue(context.getSystemService(PowerManager::class.java).isInteractive)
        assertTrue(context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications
            .first { it.notification.channelId == AlarmNotificationFactory.CHANNEL_ID }.notification
        assertEquals(android.app.Notification.CATEGORY_ALARM, notification.category)
        assertEquals(2, notification.actions.size)
        val beforeSnooze = System.currentTimeMillis()
        compose.onNodeWithText("Snooze 10 minutes").performClick()
        compose.waitUntil(10_000) { AppContainer.records.value.any { it.state == AlarmState.SNOOZED && it.platformScheduled } }
        val snoozed = AppContainer.records.value.single()
        assertTrue(snoozed.snoozedUntilMillis!! in beforeSnooze + 600_000L..System.currentTimeMillis() + 600_000L)
        // Simulate the passage of snooze time by shortening ONLY the test record's fire time.
        // Production snooze is still ten minutes (asserted above).
        runBlocking {
            AppContainer.alarmMutex.withLock {
                AppContainer.alarmScheduler.cancel(snoozed)
                val shortened = snoozed.copy(snoozedUntilMillis = System.currentTimeMillis() + 5_000L)
                AppContainer.alarmStore.upsert(shortened.copy(platformScheduled = AppContainer.alarmScheduler.schedule(shortened)))
                AppContainer.refreshRecords()
            }
        }
        compose.waitUntil(15_000) { AppContainer.records.value.any { it.state == AlarmState.RINGING } }
        compose.onNodeWithText("Dismiss").performClick()
        compose.waitUntil(10_000) { AppContainer.records.value.any { it.state == AlarmState.DISMISSED } }
        compose.waitUntil(10_000) { context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.notification.channelId == AlarmNotificationFactory.CHANNEL_ID } }
        resolver.delete(event, null, null)
        shell("input keyevent 224")
        shell("wm dismiss-keyguard")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun openMain() {
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java))
    }
    private fun toggle(tag: String) { compose.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun awaitCount(count: Int) {
        compose.waitUntil(20_000) { AppContainer.records.value.count { it.platformScheduled } == count && !AppContainer.reconciler.progress.value.busy }
        compose.waitForIdle()
    }
    private fun settings() = runBlocking { AppContainer.settingsRepository.settings.first() }
    private fun syncUri(uri: Uri): Uri = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, "com.google").build()
    private fun event(start: Long = System.currentTimeMillis() + 2 * 60 * 60_000L, recurrence: Boolean = false, declined: Boolean = false, allDay: Boolean = false): Uri {
        val actualStart = if (allDay) start / 86_400_000L * 86_400_000L else start
        return requireNotNull(resolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, "Verification lesson")
            put(CalendarContract.Events.DTSTART, actualStart)
            put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(CalendarContract.Events.SELF_ATTENDEE_STATUS, if (declined) 2 else 1)
            if (recurrence) {
                put(CalendarContract.Events.RRULE, "FREQ=DAILY;COUNT=3")
                put(CalendarContract.Events.DURATION, "PT3600S")
            } else put(CalendarContract.Events.DTEND, actualStart + if (allDay) 86_400_000L else 3_600_000L)
        }))
    }
}
