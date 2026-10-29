package com.example.iknownotes.data.repo

import com.example.iknownotes.data.model.Folder
import com.example.iknownotes.data.model.FolderRequest
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.model.NoteRequest
import com.example.iknownotes.data.remote.ApiService

class NotesRepository(private val api: ApiService) {

    suspend fun getFolders(): Result<List<Folder>> = safeApiCall { api.getFolders() }

    suspend fun createFolder(name: String): Result<Folder> =
        safeApiCall { api.createFolder(FolderRequest(name)) }

    suspend fun updateFolder(id: Int, name: String): Result<Folder> =
        safeApiCall { api.updateFolder(id, FolderRequest(name)) }

    suspend fun deleteFolder(id: Int): Result<Unit> =
        safeApiCall { api.deleteFolder(id) }.map { }

    suspend fun getNotes(folderId: Int? = null, search: String? = null): Result<List<Note>> =
        safeApiCall { api.getNotes(folderId, search?.ifBlank { null }) }

    suspend fun getArchivedNotes(): Result<List<Note>> =
        safeApiCall { api.getArchivedNotes() }

    suspend fun getNote(id: Int): Result<Note> = safeApiCall { api.getNote(id) }

    suspend fun createNote(request: NoteRequest): Result<Note> =
        safeApiCall { api.createNote(request) }

    suspend fun updateNote(id: Int, request: NoteRequest): Result<Note> =
        safeApiCall { api.updateNote(id, request) }

    suspend fun deleteNote(id: Int): Result<Unit> =
        safeApiCall { api.deleteNote(id) }.map { }

    suspend fun archiveNote(id: Int): Result<Unit> =
        safeApiCall { api.archiveNote(id) }.map { }

    suspend fun unarchiveNote(id: Int): Result<Unit> =
        safeApiCall { api.unarchiveNote(id) }.map { }
}
