package com.example.iknownotes.data.remote

import com.example.iknownotes.data.local.SessionManager
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds "Authorization: Bearer <token>" to every request except the
 * auth endpoints themselves (register/login/refresh don't need it, and
 * sending a stale/expired token there would just be noise).
 */
class AuthInterceptor(private val sessionManager: SessionManager) : Interceptor {

    private val noAuthPaths = listOf("api/register/", "api/token/", "api/token/refresh/")

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val path = original.url.encodedPath.removePrefix("/")

        if (noAuthPaths.any { path == it || path.endsWith(it) }) {
            return chain.proceed(original)
        }

        val token = sessionManager.accessToken
        val request = if (!token.isNullOrBlank()) {
            original.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}
