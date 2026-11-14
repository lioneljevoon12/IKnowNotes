package com.example.iknownotes.data.local

import android.content.Context
import com.google.gson.Gson

/** Unsaved editor state, kept so typing survives a crash, kill or accidental exit. */
data class NoteDraft(
    val title: String,
    val contentHtml: String,
    val color: String,
    val folderId: Int?,
    val reminderMillis: Long?
)

class DraftStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(key: String, draft: NoteDraft) {
        prefs.edit().putString(key, gson.toJson(draft)).apply()
    }

    fun load(key: String): NoteDraft? {
        val json = prefs.getString(key, null) ?: return null
        return try {
            gson.fromJson(json, NoteDraft::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun clear(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "iknownotes_drafts"
        const val KEY_NEW_NOTE = "new_note"

        fun keyFor(noteId: Int): String = if (noteId == -1) KEY_NEW_NOTE else "note_$noteId"
    }
}
