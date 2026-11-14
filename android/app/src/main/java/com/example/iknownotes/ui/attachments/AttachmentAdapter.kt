package com.example.iknownotes.ui.attachments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.iknownotes.data.model.AttachmentItem
import com.example.iknownotes.databinding.ItemAttachmentBinding
import com.example.iknownotes.util.AttachmentImages

/** Horizontal strip of image thumbnails; [onRemove] == null makes it read-only. */
class AttachmentAdapter(
    private val thumbnailPx: Int,
    private val onClick: (AttachmentItem) -> Unit,
    private val onRemove: ((AttachmentItem) -> Unit)? = null
) : ListAdapter<AttachmentItem, AttachmentAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemAttachmentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemAttachmentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AttachmentItem) {
            AttachmentImages.load(binding.ivThumbnail, item, thumbnailPx)
            // Images that haven't reached the server yet look a little faded.
            binding.ivThumbnail.alpha = if (item.isPending) 0.65f else 1f
            binding.ivThumbnail.contentDescription = item.name
            binding.root.setOnClickListener { onClick(item) }

            binding.btnRemove.visibility = if (onRemove != null) View.VISIBLE else View.GONE
            binding.btnRemove.setOnClickListener { onRemove?.invoke(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AttachmentItem>() {
            override fun areItemsTheSame(a: AttachmentItem, b: AttachmentItem) = a.key == b.key
            override fun areContentsTheSame(a: AttachmentItem, b: AttachmentItem) = a == b
        }
    }
}
