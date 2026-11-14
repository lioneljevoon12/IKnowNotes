package com.example.iknownotes.data.remote

import android.content.Context
import com.example.iknownotes.data.local.SessionManager
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    /**
     * Set at build time (see app/build.gradle.kts). The default 10.0.2.2 is the alias
     * the Android emulator uses to reach "localhost" on your computer, i.e.
     * `python manage.py runserver` on port 8000.
     *
     * - Real phone on the same Wi-Fi: build with your computer's LAN IP, e.g.
     *   `./gradlew assembleDebug -PbaseUrl=http://192.168.1.5:8000/`
     * - Deployed backend: pass the real domain, e.g. -PbaseUrl=https://notes.example.com/
     *
     * It can also live in gradle.properties as `baseUrl=...`.
     */
    const val BASE_URL = com.example.iknownotes.BuildConfig.BASE_URL

    @Volatile
    private var sessionManager: SessionManager? = null

    @Volatile
    private var apiService: ApiService? = null

    fun init(context: Context) {
        if (sessionManager != null) return
        sessionManager = SessionManager(context)
    }

    fun getSessionManager(context: Context): SessionManager {
        init(context)
        return sessionManager!!
    }

    fun getApi(context: Context): ApiService {
        init(context)
        return apiService ?: synchronized(this) {
            apiService ?: buildApi().also { apiService = it }
        }
    }

    private fun buildApi(): ApiService {
        val session = sessionManager!!

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(session))
            .addInterceptor(logging)
            .authenticator(TokenAuthenticator(session, BASE_URL))
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            // serializeNulls: otherwise clearing a reminder/folder (null) is dropped from
            // the PATCH body and the server keeps the old value.
            .addConverterFactory(
                GsonConverterFactory.create(GsonBuilder().serializeNulls().create())
            )
            .build()

        return retrofit.create(ApiService::class.java)
    }
}
