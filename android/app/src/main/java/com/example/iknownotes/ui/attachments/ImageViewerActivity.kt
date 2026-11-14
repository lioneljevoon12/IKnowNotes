package com.example.iknownotes.ui.attachments

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.iknownotes.data.model.Attachment
import com.example.iknownotes.data.model.AttachmentItem
import com.example.iknownotes.databinding.ActivityImageViewerBinding
import com.example.iknownotes.util.AttachmentImages

/** Shows one note image full screen. */
class ImageViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityImageViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val attachmentId = intent.getIntExtra(EXTRA_ATTACHMENT_ID, -1)
        val localPath = intent.getStringExtra(EXTRA_LOCAL_PATH)

        val item = when {
            attachmentId != -1 -> AttachmentItem(
                key = "remote:$attachmentId",
                name = name,
                attachment = Attachment(id = attachmentId, originalName = name)
            )
            localPath != null -> AttachmentItem(key = "local:$localPath", name = name, localPath = localPath)
            else -> {
                finish()
                return
            }
        }

        binding.tvName.text = name
        binding.ivImage.contentDescription = name
        AttachmentImages.load(binding.ivImage, item, resources.displayMetrics.widthPixels)
        binding.btnClose.setOnClickListener { finish() }
    }

    companion object {
        private const val EXTRA_ATTACHMENT_ID = "extra_attachment_id"
        private const val EXTRA_LOCAL_PATH = "extra_local_path"
        private const val EXTRA_NAME = "extra_name"

        fun intentFor(context: android.content.Context, item: AttachmentItem): android.content.Intent =
            android.content.Intent(context, ImageViewerActivity::class.java).apply {
                putExtra(EXTRA_NAME, item.name)
                item.attachment?.let { putExtra(EXTRA_ATTACHMENT_ID, it.id) }
                item.localPath?.let { putExtra(EXTRA_LOCAL_PATH, it) }
            }
    }
}
