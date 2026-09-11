# Calendar Alarm

## Update 1.1

The three switches now save their selected value and reconcile the real alarm schedule. The whole labelled row is tappable. Settings changes are queued in order and survive leaving/recreating the screen; a running scan no longer drops a change. Turning off alarms or an individual offset cancels the corresponding alarms even if the calendar provider fails.

Rescan now shows its source, progress, last successful check (with seconds), calendar names, the number of eligible event occurrences in the next 30 days, and alarms added/updated/removed/unchanged. A failed read does not erase existing alarms or falsely advance the last-success time. The app distinguishes paused alarms, missing permissions, no calendars and read errors. Scan summaries stay on the device and are excluded from backup.

### Getting this update onto your phone

The APK is `C:\dev\CalendarAlarm\app\build\outputs\apk\debug\app-debug.apk` (version 1.1 / versionCode 2). Copy it to the phone using USB, open it in Files, allow installation from Files if Android asks, and choose **Update**. Do not uninstall first: an in-place update keeps settings. Alternatively use `adb install -r` as shown below. The separate `app-debug-androidTest.apk` is a test harness, not the app; do not install it on your personal phone.

### What Rescan does—and does not do

Calendar Alarm reads Android's on-phone Calendar Provider. It cannot see an event edited on another device until Google's calendar sync has saved that edit on this phone. It has no Internet permission and does not fetch Google data itself. A successful check means the **local calendar was read**, not that Google's servers were contacted. Android's read-only Calendar Provider does not give this app an authoritative last-Google-sync timestamp.

If a change is missing, open Google Calendar, confirm the calendar's Sync setting is enabled, and refresh it. The app provides **Open Google Calendar** and **Sync settings** shortcuts. Returning here checks again, and provider changes also trigger automatic checks. [Google's sync troubleshooting](https://support.google.com/calendar/answer/6261951) explains the settings; [Android's Calendar Provider documentation](https://developer.android.com/identity/providers/calendar-provider) describes the local data source.

Calendar Alarm is a deliberately small Android app that reads events from Google calendars already synchronized to the phone and schedules alarm-clock-style alerts 60 minutes and 10 minutes before each timed event.

## Privacy and scope

- Calendar Provider is accessed read-only through `READ_CALENDAR`.
- Only visible calendars with `account_type = com.google` and `sync_events = 1` are included.
- There is no Internet permission, Google OAuth, web API, account creation, analytics, advertising, or tracking.
- No calendar data leaves the device. The app never creates, edits, or deletes calendar events.
- Version 1 intentionally ignores all-day events to avoid alarms around midnight.

## Reliability design

`CalendarContract.Instances` expands recurring events. A pure planner creates stable identities from event ID, occurrence start, and offset. The reconciler compares those desired alarms with an on-device SQLite record, then schedules missing alarms, cancels deleted or moved alarms (including snoozes), and leaves correct entries untouched.

Exact alarms use `AlarmManager.setAlarmClock`. A foreground media-playback service loops the device's default alarm sound with `USAGE_ALARM`, vibrates, and holds a partial wake lock while one or more alarms are active. Each alarm has its own PendingIntent and notification identity, so simultaneous events do not replace each other. The lock-screen activity calls `setShowWhenLocked(true)` and `setTurnScreenOn(true)` and exposes only Snooze and Dismiss.

Reconciliation runs at app startup, after permission changes, from a live Calendar Provider observer, from WorkManager content-URI triggers, every 15 minutes as a fallback, and after boot, app replacement, clock/timezone changes, or exact-alarm access restoration.

Android may delay background calendar-change detection because WorkManager and provider delivery are system-controlled. Already scheduled exact alarms remain precise. After editing an imminent event, ensure Google has synchronized the change to the phone, then use **Rescan calendar now** for immediate local confirmation. A provider observer debounce never cancels reconciliation midway; failed background checks retry without multiplying content-observer work.

## Required user-granted access

The main screen reports each item separately:

1. Calendar access (`READ_CALENDAR`).
2. Notifications on Android 13+ (`POST_NOTIFICATIONS`).
3. Alarms & reminders special access on Android 12+ (`SCHEDULE_EXACT_ALARM`).
4. Full-screen alarm access on Android 14+ (`USE_FULL_SCREEN_INTENT`).

The app is not fully operational until all four rows report **Granted**. Foreground-service, vibration, boot, and wake-lock permissions are normal manifest permissions and need no separate dialog.

Also leave Google Calendar/account synchronization enabled, keep the Calendar alarms notification channel at high importance, use an audible alarm-stream volume, and permit alarms through Do Not Disturb. If full-screen access is denied, the alarm falls back to its high-priority notification.

## Build and install

Set `JAVA_HOME` to Android Studio's JBR and run:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat test lint assembleDebug
```

Install the generated APK with:

```powershell
& 'C:\Users\vytna\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r .\app\build\outputs\apk\debug\app-debug.apk
```

## Five-minute device check

1. Open Calendar Alarm and grant every item in the permission checklist.
2. In Google Calendar, create a timed event roughly 12 minutes from now, then return and tap **Rescan calendar now**. The 10-minute alarm should appear; the 60-minute alarm is correctly skipped because its time has passed.
3. Lock the device. Confirm the alarm wakes it, rings, vibrates, and opens the full-screen alarm UI. Tap **Snooze 10 minutes**, then confirm the snoozed time appears in the app.
4. For faster iteration, temporarily move the event so an alarm offset lands shortly in the future, rescan, and verify the prior entry disappears and the new one appears. Delete the event and rescan; all of its entries, including a snooze, must disappear.
5. Reboot the phone or emulator, unlock once, open the app, and confirm upcoming alarms have been restored.

Some OEM battery-management systems can delay WorkManager more aggressively than stock Android. Do not force-stop the app: Android deliberately prevents alarms and receivers for a force-stopped package until it is opened again. Do Not Disturb and an alarm-stream volume of zero can also suppress audible output according to device policy.

## Important source files

- `MainActivity.kt`: the one-screen UI, permission links, working switch callbacks and local-scan feedback.
- `AppContainer.kt`: application-lifetime FIFO for settings/rescan requests and shared alarm-operation lock.
- `calendar/CalendarProviderSource.kt`: read-only Google-calendar selection and recurring instances; null provider responses fail safely.
- `calendar/CalendarReconciler.kt`: comparison, cancellation, durable registration and scan reports.
- `core/AlarmPlanner.kt` and `core/ReconciliationEngine.kt`: device-independent scheduling rules, including delivery-time race protection.
- `data/SettingsRepository.kt` and `data/AlarmStore.kt`: durable preferences/reports and SQLite alarm records.
- `CalendarAlarmApplication.kt` and `background/`: live observer, WorkManager and boot/time/permission restoration.
- `alarm/`: exact alarms, lock-screen activity, playback service and snooze/dismiss receivers.
- `src/test/`: scheduling unit tests. `src/androidTest/`: actual Compose switch interactions, persistence, Calendar Provider recurrence/move/delete, queued changes, failure preservation and lock-screen alarm regression tests. Test-only calendar writes never ship in the app.
