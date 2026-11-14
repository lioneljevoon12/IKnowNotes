package com.example.iknownotes.ui.splash

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.iknownotes.BuildConfig
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.data.repo.AuthRepository
import com.example.iknownotes.databinding.ActivitySplashBinding
import com.example.iknownotes.ui.auth.LoginActivity
import com.example.iknownotes.ui.notes.NotesListActivity
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = RetrofitClient.getSessionManager(this)
        when {
            session.isLoggedIn -> openNotes()
            // Debug-only testing shortcut (see app/build.gradle.kts); false in normal and release builds.
            BuildConfig.DEBUG && BuildConfig.DEV_AUTO_LOGIN -> devAutoLogin()
            else -> openLogin()
        }
    }

    /** Signs in to the test account, creating it on the first run; falls back to the login screen. */
    private fun devAutoLogin() {
        val repository = AuthRepository(
            RetrofitClient.getApi(this),
            RetrofitClient.getSessionManager(this)
        )
        lifecycleScope.launch {
            val username = BuildConfig.DEV_USERNAME
            val password = BuildConfig.DEV_PASSWORD
            var result = repository.login(username, password)
            if (result.isFailure) result = repository.register(username, password)

            result
                .onSuccess { openNotes() }
                .onFailure {
                    Toast.makeText(
                        this@SplashActivity,
                        "Auto-login gagal: ${it.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    openLogin()
                }
        }
    }

    private fun openNotes() {
        startActivity(Intent(this, NotesListActivity::class.java))
        finish()
    }

    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
