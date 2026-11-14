package com.example.iknownotes.ui.notedetail

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.iknownotes.R
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.model.NoteRequest
import com.example.iknownotes.data.repo.NotesRepository
import com.example.iknownotes.databinding.ActivityNoteDetailBinding
import com.example.iknownotes.ui.noteedit.NoteEditActivity
import com.example.iknownotes.reminder.ReminderScheduler
import com.example.iknownotes.util.ChecklistHelper
import com.example.iknownotes.util.NoteColorPalette
import com.example.iknownotes.util.NoteExporter
import com.example.iknownotes.util.ReminderHelper
import com.example.iknownotes.util.RichTextHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NoteDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteDetailBinding
    private lateinit var notesRepository: NotesRepository
    private var noteId: Int = -1
    private var currentNote: Note? = null
    private var folders: List<com.example.iknownotes.data.model.Folder> = emptyList()

    /** The note's content as shown (without the display-only checklist styling). */
    private var currentBody: Spanned = SpannableStringBuilder("")

    private val exportTxtLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) exportTo(uri, pdf = false)
        }
    private val exportPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) exportTo(uri, pdf = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notesRepository = NotesRepository.get(this)
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
        binding.btnShare.setOnClickListener { showShareMenu() }

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
        renderContent(note)
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

    /**
     * Shows the note body. Checklist boxes become tappable (ticking one saves right
     * away) and ticked items are struck through; that styling is display-only.
     */
    private fun renderContent(note: Note) {
        val body = RichTextHelper.fromHtml(note.content)
        currentBody = body

        val display = SpannableStringBuilder(body)
        val items = ChecklistHelper.items(display.toString())
        for (item in items) {
            display.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) = toggleChecklistItem(item.lineStart)
                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = false // keep the text colour, no link styling
                }
            }, item.lineStart, item.lineStart + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            if (item.checked && item.lineEnd > item.lineStart + 2) {
                display.setSpan(StrikethroughSpan(), item.lineStart + 2, item.lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                display.setSpan(
                    ForegroundColorSpan(getColor(R.color.text_secondary)),
                    item.lineStart + 2, item.lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        // Tappable boxes need link handling, which cannot be combined with text selection;
        // notes without a checklist stay selectable and the share menu can copy the rest.
        if (items.isEmpty()) {
            binding.tvContent.setTextIsSelectable(true)
        } else {
            binding.tvContent.setTextIsSelectable(false)
            binding.tvContent.movementMethod = LinkMovementMethod.getInstance()
        }
        binding.tvContent.text = display
    }

    private fun toggleChecklistItem(lineStart: Int) {
        val note = currentNote ?: return
        val edit = ChecklistHelper.toggleItem(currentBody.toString(), lineStart) ?: return
        val edited = SpannableStringBuilder(currentBody)
        edited.replace(edit.start, edit.end, edit.replacement)

        // Show the change at once; saving is local-first and quick.
        val updated = note.copy(content = RichTextHelper.toHtml(edited))
        currentNote = updated
        renderContent(updated)

        val request = NoteRequest(
            folder = updated.folder,
            title = updated.title,
            content = updated.content,
            color = updated.color,
            isPinned = updated.isPinned,
            reminderTime = updated.reminderTime
        )
        lifecycleScope.launch {
            notesRepository.updateNote(noteId, request)
                .onFailure {
                    showSnackbar(it.message ?: "Gagal menyimpan centang")
                    loadNote()
                }
        }
    }

    // ------------------------------------------------------- share & export

    private fun bodyText(note: Note): String = RichTextHelper.fromHtml(note.content).toString().trim()

    private fun showShareMenu() {
        val note = currentNote ?: return
        val options = arrayOf(
            getString(R.string.share_as_text),
            getString(R.string.copy_text),
            getString(R.string.export_txt),
            getString(R.string.export_pdf)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.share_title)
            .setItems(options) { _, which ->
                val fileName = NoteExporter.safeFileName(note.title)
                when (which) {
                    0 -> shareAsText(note)
                    1 -> copyText(note)
                    2 -> exportTxtLauncher.launch("$fileName.txt")
                    3 -> exportPdfLauncher.launch("$fileName.pdf")
                }
            }
            .show()
    }

    private fun shareAsText(note: Note) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, note.title)
            putExtra(Intent.EXTRA_TEXT, NoteExporter.shareText(note.title, bodyText(note)))
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }

    private fun copyText(note: Note) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(note.title, NoteExporter.shareText(note.title, bodyText(note)))
        )
        Toast.makeText(this, R.string.text_copied, Toast.LENGTH_SHORT).show()
    }

    private fun exportTo(uri: Uri, pdf: Boolean) {
        val note = currentNote ?: return
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val body = RichTextHelper.fromHtml(note.content)
                    val out = contentResolver.openOutputStream(uri, "wt")
                        ?: error("Tidak bisa membuka file tujuan")
                    out.use {
                        if (pdf) {
                            val updated = ReminderHelper.parseIso(note.updatedAt)?.let { millis ->
                                getString(R.string.export_meta_updated, ReminderHelper.formatDisplay(millis))
                            }
                            NoteExporter.writePdf(it, note.title, body, updated)
                        } else {
                            NoteExporter.writeText(it, note.title, body.toString())
                        }
                    }
                }.isSuccess
            }
            Toast.makeText(
                this@NoteDetailActivity,
                if (saved) R.string.export_success else R.string.export_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
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
