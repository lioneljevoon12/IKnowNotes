package com.example.iknownotes.data.repo

import com.example.iknownotes.data.local.SessionManager
import com.example.iknownotes.data.model.LoginRequest
import com.example.iknownotes.data.model.RegisterRequest
import com.example.iknownotes.data.remote.ApiService

class AuthRepository(
    private val api: ApiService,
    private val sessionManager: SessionManager
) {

    suspend fun login(username: String, password: String): Result<Unit> {
        return safeApiCall { api.login(LoginRequest(username, password)) }
            .map { token -> sessionManager.saveSession(token.access, token.refresh, username) }
    }

    suspend fun register(username: String, password: String): Result<Unit> {
        return safeApiCall { api.register(RegisterRequest(username, password)) }
            .map { token -> sessionManager.saveSession(token.access, token.refresh, username) }
    }

    fun logout() {
        sessionManager.clear()
    }

    fun isLoggedIn(): Boolean = sessionManager.isLoggedIn
}
