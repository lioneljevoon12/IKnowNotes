package com.example.iknownotes.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import com.example.iknownotes.R
import com.example.iknownotes.ui.notedetail.NoteDetailActivity
import com.example.iknownotes.ui.notes.NotesListActivity
import com.example.iknownotes.util.RichTextHelper

/** Fires when a note's reminder alarm goes off and shows the notification. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val noteId = intent.getIntExtra(EXTRA_NOTE_ID, -1)
        if (noteId == -1) return

        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            .ifBlank { context.getString(R.string.reminder_untitled) }
        val preview = RichTextHelper.fromHtml(intent.getStringExtra(EXTRA_CONTENT))
            .toString().trim().take(200)

        ensureChannel(context)

        // On Android 13+ the user may have denied the notification permission.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // Back from the note should land on the notes list, not leave the app.
        val tapIntent = TaskStackBuilder.create(context)
            .addNextIntent(Intent(context, NotesListActivity::class.java))
            .addNextIntent(
                Intent(context, NoteDetailActivity::class.java)
                    .putExtra(NoteDetailActivity.EXTRA_NOTE_ID, noteId)
            )
            .getPendingIntent(
                noteId,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(title)
            .setContentText(preview.ifBlank { context.getString(R.string.reminder_default_text) })
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(preview.ifBlank { context.getString(R.string.reminder_default_text) })
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tapIntent)
            .build()

        NotificationManagerCompat.from(context).notify(noteId, notification)
        ReminderScheduler.cancel(context, noteId) // alarm has fired; drop it from the tracked set
    }

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_CONTENT = "extra_content"
        private const val CHANNEL_ID = "note_reminders"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.reminder_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.reminder_channel_description)
                }
            )
        }
    }
}
