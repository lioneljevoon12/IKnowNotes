package com.example.iknownotes.ui.notes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.iknownotes.R
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.databinding.ItemNoteBinding
import com.example.iknownotes.util.NoteColorPalette
import com.example.iknownotes.util.ReminderHelper
import com.example.iknownotes.util.RichTextHelper

class NoteAdapter(
    private val folderNameOf: (Int?) -> String?,
    private val onClick: (Note) -> Unit,
    private val onLongClick: ((Note) -> Unit)? = null
) : ListAdapter<Note, NoteAdapter.NoteViewHolder>(DIFF) {

    /** Ids of notes currently ticked in selection mode. */
    var selectedIds: Set<Int> = emptySet()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
        val binding = ItemNoteBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return NoteViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class NoteViewHolder(private val binding: ItemNoteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(note: Note) {
            binding.tvTitle.text = note.title.ifBlank { "(Tanpa judul)" }
            binding.tvContent.text = RichTextHelper.fromHtml(note.content)
            binding.ivPin.visibility = if (note.isPinned) android.view.View.VISIBLE else android.view.View.GONE

            val folderName = folderNameOf(note.folder)
            if (folderName != null) {
                binding.tvFolder.text = folderName
                binding.tvFolder.visibility = android.view.View.VISIBLE
            } else {
                binding.tvFolder.visibility = android.view.View.GONE
            }

            val reminderMillis = ReminderHelper.parseIso(note.reminderTime)
            if (reminderMillis != null) {
                binding.tvReminder.text = ReminderHelper.formatDisplay(reminderMillis)
                binding.layoutReminder.visibility = android.view.View.VISIBLE
            } else {
                binding.layoutReminder.visibility = android.view.View.GONE
            }

            val attachmentCount = note.attachments.orEmpty().size
            if (attachmentCount > 0) {
                binding.tvAttachmentCount.text = attachmentCount.toString()
                binding.layoutAttachments.visibility = android.view.View.VISIBLE
            } else {
                binding.layoutAttachments.visibility = android.view.View.GONE
            }

            val colorRes = NoteColorPalette.colorResFor(note.color)
            binding.root.setCardBackgroundColor(
                binding.root.context.getColor(colorRes)
            )

            val selected = note.id in selectedIds
            binding.ivSelected.visibility = if (selected) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.strokeWidth = if (selected) (3 * binding.root.resources.displayMetrics.density).toInt() else 0
            binding.root.strokeColor = binding.root.context.getColor(R.color.blue_primary)

            binding.root.setOnClickListener { onClick(note) }
            if (onLongClick != null) {
                binding.root.setOnLongClickListener {
                    onLongClick.invoke(note)
                    true
                }
            } else {
                binding.root.setOnLongClickListener(null)
                binding.root.isLongClickable = false
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
        }
    }
}
