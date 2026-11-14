package com.example.iknownotes.data.repo

import android.content.Context
import com.example.iknownotes.data.local.CachedNote
import com.example.iknownotes.data.local.LocalStore
import com.example.iknownotes.data.local.PendingUpload
import com.example.iknownotes.data.model.Attachment
import com.example.iknownotes.data.model.AttachmentItem
import com.example.iknownotes.data.model.Folder
import com.example.iknownotes.data.model.FolderRequest
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.model.NoteRequest
import com.example.iknownotes.data.model.PagedResponse
import com.example.iknownotes.data.remote.ApiService
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.reminder.ReminderScheduler
import com.example.iknownotes.util.ReminderHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException

/** What the UI shows about connectivity and unsynced work. */
data class SyncStatus(
    /** True once a request failed because the server could not be reached. */
    val offline: Boolean = false,
    /** Local changes (create/edit/archive/trash/...) not yet pushed to the server. */
    val pendingChanges: Int = 0
)

/**
 * Offline-first access to notes.
 *
 * - Reads try the server and refresh the local cache; when the server can't be
 *   reached they are answered from the cache.
 * - Note writes (create, edit, pin, archive, trash, restore, permanent delete)
 *   are applied to the cache immediately and pushed to the server by [syncPending],
 *   which runs right away and again whenever the network comes back.
 * - Folder writes need a connection (they are rare and affect other notes).
 *
 * Conflicts are last-write-wins: unsynced local changes are pushed over the
 * server's copy, and the server's copy only replaces notes without local changes.
 */
