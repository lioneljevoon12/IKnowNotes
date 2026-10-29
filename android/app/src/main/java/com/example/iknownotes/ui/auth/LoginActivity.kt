package com.example.iknownotes.ui.auth

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.iknownotes.R
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.data.repo.AuthRepository
import com.example.iknownotes.databinding.ActivityLoginBinding
import com.example.iknownotes.ui.notes.NotesListActivity
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authRepository: AuthRepository

    /** true = login mode, false = register mode */
    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = RetrofitClient.getSessionManager(this)
        authRepository = AuthRepository(RetrofitClient.getApi(this), session)

        binding.btnSubmit.setOnClickListener { submit() }
        binding.tvToggleMode.setOnClickListener { toggleMode() }

        applyMode()
    }

    private fun toggleMode() {
        isLoginMode = !isLoginMode
        applyMode()
    }

    private fun applyMode() {
        binding.tvError.visibility = View.GONE
        if (isLoginMode) {
            binding.btnSubmit.text = getString(R.string.action_login)
            binding.tvToggleMode.text = getString(R.string.toggle_to_register)
        } else {
            binding.btnSubmit.text = getString(R.string.action_register)
            binding.tvToggleMode.text = getString(R.string.toggle_to_login)
        }
    }

    private fun submit() {
        val username = binding.etUsername.text?.toString()?.trim().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (username.isBlank() || password.isBlank()) {
            showError(getString(R.string.error_fields_required))
            return
        }
        if (!isLoginMode && password.length < 8) {
            showError(getString(R.string.error_password_too_short))
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            val result = if (isLoginMode) {
                authRepository.login(username, password)
            } else {
                authRepository.register(username, password)
            }
            setLoading(false)
            result
                .onSuccess { goToNotesList() }
                .onFailure { showError(it.message ?: getString(R.string.error_fields_required)) }
        }
    }

    private fun goToNotesList() {
        startActivity(Intent(this, NotesListActivity::class.java))
        finish()
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !loading
        binding.tvToggleMode.isEnabled = !loading
    }
}
