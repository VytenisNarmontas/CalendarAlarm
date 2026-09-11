package com.calendaralarm

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.net.toUri
import com.calendaralarm.alarm.AlarmActionReceiver
import com.calendaralarm.core.AlarmRecord
import com.calendaralarm.core.AlarmState
import com.calendaralarm.core.UserSettings
import com.calendaralarm.core.ScanProgress
import com.calendaralarm.platform.PermissionChecks
import com.calendaralarm.platform.PermissionSnapshot
import com.calendaralarm.ui.CalendarAlarmTheme
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var permissions by mutableStateOf(PermissionSnapshot(false, false, false, false))

    private val calendarPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        refreshPermissionState()
        (application as CalendarAlarmApplication).ensureCalendarObserver()
        rescan()
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { refreshPermissionState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissionState()
        setContent {
            CalendarAlarmTheme {
                val settings by AppContainer.settingsRepository.settings
                    .collectAsStateWithLifecycle(initialValue = null)
                val records by AppContainer.records.collectAsStateWithLifecycle()
                val progress by AppContainer.reconciler.progress.collectAsStateWithLifecycle()
                if (settings == null) {
                    Text("Loading saved alarm settings…", Modifier.padding(24.dp))
                } else MainScreen(
                    settings = requireNotNull(settings),
                    permissions = permissions,
                    records = records,
                    progress = progress,
                    onEnabledChange = { value -> AppContainer.updateSetting { setEnabled(value) } },
                    onOneHourChange = { value -> AppContainer.updateSetting { setOneHourEnabled(value) } },
                    onTenMinutesChange = { value -> AppContainer.updateSetting { setTenMinutesEnabled(value) } },
                    onCalendarPermission = { calendarPermission.launch(Manifest.permission.READ_CALENDAR) },
                    onNotificationPermission = ::requestNotifications,
                    onExactAlarmPermission = ::openExactAlarmSettings,
                    onFullScreenPermission = ::openFullScreenSettings,
                    onRescan = ::rescan,
                    onOpenCalendar = ::openCalendar,
                    onSyncSettings = ::openSyncSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionState()
        (application as CalendarAlarmApplication).ensureCalendarObserver()
        AppContainer.requestScan("App opened / returned to foreground")
    }

    private fun refreshPermissionState() {
        permissions = PermissionChecks.snapshot(this)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            "package:$packageName".toUri(),
        )
        runCatching { startActivity(intent) }.onFailure { openAppDetails() }
    }

    private fun openFullScreenSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            "package:$packageName".toUri(),
        )
        runCatching { startActivity(intent) }.onFailure { openAppDetails() }
    }

    private fun openAppDetails() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:$packageName".toUri(),
                ),
            )
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun rescan() {
        AppContainer.requestScan("Rescan button")
    }

    private fun openCalendar() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
            .setPackage("com.google.android.calendar")
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { openSyncSettings() }
    }

    private fun openSyncSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SYNC_SETTINGS).putExtra(Settings.EXTRA_AUTHORITIES, arrayOf("com.android.calendar")))
        } catch (_: ActivityNotFoundException) { openAppDetails() }
    }
}

@Composable
private fun MainScreen(
    settings: UserSettings,
    permissions: PermissionSnapshot,
    records: List<AlarmRecord>,
    progress: ScanProgress,
    onEnabledChange: (Boolean) -> Unit,
    onOneHourChange: (Boolean) -> Unit,
    onTenMinutesChange: (Boolean) -> Unit,
    onCalendarPermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onExactAlarmPermission: () -> Unit,
    onFullScreenPermission: () -> Unit,
    onRescan: () -> Unit,
    onOpenCalendar: () -> Unit,
    onSyncSettings: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val upcoming = records
        .filter { it.state == AlarmState.SCHEDULED || it.state == AlarmState.SNOOZED }
        .filter { it.platformScheduled }
        .filter { it.effectiveTriggerAtMillis > now }
        .sortedBy { it.effectiveTriggerAtMillis }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Calendar Alarm", style = MaterialTheme.typography.headlineMedium)
            StatusCard(settings, permissions, upcoming.size, progress.busy)
            SettingSwitch(
                title = "Calendar alarms enabled",
                checked = settings.enabled,
                onCheckedChange = onEnabledChange,
                emphasized = true,
            )
            HorizontalDivider()
            Text("Alarm times", style = MaterialTheme.typography.titleMedium)
            SettingSwitch("1 hour before", settings.oneHourEnabled, onOneHourChange)
            SettingSwitch("10 minutes before", settings.tenMinutesEnabled, onTenMinutesChange)
            Text(
                "Snooze duration: ${AlarmActionReceiver.SNOOZE_MINUTES} minutes",
                style = MaterialTheme.typography.bodyMedium,
            )
            ScanFeedback(settings, progress, onOpenCalendar, onSyncSettings)
            Button(
                onClick = onRescan,
                enabled = !progress.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("rescan"),
            ) {
                Text(if (progress.busy) "Checking phone calendar…" else "Rescan calendar now")
            }
            UpcomingAlarms(upcoming.take(5))
            PermissionChecklist(
                permissions, onCalendarPermission, onNotificationPermission,
                onExactAlarmPermission, onFullScreenPermission,
            )
            Text(
                "Version 1 ignores all-day events. Calendar data stays on this device and is read-only. " +
                    "Android may delay background change detection, but exact alarms already scheduled remain precise.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusCard(
    settings: UserSettings,
    permissions: PermissionSnapshot,
    upcomingCount: Int,
    busy: Boolean,
) {
    val ready = settings.enabled && permissions.operational && settings.enabledOffsets.isNotEmpty() &&
        settings.lastScanError == null && settings.lastScanReport?.calendarNames?.isNotEmpty() == true
    val status = when {
        !settings.enabled -> "Alarms paused"
        settings.enabledOffsets.isEmpty() -> "Choose at least one alarm time"
        !permissions.operational -> "Permission setup incomplete"
        busy -> "Checking calendar…"
        settings.lastScanError != null -> "Calendar check failed"
        settings.lastScanReport == null -> "Waiting for first calendar check"
        settings.lastScanReport.calendarNames.isEmpty() -> "No synced Google calendars found"
        else -> "Ready · watching the phone’s calendar"
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                status,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(if (permissions.calendarGranted) "Calendar access granted" else "Calendar access missing")
            Text(if (permissions.exactAlarmsGranted) "Exact alarms allowed" else "Exact alarms not allowed")
            Text(if (permissions.fullScreenGranted) "Full-screen alarms allowed" else "Full-screen alarms not allowed")
            Text(if (permissions.notificationsGranted) "Notifications allowed" else "Notifications not allowed")
            Text("$upcomingCount upcoming alarms scheduled")
        }
    }
}

