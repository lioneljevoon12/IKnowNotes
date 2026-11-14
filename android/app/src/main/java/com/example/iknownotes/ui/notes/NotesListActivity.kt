package com.example.iknownotes.ui.notes

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.example.iknownotes.R
import com.example.iknownotes.data.model.Folder
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.data.repo.AuthRepository
import com.example.iknownotes.data.local.DraftStore
import com.example.iknownotes.data.local.UiPrefs
import com.example.iknownotes.data.repo.NotesRepository
import com.example.iknownotes.data.repo.SyncStatus
import com.example.iknownotes.databinding.ActivityNotesListBinding
import com.example.iknownotes.databinding.DialogNoteFilterBinding
import com.example.iknownotes.reminder.ReminderScheduler
import com.example.iknownotes.util.DateRange
import com.example.iknownotes.util.NoteColorPalette
import com.example.iknownotes.util.NoteFilter
import com.example.iknownotes.util.SortOrder
import com.example.iknownotes.ui.auth.LoginActivity
import com.example.iknownotes.ui.notedetail.NoteDetailActivity
import com.example.iknownotes.ui.noteedit.NoteEditActivity
import com.example.iknownotes.ui.trash.TrashActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class NotesListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotesListBinding
    private lateinit var notesRepository: NotesRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var adapter: NoteAdapter

    private lateinit var uiPrefs: UiPrefs

    private var folders: List<Folder> = emptyList()

    /** Every active (not archived/trashed) note; [filter] decides what is shown. */
    private var allNotes: List<Note> = emptyList()
    private var filter = NoteFilter()
    private var statusJob: Job? = null

    /** Ids ticked in selection mode; selection mode is on while this is not empty. */
    private val selectedIds = linkedSetOf<Int>()
    private val isSelecting: Boolean get() = selectedIds.isNotEmpty()

    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = clearSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotesListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val api = RetrofitClient.getApi(this)
        val session = RetrofitClient.getSessionManager(this)
        notesRepository = NotesRepository.get(this)
        authRepository = AuthRepository(api, session)

        binding.tvGreeting.text = "Halo, ${session.username ?: "kamu"}"

        uiPrefs = UiPrefs(this)
        filter = NoteFilter(sort = uiPrefs.sortOrder)
        onBackPressedDispatcher.addCallback(this, selectionBackCallback)

        setupRecyclerView()
        setupSwipeActions()
        setupSearch()
        setupFilterControls()
        setupSelectionBar()
        setupHeaderAndFab()

        loadFolders()
    }

    /** Shows the offline / "waiting to sync" banner and refreshes once everything synced. */
    private fun observeSyncStatus() {
        statusJob?.cancel()
        statusJob = lifecycleScope.launch {
            var previousPending = notesRepository.status.value.pendingChanges
            notesRepository.status.collect { status ->
                renderSyncBanner(status)
                // Temporary ids were swapped for real ones: reload the list.
                if (previousPending > 0 && status.pendingChanges == 0) loadNotes()
                previousPending = status.pendingChanges
            }
        }
    }

    override fun onStart() {
        super.onStart()
        observeSyncStatus()
    }

    override fun onStop() {
        super.onStop()
        statusJob?.cancel()
    }

    private fun renderSyncBanner(status: SyncStatus) {
        val text = when {
            status.offline && status.pendingChanges > 0 ->
                getString(R.string.banner_offline_pending, status.pendingChanges)
            status.offline -> getString(R.string.banner_offline)
            status.pendingChanges > 0 ->
                getString(R.string.banner_syncing, status.pendingChanges)
            else -> null
        }
        binding.tvSyncBanner.text = text
        binding.tvSyncBanner.visibility = if (text == null) View.GONE else View.VISIBLE
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
                if (isSelecting) {
                    toggleSelection(note)
                } else {
                    startActivity(
                        Intent(this, NoteDetailActivity::class.java)
                            .putExtra(NoteDetailActivity.EXTRA_NOTE_ID, note.id)
                    )
                }
            },
            onLongClick = { note -> toggleSelection(note) }
        )
        binding.rvNotes.layoutManager = StaggeredGridLayoutManager(1, StaggeredGridLayoutManager.VERTICAL)
        binding.rvNotes.adapter = adapter
        binding.swipeRefresh.setOnRefreshListener { loadNotes() }
        applyViewMode()
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filter = filter.copy(query = s?.toString().orEmpty())
                applyFilters()
            }
        })
    }

    private fun setupHeaderAndFab() {
        binding.btnArchivedNotes.setOnClickListener {
            startActivity(Intent(this, ArchivedNotesActivity::class.java))
        }
        binding.btnTrash.setOnClickListener {
            startActivity(Intent(this, TrashActivity::class.java))
        }
        binding.fabAddNote.setOnClickListener {
            startActivity(
                Intent(this, NoteEditActivity::class.java)
                    .putExtra(NoteEditActivity.EXTRA_FOLDER_ID, filter.folderId)
            )
        }
        binding.btnLogout.setOnClickListener { confirmLogout() }
    }

    private fun confirmLogout() {
        val pending = notesRepository.status.value.pendingChanges
        if (pending == 0) {
            logout()
            return
        }
        // Logging out wipes the offline cache, including changes that never reached the server.
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_logout_title)
            .setMessage(getString(R.string.confirm_logout_message, pending))
            .setPositiveButton(R.string.action_logout) { _, _ -> logout() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun logout() {
        ReminderScheduler.cancelAll(this)
        notesRepository.clearLocalData()
        DraftStore(this).clearAll()
        authRepository.logout()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
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
        allChip.isChecked = filter.folderId == null
        allChip.setOnClickListener {
            filter = filter.copy(folderId = null)
            applyFilters()
        }
        group.addView(allChip)

        folders.forEach { folder ->
            val chip = inflater.inflate(R.layout.item_folder_chip, group, false) as Chip
            chip.text = folder.name
            chip.isChecked = filter.folderId == folder.id
            chip.setOnClickListener {
                filter = filter.copy(folderId = folder.id)
                applyFilters()
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
                    if (filter.folderId == id) {
                        filter = filter.copy(folderId = null)
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
            notesRepository.getNotes()
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
        allNotes = notes
        // Notes that disappeared (deleted elsewhere, synced away) can't stay selected.
        if (selectedIds.retainAll(notes.map { it.id }.toSet())) renderSelection()
        applyFilters()
        // Rebuilds alarms from server data (covers reboots and edits from other devices).
        ReminderScheduler.syncAll(this, notes)
    }

    /** Filtering, searching and sorting all happen on the device, so they also work offline. */
    private fun applyFilters() {
        val shown = filter.apply(allNotes)
        adapter.submitList(shown)

        binding.layoutEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        val noNotesAtAll = allNotes.isEmpty()
        binding.tvEmptyTitle.setText(
            if (noNotesAtAll) R.string.empty_notes_title else R.string.empty_filtered_title
        )
        binding.tvEmptySubtitle.setText(
            if (noNotesAtAll) R.string.empty_notes_subtitle else R.string.empty_filtered_subtitle
        )
        renderFilterIndicators()
    }

    private fun renderFilterIndicators() {
        val active = filter.advancedCount
        binding.tvActiveFilters.visibility = if (active > 0) View.VISIBLE else View.GONE
        binding.tvActiveFilters.text = getString(R.string.active_filters, active)

        val highlighted = active > 0 || filter.sort != SortOrder.UPDATED_DESC
        binding.btnFilter.imageTintList = ColorStateList.valueOf(
            getColor(if (highlighted) R.color.blue_primary else R.color.text_secondary)
        )
    }

    // ------------------------------------------------ sort / filter / view mode

    private fun setupFilterControls() {
        binding.btnFilter.setOnClickListener { showFilterSheet() }
        binding.btnViewMode.setOnClickListener {
            uiPrefs.gridView = !uiPrefs.gridView
            applyViewMode()
        }
        binding.tvActiveFilters.setOnClickListener {
            filter = filter.withoutAdvancedFilters()
            applyFilters()
        }
    }

    private fun applyViewMode() {
        val grid = uiPrefs.gridView
        (binding.rvNotes.layoutManager as StaggeredGridLayoutManager).spanCount = if (grid) 2 else 1
        // The button shows what a tap switches to.
        binding.btnViewMode.setImageResource(if (grid) R.drawable.ic_view_list else R.drawable.ic_view_grid)
        binding.btnViewMode.contentDescription = getString(if (grid) R.string.view_list else R.string.view_grid)
    }

    private fun showFilterSheet() {
        val sheet = BottomSheetDialog(this)
        val sheetBinding = DialogNoteFilterBinding.inflate(layoutInflater)
        sheet.setContentView(sheetBinding.root)
        val inflater = LayoutInflater.from(this)

        fun addChip(group: ChipGroup, label: String, checked: Boolean): Chip {
            val chip = inflater.inflate(R.layout.item_folder_chip, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = label
            chip.isCheckedIconVisible = true
            group.addView(chip)
            chip.isChecked = checked
            return chip
        }

        val sortLabels = linkedMapOf(
            SortOrder.UPDATED_DESC to R.string.sort_updated_desc,
            SortOrder.UPDATED_ASC to R.string.sort_updated_asc,
            SortOrder.TITLE_ASC to R.string.sort_title_asc,
            SortOrder.TITLE_DESC to R.string.sort_title_desc
        )
        val sortChips = sortLabels.map { (order, label) ->
            order to addChip(sheetBinding.chipGroupSort, getString(label), filter.sort == order)
        }

        val colorChips = NoteColorPalette.swatches.map { swatch ->
            val chip = addChip(
                sheetBinding.chipGroupColors, getString(swatch.labelRes), swatch.key in filter.colors
            )
            chip.chipBackgroundColor = ColorStateList.valueOf(getColor(swatch.colorRes))
            swatch.key to chip
        }

        val dateLabels = linkedMapOf(
            DateRange.ANY to R.string.date_any,
            DateRange.TODAY to R.string.date_today,
            DateRange.LAST_7_DAYS to R.string.date_7_days,
            DateRange.LAST_30_DAYS to R.string.date_30_days
        )
        val dateChips = dateLabels.map { (range, label) ->
            range to addChip(sheetBinding.chipGroupDate, getString(label), filter.dateRange == range)
        }

        sheetBinding.chipOnlyReminder.isCheckedIconVisible = true
        sheetBinding.chipOnlyReminder.isChecked = filter.onlyWithReminder

        sheetBinding.btnFilterReset.setOnClickListener {
            sortChips.first { it.first == SortOrder.UPDATED_DESC }.second.isChecked = true
            colorChips.forEach { it.second.isChecked = false }
            dateChips.first { it.first == DateRange.ANY }.second.isChecked = true
            sheetBinding.chipOnlyReminder.isChecked = false
        }
        sheetBinding.btnFilterApply.setOnClickListener {
            filter = filter.copy(
                sort = sortChips.firstOrNull { it.second.isChecked }?.first ?: SortOrder.UPDATED_DESC,
                colors = colorChips.filter { it.second.isChecked }.map { it.first }.toSet(),
                dateRange = dateChips.firstOrNull { it.second.isChecked }?.first ?: DateRange.ANY,
                onlyWithReminder = sheetBinding.chipOnlyReminder.isChecked
            )
            uiPrefs.sortOrder = filter.sort
            applyFilters()
            sheet.dismiss()
        }
        sheet.show()
    }

    // ------------------------------------------------ selection mode (bulk actions)

    private fun setupSelectionBar() {
        binding.btnSelectionClose.setOnClickListener { clearSelection() }
        binding.btnSelectPin.setOnClickListener { pinSelected() }
        binding.btnSelectArchive.setOnClickListener { archiveSelected() }
        binding.btnSelectTrash.setOnClickListener { confirmTrashSelected() }
    }

    private fun toggleSelection(note: Note) {
        if (!selectedIds.remove(note.id)) selectedIds.add(note.id)
        renderSelection()
    }

    private fun clearSelection() {
        selectedIds.clear()
        renderSelection()
    }

    private fun renderSelection() {
        val selecting = isSelecting
        adapter.selectedIds = selectedIds.toSet()
        selectionBackCallback.isEnabled = selecting
        binding.headerBar.visibility = if (selecting) View.GONE else View.VISIBLE
        binding.selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        binding.fabAddNote.visibility = if (selecting) View.GONE else View.VISIBLE
        binding.swipeRefresh.isEnabled = !selecting
        if (!selecting) return

        binding.tvSelectionCount.text = getString(R.string.selection_count, selectedIds.size)
        val allPinned = selectedNotes().all { it.isPinned }
        binding.btnSelectPin.setImageResource(
            if (allPinned) R.drawable.ic_pin_filled else R.drawable.ic_pin_outline
        )
        binding.btnSelectPin.contentDescription =
            getString(if (allPinned) R.string.action_unpin else R.string.action_pin)
    }

    private fun selectedNotes(): List<Note> = allNotes.filter { it.id in selectedIds }

    private fun pinSelected() {
        val targets = selectedNotes()
        if (targets.isEmpty()) return
        val pin = !targets.all { it.isPinned } // all pinned already -> unpin
        lifecycleScope.launch {
            targets.forEach { notesRepository.setPinned(it.id, pin) }
            showSnackbar(getString(if (pin) R.string.notes_pinned else R.string.notes_unpinned, targets.size))
            clearSelection()
            loadNotes()
        }
    }

    private fun archiveSelected() {
        val targets = selectedNotes()
        if (targets.isEmpty()) return
        lifecycleScope.launch {
            targets.forEach {
                ReminderScheduler.cancel(this@NotesListActivity, it.id)
                notesRepository.archiveNote(it.id)
            }
            showSnackbar(getString(R.string.notes_archived, targets.size))
            clearSelection()
            loadNotes()
        }
    }

    private fun confirmTrashSelected() {
        val targets = selectedNotes()
        if (targets.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_trash_selected_title)
            .setMessage(getString(R.string.confirm_trash_selected_message, targets.size))
            .setPositiveButton(R.string.action_move_to_trash) { _, _ -> trashNotes(targets) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun trashNotes(targets: List<Note>) {
        lifecycleScope.launch {
            targets.forEach {
                ReminderScheduler.cancel(this@NotesListActivity, it.id)
                notesRepository.deleteNote(it.id)
            }
            showSnackbar(getString(R.string.notes_trashed, targets.size))
            clearSelection()
            loadNotes()
        }
    }

    // ------------------------------------------------ swipe: left = archive, right = trash

    private fun setupSwipeActions() {
        val density = resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun whiteIcon(res: Int) = ContextCompat.getDrawable(this, res)!!.mutate().also {
            it.setTint(Color.WHITE)
        }
        val archiveIcon = whiteIcon(R.drawable.ic_archive)
        val trashIcon = whiteIcon(R.drawable.ic_delete)

        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            // No swiping while notes are being selected.
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
                if (isSelecting) 0 else super.getSwipeDirs(recyclerView, viewHolder)

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val note = adapter.currentList.getOrNull(viewHolder.bindingAdapterPosition)
                if (note == null) {
                    adapter.notifyDataSetChanged()
                    return
                }
                if (direction == ItemTouchHelper.LEFT) archiveWithUndo(note) else trashWithUndo(note)
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val item = viewHolder.itemView
                if (dX != 0f) {
                    val archiving = dX < 0
                    paint.color = getColor(if (archiving) R.color.swipe_archive else R.color.swipe_trash)
                    val left = if (archiving) item.right + dX else item.left.toFloat()
                    val right = if (archiving) item.right.toFloat() else item.left + dX
                    c.drawRect(left, item.top.toFloat(), right, item.bottom.toFloat(), paint)

                    val size = (24 * density).toInt()
                    val margin = (20 * density).toInt()
                    val top = item.top + (item.height - size) / 2
                    val iconLeft = if (archiving) item.right - margin - size else item.left + margin
                    val icon = if (archiving) archiveIcon else trashIcon
                    icon.setBounds(iconLeft, top, iconLeft + size, top + size)
                    icon.draw(c)
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.rvNotes)
    }

    private fun removeFromList(note: Note) {
        allNotes = allNotes.filterNot { it.id == note.id }
        applyFilters()
        ReminderScheduler.cancel(this, note.id)
    }

    private fun archiveWithUndo(note: Note) {
        removeFromList(note)
        lifecycleScope.launch {
            notesRepository.archiveNote(note.id).onFailure {
                showSnackbar(it.message ?: "Gagal mengarsipkan catatan")
                loadNotes()
            }
        }
        Snackbar.make(binding.root, R.string.note_archived, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.fabAddNote)
            .setAction(R.string.action_undo) {
                lifecycleScope.launch {
                    notesRepository.unarchiveNote(note.id)
                    loadNotes()
                }
            }
            .show()
    }

    private fun trashWithUndo(note: Note) {
        removeFromList(note)
        lifecycleScope.launch {
            notesRepository.deleteNote(note.id).onFailure {
                showSnackbar(it.message ?: "Gagal memindahkan catatan ke sampah")
                loadNotes()
            }
        }
        Snackbar.make(binding.root, R.string.note_moved_to_trash, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.fabAddNote)
            .setAction(R.string.action_undo) {
                lifecycleScope.launch {
                    notesRepository.restoreNote(note.id)
                    loadNotes()
                }
            }
            .show()
    }

    private fun maybeRedirectToLogin(error: Throwable) {
        if (error.message?.contains("Sesi kamu udah habis") == true) {
            ReminderScheduler.cancelAll(this)
            authRepository.logout()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
}
