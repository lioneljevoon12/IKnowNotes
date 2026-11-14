package com.example.iknownotes.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.data.repo.NotesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Alarms are wiped when the phone reboots, so re-fetch the user's notes and
 * schedule the upcoming reminders again.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val appContext = context.applicationContext
        if (!RetrofitClient.getSessionManager(appContext).isLoggedIn) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotesRepository.get(appContext).getNotes()
                    .onSuccess { notes -> ReminderScheduler.syncAll(appContext, notes) }
            } finally {
                pending.finish()
            }
        }
    }
}
