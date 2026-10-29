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
    @SerializedName("reminder_time") val reminderTime: String? = null,
    @SerializedName("is_reminded") val isReminded: Boolean = false,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null
)

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
