package com.calendaralarm.core

data class CalendarInstance(
    val eventId: Long,
    val occurrenceStartMillis: Long,
    val title: String,
    val allDay: Boolean = false,
    val selfAttendeeStatus: Int = ATTENDEE_STATUS_ACCEPTED,
    val deleted: Boolean = false,
    val calendarVisible: Boolean = true,
    val syncEvents: Boolean = true,
    val accountType: String = GOOGLE_ACCOUNT_TYPE,
) {
    val occurrenceKey: String
        get() = "$eventId:$occurrenceStartMillis"

    companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val ATTENDEE_STATUS_ACCEPTED = 1
        const val ATTENDEE_STATUS_DECLINED = 2
    }
}

data class AlarmSpec(
    val key: String,
    val eventId: Long,
    val occurrenceStartMillis: Long,
    val offsetMinutes: Int,
    val triggerAtMillis: Long,
    val eventTitle: String,
) {
    val occurrenceKey: String
        get() = "$eventId:$occurrenceStartMillis"

    companion object {
        fun from(instance: CalendarInstance, offsetMinutes: Int): AlarmSpec = AlarmSpec(
            key = "${instance.eventId}:${instance.occurrenceStartMillis}:$offsetMinutes",
            eventId = instance.eventId,
            occurrenceStartMillis = instance.occurrenceStartMillis,
            offsetMinutes = offsetMinutes,
            triggerAtMillis = instance.occurrenceStartMillis - offsetMinutes * 60_000L,
            eventTitle = instance.title.ifBlank { "Untitled event" },
        )
    }
}

enum class AlarmState {
    SCHEDULED,
    RINGING,
    SNOOZED,
    DISMISSED,
}

data class AlarmRecord(
    val key: String,
    val eventId: Long,
    val occurrenceStartMillis: Long,
    val offsetMinutes: Int,
    val triggerAtMillis: Long,
    val eventTitle: String,
    val state: AlarmState = AlarmState.SCHEDULED,
    val snoozedUntilMillis: Long? = null,
    val platformScheduled: Boolean = false,
) {
    val occurrenceKey: String
        get() = "$eventId:$occurrenceStartMillis"

    val effectiveTriggerAtMillis: Long
        get() = if (state == AlarmState.SNOOZED) snoozedUntilMillis ?: triggerAtMillis else triggerAtMillis

    companion object {
        fun from(spec: AlarmSpec, platformScheduled: Boolean = false) = AlarmRecord(
            key = spec.key,
            eventId = spec.eventId,
            occurrenceStartMillis = spec.occurrenceStartMillis,
            offsetMinutes = spec.offsetMinutes,
            triggerAtMillis = spec.triggerAtMillis,
            eventTitle = spec.eventTitle,
            platformScheduled = platformScheduled,
        )
    }
}

data class UserSettings(
    val enabled: Boolean = true,
    val oneHourEnabled: Boolean = true,
    val tenMinutesEnabled: Boolean = true,
    val lastScanMillis: Long? = null,
    val lastScanError: String? = null,
    val lastScanAttemptMillis: Long? = null,
    val lastScanReport: ScanReport? = null,
) {
    val enabledOffsets: Set<Int>
        get() = buildSet {
            if (oneHourEnabled) add(60)
            if (tenMinutesEnabled) add(10)
        }
}

data class ScanReport(
    val calendarNames: List<String>,
    val eventCount: Int,
    val added: Int,
    val updated: Int,
    val removed: Int,
    val unchanged: Int,
    val reason: String,
)

data class ScanProgress(val message: String? = null, val actionError: String? = null) {
    val busy: Boolean get() = message != null
}
