package com.example.iknownotes

import com.example.iknownotes.data.local.LocalStore
import com.example.iknownotes.data.model.Folder
import com.example.iknownotes.data.model.FolderRequest
import com.example.iknownotes.data.model.LoginRequest
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.model.NoteRequest
import com.example.iknownotes.data.model.RefreshRequest
import com.example.iknownotes.data.model.RefreshResponse
import com.example.iknownotes.data.model.RegisterRequest
import com.example.iknownotes.data.model.TokenResponse
import com.example.iknownotes.data.remote.ApiService
import com.example.iknownotes.data.repo.NotesRepository
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.IOException

/** In-memory stand-in for the Django backend that can be switched offline. */
private class FakeApi : ApiService {
    var online = true
    var rejectFolderId: Int? = null
    val server = mutableMapOf<Int, Note>()
    val calls = mutableListOf<String>()
    private var nextId = 100

    fun seed(title: String, archived: Boolean = false, deletedAt: String? = null): Note {
        val note = Note(
            id = nextId++, folder = null, title = title, content = "isi", color = null,
            isPinned = false, isArchived = archived, deletedAt = deletedAt,
            createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z"
        )
        server[note.id] = note
        return note
    }

    private fun check(call: String) {
        calls += call
        if (!online) throw IOException("offline")
    }

    private fun http(code: Int): Nothing =
        throw HttpException(Response.error<Any>(code, "".toResponseBody(null)))

    private fun live(id: Int): Note {
        val note = server[id] ?: http(404)
        if (note.deletedAt != null) http(404)
        return note
    }

    override suspend fun register(body: RegisterRequest): TokenResponse = TODO()
    override suspend fun login(body: LoginRequest): TokenResponse = TODO()
    override suspend fun refreshToken(body: RefreshRequest): RefreshResponse = TODO()

    override suspend fun getFolders(): List<Folder> { check("getFolders"); return emptyList() }
    override suspend fun createFolder(body: FolderRequest): Folder = TODO()
    override suspend fun updateFolder(id: Int, body: FolderRequest): Folder = TODO()
    override suspend fun deleteFolder(id: Int) = TODO()

    override suspend fun getNotes(folderId: Int?, search: String?): List<Note> {
        check("getNotes")
        return server.values.filter { it.deletedAt == null && !it.isArchived }
    }

    override suspend fun getArchivedNotes(): List<Note> {
        check("getArchivedNotes")
        return server.values.filter { it.deletedAt == null && it.isArchived }
    }

    override suspend fun getNote(id: Int): Note { check("getNote"); return live(id) }

    override suspend fun createNote(body: NoteRequest): Note {
        check("createNote")
        if (body.folder != null && body.folder == rejectFolderId) http(400)
        val note = Note(
            id = nextId++, folder = body.folder, title = body.title, content = body.content,
            color = body.color, isPinned = body.isPinned, isArchived = false, deletedAt = null,
            reminderTime = body.reminderTime, createdAt = "2026-02-01T00:00:00Z",
            updatedAt = "2026-02-01T00:00:00Z"
        )
        server[note.id] = note
        return note
    }

    override suspend fun updateNote(id: Int, body: NoteRequest): Note {
        check("updateNote")
        val updated = live(id).copy(
            folder = body.folder, title = body.title, content = body.content,
            color = body.color, isPinned = body.isPinned, reminderTime = body.reminderTime
        )
        server[id] = updated
        return updated
    }

    override suspend fun deleteNote(id: Int) {
        check("deleteNote")
        server[id] = live(id).copy(deletedAt = "2026-03-01T00:00:00Z")
    }

    override suspend fun getTrashNotes(): List<Note> {
        check("getTrashNotes")
        return server.values.filter { it.deletedAt != null }
    }

    override suspend fun restoreNote(id: Int) {
        check("restoreNote")
        val note = server[id] ?: http(404)
        if (note.deletedAt == null) http(404)
        server[id] = note.copy(deletedAt = null)
    }

    override suspend fun permanentlyDeleteNote(id: Int) {
        check("permanentlyDeleteNote")
        val note = server[id] ?: http(404)
        if (note.deletedAt == null) http(404)
        server.remove(id)
    }

