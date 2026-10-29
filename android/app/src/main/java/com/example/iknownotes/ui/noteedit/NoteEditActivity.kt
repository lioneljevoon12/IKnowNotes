package com.example.iknownotes.ui.noteedit

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.iknownotes.R
import com.example.iknownotes.data.model.Folder
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.model.NoteRequest
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.data.repo.NotesRepository
import com.example.iknownotes.databinding.ActivityNoteEditBinding
import com.example.iknownotes.util.NoteColorPalette
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class NoteEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteEditBinding
    private lateinit var notesRepository: NotesRepository

    private var noteId: Int = -1
    private var isEditMode = false
    private var existingNote: Note? = null

    private var folders: List<Folder> = emptyList()
    /** index 0 is always "Tanpa folder" -> null */
    private var folderIdByPosition: List<Int?> = listOf(null)

    private var selectedColorKey: String = "default"
    private val swatchViews = mutableMapOf<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notesRepository = NotesRepository(RetrofitClient.getApi(this))

        noteId = intent.getIntExtra(EXTRA_NOTE_ID, -1)
        isEditMode = noteId != -1
        binding.tvScreenTitle.text = getString(
            if (isEditMode) R.string.action_edit else R.string.action_create
        )

        binding.btnClose.setOnClickListener { finish() }
        binding.btnSave.setOnClickListener { save() }

        buildColorSwatches()
        loadFolders()

        if (isEditMode) loadExistingNote()
    }

    private fun buildColorSwatches() {
        binding.layoutColorSwatches.removeAllViews()
        swatchViews.clear()

        val sizePx = (36 * resources.displayMetrics.density).toInt()
        val marginPx = (8 * resources.displayMetrics.density).toInt()

        NoteColorPalette.swatches.forEach { swatch ->
            val container = FrameLayout(this)
            val containerParams = android.widget.LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginEnd = marginPx
            }
            container.layoutParams = containerParams

            val circle = View(this)
            val circleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(swatch.colorRes))
                setStroke((1 * resources.displayMetrics.density).toInt(), getColor(R.color.divider))
            }
            circle.background = circleDrawable
            container.addView(circle, FrameLayout.LayoutParams(sizePx, sizePx))

            val ring = View(this)
            ring.background = androidx.core.content.ContextCompat.getDrawable(
                this, R.drawable.bg_color_swatch_selected
            )
            ring.visibility = if (swatch.key == selectedColorKey) View.VISIBLE else View.GONE
            container.addView(
                ring,
                FrameLayout.LayoutParams(sizePx, sizePx).apply { gravity = Gravity.CENTER }
            )

            container.setOnClickListener { selectColor(swatch.key) }
            swatchViews[swatch.key] = ring
            binding.layoutColorSwatches.addView(container)
        }
    }

    private fun selectColor(key: String) {
        selectedColorKey = key
        swatchViews.forEach { (swatchKey, ringView) ->
            ringView.visibility = if (swatchKey == key) View.VISIBLE else View.GONE
        }
    }

    private fun loadFolders() {
        lifecycleScope.launch {
            notesRepository.getFolders()
                .onSuccess { list ->
                    folders = list
                    setupFolderSpinner()
                }
                .onFailure {
                    folders = emptyList()
                    setupFolderSpinner()
                }
        }
    }

    private fun setupFolderSpinner() {
        val names = mutableListOf(getString(R.string.folder_none))
        val ids = mutableListOf<Int?>(null)
        folders.forEach { folder ->
            names.add(folder.name)
            ids.add(folder.id)
        }
        folderIdByPosition = ids

        binding.spinnerFolder.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, names
        )

        val preselectFolderId = existingNote?.folder ?: intent.getIntExtra(EXTRA_FOLDER_ID, -1)
            .takeIf { it != -1 }
        val position = ids.indexOf(preselectFolderId).takeIf { it >= 0 } ?: 0
        binding.spinnerFolder.setSelection(position)
    }

    private fun loadExistingNote() {
        setLoading(true)
        lifecycleScope.launch {
            notesRepository.getNote(noteId)
                .onSuccess { note ->
                    setLoading(false)
                    existingNote = note
                    binding.etTitle.setText(note.title)
                    binding.etContent.setText(note.content)
                    selectColor(note.color ?: "default")
                    setupFolderSpinner()
                }
                .onFailure {
                    setLoading(false)
                    showSnackbar(it.message ?: "Gagal memuat catatan")
                    finish()
                }
        }
    }

    private fun save() {
        val title = binding.etTitle.text?.toString()?.trim().orEmpty()
        val content = binding.etContent.text?.toString().orEmpty()

        if (title.isBlank()) {
            showSnackbar(getString(R.string.error_title_required))
            return
        }

        val folderId = folderIdByPosition.getOrNull(binding.spinnerFolder.selectedItemPosition)
        val request = NoteRequest(
            folder = folderId,
            title = title,
            content = content,
            color = selectedColorKey,
            isPinned = existingNote?.isPinned ?: false,
            reminderTime = existingNote?.reminderTime
        )

        setLoading(true)
        lifecycleScope.launch {
            val result = if (isEditMode) {
                notesRepository.updateNote(noteId, request)
            } else {
                notesRepository.createNote(request)
            }
            setLoading(false)
            result
                .onSuccess { finish() }
                .onFailure { showSnackbar(it.message ?: "Gagal menyimpan catatan") }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSave.isEnabled = !loading
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_FOLDER_ID = "extra_folder_id"
    }
}
