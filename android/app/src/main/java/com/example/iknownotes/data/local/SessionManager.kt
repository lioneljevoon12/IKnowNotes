package com.example.iknownotes.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Simple wrapper around SharedPreferences to persist the JWT access/refresh
 * tokens and the logged-in username between app launches.
 *
 * NOTE: for a production app these should live in EncryptedSharedPreferences
 * instead of plain SharedPreferences. Kept simple here since this is a
 * student project, but worth upgrading later.
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) = prefs.edit().putString(KEY_ACCESS, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) = prefs.edit().putString(KEY_REFRESH, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    val isLoggedIn: Boolean
        get() = !accessToken.isNullOrBlank()

    fun saveSession(access: String, refresh: String, username: String) {
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "iknownotes_session"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_USERNAME = "username"
    }
}