    override suspend fun emptyTrash() {
        check("emptyTrash")
        server.values.removeAll { it.deletedAt != null }
    }

    override suspend fun archiveNote(id: Int) {
        check("archiveNote")
        server[id] = live(id).copy(isArchived = true)
    }

    override suspend fun unarchiveNote(id: Int) {
        check("unarchiveNote")
        server[id] = live(id).copy(isArchived = false)
    }
}

class NotesRepositoryOfflineTest {

    private lateinit var api: FakeApi
    private lateinit var store: LocalStore
    private lateinit var repo: NotesRepository
    private val replacedIds = mutableListOf<Pair<Int, Int>>()
    private var user: String? = "budi"
    private var clockMillis = 1_800_000_000_000L

    @Before
    fun setUp() {
        api = FakeApi()
        store = LocalStore(null)
        repo = newRepository(store)
    }

    private fun newRepository(store: LocalStore) = NotesRepository(
        api = api,
        store = store,
        currentUser = { user },
        onTempIdReplaced = { temp, note -> replacedIds += temp to note.id },
        autoSync = false,
        clock = { clockMillis.also { clockMillis += 1000 } }
    )

    private fun request(title: String, folder: Int? = null) =
        NoteRequest(folder, title, "isi", "yellow", false, null)

    @Test
    fun createdOffline_isShownImmediately_andPushedWhenOnline() = runBlocking {
        api.online = false
        val created = repo.createNote(request("Baru")).getOrThrow()

        assertTrue("temporary ids are negative", created.id < -1)
        assertEquals(1, repo.status.value.pendingChanges)
        assertEquals(listOf("Baru"), repo.getNotes().getOrThrow().map { it.title })
        assertTrue(repo.status.value.offline)
        assertTrue(api.server.isEmpty())

        api.online = true
        assertTrue(repo.syncPending())

        assertEquals(listOf("Baru"), api.server.values.map { it.title })
        assertEquals(0, repo.status.value.pendingChanges)
        val realId = api.server.keys.single()
        assertEquals(listOf(created.id to realId), replacedIds)
        // Screens still holding the temporary id keep working.
        assertEquals(realId, repo.getNote(created.id).getOrThrow().id)
    }

    @Test
    fun repeatedOfflineEdits_collapseIntoOneRequest() = runBlocking {
        val seeded = api.seed("Lama")
        repo.getNotes()
        api.online = false
        repo.updateNote(seeded.id, request("Edit 1"))
        repo.updateNote(seeded.id, request("Edit 2"))
        repo.updateNote(seeded.id, request("Edit 3"))
        assertEquals(1, repo.status.value.pendingChanges)

        api.calls.clear()
        api.online = true
        repo.syncPending()

        assertEquals("Edit 3", api.server[seeded.id]?.title)
        assertEquals(1, api.calls.count { it == "updateNote" })
    }

    @Test
    fun trashingOffline_hidesNoteLocally_andTrashesOnServerAfterSync() = runBlocking {
        val seeded = api.seed("Hapus aku")
        repo.getNotes()
        api.online = false

        repo.deleteNote(seeded.id)

        assertTrue(repo.getNotes().getOrThrow().isEmpty())
        assertEquals(listOf("Hapus aku"), repo.getTrashNotes().getOrThrow().map { it.title })

        api.online = true
        repo.syncPending()
        assertNotNull(api.server[seeded.id]?.deletedAt)
    }

    @Test
    fun trashThenRestoreOffline_endsActiveWithoutErrors() = runBlocking {
        val seeded = api.seed("Plin-plan")
        repo.getNotes()
        api.online = false
        repo.deleteNote(seeded.id)
        repo.restoreNote(seeded.id)

        api.online = true
        assertTrue(repo.syncPending())

        assertNull(api.server[seeded.id]?.deletedAt)
        assertEquals(0, repo.status.value.pendingChanges)
        assertEquals(listOf("Plin-plan"), repo.getNotes().getOrThrow().map { it.title })
    }

    @Test
    fun archiveOffline_isPushedAndListedInArchive() = runBlocking {
        val seeded = api.seed("Arsipkan")
        repo.getNotes()
        api.online = false
        repo.archiveNote(seeded.id)

        assertTrue(repo.getNotes().getOrThrow().isEmpty())
        assertEquals(1, repo.getArchivedNotes().getOrThrow().size)

        api.online = true
        repo.syncPending()
        assertTrue(api.server[seeded.id]!!.isArchived)
    }

