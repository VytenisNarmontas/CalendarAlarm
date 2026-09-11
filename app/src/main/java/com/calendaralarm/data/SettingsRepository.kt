package com.calendaralarm.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.calendaralarm.core.UserSettings
import com.calendaralarm.core.ScanReport
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    val settings: Flow<UserSettings> = context.settingsDataStore.data
        .catch { error ->
            // Never silently enable alarms with default settings after a storage failure.
            if (error is IOException) throw IOException("Cannot read saved alarm settings", error) else throw error
        }
        .map { preferences ->
            UserSettings(
                enabled = preferences[Keys.ENABLED] ?: true,
                oneHourEnabled = preferences[Keys.ONE_HOUR] ?: true,
                tenMinutesEnabled = preferences[Keys.TEN_MINUTES] ?: true,
                lastScanMillis = preferences[Keys.LAST_SCAN],
                lastScanError = preferences[Keys.LAST_SCAN_ERROR],
                lastScanAttemptMillis = preferences[Keys.LAST_ATTEMPT],
                lastScanReport = preferences[Keys.LAST_REPORT]?.let(::decodeReport),
            )
        }

    suspend fun setEnabled(value: Boolean) = context.settingsDataStore.edit { it[Keys.ENABLED] = value }

    suspend fun setOneHourEnabled(value: Boolean) = context.settingsDataStore.edit { it[Keys.ONE_HOUR] = value }

    suspend fun setTenMinutesEnabled(value: Boolean) = context.settingsDataStore.edit { it[Keys.TEN_MINUTES] = value }

    suspend fun recordScan(timeMillis: Long, error: String?, report: ScanReport? = null) = context.settingsDataStore.edit {
        it[Keys.LAST_ATTEMPT] = timeMillis
        if (error == null && report != null) {
            it[Keys.LAST_SCAN] = timeMillis
            it[Keys.LAST_REPORT] = encodeReport(report)
        }
        if (error == null) it.remove(Keys.LAST_SCAN_ERROR) else it[Keys.LAST_SCAN_ERROR] = error
    }

    private fun encodeReport(report: ScanReport): String = JSONObject().apply {
        put("calendars", JSONArray(report.calendarNames))
        put("events", report.eventCount)
        put("added", report.added)
        put("updated", report.updated)
        put("removed", report.removed)
        put("unchanged", report.unchanged)
        put("reason", report.reason)
    }.toString()

    private fun decodeReport(value: String): ScanReport? = runCatching {
        val json = JSONObject(value)
        val names = json.getJSONArray("calendars")
        ScanReport(
            List(names.length()) { names.getString(it) }, json.getInt("events"),
            json.getInt("added"), json.getInt("updated"), json.getInt("removed"),
            json.getInt("unchanged"), json.getString("reason"),
        )
    }.getOrNull()

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val ONE_HOUR = booleanPreferencesKey("one_hour_enabled")
        val TEN_MINUTES = booleanPreferencesKey("ten_minutes_enabled")
        val LAST_SCAN = longPreferencesKey("last_scan")
        val LAST_SCAN_ERROR = stringPreferencesKey("last_scan_error")
        val LAST_ATTEMPT = longPreferencesKey("last_scan_attempt")
        val LAST_REPORT = stringPreferencesKey("last_scan_report")
    }
}
