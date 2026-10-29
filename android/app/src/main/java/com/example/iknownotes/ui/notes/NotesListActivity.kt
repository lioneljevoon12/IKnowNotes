package com.example.iknownotes.ui.notes

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.iknownotes.R
import com.example.iknownotes.data.model.Folder
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.data.repo.AuthRepository
import com.example.iknownotes.data.repo.NotesRepository
import com.example.iknownotes.databinding.ActivityNotesListBinding
import com.example.iknownotes.ui.auth.LoginActivity
import com.example.iknownotes.ui.notedetail.NoteDetailActivity
import com.example.iknownotes.ui.noteedit.NoteEditActivity
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotesListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotesListBinding
    private lateinit var notesRepository: NotesRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var adapter: NoteAdapter

    private var folders: List<Folder> = emptyList()
    private var selectedFolderId: Int? = null
    private var searchQuery: String = ""
    private var searchDebounceJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotesListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val api = RetrofitClient.getApi(this)
        val session = RetrofitClient.getSessionManager(this)
        notesRepository = NotesRepository(api)
        authRepository = AuthRepository(api, session)

        binding.tvGreeting.text = "Halo, ${session.username ?: "kamu"}"

        setupRecyclerView()
        setupSearch()
        setupHeaderAndFab()

        loadFolders()
    }

    override fun onResume() {
        super.onResume()
        // Covers coming back from create/edit/detail/archive screens with fresh data.
        loadNotes()
    }

    private fun setupRecyclerView() {
        adapter = NoteAdapter(
            folderNameOf = { id -> folders.firstOrNull { it.id == id }?.name },
            onClick = { note ->
                startActivity(
                    Intent(this, NoteDetailActivity::class.java)
                        .putExtra(NoteDetailActivity.EXTRA_NOTE_ID, note.id)
                )
            }
        )
        binding.rvNotes.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.rvNotes.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { loadNotes() }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                searchDebounceJob?.cancel()
                searchDebounceJob = lifecycleScope.launch {
                    delay(350)
                    loadNotes()
                }
            }
        })
    }

    private fun setupHeaderAndFab() {
        binding.btnArchivedNotes.setOnClickListener {
            startActivity(Intent(this, ArchivedNotesActivity::class.java))
        }
        binding.fabAddNote.setOnClickListener {
            startActivity(
                Intent(this, NoteEditActivity::class.java)
                    .putExtra(NoteEditActivity.EXTRA_FOLDER_ID, selectedFolderId)
            )
        }
        binding.btnLogout.setOnClickListener {
            authRepository.logout()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun loadFolders() {
        lifecycleScope.launch {
            notesRepository.getFolders()
                .onSuccess { list ->
                    folders = list
                    renderFolderChips()
                    adapter.notifyDataSetChanged()
                }
                .onFailure {
                    // Non-fatal: notes list still works without folder chips.
                }
        }
    }

    private fun renderFolderChips() {
        val group = binding.chipGroupFolders
        group.removeAllViews()
        val inflater = LayoutInflater.from(this)

        val allChip = inflater.inflate(R.layout.item_folder_chip, group, false) as Chip
        allChip.text = getString(R.string.chip_all)
        allChip.isChecked = selectedFolderId == null
        allChip.setOnClickListener {
            selectedFolderId = null
            loadNotes()
        }
        group.addView(allChip)

        folders.forEach { folder ->
            val chip = inflater.inflate(R.layout.item_folder_chip, group, false) as Chip
            chip.text = folder.name
            chip.isChecked = selectedFolderId == folder.id
            chip.setOnClickListener {
                selectedFolderId = folder.id
                loadNotes()
            }
            chip.setOnLongClickListener {
                showFolderOptionsDialog(folder)
                true
            }
            group.addView(chip)
        }

        val addChip = inflater.inflate(R.layout.item_folder_chip, group, false) as Chip
        addChip.text = getString(R.string.chip_add_folder)
        addChip.isCheckable = false
        addChip.setOnClickListener { showCreateFolderDialog() }
        group.addView(addChip)
    }

    private fun showFolderOptionsDialog(folder: Folder) {
        val options = arrayOf(
            getString(R.string.action_rename_folder),
            getString(R.string.action_delete_folder)
        )
        AlertDialog.Builder(this)
            .setTitle(folder.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameFolderDialog(folder)
                    1 -> confirmDeleteFolder(folder)
                }
            }
            .show()
    }

    private fun showRenameFolderDialog(folder: Folder) {
        val input = EditText(this).apply {
            setText(folder.name)
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename_folder)
            .setView(input)
            .setPositiveButton(R.string.action_rename) { _, _ ->
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isNotBlank() && newName != folder.name) {
                    renameFolder(folder.id, newName)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDeleteFolder(folder: Folder) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_folder_title)
            .setMessage(getString(R.string.confirm_delete_folder_message))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                deleteFolder(folder.id)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showCreateFolderDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_folder_name)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_new_folder)
            .setView(input)
            .setPositiveButton(R.string.action_create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) createFolder(name)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun createFolder(name: String) {
        lifecycleScope.launch {
            notesRepository.createFolder(name)
                .onSuccess { loadFolders() }
                .onFailure { showSnackbar(it.message ?: "Gagal membuat folder") }
        }
    }

    private fun renameFolder(id: Int, newName: String) {
        lifecycleScope.launch {
            notesRepository.updateFolder(id, newName)
                .onSuccess {
                    loadFolders()
                    loadNotes()
                }
                .onFailure { showSnackbar(it.message ?: "Gagal mengubah folder") }
        }
    }

    private fun deleteFolder(id: Int) {
        lifecycleScope.launch {
            notesRepository.deleteFolder(id)
                .onSuccess {
                    if (selectedFolderId == id) {
                        selectedFolderId = null
                    }
                    loadFolders()
                    loadNotes()
                }
                .onFailure { showSnackbar(it.message ?: "Gagal menghapus folder") }
        }
    }

    private fun loadNotes() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            notesRepository.getNotes(selectedFolderId, searchQuery)
                .onSuccess { notes -> renderNotes(notes) }
                .onFailure { error ->
                    binding.swipeRefresh.isRefreshing = false
                    showSnackbar(error.message ?: "Gagal memuat catatan")
                    maybeRedirectToLogin(error)
                }
        }
    }

    private fun renderNotes(notes: List<Note>) {
        binding.swipeRefresh.isRefreshing = false
        adapter.submitList(notes)
        binding.layoutEmpty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun maybeRedirectToLogin(error: Throwable) {
        if (error.message?.contains("Sesi kamu udah habis") == true) {
            authRepository.logout()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}
