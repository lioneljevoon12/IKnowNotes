package com.example.iknownotes.ui.trash

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.iknownotes.R
import com.example.iknownotes.data.model.Folder
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.repo.NotesRepository
import com.example.iknownotes.databinding.ActivityTrashBinding
import com.example.iknownotes.ui.notes.NoteAdapter
import com.example.iknownotes.util.ReminderHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Lists soft-deleted notes; each one can be restored or deleted permanently. */
class TrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrashBinding
    private lateinit var notesRepository: NotesRepository
    private lateinit var adapter: NoteAdapter

    private var folders: List<Folder> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notesRepository = NotesRepository.get(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEmptyTrash.setOnClickListener { confirmEmptyTrash() }

        setupRecyclerView()
        loadFolders()
    }

    override fun onResume() {
        super.onResume()
        loadTrash()
    }

    private fun setupRecyclerView() {
        adapter = NoteAdapter(
            folderNameOf = { id -> folders.firstOrNull { it.id == id }?.name },
            onClick = { note -> showNoteOptions(note) }
        )
        binding.rvTrashNotes.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.rvTrashNotes.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { loadTrash() }
    }

    private fun loadFolders() {
        lifecycleScope.launch {
            notesRepository.getFolders().onSuccess { list ->
                folders = list
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun loadTrash() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            notesRepository.getTrashNotes()
                .onSuccess { notes -> renderNotes(notes) }
                .onFailure {
                    binding.swipeRefresh.isRefreshing = false
                    showSnackbar(it.message ?: "Gagal memuat sampah")
                }
        }
    }

    private fun renderNotes(notes: List<Note>) {
        binding.swipeRefresh.isRefreshing = false
        adapter.submitList(notes)
        binding.layoutEmpty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        binding.btnEmptyTrash.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showNoteOptions(note: Note) {
        AlertDialog.Builder(this)
            .setTitle(note.title.ifBlank { "(Tanpa judul)" })
            .setMessage(daysLeftText(note))
            .setPositiveButton(R.string.action_restore) { _, _ -> restore(note) }
            .setNegativeButton(R.string.action_delete_permanently) { _, _ ->
                confirmPermanentDelete(note)
            }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
    }

    private fun daysLeftText(note: Note): String {
        val deletedAt = ReminderHelper.parseIso(note.deletedAt)
            ?: return getString(R.string.trash_info)
        val expiresAt = deletedAt + TimeUnit.DAYS.toMillis(RETENTION_DAYS)
        val daysLeft = TimeUnit.MILLISECONDS.toDays(expiresAt - System.currentTimeMillis())
        return if (daysLeft < 1) {
            getString(R.string.trash_less_than_a_day)
        } else {
            getString(R.string.trash_days_left, daysLeft.toInt())
        }
    }

    private fun restore(note: Note) {
        lifecycleScope.launch {
            notesRepository.restoreNote(note.id)
                .onSuccess {
                    showSnackbar(getString(R.string.note_restored))
                    loadTrash()
                }
                .onFailure { showSnackbar(it.message ?: "Gagal memulihkan catatan") }
        }
    }

    private fun confirmPermanentDelete(note: Note) {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_permanent_delete_title)
            .setMessage(R.string.confirm_permanent_delete_message)
            .setPositiveButton(R.string.action_delete_permanently) { _, _ -> deletePermanently(note) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deletePermanently(note: Note) {
        lifecycleScope.launch {
            notesRepository.permanentlyDeleteNote(note.id)
                .onSuccess {
                    showSnackbar(getString(R.string.note_deleted_permanently))
                    loadTrash()
                }
                .onFailure { showSnackbar(it.message ?: "Gagal menghapus catatan") }
        }
    }

    private fun confirmEmptyTrash() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_empty_trash_title)
            .setMessage(R.string.confirm_empty_trash_message)
            .setPositiveButton(R.string.action_empty_trash) { _, _ -> emptyTrash() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun emptyTrash() {
        lifecycleScope.launch {
            notesRepository.emptyTrash()
                .onSuccess {
                    showSnackbar(getString(R.string.trash_emptied))
                    loadTrash()
                }
                .onFailure { showSnackbar(it.message ?: "Gagal mengosongkan sampah") }
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        /** Keep in sync with TRASH_RETENTION_DAYS in backend/notes/views.py. */
        private const val RETENTION_DAYS = 30L
    }
}
