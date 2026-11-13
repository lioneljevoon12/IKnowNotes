package com.example.iknownotes

import com.example.iknownotes.util.ReminderHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderHelperTest {

    // 2026-11-14T03:00:00Z
    private val knownMillis = 1794625200000L

    @Test
    fun parseIso_readsUtcTimestamp() {
        assertEquals(knownMillis, ReminderHelper.parseIso("2026-11-14T03:00:00Z"))
    }

    @Test
    fun parseIso_ignoresFractionalSeconds() {
        assertEquals(knownMillis, ReminderHelper.parseIso("2026-11-14T03:00:00.123456Z"))
    }

    @Test
    fun toIso_roundTrips() {
        assertEquals("2026-11-14T03:00:00Z", ReminderHelper.toIso(knownMillis))
        assertEquals(knownMillis, ReminderHelper.parseIso(ReminderHelper.toIso(knownMillis)))
    }

    @Test
    fun parseIso_returnsNullForMissingOrInvalid() {
        assertNull(ReminderHelper.parseIso(null))
        assertNull(ReminderHelper.parseIso(""))
        assertNull(ReminderHelper.parseIso("bukan tanggal"))
    }
}
