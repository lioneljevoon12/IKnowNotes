package com.example.iknownotes.data.remote

import com.example.iknownotes.data.model.Folder
import com.example.iknownotes.data.model.FolderRequest
import com.example.iknownotes.data.model.LoginRequest
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.model.NoteRequest
import com.example.iknownotes.data.model.RefreshRequest
import com.example.iknownotes.data.model.RefreshResponse
import com.example.iknownotes.data.model.RegisterRequest
import com.example.iknownotes.data.model.TokenResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Mirrors backend/config/urls.py + notes/views.py from the Django project.
 */
interface ApiService {

    @POST("api/register/")
    suspend fun register(@Body body: RegisterRequest): TokenResponse

    @POST("api/token/")
    suspend fun login(@Body body: LoginRequest): TokenResponse

    @POST("api/token/refresh/")
    suspend fun refreshToken(@Body body: RefreshRequest): RefreshResponse

    @GET("api/folders/")
    suspend fun getFolders(): List<Folder>

    @POST("api/folders/")
    suspend fun createFolder(@Body body: FolderRequest): Folder

    @PATCH("api/folders/{id}/")
    suspend fun updateFolder(@Path("id") id: Int, @Body body: FolderRequest): Folder

    @DELETE("api/folders/{id}/")
    suspend fun deleteFolder(@Path("id") id: Int): Response<Unit>

    @GET("api/notes/")
    suspend fun getNotes(
        @Query("folder") folderId: Int? = null,
        @Query("search") search: String? = null
    ): List<Note>

    @GET("api/notes/archived/")
    suspend fun getArchivedNotes(): List<Note>

    @GET("api/notes/{id}/")
    suspend fun getNote(@Path("id") id: Int): Note

    @POST("api/notes/")
    suspend fun createNote(@Body body: NoteRequest): Note

    @PATCH("api/notes/{id}/")
    suspend fun updateNote(@Path("id") id: Int, @Body body: NoteRequest): Note

    @DELETE("api/notes/{id}/")
    suspend fun deleteNote(@Path("id") id: Int): Response<Unit>

    @PATCH("api/notes/{id}/archive/")
    suspend fun archiveNote(@Path("id") id: Int): Response<Unit>

    @PATCH("api/notes/{id}/unarchive/")
    suspend fun unarchiveNote(@Path("id") id: Int): Response<Unit>
}
