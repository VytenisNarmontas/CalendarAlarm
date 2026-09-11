package com.calendaralarm.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconciliationEngineTest {
    private val now = 1_000_000_000L

    @Test fun scanAtDeliveryTimeDoesNotCancelAlreadyRegisteredAlarm() {
        val due = AlarmRecord.from(spec(1, now + minutes(60) - 1_000L, 60), true)
        val result = plan(emptyList(), setOf(due.occurrenceKey), listOf(due))
        assertTrue(result.toCancel.isEmpty())
        assertTrue(result.toSchedule.isEmpty())
    }

    @Test fun disablingOffsetCancelsEvenSnoozedOrJustDueAlarms() {
        val due = AlarmRecord.from(spec(1, now + minutes(60) - 1_000L, 60), true)
        val snoozed = due.copy(key = "other", state = AlarmState.SNOOZED, snoozedUntilMillis = now + minutes(10))
        val result = ReconciliationEngine.plan(emptyList(), setOf(due.occurrenceKey), emptySet(), listOf(due, snoozed), now)
        assertEquals(2, result.toCancel.size)
    }

    @Test fun expiredDeliveryGraceDoesNotLeaveStaleScheduledRecord() {
        val due = AlarmRecord.from(spec(1, now + minutes(40), 60), true)
        assertEquals(1, plan(emptyList(), setOf(due.occurrenceKey), listOf(due)).toCancel.size)
    }

    @Test fun titleChangeUpdatesRecordWithoutReplacingCorrectExactAlarm() {
        val wanted = spec(1, now + hours(2), 60)
        val record = AlarmRecord.from(wanted, true)
        val result = plan(listOf(wanted.copy(eventTitle = "New lesson title")), setOf(wanted.occurrenceKey), listOf(record))
        assertTrue(result.toSchedule.isEmpty())
        assertEquals("New lesson title", result.toUpsert.single().eventTitle)
    }

    @Test
    fun existingCorrectAlarmIsNotDuplicated() {
        val spec = spec(eventId = 1, start = now + hours(2), offset = 60)
        val existing = AlarmRecord.from(spec, platformScheduled = true)

        val result = plan(listOf(spec), setOf(spec.occurrenceKey), listOf(existing))

        assertTrue(result.toSchedule.isEmpty())
        assertTrue(result.toCancel.isEmpty())
    }

    @Test
    fun deletedEventCancelsItsAlarm() {
        val existing = AlarmRecord.from(spec(eventId = 1, start = now + hours(2), offset = 60), true)

        val result = plan(emptyList(), emptySet(), listOf(existing))

        assertEquals(listOf(existing.key), result.toCancel.map { it.key })
    }

    @Test
    fun movedEventCancelsOldAndSchedulesNewAlarm() {
        val old = spec(eventId = 1, start = now + hours(2), offset = 60)
        val moved = spec(eventId = 1, start = now + hours(3), offset = 60)
        val existing = AlarmRecord.from(old, platformScheduled = true)

        val result = plan(listOf(moved), setOf(moved.occurrenceKey), listOf(existing))

        assertEquals(listOf(old.key), result.toCancel.map { it.key })
        assertEquals(listOf(moved.key), result.toSchedule.map { it.key })
    }

    @Test
    fun snoozeKeepsOriginalStableIdentity() {
        val wanted = spec(eventId = 5, start = now + hours(2), offset = 60)
        val snoozed = AlarmRecord.from(wanted, platformScheduled = true).copy(
            state = AlarmState.SNOOZED,
            snoozedUntilMillis = now + minutes(10),
        )

        val result = plan(listOf(wanted), setOf(wanted.occurrenceKey), listOf(snoozed))

        assertTrue(result.toCancel.isEmpty())
        assertTrue(result.toSchedule.isEmpty())
        assertEquals(wanted.key, snoozed.key)
    }

    @Test
    fun deletingEventCancelsItsSnoozedAlarm() {
        val wanted = spec(eventId = 5, start = now + hours(2), offset = 60)
        val snoozed = AlarmRecord.from(wanted, platformScheduled = true).copy(
            state = AlarmState.SNOOZED,
            snoozedUntilMillis = now + minutes(10),
        )

        val result = plan(emptyList(), emptySet(), listOf(snoozed))

        assertEquals(listOf(snoozed.key), result.toCancel.map { it.key })
        assertEquals(AlarmState.SNOOZED, result.toCancel.single().state)
    }

    private fun plan(
        desired: List<AlarmSpec>,
        occurrences: Set<String>,
        existing: List<AlarmRecord>,
    ) = ReconciliationEngine.plan(
        desired = desired,
        eligibleOccurrenceKeys = occurrences,
        enabledOffsets = setOf(60, 10),
        existing = existing,
        nowMillis = now,
    )

    private fun spec(eventId: Long, start: Long, offset: Int) = AlarmSpec(
        key = "$eventId:$start:$offset",
        eventId = eventId,
        occurrenceStartMillis = start,
        offsetMinutes = offset,
        triggerAtMillis = start - minutes(offset.toLong()),
        eventTitle = "Lesson",
    )

    private fun minutes(value: Long) = value * 60_000L
    private fun hours(value: Long) = minutes(value * 60)
}
