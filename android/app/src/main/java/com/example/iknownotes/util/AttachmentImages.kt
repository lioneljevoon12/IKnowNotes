package com.example.iknownotes.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import com.example.iknownotes.R
import com.example.iknownotes.data.model.AttachmentItem
import com.example.iknownotes.data.remote.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shows note images. Images already on the server are downloaded once (the
 * endpoint needs the login token, so a plain URL loader wouldn't work) and kept
 * in the app cache; images still waiting to upload are read from their local file.
 */
object AttachmentImages {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    /** Sets [view] to the image, or a placeholder until it is ready / if it can't be loaded. */
    fun load(view: ImageView, item: AttachmentItem, targetPx: Int) {
        val key = "${item.key}@$targetPx"
        // The view may be recycled for another item before this one finishes loading.
        view.setTag(R.id.attachment_image_key, key)

        memory.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageResource(R.drawable.ic_image)

        val appContext = view.context.applicationContext
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(appContext, item, targetPx) }
            if (bitmap != null) {
                memory.put(key, bitmap)
                if (view.getTag(R.id.attachment_image_key) == key) view.setImageBitmap(bitmap)
            }
        }
    }

    private suspend fun decode(context: Context, item: AttachmentItem, targetPx: Int): Bitmap? {
        val file = when {
            item.localPath != null -> File(item.localPath)
            item.attachment != null -> cachedFile(context, item.attachment.id)
            else -> null
        } ?: return null
        if (!file.exists()) return null
        return decodeFile(file, targetPx)
    }

    private suspend fun cachedFile(context: Context, attachmentId: Int): File? {
        val dir = File(context.cacheDir, "attachments").apply { mkdirs() }
        val file = File(dir, attachmentId.toString())
        if (file.exists() && file.length() > 0) return file

        return try {
            val body = RetrofitClient.getApi(context).downloadAttachment(attachmentId)
            val partial = File(dir, "$attachmentId.part")
            body.use { source -> partial.outputStream().use { out -> source.byteStream().copyTo(out) } }
            if (partial.renameTo(file)) file else null
        } catch (e: Exception) {
            null // offline or not allowed: the placeholder stays
        }
    }

    private fun decodeFile(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageProcessor.sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }
}
