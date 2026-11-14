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
import com.example.iknownotes.data.model.Attachment
import com.example.iknownotes.data.model.PagedResponse
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.Part
import retrofit2.http.Streaming
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
    suspend fun deleteFolder(@Path("id") id: Int)

    // Lists are paginated; ordering=id keeps pages stable while notes are being edited.
    @GET("api/notes/")
    suspend fun getNotes(
        @Query("folder") folderId: Int? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100,
        @Query("ordering") ordering: String? = "id"
    ): PagedResponse<Note>

    @GET("api/notes/archived/")
    suspend fun getArchivedNotes(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100,
        @Query("ordering") ordering: String? = "id"
    ): PagedResponse<Note>

    @GET("api/notes/{id}/")
    suspend fun getNote(@Path("id") id: Int): Note

    @POST("api/notes/")
    suspend fun createNote(@Body body: NoteRequest): Note

    @PATCH("api/notes/{id}/")
    suspend fun updateNote(@Path("id") id: Int, @Body body: NoteRequest): Note

    /** Soft delete: moves the note to the trash. */
    @DELETE("api/notes/{id}/")
    suspend fun deleteNote(@Path("id") id: Int)

    @GET("api/notes/trash/")
    suspend fun getTrashNotes(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 100,
        @Query("ordering") ordering: String? = "id"
    ): PagedResponse<Note>

    @PATCH("api/notes/{id}/restore/")
    suspend fun restoreNote(@Path("id") id: Int)

    /** Only works for notes already in the trash. */
    @DELETE("api/notes/{id}/permanent/")
    suspend fun permanentlyDeleteNote(@Path("id") id: Int)

    @DELETE("api/notes/trash/empty/")
    suspend fun emptyTrash()

    @PATCH("api/notes/{id}/archive/")
    suspend fun archiveNote(@Path("id") id: Int)

    @PATCH("api/notes/{id}/unarchive/")
    suspend fun unarchiveNote(@Path("id") id: Int)

    @Multipart
    @POST("api/notes/{id}/attachments/")
    suspend fun uploadAttachment(@Path("id") noteId: Int, @Part file: MultipartBody.Part): Attachment

    @Streaming
    @GET("api/attachments/{id}/file/")
    suspend fun downloadAttachment(@Path("id") attachmentId: Int): ResponseBody

    @DELETE("api/attachments/{id}/")
    suspend fun deleteAttachment(@Path("id") attachmentId: Int)
}
