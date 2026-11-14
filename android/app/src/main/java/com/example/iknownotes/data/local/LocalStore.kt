package com.example.iknownotes.data.local

import com.example.iknownotes.data.model.Folder
import com.example.iknownotes.data.model.Note
import com.google.gson.Gson
import java.io.File

/**
 * A note in the offline cache plus what still has to be pushed to the server.
 *
 * Instead of keeping an operation log, each note carries "dirty" flags and the
 * sync pushes its *current* state. Several offline edits to one note therefore
 * collapse into a single request.
 *
 * @property isNew created offline, has a temporary (negative) id and is not on the server yet
 * @property dirtyFields title/content/color/folder/pin/reminder changed locally
 * @property dirtyArchive archived state changed locally
 * @property dirtyTrash moved to / restored from the trash locally
 * @property rev bumped on every local change so the sync can tell if a note changed mid-flight
 */
data class CachedNote(
    val note: Note,
    val isNew: Boolean = false,
    val dirtyFields: Boolean = false,
    val dirtyArchive: Boolean = false,
    val dirtyTrash: Boolean = false,
    val rev: Int = 0
) {
    val isDirty: Boolean get() = isNew || dirtyFields || dirtyArchive || dirtyTrash
}

data class CacheData(
    /** Username the cache belongs to, so another account never sees it. */
    val owner: String? = null,
    val folders: List<Folder> = emptyList(),
    val notes: List<CachedNote> = emptyList(),
    /** Server ids to delete permanently on the next sync. */
    val tombstones: List<Int> = emptyList(),
    val emptyTrashPending: Boolean = false,
    // Starts at -2: screens use -1 as "no note id" in their intents.
    val nextTempId: Int = -2,
    /** temporary id -> real id, so screens still holding a temp id keep working. */
    val idMap: Map<Int, Int> = emptyMap()
) {
    val pendingCount: Int
        get() = notes.count { it.isDirty } + tombstones.size + if (emptyTrashPending) 1 else 0
}

/**
 * Tiny JSON-backed persistence for [CacheData]. Pass `file = null` for a purely
 * in-memory store (used in unit tests).
 */
class LocalStore(private val file: File?) {

    private val gson = Gson()

    @Volatile
    private var data: CacheData = load()

    fun read(): CacheData = data

    /** Atomically replaces the cache with `block(current)` and persists it. */
    @Synchronized
    fun edit(block: (CacheData) -> CacheData) {
        data = block(data)
        persist()
    }

    /** Wipes the cache when it belongs to someone other than [user]. */
    @Synchronized
    fun ensureOwner(user: String?) {
        if (user == null || data.owner == user) return
        data = CacheData(owner = user)
        persist()
    }

    @Synchronized
    fun clear() {
        data = CacheData()
        persist()
    }

    private fun load(): CacheData {
        val f = file ?: return CacheData()
        return try {
            if (!f.exists()) CacheData() else gson.fromJson(f.readText(), CacheData::class.java) ?: CacheData()
        } catch (e: Exception) {
            CacheData() // corrupt cache: start over, the server is the source of truth
        }
    }

    private fun persist() {
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(data))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // Cache is best-effort; failing to persist must never break the app.
        }
    }
}