class NotesRepository internal constructor(
    private val api: ApiService,
    private val store: LocalStore,
    private val currentUser: () -> String?,
    private val onTempIdReplaced: (tempId: Int, note: Note) -> Unit = { _, _ -> },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val autoSync: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pageSize: Int = PAGE_SIZE
) {

    private val syncMutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus(pendingChanges = store.read().pendingCount))
    val status: StateFlow<SyncStatus> = _status

    // ---------------------------------------------------------------- folders

    suspend fun getFolders(): Result<List<Folder>> = withContext(Dispatchers.IO) {
        ensureOwner()
        val result = safeApiCall { api.getFolders() }
        result.onSuccess { list ->
            store.edit { it.copy(folders = list) }
            markOnline()
        }
        if (result.isNetworkFailure()) {
            markOffline()
            return@withContext Result.success(store.read().folders)
        }
        result
    }

    suspend fun createFolder(name: String): Result<Folder> =
        safeApiCall { api.createFolder(FolderRequest(name)) }

    suspend fun updateFolder(id: Int, name: String): Result<Folder> =
        safeApiCall { api.updateFolder(id, FolderRequest(name)) }

    suspend fun deleteFolder(id: Int): Result<Unit> =
        safeApiCall { api.deleteFolder(id) }

    // ------------------------------------------------------------------ reads

    suspend fun getNotes(folderId: Int? = null, search: String? = null): Result<List<Note>> =
        withContext(Dispatchers.IO) {
            ensureOwner()
            val query = search?.ifBlank { null }
            if (!syncPending()) return@withContext Result.success(cachedActive(folderId, query))

            val result = safeApiCall { fetchAllPages { page -> api.getNotes(folderId, query, page, pageSize) } }
            result.onSuccess { list ->
                if (folderId == null && query == null) {
                    replaceSet(list) { it.deletedAt == null && !it.isArchived }
                } else {
                    upsertSynced(list)
                }
                markOnline()
            }
            if (result.isNetworkFailure()) {
                markOffline()
                return@withContext Result.success(cachedActive(folderId, query))
            }
            result
        }

    suspend fun getArchivedNotes(): Result<List<Note>> = withContext(Dispatchers.IO) {
        ensureOwner()
        if (!syncPending()) return@withContext Result.success(cachedArchived())

        val result = safeApiCall { fetchAllPages { page -> api.getArchivedNotes(page, pageSize) } }
        result.onSuccess { list ->
            replaceSet(list) { it.deletedAt == null && it.isArchived }
            markOnline()
        }
        if (result.isNetworkFailure()) {
            markOffline()
            return@withContext Result.success(cachedArchived())
        }
        result
    }

    suspend fun getTrashNotes(): Result<List<Note>> = withContext(Dispatchers.IO) {
        ensureOwner()
        if (!syncPending()) return@withContext Result.success(cachedTrash())

        val result = safeApiCall { fetchAllPages { page -> api.getTrashNotes(page, pageSize) } }
        result.onSuccess { list ->
            replaceSet(list) { it.deletedAt != null }
            markOnline()
        }
        if (result.isNetworkFailure()) {
            markOffline()
            return@withContext Result.success(cachedTrash())
        }
        result
    }

    suspend fun getNote(id: Int): Result<Note> = withContext(Dispatchers.IO) {
        ensureOwner()
        val realId = resolve(id)
        val cached = store.read().notes.firstOrNull { it.note.id == realId }

        // Local changes (and trashed notes, which the server won't serve) are authoritative.
        if (cached != null && (cached.isDirty || cached.note.deletedAt != null)) {
            return@withContext Result.success(cached.note)
        }

        val result = safeApiCall { api.getNote(realId) }
        result.onSuccess { upsertSynced(listOf(it)); markOnline() }
        if (result.isNetworkFailure()) {
            markOffline()
            return@withContext if (cached != null) Result.success(cached.note) else Result.failure(
                Exception("Catatan ini belum tersimpan di perangkat. Sambungkan internet dulu.")
            )
        }
        result
    }

    // ----------------------------------------------------------------- writes

    suspend fun createNote(request: NoteRequest): Result<Note> = withContext(Dispatchers.IO) {
        ensureOwner()
        val nowIso = ReminderHelper.toIso(clock())
        var created: Note? = null
        store.edit { data ->
            val tempId = data.nextTempId
            val note = Note(
                id = tempId,
                folder = request.folder,
                title = request.title,
                content = request.content,
                color = request.color,
                isPinned = request.isPinned,
                isArchived = false,
                deletedAt = null,
                reminderTime = request.reminderTime,
                isReminded = false,
                createdAt = nowIso,
                updatedAt = nowIso
            )
            created = note
            data.copy(
                nextTempId = tempId - 1,
                notes = data.notes + CachedNote(note, isNew = true)
            )
        }
        afterLocalChange()
        Result.success(created!!)
    }

    suspend fun updateNote(id: Int, request: NoteRequest): Result<Note> =
        mutateNote(id) { cached, nowIso ->
            cached.copy(
                note = cached.note.copy(
                    folder = request.folder,
                    title = request.title,
                    content = request.content,
                    color = request.color,
                    isPinned = request.isPinned,
                    reminderTime = request.reminderTime,
                    updatedAt = nowIso
                ),
                dirtyFields = true
            )
        }

    suspend fun setPinned(id: Int, pinned: Boolean): Result<Unit> =
        mutateNote(id) { cached, nowIso ->
            cached.copy(note = cached.note.copy(isPinned = pinned, updatedAt = nowIso), dirtyFields = true)
        }.map { }

    /** Soft delete: the note goes to the trash and can be restored. */
    suspend fun deleteNote(id: Int): Result<Unit> =
        mutateNote(id) { cached, nowIso ->
            cached.copy(note = cached.note.copy(deletedAt = nowIso), dirtyTrash = true)
        }.map { }

    suspend fun restoreNote(id: Int): Result<Unit> =
        mutateNote(id) { cached, _ ->
            cached.copy(note = cached.note.copy(deletedAt = null), dirtyTrash = true)
        }.map { }

    suspend fun archiveNote(id: Int): Result<Unit> =
        mutateNote(id) { cached, _ ->
            cached.copy(note = cached.note.copy(isArchived = true), dirtyArchive = true)
        }.map { }

    suspend fun unarchiveNote(id: Int): Result<Unit> =
        mutateNote(id) { cached, _ ->
            cached.copy(note = cached.note.copy(isArchived = false), dirtyArchive = true)
        }.map { }

    suspend fun permanentlyDeleteNote(id: Int): Result<Unit> = withContext(Dispatchers.IO) {
        ensureOwner()
        val realId = resolve(id)
        deleteFiles(store.read().notes.firstOrNull { it.note.id == realId }?.uploads.orEmpty())
        store.edit { data ->
            val target = data.notes.firstOrNull { it.note.id == realId }
            data.copy(
                notes = data.notes.filterNot { it.note.id == realId },
                // A note the server never saw has nothing to delete there.
                tombstones = if (target?.isNew == true || realId in data.tombstones) data.tombstones
                else data.tombstones + realId
            )
        }
        afterLocalChange()
        Result.success(Unit)
    }

    suspend fun emptyTrash(): Result<Unit> = withContext(Dispatchers.IO) {
        ensureOwner()
        deleteFiles(store.read().notes.filter { it.note.deletedAt != null }.flatMap { it.uploads })
        store.edit { data ->
            val trashed = data.notes.filter { it.note.deletedAt != null }
            val trashedIds = trashed.map { it.note.id }.toSet()
            data.copy(
                notes = data.notes.filterNot { it.note.id in trashedIds },
                tombstones = (data.tombstones + trashed.filter { !it.isNew }.map { it.note.id }).distinct(),
                // Also clears anything in the server's trash that this device never cached.
                emptyTrashPending = true
            )
        }
        afterLocalChange()
        Result.success(Unit)
    }

    // ------------------------------------------------------------ attachments

    /** Everything shown in a note's image strip: uploaded images, then ones waiting to upload. */
    suspend fun getAttachmentItems(noteId: Int): List<AttachmentItem> = withContext(Dispatchers.IO) {
        val cached = store.read().notes.firstOrNull { it.note.id == resolve(noteId) }
            ?: return@withContext emptyList()
        cached.note.attachments.orEmpty().map {
            AttachmentItem(key = "remote:${it.id}", name = it.originalName, attachment = it)
        } + cached.uploads.map {
            AttachmentItem(key = "local:${it.localPath}", name = it.name, localPath = it.localPath)
        }
    }

    /** Queues an already-compressed image (owned by the repository from now on) for upload. */
    suspend fun addAttachment(noteId: Int, file: File, name: String, mime: String): Result<Unit> =
        mutateNote(noteId) { cached, nowIso ->
            cached.copy(
                note = cached.note.copy(updatedAt = nowIso),
                pendingUploads = cached.uploads + PendingUpload(file.absolutePath, name, mime)
            )
        }.map { }

    /** Removes an image that is already on the server (the delete is pushed when online). */
    suspend fun removeAttachment(noteId: Int, attachmentId: Int): Result<Unit> =
        mutateNote(noteId) { cached, nowIso ->
            cached.copy(
                note = cached.note.copy(
                    attachments = cached.note.attachments.orEmpty().filterNot { it.id == attachmentId },
                    updatedAt = nowIso
                ),
                pendingAttachmentDeletes = (cached.attachmentDeletes + attachmentId).distinct()
            )
        }.map { }

    /** Drops an image that never reached the server and deletes its file. */
    suspend fun removePendingUpload(noteId: Int, localPath: String): Result<Unit> {
        val result = mutateNote(noteId) { cached, _ ->
            cached.copy(pendingUploads = cached.uploads.filterNot { it.localPath == localPath })
        }.map { }
        File(localPath).delete()
        return result
    }

    /** Removes the cache entirely (explicit logout). */
    fun clearLocalData() {
        deleteFiles(store.read().notes.flatMap { it.uploads })
        store.clear()
        _status.value = SyncStatus()
    }

    /** Called when the device regains connectivity. */
    fun onNetworkAvailable() {
        _status.value = _status.value.copy(offline = false)
        requestSync()
    }

    fun requestSync() {
        scope.launch { syncPending() }
    }

    // ------------------------------------------------------------------- sync

    /**
     * Pushes local changes to the server.
     *
     * @return false if the server could not be reached (caller should use the cache),
     *         true if there was nothing to push or the push reached the server.
     */
    suspend fun syncPending(): Boolean = syncMutex.withLock {
        if (store.read().pendingCount == 0) return true

        val reachable = pushPending()
        if (reachable) markOnline() else markOffline()
        updatePending()
        reachable
    }

    private suspend fun pushPending(): Boolean {
        // 1. Empty trash first: only notes trashed *before* it were meant to go.
        if (store.read().emptyTrashPending) {
            val r = safeApiCall { api.emptyTrash() }
            if (r.isNetworkFailure()) return false
            store.edit { it.copy(emptyTrashPending = false) }
        }

        // 2. Permanent deletes (soft delete first in case the note was only trashed locally).
        for (id in store.read().tombstones) {
            if (safeApiCall { api.deleteNote(id) }.isNetworkFailure()) return false
            if (safeApiCall { api.permanentlyDeleteNote(id) }.isNetworkFailure()) return false
            store.edit { it.copy(tombstones = it.tombstones - id) }
        }

        // 3. Notes created offline (oldest first).
        val created = store.read().notes.filter { it.isNew }.sortedByDescending { it.note.id }
        for (cached in created) {
            if (!pushCreated(cached)) return false
        }

        // 4. Edits to notes that already exist on the server.
        val changed = store.read().notes.filter { !it.isNew && it.isDirty }
        for (cached in changed) {
            if (!pushChanged(cached)) return false
            if (!pushAttachments(cached.note.id)) return false
        }
        return true
    }

    /**
     * Uploads images picked offline and deletes ones the user removed. Notes in the
     * trash wait (the server refuses uploads to them) until they are restored.
     */
    private suspend fun pushAttachments(noteId: Int): Boolean {
        val start = store.read().notes.firstOrNull { it.note.id == noteId } ?: return true
        if (start.note.deletedAt != null) return true

        for (attachmentId in start.attachmentDeletes) {
            // 404 just means it is already gone.
            if (safeApiCall { api.deleteAttachment(attachmentId) }.isNetworkFailure()) return false
            updateEntry(noteId) { it.copy(pendingAttachmentDeletes = it.attachmentDeletes - attachmentId) }
        }

        for (upload in start.uploads) {
            val file = File(upload.localPath)
            if (!file.exists()) {
                updateEntry(noteId) { it.copy(pendingUploads = it.uploads - upload) }
                continue
            }
            val part = MultipartBody.Part.createFormData(
                "file", upload.name, file.asRequestBody(upload.mime.toMediaType())
            )
            val result = safeApiCall { api.uploadAttachment(noteId, part) }
            if (result.isNetworkFailure()) return false

            val uploaded = result.getOrNull()
            val rejected = (result.httpCode() ?: 0) in 400..499 // retrying would never help
            if (uploaded != null || rejected) {
                updateEntry(noteId) {
                    it.copy(
                        pendingUploads = it.uploads - upload,
                        note = if (uploaded == null) it.note
                        else it.note.copy(attachments = it.note.attachments.orEmpty() + uploaded)
                    )
                }
                file.delete()
            }
        }
        return true
    }

    private fun updateEntry(noteId: Int, change: (CachedNote) -> CachedNote) {
        store.edit { data ->
            data.copy(notes = data.notes.map { if (it.note.id == noteId) change(it) else it })
        }
    }

    private suspend fun pushCreated(cached: CachedNote): Boolean {
        val note = cached.note
        var request = NoteRequest(
            note.folder, note.title, note.content, note.color, note.isPinned, note.reminderTime
        )
        var result = safeApiCall { api.createNote(request) }
        if (result.isNetworkFailure()) return false
        if (result.httpCode() == 400 && request.folder != null) {
            // The folder was probably deleted meanwhile; keep the note, drop the folder.
            request = request.copy(folder = null)
            result = safeApiCall { api.createNote(request) }
            if (result.isNetworkFailure()) return false
        }
        val server = result.getOrNull() ?: return true // rejected: stays local, retried later

        var replacement: Note? = null
        store.edit { data ->
            val local = data.notes.firstOrNull { it.note.id == note.id } ?: return@edit data
            val editedMeanwhile = local.rev != cached.rev
            val merged = if (editedMeanwhile) {
                local.note.copy(id = server.id, createdAt = server.createdAt)
            } else {
                server.copy(
                    folder = request.folder,
                    isArchived = local.note.isArchived,
                    deletedAt = local.note.deletedAt
                )
            }
            replacement = merged
            data.copy(
                notes = data.notes.map {
                    if (it.note.id != note.id) it else CachedNote(
                        note = merged,
                        dirtyFields = editedMeanwhile,
                        dirtyArchive = local.note.isArchived,
                        dirtyTrash = local.note.deletedAt != null,
                        rev = local.rev,
                        pendingUploads = local.pendingUploads,
                        pendingAttachmentDeletes = local.pendingAttachmentDeletes
                    )
                },
                idMap = data.idMap + (note.id to server.id)
            )
        }
        replacement?.let { onTempIdReplaced(note.id, it) }
        return true
    }

    private suspend fun pushChanged(cached: CachedNote): Boolean {
        val note = cached.note
        val id = note.id
        var gone = false

        // A restored note must leave the trash before it can be edited on the server.
        if (cached.dirtyTrash && note.deletedAt == null) {
            val r = safeApiCall { api.restoreNote(id) }
            if (r.isNetworkFailure()) return false
        }
        if (cached.dirtyFields) {
            val request = NoteRequest(
                note.folder, note.title, note.content, note.color, note.isPinned, note.reminderTime
            )
            val r = safeApiCall { api.updateNote(id, request) }
            if (r.isNetworkFailure()) return false
            if (r.httpCode() == 404) gone = true
        }
        if (!gone && cached.dirtyArchive) {
            val r = safeApiCall { if (note.isArchived) api.archiveNote(id) else api.unarchiveNote(id) }
            if (r.isNetworkFailure()) return false
            if (r.httpCode() == 404) gone = true
        }
        if (!gone && cached.dirtyTrash && note.deletedAt != null) {
            if (safeApiCall { api.deleteNote(id) }.isNetworkFailure()) return false
        }
        if (gone) deleteFiles(cached.uploads)

        store.edit { data ->
            data.copy(
                notes = data.notes.mapNotNull {
                    when {
                        it.note.id != id -> it
                        gone -> null // deleted on the server by someone else
                        it.rev != cached.rev -> it // changed again during the push: keep dirty
                        else -> it.copy(dirtyFields = false, dirtyArchive = false, dirtyTrash = false)
                    }
                }
            )
        }
        return true
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun mutateNote(
        id: Int,
        change: (CachedNote, String) -> CachedNote
    ): Result<Note> = withContext(Dispatchers.IO) {
        ensureOwner()
        val realId = resolve(id)
        val nowIso = ReminderHelper.toIso(clock())
        var updated: Note? = null
        store.edit { data ->
            data.copy(
                notes = data.notes.map {
                    if (it.note.id != realId) it else change(it, nowIso).let { changed ->
                        updated = changed.note
                        changed.copy(rev = it.rev + 1)
                    }
                }
            )
        }
        val note = updated ?: return@withContext Result.failure(
            Exception("Catatan tidak ditemukan di penyimpanan perangkat.")
        )
        afterLocalChange()
        Result.success(note)
    }

    private fun afterLocalChange() {
        updatePending()
        if (autoSync) requestSync()
    }

    private fun updatePending() {
        _status.value = _status.value.copy(pendingChanges = store.read().pendingCount)
    }

    private fun markOffline() {
        _status.value = _status.value.copy(offline = true, pendingChanges = store.read().pendingCount)
    }

    private fun markOnline() {
        _status.value = _status.value.copy(offline = false, pendingChanges = store.read().pendingCount)
    }

    private fun ensureOwner() {
        // Another account's unsynced images must not linger in app storage.
        store.ensureOwner(currentUser())?.let { discarded ->
            deleteFiles(discarded.notes.flatMap { it.uploads })
        }
    }

    private fun deleteFiles(uploads: List<PendingUpload>) {
        uploads.forEach { File(it.localPath).delete() }
    }

    /** DRF pages -> one list. Pages are fetched in id order so edits can't shift them. */
    private suspend fun <T> fetchAllPages(fetch: suspend (page: Int) -> PagedResponse<T>): List<T> {
        val all = mutableListOf<T>()
        var page = 1
        while (page <= MAX_PAGES) {
            val response = fetch(page)
            all += response.results
            if (response.next == null) break
            page++
        }
        return all
    }

    private fun resolve(id: Int): Int = store.read().idMap[id] ?: id

    /** Server notes win, except for notes that still have unsynced local changes. */
    private fun upsertSynced(server: List<Note>) {
        store.edit { data ->
            val local = data.notes.associateBy { it.note.id }
            val merged = data.notes.toMutableList()
            for (note in server) {
                val existing = local[note.id]
                when {
                    existing == null -> merged += CachedNote(note)
                    existing.isDirty -> Unit
                    else -> merged[merged.indexOf(existing)] = existing.copy(note = note)
                }
            }
            data.copy(notes = merged)
        }
    }

    /** Like [upsertSynced], and drops synced notes of this set the server no longer lists. */
    private fun replaceSet(server: List<Note>, inSet: (Note) -> Boolean) {
        upsertSynced(server)
        val serverIds = server.map { it.id }.toSet()
        store.edit { data ->
            data.copy(
                notes = data.notes.filterNot {
                    !it.isDirty && inSet(it.note) && it.note.id !in serverIds
                }
            )
        }
    }

    private fun cachedActive(folderId: Int?, query: String?): List<Note> =
        store.read().notes.map { it.note }
            .filter { it.deletedAt == null && !it.isArchived }
            .filter { folderId == null || it.folder == folderId }
            .filter {
                query == null ||
                    it.title.contains(query, ignoreCase = true) ||
                    it.content.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<Note> { it.isPinned }.thenByDescending { it.updatedAt.orEmpty() }
            )

    private fun cachedArchived(): List<Note> =
        store.read().notes.map { it.note }
            .filter { it.deletedAt == null && it.isArchived }
            .sortedByDescending { it.updatedAt.orEmpty() }

    private fun cachedTrash(): List<Note> =
        store.read().notes.map { it.note }
            .filter { it.deletedAt != null }
            .sortedByDescending { it.deletedAt.orEmpty() }

    companion object {
        const val PAGE_SIZE = 100
        private const val MAX_PAGES = 500
        private const val CACHE_FILE = "offline_cache.json"

        @Volatile
        private var instance: NotesRepository? = null

        /** One shared repository so every screen sees the same cache and sync state. */
        fun get(context: Context): NotesRepository {
            val appContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: NotesRepository(
                    api = RetrofitClient.getApi(appContext),
                    store = LocalStore(File(appContext.filesDir, CACHE_FILE)),
                    currentUser = { RetrofitClient.getSessionManager(appContext).username },
                    onTempIdReplaced = { tempId, note ->
                        // Move the reminder alarm from the temporary id to the real one.
                        ReminderScheduler.cancel(appContext, tempId)
                        ReminderScheduler.sync(appContext, note)
                    }
                ).also { instance = it }
            }
        }
    }
}

private fun Result<*>.isNetworkFailure(): Boolean {
    val error = exceptionOrNull() ?: return false
    return error is IOException || error.cause is IOException
}

private fun Result<*>.httpCode(): Int? {
    val error = exceptionOrNull() ?: return null
    return (error as? HttpException)?.code() ?: (error.cause as? HttpException)?.code()
}
