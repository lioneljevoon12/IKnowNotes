package com.example.iknownotes.ui.notedetail

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.iknownotes.R
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.model.NoteRequest
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.data.repo.NotesRepository
import com.example.iknownotes.databinding.ActivityNoteDetailBinding
import com.example.iknownotes.ui.noteedit.NoteEditActivity
import com.example.iknownotes.reminder.ReminderScheduler
import com.example.iknownotes.util.NoteColorPalette
import com.example.iknownotes.util.ReminderHelper
import com.example.iknownotes.util.RichTextHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class NoteDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteDetailBinding
    private lateinit var notesRepository: NotesRepository
    private var noteId: Int = -1
    private var currentNote: Note? = null
    private var folders: List<com.example.iknownotes.data.model.Folder> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notesRepository = NotesRepository(RetrofitClient.getApi(this))
        noteId = intent.getIntExtra(EXTRA_NOTE_ID, -1)

        if (noteId == -1) {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnEdit.setOnClickListener { openEdit() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnArchive.setOnClickListener { toggleArchive() }
        binding.btnPin.setOnClickListener { togglePin() }

        loadFolders()
        loadNote()
    }

    private fun loadFolders() {
        lifecycleScope.launch {
            notesRepository.getFolders()
                .onSuccess { folders = it }
                .onFailure { /* non-fatal: folder label just won't show */ }
        }
    }

    override fun onResume() {
        super.onResume()
        if (currentNote != null) loadNote()
    }

    private fun loadNote() {
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            notesRepository.getNote(noteId)
                .onSuccess { note -> bindNote(note) }
                .onFailure {
                    binding.progress.visibility = View.GONE
                    showSnackbar(it.message ?: "Gagal memuat catatan")
                    finish()
                }
        }
    }

    private fun bindNote(note: Note) {
        currentNote = note
        binding.progress.visibility = View.GONE
        binding.tvTitle.text = note.title
        binding.tvContent.text = RichTextHelper.fromHtml(note.content)
        val folderName = folders.firstOrNull { it.id == note.folder }?.name
        if (folderName != null) {
            binding.tvFolder.text = folderName
            binding.tvFolder.visibility = View.VISIBLE
        } else {
            binding.tvFolder.visibility = View.GONE
        }
        binding.headerBar.setBackgroundColor(
            getColor(NoteColorPalette.colorResFor(note.color))
        )
        val reminderMillis = ReminderHelper.parseIso(note.reminderTime)
        if (reminderMillis != null) {
            binding.tvReminder.text = ReminderHelper.formatDisplay(reminderMillis)
            binding.layoutReminder.visibility = View.VISIBLE
        } else {
            binding.layoutReminder.visibility = View.GONE
        }
        // Keeps the alarm in step with pin/archive/edit changes made from here.
        ReminderScheduler.sync(this, note)
        binding.btnPin.setImageResource(
            if (note.isPinned) R.drawable.ic_pin_filled else R.drawable.ic_pin_outline
        )
        binding.btnArchive.setImageResource(
            if (note.isArchived) R.drawable.ic_unarchive else R.drawable.ic_archive
        )
    }

    private fun openEdit() {
        startActivity(
            Intent(this, NoteEditActivity::class.java)
                .putExtra(NoteEditActivity.EXTRA_NOTE_ID, noteId)
        )
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_message)
            .setPositiveButton(R.string.action_move_to_trash) { _, _ -> deleteNote() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deleteNote() {
        lifecycleScope.launch {
            notesRepository.deleteNote(noteId)
                .onSuccess {
                    // Trashed notes shouldn't alert; syncAll re-arms it if restored.
                    ReminderScheduler.cancel(this@NoteDetailActivity, noteId)
                    Toast.makeText(
                        this@NoteDetailActivity,
                        R.string.note_moved_to_trash,
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
                .onFailure { showSnackbar(it.message ?: "Gagal memindahkan catatan ke sampah") }
        }
    }

    private fun toggleArchive() {
        val note = currentNote ?: return
        lifecycleScope.launch {
            val result = if (note.isArchived) {
                notesRepository.unarchiveNote(noteId)
            } else {
                notesRepository.archiveNote(noteId)
            }
            result
                .onSuccess { loadNote() }
                .onFailure { showSnackbar(it.message ?: "Gagal mengubah status arsip") }
        }
    }

    private fun togglePin() {
        val note = currentNote ?: return
        val request = NoteRequest(
            folder = note.folder,
            title = note.title,
            content = note.content,
            color = note.color,
            isPinned = !note.isPinned,
            reminderTime = note.reminderTime
        )
        lifecycleScope.launch {
            notesRepository.updateNote(noteId, request)
                .onSuccess { bindNote(it) }
                .onFailure { showSnackbar(it.message ?: "Gagal mengubah pin") }
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
    }
}