    @Test
    fun permanentDeleteOffline_removesFromServerAfterSync() = runBlocking {
        val seeded = api.seed("Sudah di sampah", deletedAt = "2026-03-01T00:00:00Z")
        repo.getTrashNotes()
        api.online = false

        repo.permanentlyDeleteNote(seeded.id)
        assertTrue(repo.getTrashNotes().getOrThrow().isEmpty())
        assertTrue(api.server.containsKey(seeded.id))

        api.online = true
        repo.syncPending()
        assertFalse(api.server.containsKey(seeded.id))
    }

    @Test
    fun permanentDeleteOfNoteNeverSynced_neverReachesServer() = runBlocking {
        api.online = false
        val created = repo.createNote(request("Cuma lokal")).getOrThrow()
        repo.deleteNote(created.id)
        repo.permanentlyDeleteNote(created.id)

        assertEquals(0, repo.status.value.pendingChanges)
        api.online = true
        repo.syncPending()
        assertTrue(api.server.isEmpty())
    }

    @Test
    fun emptyTrashOffline_clearsServerTrashAfterSync() = runBlocking {
        val keep = api.seed("Aman")
        val trashed = api.seed("Sampah", deletedAt = "2026-03-01T00:00:00Z")
        repo.getNotes()
        repo.getTrashNotes()
        api.online = false

        repo.emptyTrash()
        assertTrue(repo.getTrashNotes().getOrThrow().isEmpty())

        api.online = true
        repo.syncPending()
        assertFalse(api.server.containsKey(trashed.id))
        assertTrue(api.server.containsKey(keep.id))
    }

    @Test
    fun unsyncedLocalEdit_isNotOverwrittenByServerCopy() = runBlocking {
        val seeded = api.seed("Server")
        repo.getNotes()
        api.online = false
        repo.updateNote(seeded.id, request("Lokal"))

        // Still offline: the cache must keep showing the local edit.
        assertEquals("Lokal", repo.getNotes().getOrThrow().single().title)

        api.online = true
        repo.getNotes() // flushes first, then refreshes
        assertEquals("Lokal", api.server[seeded.id]?.title)
        assertEquals("Lokal", repo.getNotes().getOrThrow().single().title)
    }

    @Test
    fun noteCreatedOfflineInDeletedFolder_isSavedWithoutFolder() = runBlocking {
        api.online = false
        repo.createNote(request("Folder hilang", folder = 7))
        api.rejectFolderId = 7
        api.online = true

        repo.syncPending()

        val saved = api.server.values.single()
        assertEquals("Folder hilang", saved.title)
        assertNull(saved.folder)
    }

    @Test
    fun offlineSearchAndOrdering_useTheCache() = runBlocking {
        api.seed("Belanja bulanan")
        api.seed("Rapat tim")
        repo.getNotes()
        api.online = false

        assertEquals(listOf("Rapat tim"), repo.getNotes(search = "rapat").getOrThrow().map { it.title })
        assertEquals(2, repo.getNotes().getOrThrow().size)
    }

    @Test
    fun pendingChanges_surviveRestart() = runBlocking {
        val file = File.createTempFile("cache", ".json")
        try {
            val firstStore = LocalStore(file)
            val first = newRepository(firstStore)
            api.online = false
            first.createNote(request("Tahan banting"))

            val reopened = newRepository(LocalStore(file))
            assertEquals(1, reopened.status.value.pendingChanges)

            api.online = true
            reopened.syncPending()
            assertEquals(listOf("Tahan banting"), api.server.values.map { it.title })
        } finally {
            file.delete()
        }
    }

    @Test
    fun cacheBelongingToAnotherUser_isDiscarded() = runBlocking {
        api.online = false
        repo.createNote(request("Punya Budi"))

        user = "ani"
        assertTrue(repo.getNotes().getOrThrow().isEmpty())
        assertEquals(0, store.read().pendingCount)
    }

    @Test
    fun getNote_withoutCacheAndOffline_fails() = runBlocking {
        api.online = false
        assertTrue(repo.getNote(123).isFailure)
    }
}
