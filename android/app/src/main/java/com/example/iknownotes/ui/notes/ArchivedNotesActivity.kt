package com.example.iknownotes.ui.notes

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.iknownotes.data.model.Folder
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.data.repo.NotesRepository
import com.example.iknownotes.databinding.ActivityArchivedNotesBinding
import com.example.iknownotes.ui.notedetail.NoteDetailActivity
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class ArchivedNotesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityArchivedNotesBinding
    private lateinit var notesRepository: NotesRepository
    private lateinit var adapter: NoteAdapter

    private var folders: List<Folder> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityArchivedNotesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val api = RetrofitClient.getApi(this)
        notesRepository = NotesRepository(api)

        binding.btnBack.setOnClickListener { finish() }

        setupRecyclerView()
        loadFolders()
    }

    override fun onResume() {
        super.onResume()
        loadArchivedNotes()
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
        binding.rvArchivedNotes.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.rvArchivedNotes.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { loadArchivedNotes() }
    }

    private fun loadFolders() {
        lifecycleScope.launch {
            notesRepository.getFolders()
                .onSuccess { list ->
                    folders = list
                    adapter.notifyDataSetChanged()
                }
        }
    }

    private fun loadArchivedNotes() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            notesRepository.getArchivedNotes()
                .onSuccess { notes -> renderNotes(notes) }
                .onFailure { error ->
                    binding.swipeRefresh.isRefreshing = false
                    showSnackbar(error.message ?: "Gagal memuat catatan terarsip")
                }
        }
    }

    private fun renderNotes(notes: List<Note>) {
        binding.swipeRefresh.isRefreshing = false
        adapter.submitList(notes)
        binding.layoutEmpty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}
