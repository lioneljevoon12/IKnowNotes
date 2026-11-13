package com.example.iknownotes.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Converts between the backend's ISO-8601 (UTC) `reminder_time` strings and
 * epoch millis / display text. Uses SimpleDateFormat so it works on minSdk 24
 * without core-library desugaring.
 */
object ReminderHelper {

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** "2026-11-14T03:00:00Z" or "2026-11-14T03:00:00.123456Z" -> epoch millis. */
    fun parseIso(iso: String?): Long? {
        if (iso.isNullOrBlank() || iso.length < 19) return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = UTC
            }
            // The backend runs with TIME_ZONE = 'UTC', so the first 19 chars are UTC.
            format.parse(iso.substring(0, 19))?.time
        } catch (e: Exception) {
            null
        }
    }

    fun toIso(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = UTC
        }
        return format.format(Date(millis))
    }

    /** Human readable text in the device's time zone, e.g. "14 Nov 2026, 10:30". */
    fun formatDisplay(millis: Long): String {
        val format = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id", "ID"))
        return format.format(Date(millis))
    }

    fun isInFuture(millis: Long): Boolean = millis > System.currentTimeMillis()
}
