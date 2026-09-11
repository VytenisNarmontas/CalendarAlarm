package com.calendaralarm.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.calendaralarm.core.AlarmRecord
import com.calendaralarm.core.AlarmState

class AlarmStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE alarms (
                alarm_key TEXT NOT NULL PRIMARY KEY,
                event_id INTEGER NOT NULL,
                occurrence_start INTEGER NOT NULL,
                offset_minutes INTEGER NOT NULL,
                trigger_at INTEGER NOT NULL,
                event_title TEXT NOT NULL,
                state TEXT NOT NULL,
                snoozed_until INTEGER,
                platform_scheduled INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX alarms_effective_time ON alarms(trigger_at, snoozed_until)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun all(): List<AlarmRecord> = readableDatabase.query(
        TABLE,
        COLUMNS,
        null,
        null,
        null,
        null,
        "trigger_at ASC, alarm_key ASC",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toRecord()) } }

    @Synchronized
    fun find(key: String): AlarmRecord? = readableDatabase.query(
        TABLE,
        COLUMNS,
        "alarm_key = ?",
        arrayOf(key),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    @Synchronized
    fun upsert(record: AlarmRecord) {
        writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            record.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun upsertAll(records: Collection<AlarmRecord>) {
        if (records.isEmpty()) return
        writableDatabase.inTransaction {
            records.forEach { record ->
                insertWithOnConflict(TABLE, null, record.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    @Synchronized
    fun delete(keys: Collection<String>) {
        if (keys.isEmpty()) return
        writableDatabase.inTransaction {
            keys.forEach { key -> delete(TABLE, "alarm_key = ?", arrayOf(key)) }
        }
    }

    @Synchronized
    fun deleteAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    @Synchronized
    fun markAllPlatformUnscheduled() {
        writableDatabase.update(
            TABLE,
            ContentValues().apply { put("platform_scheduled", 0) },
            null,
            null,
        )
    }

    private fun AlarmRecord.toValues() = ContentValues().apply {
        put("alarm_key", key)
        put("event_id", eventId)
        put("occurrence_start", occurrenceStartMillis)
        put("offset_minutes", offsetMinutes)
        put("trigger_at", triggerAtMillis)
        put("event_title", eventTitle)
        put("state", state.name)
        if (snoozedUntilMillis == null) putNull("snoozed_until") else put("snoozed_until", snoozedUntilMillis)
        put("platform_scheduled", if (platformScheduled) 1 else 0)
    }

    private fun Cursor.toRecord() = AlarmRecord(
        key = getString(getColumnIndexOrThrow("alarm_key")),
        eventId = getLong(getColumnIndexOrThrow("event_id")),
        occurrenceStartMillis = getLong(getColumnIndexOrThrow("occurrence_start")),
        offsetMinutes = getInt(getColumnIndexOrThrow("offset_minutes")),
        triggerAtMillis = getLong(getColumnIndexOrThrow("trigger_at")),
        eventTitle = getString(getColumnIndexOrThrow("event_title")),
        state = AlarmState.valueOf(getString(getColumnIndexOrThrow("state"))),
        snoozedUntilMillis = getColumnIndexOrThrow("snoozed_until").let { index ->
            if (isNull(index)) null else getLong(index)
        },
        platformScheduled = getInt(getColumnIndexOrThrow("platform_scheduled")) != 0,
    )

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    companion object {
        private const val DATABASE_NAME = "calendar_alarms.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE = "alarms"
        private val COLUMNS = arrayOf(
            "alarm_key",
            "event_id",
            "occurrence_start",
            "offset_minutes",
            "trigger_at",
            "event_title",
            "state",
            "snoozed_until",
            "platform_scheduled",
        )
    }
}