private fun formatLastScan(settings: UserSettings): String {
    val time = settings.lastScanMillis ?: return "Not yet"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(time))
}

@Composable
private fun ScanFeedback(settings: UserSettings, progress: ScanProgress, onOpenCalendar: () -> Unit, onSyncSettings: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("scan_feedback")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Calendar check", style = MaterialTheme.typography.titleMedium)
            Text("Source: Google calendars stored on this phone", fontWeight = FontWeight.Medium)
            if (progress.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(progress.message.orEmpty())
            }
            Text("Last successful check: ${formatLastScan(settings)}", Modifier.testTag("last_scan"))
            val error = settings.lastScanError ?: progress.actionError
            if (error != null) Text("Check failed: $error", color = MaterialTheme.colorScheme.error)
            settings.lastScanReport?.let { report ->
                Text("${report.calendarNames.size} visible, sync-enabled Google calendar(s) · ${report.eventCount} eligible event occurrence(s) in the next 30 days")
                if (report.calendarNames.isNotEmpty()) Text(report.calendarNames.joinToString(" · "))
                if (report.added + report.updated + report.removed == 0) {
                    Text("No alarm changes needed · ${report.unchanged} existing records kept", Modifier.testTag("scan_changes"))
                } else {
                    Text("Alarms: ${report.added} added · ${report.updated} updated · ${report.removed} removed · ${report.unchanged} unchanged", Modifier.testTag("scan_changes"))
                }
                if (!settings.enabled) Text("Alarms are paused. Calendar checks do not enable them.")
                else if (settings.enabledOffsets.isEmpty()) Text("Both alarm times are off. Turn one on to schedule alarms.")
                else if (report.eventCount == 0) Text("No eligible upcoming events in the phone’s calendar yet.")
            }
            Text("Rescan reads Android’s local Calendar storage; it does not download from Google. Google Calendar handles that sync separately. This app cannot verify Google’s last sync time.", style = MaterialTheme.typography.bodySmall)
            Text("Missing a recent change? Check Google Calendar’s sync setting, then open it and refresh. Return here for an automatic check. All-day and declined events are ignored; alarm times already past are skipped.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenCalendar, modifier = Modifier.weight(1f)) { Text("Open Google Calendar") }
                OutlinedButton(onClick = onSyncSettings, modifier = Modifier.weight(1f)) { Text("Sync settings") }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    emphasized: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(title)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun PermissionChecklist(
    permissions: PermissionSnapshot,
    onCalendar: () -> Unit,
    onNotifications: () -> Unit,
    onExact: () -> Unit,
    onFullScreen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Required permissions", style = MaterialTheme.typography.titleMedium)
        PermissionRow("Calendar access", permissions.calendarGranted, "Allow", onCalendar)
        PermissionRow("Notifications", permissions.notificationsGranted, "Allow", onNotifications)
        PermissionRow("Exact alarms", permissions.exactAlarmsGranted, "Open settings", onExact)
        PermissionRow("Full-screen alarms", permissions.fullScreenGranted, "Open settings", onFullScreen)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    buttonText: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "$title: ${if (granted) "Granted" else "Missing"}",
            modifier = Modifier.weight(1f),
            color = if (granted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
        )
        if (!granted) OutlinedButton(onClick = onClick) { Text(buttonText) }
    }
}

@Composable
private fun UpcomingAlarms(alarms: List<AlarmRecord>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Next alarms", style = MaterialTheme.typography.titleMedium)
        if (alarms.isEmpty()) {
            Text("No upcoming alarms found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            alarms.forEach { alarm ->
                val fireAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(alarm.effectiveTriggerAtMillis))
                val eventAt = DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(Date(alarm.occurrenceStartMillis))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(alarm.eventTitle, fontWeight = FontWeight.Medium)
                        Text(
                            if (alarm.state == AlarmState.SNOOZED) "Snoozed until $fireAt" else "Alarm $fireAt",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Event at $eventAt · ${alarm.offsetMinutes} minutes before",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
