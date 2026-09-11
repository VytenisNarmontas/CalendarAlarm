package com.calendaralarm.core

data class ReconciliationPlan(
    val toCancel: List<AlarmRecord>,
    val toSchedule: List<AlarmRecord>,
    val toUpsert: List<AlarmRecord>,
) {
    val deleteKeys: Set<String> = toCancel.mapTo(mutableSetOf()) { it.key }
}

object ReconciliationEngine {
    fun plan(
        desired: List<AlarmSpec>,
        eligibleOccurrenceKeys: Set<String>,
        enabledOffsets: Set<Int>,
        existing: List<AlarmRecord>,
        nowMillis: Long,
    ): ReconciliationPlan {
        val desiredByKey = desired.associateBy { it.key }
        val existingByKey = existing.associateBy { it.key }
        val cancel = mutableListOf<AlarmRecord>()
        val schedule = mutableListOf<AlarmRecord>()
        val upsert = mutableListOf<AlarmRecord>()

        existingByKey.values.forEach { record ->
            val wanted = desiredByKey[record.key]
            if (wanted != null) {
                val updated = record.copy(
                    eventId = wanted.eventId,
                    occurrenceStartMillis = wanted.occurrenceStartMillis,
                    offsetMinutes = wanted.offsetMinutes,
                    triggerAtMillis = wanted.triggerAtMillis,
                    eventTitle = wanted.eventTitle,
                )
                when (record.state) {
                    AlarmState.SCHEDULED -> {
                        if (!record.platformScheduled || record.triggerAtMillis != wanted.triggerAtMillis) {
                            cancel += record
                            schedule += AlarmRecord.from(wanted)
                        } else if (updated != record) {
                            upsert += updated
                        }
                    }

                    AlarmState.SNOOZED -> {
                        if (!record.platformScheduled && (record.snoozedUntilMillis ?: 0L) > nowMillis) {
                            schedule += updated
                        } else if (updated != record) {
                            upsert += updated
                        }
                    }

                    AlarmState.RINGING,
                    AlarmState.DISMISSED,
                    -> if (updated != record) upsert += updated
                }
            } else {
                val activeOccurrenceStillExists =
                    (record.state != AlarmState.SCHEDULED ||
                        // Startup can scan before the due alarm's broadcast is delivered.
                        (record.platformScheduled && nowMillis - record.triggerAtMillis in 0..DELIVERY_GRACE_MILLIS)) &&
                        record.occurrenceKey in eligibleOccurrenceKeys &&
                        record.offsetMinutes in enabledOffsets

                if (activeOccurrenceStillExists) {
                    if (
                        record.state == AlarmState.SNOOZED &&
                        !record.platformScheduled &&
                        (record.snoozedUntilMillis ?: 0L) > nowMillis
                    ) {
                        schedule += record
                    }
                } else {
                    cancel += record
                }
            }
        }

        desired.forEach { wanted ->
            if (wanted.key !in existingByKey) schedule += AlarmRecord.from(wanted)
        }

        return ReconciliationPlan(
            toCancel = cancel.distinctBy { it.key },
            toSchedule = schedule.distinctBy { it.key },
            toUpsert = upsert.distinctBy { it.key },
        )
    }
    private const val DELIVERY_GRACE_MILLIS = 15 * 60_000L
}
