package com.example.iknownotes.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.util.ReminderHelper

/**
 * Schedules / cancels the local alarms that fire the reminder notifications.
 *
 * The backend only stores `reminder_time`; the phone is responsible for
 * actually alerting the user, so alarms are (re)built from the notes whenever
 * the app loads them and after a device reboot.
 */
object ReminderScheduler {

    private const val PREFS_NAME = "iknownotes_reminders"
    private const val KEY_SCHEDULED_IDS = "scheduled_ids"

    /**
     * Makes the alarm for [note] match its current state: scheduled when it has
     * a future reminder and is not archived, otherwise cancelled.
     */
    fun sync(context: Context, note: Note) {
        val triggerAt = ReminderHelper.parseIso(note.reminderTime)
        if (triggerAt != null && !note.isArchived && ReminderHelper.isInFuture(triggerAt)) {
            schedule(context, note.id, note.title, note.content, triggerAt)
        } else {
            cancel(context, note.id)
        }
    }

    fun syncAll(context: Context, notes: List<Note>) {
        notes.forEach { sync(context, it) }
    }

    fun schedule(context: Context, noteId: Int, title: String, content: String, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context, noteId, title, content, create = true) ?: return

        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        try {
            if (canUseExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
                )
            }
        } catch (e: SecurityException) {
            // Exact-alarm permission revoked between the check and the call.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
        rememberId(context, noteId)
    }

    fun cancel(context: Context, noteId: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        pendingIntent(context, noteId, "", "", create = false)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
        forgetId(context, noteId)
    }

    /** Used on logout so another account's reminders never show up. */
    fun cancelAll(context: Context) {
        val ids = prefs(context).getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty()
        ids.mapNotNull { it.toIntOrNull() }.forEach { cancel(context, it) }
        prefs(context).edit().remove(KEY_SCHEDULED_IDS).apply()
    }

    private fun pendingIntent(
        context: Context,
        noteId: Int,
        title: String,
        content: String,
        create: Boolean
    ): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(ReminderReceiver.EXTRA_NOTE_ID, noteId)
            putExtra(ReminderReceiver.EXTRA_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_CONTENT, content)
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, noteId, intent, flags)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun rememberId(context: Context, noteId: Int) {
        val set = prefs(context).getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().toMutableSet()
        if (set.add(noteId.toString())) {
            prefs(context).edit().putStringSet(KEY_SCHEDULED_IDS, set).apply()
        }
    }

    private fun forgetId(context: Context, noteId: Int) {
        val set = prefs(context).getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty().toMutableSet()
        if (set.remove(noteId.toString())) {
            prefs(context).edit().putStringSet(KEY_SCHEDULED_IDS, set).apply()
        }
    }

    private const val ACTION_REMINDER = "com.example.iknownotes.ACTION_REMINDER"
}
