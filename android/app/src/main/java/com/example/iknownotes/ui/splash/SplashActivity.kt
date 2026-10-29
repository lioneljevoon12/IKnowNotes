package com.example.iknownotes.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.databinding.ActivitySplashBinding
import com.example.iknownotes.ui.auth.LoginActivity
import com.example.iknownotes.ui.notes.NotesListActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = RetrofitClient.getSessionManager(this)
        val destination = if (session.isLoggedIn) {
            Intent(this, NotesListActivity::class.java)
        } else {
            Intent(this, LoginActivity::class.java)
        }
        startActivity(destination)
        finish()
    }
}
