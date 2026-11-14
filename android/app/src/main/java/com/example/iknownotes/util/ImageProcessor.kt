package com.example.iknownotes.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/**
 * Prepares a picked or captured photo for upload: fixes its rotation, scales it
 * down and re-encodes it as JPEG, so a 12 MP camera shot becomes a few hundred KB.
 * (Animated GIFs become still images; transparency becomes white.)
 */
object ImageProcessor {

    const val MAX_DIMENSION = 1600
    const val JPEG_QUALITY = 85
    const val MIME = "image/jpeg"
    private const val PENDING_DIR = "pending_attachments"

    /** Where compressed images wait for upload; owned by the repository once queued. */
    fun pendingDir(context: Context): File =
        File(context.filesDir, PENDING_DIR).also { it.mkdirs() }

    fun newPendingFile(context: Context): File =
        File(pendingDir(context), "${UUID.randomUUID()}.jpg")

    /** Largest power-of-two shrink that keeps both sides at least [target] pixels. */
    fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** Scale factor (<= 1) that brings the longer side down to [maxDimension]. */
    fun scaleFor(width: Int, height: Int, maxDimension: Int): Float =
        min(1f, maxDimension.toFloat() / max(width, height))

    /** Display name of [uri] with a .jpg extension, e.g. "IMG_2041.jpg". */
    fun displayName(context: Context, uri: Uri): String {
        val raw = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: "foto"
        return raw.substringBeforeLast('.').ifBlank { "foto" } + ".jpg"
    }

    /** @return true when [outFile] now holds the compressed image. */
    fun compress(context: Context, uri: Uri, outFile: File): Boolean {
        val resolver = context.contentResolver
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            // With inJustDecodeBounds the call returns null by design; the size lands in [bounds].
            (resolver.openInputStream(uri) ?: return false).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
            }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                ?: return false

            val orientation = resolver.openInputStream(uri)?.use {
                runCatching {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            applyOrientation(matrix, orientation)
            val scale = scaleFor(decoded.width, decoded.height, MAX_DIMENSION)
            if (scale < 1f) matrix.postScale(scale, scale)

            var result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (result !== decoded) decoded.recycle()

            if (result.hasAlpha()) {
                // JPEG has no alpha: flatten onto white instead of black.
                val flat = Bitmap.createBitmap(result.width, result.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(result, 0f, 0f, null)
                }
                result.recycle()
                result = flat
            }

            outFile.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            result.recycle()
            true
        } catch (e: Exception) {
            outFile.delete()
            false
        } catch (e: OutOfMemoryError) {
            outFile.delete()
            false
        }
    }

    private fun applyOrientation(matrix: Matrix, orientation: Int) {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
    }
}
