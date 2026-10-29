package com.example.iknownotes.ui.notes

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.iknownotes.data.model.Note
import com.example.iknownotes.databinding.ItemNoteBinding
import com.example.iknownotes.util.NoteColorPalette
import com.example.iknownotes.util.RichTextHelper

class NoteAdapter(
    private val folderNameOf: (Int?) -> String?,
    private val onClick: (Note) -> Unit
) : ListAdapter<Note, NoteAdapter.NoteViewHolder>(DIFF) {

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

            val colorRes = NoteColorPalette.colorResFor(note.color)
            binding.root.setCardBackgroundColor(
                binding.root.context.getColor(colorRes)
            )

            binding.root.setOnClickListener { onClick(note) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Note>() {
            override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
        }
    }
}
