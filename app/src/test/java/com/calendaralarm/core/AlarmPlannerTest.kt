package com.calendaralarm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmPlannerTest {
    private val now = 1_000_000_000L

    @Test
    fun createsTwoAlarmsForOrdinaryFutureEvent() {
        val event = event(id = 1, start = now + hours(2))

        val result = AlarmPlanner.plan(listOf(event), setOf(60, 10), now)

        assertEquals(listOf(60, 10), result.desiredAlarms.map { it.offsetMinutes })
    }

    @Test
    fun skipsOffsetThatIsAlreadyInPast() {
        val event = event(id = 1, start = now + minutes(30))

        val result = AlarmPlanner.plan(listOf(event), setOf(60, 10), now)

        assertEquals(listOf(10), result.desiredAlarms.map { it.offsetMinutes })
    }

    @Test
    fun recurringInstancesGetIndependentAlarms() {
        val first = event(id = 9, start = now + hours(3))
        val second = event(id = 9, start = now + hours(27))

        val result = AlarmPlanner.plan(listOf(first, second), setOf(60, 10), now)

        assertEquals(4, result.desiredAlarms.size)
        assertEquals(2, result.desiredAlarms.map { it.occurrenceStartMillis }.distinct().size)
    }

    @Test
    fun declinedEventsAreExcluded() {
        val event = event(id = 1, start = now + hours(2)).copy(
            selfAttendeeStatus = CalendarInstance.ATTENDEE_STATUS_DECLINED,
        )

        assertTrue(AlarmPlanner.plan(listOf(event), setOf(60, 10), now).desiredAlarms.isEmpty())
    }

    @Test
    fun allDayEventsAreIgnored() {
        val event = event(id = 1, start = now + hours(24)).copy(allDay = true)

        assertTrue(AlarmPlanner.plan(listOf(event), setOf(60, 10), now).desiredAlarms.isEmpty())
    }

    @Test
    fun simultaneousEventsDoNotOverwriteEachOther() {
        val start = now + hours(2)

        val result = AlarmPlanner.plan(
            listOf(event(id = 1, start = start), event(id = 2, start = start)),
            setOf(60, 10),
            now,
        )

        assertEquals(4, result.desiredAlarms.size)
        assertEquals(4, result.desiredAlarms.map { it.key }.toSet().size)
        assertNotEquals(result.desiredAlarms[0].key, result.desiredAlarms[1].key)
    }

    private fun event(id: Long, start: Long) = CalendarInstance(
        eventId = id,
        occurrenceStartMillis = start,
        title = "Tutoring",
    )

    private fun minutes(value: Long) = value * 60_000L
    private fun hours(value: Long) = minutes(value * 60)
}
