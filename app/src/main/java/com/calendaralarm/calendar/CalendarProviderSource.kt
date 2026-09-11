package com.calendaralarm.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.calendaralarm.core.CalendarInstance

data class CalendarSnapshot(val calendarNames: List<String>, val instances: List<CalendarInstance>)

fun interface CalendarSource {
    fun queryInstances(nowMillis: Long): CalendarSnapshot
}

class CalendarProviderSource(private val context: Context) : CalendarSource {
    override fun queryInstances(nowMillis: Long): CalendarSnapshot {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "Calendar access not granted" }

        val googleCalendars = queryGoogleCalendarIds()
        if (googleCalendars.isEmpty()) return CalendarSnapshot(emptyList(), emptyList())

        val rangeStart = nowMillis - LOOK_BACK_MILLIS
        val rangeEnd = nowMillis + LOOK_AHEAD_MILLIS
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { builder ->
            ContentUris.appendId(builder, rangeStart)
            ContentUris.appendId(builder, rangeEnd)
        }.build()
        val placeholders = googleCalendars.keys.joinToString(",") { "?" }
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val selection =
            "${CalendarContract.Instances.CALENDAR_ID} IN ($placeholders) AND " +
                "${CalendarContract.Instances.ALL_DAY} = 0 AND " +
                "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ?)"
        val args = googleCalendars.keys.map(Long::toString).toMutableList().apply {
            add(CalendarContract.Events.STATUS_CANCELED.toString())
        }.toTypedArray()

        val instances = (context.contentResolver.query(
            uri,
            projection,
            selection,
            args,
            "${CalendarContract.Instances.BEGIN} ASC",
        ) ?: error("Android Calendar storage did not respond. Try again; existing alarms are kept.")) .use { cursor ->
            val eventIdIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val attendeeIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.SELF_ATTENDEE_STATUS)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CalendarInstance(
                            eventId = cursor.getLong(eventIdIndex),
                            occurrenceStartMillis = cursor.getLong(beginIndex),
                            title = cursor.getString(titleIndex).orEmpty(),
                            allDay = cursor.getInt(allDayIndex) != 0,
                            selfAttendeeStatus = cursor.getInt(attendeeIndex),
                        ),
                    )
                }
            }
        }
        return CalendarSnapshot(googleCalendars.values.toList(), instances)
    }

    private fun queryGoogleCalendarIds(): Map<Long, String> {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
        val selection =
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND " +
                "${CalendarContract.Calendars.SYNC_EVENTS} = 1 AND " +
                "${CalendarContract.Calendars.VISIBLE} = 1 AND " +
                "${CalendarContract.Calendars.DELETED} = 0"
        return (context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            arrayOf(CalendarInstance.GOOGLE_ACCOUNT_TYPE),
            null,
        ) ?: error("Android Calendar storage did not respond. Try again; existing alarms are kept.")).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            buildMap {
                while (cursor.moveToNext()) put(cursor.getLong(idIndex), cursor.getString(nameIndex).orEmpty().ifBlank { "Google calendar" })
            }
        }
    }

    companion object {
        const val LOOK_AHEAD_DAYS = 30L
        private const val LOOK_AHEAD_MILLIS = LOOK_AHEAD_DAYS * 24 * 60 * 60 * 1_000L
        private const val LOOK_BACK_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
