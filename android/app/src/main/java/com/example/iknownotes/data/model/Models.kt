package com.example.iknownotes.data.model

import com.google.gson.annotations.SerializedName

data class Folder(
    val id: Int,
    val name: String,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
)

data class FolderRequest(
    val name: String
)

data class Note(
    val id: Int,
    val folder: Int?,
    val title: String,
    val content: String,
    val color: String?,
    @SerializedName("is_pinned") val isPinned: Boolean,
    @SerializedName("is_archived") val isArchived: Boolean,
    @SerializedName("deleted_at") val deletedAt: String? = null,
    @SerializedName("reminder_time") val reminderTime: String? = null,
    @SerializedName("is_reminded") val isReminded: Boolean = false,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    /** Nullable on purpose: caches written by older app versions don't have the field. */
    val attachments: List<Attachment>? = null
)

/** An image attached to a note; the bytes live on the server (/api/attachments/{id}/file/). */
data class Attachment(
    val id: Int,
    @SerializedName("original_name") val originalName: String,
    @SerializedName("content_type") val contentType: String? = null,
    val size: Long = 0,
    @SerializedName("created_at") val createdAt: String? = null
)

/** Shape of DRF's PageNumberPagination responses. */
data class PagedResponse<T>(
    val count: Int,
    val next: String?,
    val previous: String?,
    val results: List<T>
)

/**
 * One thumbnail in the editor/detail strip: either already on the server
 * ([attachment]) or still waiting to be uploaded ([localPath]).
 */
data class AttachmentItem(
    val key: String,
    val name: String,
    val attachment: Attachment? = null,
    val localPath: String? = null
) {
    val isPending: Boolean get() = localPath != null && attachment == null
}

/**
 * Body used when creating/updating a note. Only fields the user can edit.
 */
data class NoteRequest(
    val folder: Int?,
    val title: String,
    val content: String,
    val color: String?,
    @SerializedName("is_pinned") val isPinned: Boolean = false,
    @SerializedName("reminder_time") val reminderTime: String? = null
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class RegisterRequest(
    val username: String,
    val password: String
)

data class TokenResponse(
    val access: String,
    val refresh: String
)

data class RefreshRequest(
    val refresh: String
)

data class RefreshResponse(
    val access: String
)
