package com.calendaralarm.core

data class PlanningResult(
    val desiredAlarms: List<AlarmSpec>,
    val eligibleOccurrenceKeys: Set<String>,
)

object AlarmPlanner {
    fun plan(
        instances: List<CalendarInstance>,
        enabledOffsets: Set<Int>,
        nowMillis: Long,
    ): PlanningResult {
        val eligible = instances
            .asSequence()
            .filter(::isEligible)
            .distinctBy { it.occurrenceKey }
            .toList()

        val alarms = eligible
            .asSequence()
            .flatMap { instance ->
                enabledOffsets.asSequence().map { offset -> AlarmSpec.from(instance, offset) }
            }
            .filter { it.triggerAtMillis > nowMillis }
            .distinctBy { it.key }
            .sortedWith(compareBy(AlarmSpec::triggerAtMillis, AlarmSpec::key))
            .toList()

        return PlanningResult(
            desiredAlarms = alarms,
            eligibleOccurrenceKeys = eligible.mapTo(mutableSetOf()) { it.occurrenceKey },
        )
    }

    private fun isEligible(instance: CalendarInstance): Boolean =
        instance.accountType == CalendarInstance.GOOGLE_ACCOUNT_TYPE &&
            instance.syncEvents &&
            instance.calendarVisible &&
            !instance.deleted &&
            !instance.allDay &&
            instance.selfAttendeeStatus != CalendarInstance.ATTENDEE_STATUS_DECLINED
}
