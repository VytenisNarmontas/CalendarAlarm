package com.calendaralarm.alarm

import java.security.MessageDigest

object AlarmIdentity {
    fun requestCode(value: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return ((digest[0].toInt() and 0x7f) shl 24) or
            ((digest[1].toInt() and 0xff) shl 16) or
            ((digest[2].toInt() and 0xff) shl 8) or
            (digest[3].toInt() and 0xff)
    }
}
