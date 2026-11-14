package com.example.iknownotes.ui.noteedit

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.iknownotes.reminder.ReminderScheduler
import com.example.iknownotes.util.ReminderHelper
import java.util.Calendar
import com.example.iknownotes.R
import com.example.iknownotes.data.local.DraftStore
import com.example.iknownotes.data.local.NoteDraft
import com.example.iknownotes.data.model.Folder
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.model.NoteRequest
import com.example.iknownotes.data.repo.NotesRepository
import com.example.iknownotes.databinding.ActivityNoteEditBinding
import com.example.iknownotes.util.NoteColorPalette
import com.example.iknownotes.util.RichTextHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** Reminder as epoch millis, or null when no reminder is set. */
    private var reminderMillis: Long? = null

    private val draftStore by lazy { DraftStore(this) }
    private val draftKey: String get() = DraftStore.keyFor(noteId)

    /** What the editor looked like once everything finished loading; null until then. */
    private var initialState: EditorState? = null
    private var foldersLoaded = false
    private var noteLoaded = false

    /** True while the "restore draft?" dialog is open, so autosave can't clobber the draft. */
    private var draftDecisionPending = false

    /** Set once the note was saved or the user chose to discard, so onStop keeps no draft. */
    private var exiting = false
    private var draftJob: Job? = null

    private data class EditorState(
        val title: String,
        val contentHtml: String,
        val color: String,
        val folderId: Int?,
        val reminderMillis: Long?
    )

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) showSnackbar(getString(R.string.reminder_permission_denied))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        notesRepository = NotesRepository.get(this)

        noteId = intent.getIntExtra(EXTRA_NOTE_ID, -1)
        isEditMode = noteId != -1
        binding.tvScreenTitle.text = getString(
            if (isEditMode) R.string.action_edit else R.string.action_create
        )

        binding.btnClose.setOnClickListener { requestExit() }
        binding.btnSave.setOnClickListener { save() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestExit()
        })

        setupDraftAutosave()
        setupFormattingToolbar()
        buildColorSwatches()
        setupReminder()
        loadFolders()

        if (isEditMode) loadExistingNote()
    }

    private fun setupReminder() {
        binding.tvReminder.setOnClickListener { pickReminderDate() }
        binding.btnClearReminder.setOnClickListener {
            reminderMillis = null
            renderReminder()
            onEditorChanged()
        }
        renderReminder()
    }

    private fun renderReminder() {
        val millis = reminderMillis
        if (millis == null) {
            binding.tvReminder.text = getString(R.string.reminder_none)
            binding.btnClearReminder.visibility = View.GONE
        } else {
            binding.tvReminder.text = ReminderHelper.formatDisplay(millis)
            binding.btnClearReminder.visibility = View.VISIBLE
        }
    }

    private fun pickReminderDate() {
        val initial = Calendar.getInstance().apply {
            reminderMillis?.let { timeInMillis = it }
        }
        DatePickerDialog(
            this,
            { _, year, month, day -> pickReminderTime(year, month, day, initial) },
            initial.get(Calendar.YEAR),
            initial.get(Calendar.MONTH),
            initial.get(Calendar.DAY_OF_MONTH)
        ).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
            setTitle(R.string.reminder_pick_date)
        }.show()
    }

    private fun pickReminderTime(year: Int, month: Int, day: Int, initial: Calendar) {
        TimePickerDialog(
            this,
            { _, hour, minute ->
                val chosen = Calendar.getInstance().apply {
                    set(year, month, day, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                if (!ReminderHelper.isInFuture(chosen)) {
                    showSnackbar(getString(R.string.reminder_error_past))
                } else {
                    reminderMillis = chosen
                    renderReminder()
                    onEditorChanged()
                    requestNotificationPermissionIfNeeded()
                }
            },
            initial.get(Calendar.HOUR_OF_DAY),
            initial.get(Calendar.MINUTE),
            true
        ).apply { setTitle(R.string.reminder_pick_time) }.show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupFormattingToolbar() {
        binding.btnBold.setOnClickListener {
            RichTextHelper.applyBold(binding.etContent)
            onEditorChanged()
        }
        binding.btnItalic.setOnClickListener {
            RichTextHelper.applyItalic(binding.etContent)
            onEditorChanged()
        }
        binding.btnUnderline.setOnClickListener {
            RichTextHelper.applyUnderline(binding.etContent)
            onEditorChanged()
        }
        binding.btnStrikethrough.setOnClickListener {
            RichTextHelper.applyStrikethrough(binding.etContent)
            onEditorChanged()
        }
        binding.btnBulletList.setOnClickListener {
            RichTextHelper.applyBulletList(binding.etContent)
            onEditorChanged()
        }
        binding.btnNumberedList.setOnClickListener {
            RichTextHelper.applyNumberedList(binding.etContent)
            onEditorChanged()
        }
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
        onEditorChanged()
    }

    private fun loadFolders() {
        lifecycleScope.launch {
            notesRepository.getFolders()
                .onSuccess { list ->
                    folders = list
                    foldersLoaded = true
                    setupFolderSpinner()
                }
                .onFailure {
                    folders = emptyList()
                    foldersLoaded = true
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
        maybeCaptureInitialState()
    }

    private fun loadExistingNote() {
        setLoading(true)
        lifecycleScope.launch {
            notesRepository.getNote(noteId)
                .onSuccess { note ->
                    setLoading(false)
                    existingNote = note
                    binding.etTitle.setText(note.title)
                    binding.etContent.setText(RichTextHelper.fromHtml(note.content))
                    selectColor(note.color ?: "default")
                    reminderMillis = ReminderHelper.parseIso(note.reminderTime)
                    renderReminder()
                    noteLoaded = true
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
        val content = RichTextHelper.toHtml(binding.etContent.text)

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
            reminderTime = reminderMillis?.let { ReminderHelper.toIso(it) }
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
                .onSuccess { saved ->
                    ReminderScheduler.sync(this@NoteEditActivity, saved)
                    exiting = true
                    draftJob?.cancel()
                    draftStore.clear(draftKey)
                    finish()
                }
                .onFailure { showSnackbar(it.message ?: "Gagal menyimpan catatan") }
        }
    }

    // ------------------------------------------------- drafts & unsaved changes

    private fun setupDraftAutosave() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = onEditorChanged()
        }
        binding.etTitle.addTextChangedListener(watcher)
        binding.etContent.addTextChangedListener(watcher)
        binding.spinnerFolder.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                onEditorChanged()

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun currentState() = EditorState(
        title = binding.etTitle.text?.toString()?.trim().orEmpty(),
        contentHtml = RichTextHelper.toHtml(binding.etContent.text),
        color = selectedColorKey,
        folderId = folderIdByPosition.getOrNull(binding.spinnerFolder.selectedItemPosition),
        reminderMillis = reminderMillis
    )

    private fun isDirty(): Boolean {
        val initial = initialState ?: return false
        return currentState() != initial
    }

    /** Remembers the freshly loaded editor so later edits can be detected. */
    private fun maybeCaptureInitialState() {
        if (initialState != null || !foldersLoaded || (isEditMode && !noteLoaded)) return
        initialState = currentState()
        offerDraftRestore()
    }

    private fun offerDraftRestore() {
        val draft = draftStore.load(draftKey) ?: return
        val initial = initialState ?: return
        if (draft.toEditorState() == initial) {
            draftStore.clear(draftKey)
            return
        }
        draftDecisionPending = true
        AlertDialog.Builder(this)
            .setTitle(R.string.draft_restore_title)
            .setMessage(R.string.draft_restore_message)
            .setCancelable(false)
            .setPositiveButton(R.string.action_restore_draft) { _, _ ->
                applyDraft(draft)
                draftDecisionPending = false
            }
            .setNegativeButton(R.string.action_discard) { _, _ ->
                draftStore.clear(draftKey)
                draftDecisionPending = false
            }
            .show()
    }

    private fun applyDraft(draft: NoteDraft) {
        binding.etTitle.setText(draft.title)
        binding.etContent.setText(RichTextHelper.fromHtml(draft.contentHtml))
        selectColor(draft.color)
        reminderMillis = draft.reminderMillis
        renderReminder()
        val position = folderIdByPosition.indexOf(draft.folderId).takeIf { it >= 0 } ?: 0
        binding.spinnerFolder.setSelection(position)
    }

    private fun NoteDraft.toEditorState() = EditorState(
        title = title,
        contentHtml = contentHtml,
        color = color,
        folderId = folderId,
        reminderMillis = reminderMillis
    )

    /** Called after any edit: schedules a debounced draft save. */
    private fun onEditorChanged() {
        if (initialState == null || draftDecisionPending || exiting) return
        draftJob?.cancel()
        draftJob = lifecycleScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            persistDraft()
        }
    }

    private fun persistDraft() {
        if (initialState == null || draftDecisionPending || exiting) return
        if (!isDirty()) {
            draftStore.clear(draftKey)
            return
        }
        val state = currentState()
        draftStore.save(
            draftKey,
            NoteDraft(state.title, state.contentHtml, state.color, state.folderId, state.reminderMillis)
        )
    }

    override fun onStop() {
        super.onStop()
        // Covers the app being backgrounded and then killed before the debounce fires.
        draftJob?.cancel()
        persistDraft()
    }

    private fun requestExit() {
        if (!isDirty()) {
            draftStore.clear(draftKey)
            exiting = true
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(R.string.unsaved_message)
            .setPositiveButton(R.string.action_save) { _, _ -> save() }
            .setNegativeButton(R.string.action_discard) { _, _ ->
                draftJob?.cancel()
                draftStore.clear(draftKey)
                exiting = true
                finish()
            }
            .setNeutralButton(R.string.action_cancel, null)
            .show()
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
        private const val AUTOSAVE_DELAY_MS = 1500L
    }
}
