package com.example.iknownotes.data.remote

import android.content.Context
import com.example.iknownotes.data.local.SessionManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    /**
     * 10.0.2.2 is the special alias the Android emulator uses to reach
     * "localhost" on the machine running Android Studio, i.e. your
     * `python manage.py runserver` on port 8000.
     *
     * - Testing on a physical device on the same Wi-Fi: replace with your
     *   computer's LAN IP, e.g. "http://192.168.1.5:8000/".
     * - Deployed backend: swap in the real domain/https URL.
     */
    const val BASE_URL = "http://10.0.2.2:8000/"

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
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(ApiService::class.java)
    }
}
