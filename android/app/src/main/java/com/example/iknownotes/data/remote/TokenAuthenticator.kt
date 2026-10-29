package com.example.iknownotes.data.remote

import com.example.iknownotes.data.local.SessionManager
import com.example.iknownotes.data.model.RefreshResponse
import com.google.gson.Gson
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/**
 * When a request comes back 401 (access token expired), this tries once to
 * get a new access token using the stored refresh token, then retries the
 * original request. If the refresh itself fails (refresh token expired too,
 * or no refresh token stored), the session is cleared so the next screen the
 * user hits will bounce them back to LoginActivity.
 */
class TokenAuthenticator(
    private val sessionManager: SessionManager,
    private val baseUrl: String
) : Authenticator {

    private val gson = Gson()
    private val plainClient = OkHttpClient.Builder().build()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Avoid infinite retry loops.
        if (responseCount(response) >= 2) return null

        val refreshToken = sessionManager.refreshToken ?: return null

        val newAccessToken = try {
            requestNewAccessToken(refreshToken)
        } catch (e: Exception) {
            null
        }

        if (newAccessToken.isNullOrBlank()) {
            sessionManager.clear()
            return null
        }

        sessionManager.accessToken = newAccessToken

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newAccessToken")
            .build()
    }

    private fun requestNewAccessToken(refreshToken: String): String? {
        val json = gson.toJson(mapOf("refresh" to refreshToken))
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/token/refresh/")
            .post(body)
            .build()

        plainClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bodyString = resp.body?.string() ?: return null
            return gson.fromJson(bodyString, RefreshResponse::class.java).access
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
