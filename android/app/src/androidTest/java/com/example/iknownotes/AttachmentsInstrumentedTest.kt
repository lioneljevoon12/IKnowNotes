package com.example.iknownotes

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.media.ExifInterface
import android.net.Uri
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.iknownotes.data.remote.RetrofitClient
import com.example.iknownotes.data.repo.NotesRepository
import com.example.iknownotes.ui.notedetail.NoteDetailActivity
import com.example.iknownotes.util.ImageProcessor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Attachments and pagination against the real Django dev server
 * (10.0.2.2:8000, user tester/password123), plus image processing on a device.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentsInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val base = "http://10.0.2.2:8000"
    private lateinit var access: String

    @Before
    fun setUp() {
        val tokens = JSONObject(
            http("POST", "/api/token/", JSONObject().put("username", "tester").put("password", "password123").toString(), null)
        )
        access = tokens.getString("access")
        RetrofitClient.getSessionManager(context).saveSession(access, tokens.getString("refresh"), "tester")
        NotesRepository.get(context).clearLocalData()

        // Empty the account: every page of active, archived and trashed notes.
        for (path in listOf("/api/notes/", "/api/notes/archived/")) {
            val results = JSONObject(http("GET", "$path?page_size=100", null, access)).getJSONArray("results")
            for (i in 0 until results.length()) {
                val id = results.getJSONObject(i).getInt("id")
                http("DELETE", "/api/notes/$id/", null, access)
            }
        }
        http("DELETE", "/api/notes/trash/empty/", null, access)
    }

    @After
    fun tearDown() {
        NotesRepository.get(context).clearLocalData()
    }

    @Test
    fun api_returnsPages_andTheRepositoryFollowsThemAll() {
        repeat(7) { createNote("Catatan $it") }
        val api = RetrofitClient.getApi(context)

        val first = runBlocking { api.getNotes(page = 1, pageSize = 3) }
        assertEquals(7, first.count)
        assertEquals(3, first.results.size)
        assertNotNull(first.next)
        assertNull(first.previous)

        val last = runBlocking { api.getNotes(page = 3, pageSize = 3) }
        assertEquals(1, last.results.size)
        assertNull(last.next)

        val everything = runBlocking { NotesRepository.get(context).getNotes().getOrThrow() }
        assertEquals(7, everything.map { it.id }.toSet().size)
    }

    @Test
    fun imageAddedToANote_isUploaded_downloadsWithTheLogin_andShowsInTheDetailScreen() {
        val noteId = createNote("Dengan gambar")
        val repository = NotesRepository.get(context)
        runBlocking { repository.getNotes() }

        val image = ImageProcessor.newPendingFile(context)
        solidBitmap(300, 200, Color.RED).also { bitmap ->
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }
        runBlocking { repository.addAttachment(noteId, image, "merah.jpg", ImageProcessor.MIME) }
        assertEquals(1, runBlocking { repository.getAttachmentItems(noteId) }.count { it.isPending })

        assertTrue(runBlocking { repository.syncPending() })
        assertEquals(0, repository.status.value.pendingChanges)
        assertTrue("temp file is cleaned up after upload", !image.exists())

        val attachments = JSONObject(http("GET", "/api/notes/$noteId/", null, access)).getJSONArray("attachments")
        assertEquals(1, attachments.length())
        assertEquals("merah.jpg", attachments.getJSONObject(0).getString("original_name"))

        // The file endpoint needs the token and returns the real JPEG.
        val attachmentId = attachments.getJSONObject(0).getInt("id")
        val bytes = httpBytes("/api/attachments/$attachmentId/file/", access)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(300, decoded.width)
        assertEquals(401, httpStatus("/api/attachments/$attachmentId/file/", null))

        val intent = Intent(context, NoteDetailActivity::class.java)
            .putExtra(NoteDetailActivity.EXTRA_NOTE_ID, noteId)
        ActivityScenario.launch<NoteDetailActivity>(intent).use { scenario ->
            waitUntil("thumbnail is downloaded and shown") {
                var loaded = false
                instrumentation.runOnMainSync {
                    scenario.onActivity { activity ->
                        val list = activity.findViewById<RecyclerView>(R.id.rv_attachments)
                        val thumbnail = list.findViewHolderForAdapterPosition(0)
                            ?.itemView?.findViewById<ImageView>(R.id.iv_thumbnail)
                        loaded = list.isShown && thumbnail?.drawable is BitmapDrawable
                    }
                }
                loaded
            }
        }
    }

    @Test
    fun removingAServerImage_whileOffline_isDeletedAfterSync() {
        val noteId = createNote("Hapus gambar")
        val repository = NotesRepository.get(context)
        runBlocking { repository.getNotes() }

        val image = ImageProcessor.newPendingFile(context)
        solidBitmap(80, 80, Color.BLUE).also { b -> image.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        runBlocking { repository.addAttachment(noteId, image, "biru.jpg", ImageProcessor.MIME) }
        assertTrue(runBlocking { repository.syncPending() })

        val items = runBlocking { repository.getAttachmentItems(noteId) }
        assertEquals(1, items.size)
        runBlocking { repository.removeAttachment(noteId, items.single().attachment!!.id) }
        assertTrue(runBlocking { repository.getAttachmentItems(noteId) }.isEmpty())

        assertTrue(runBlocking { repository.syncPending() })
        val attachments = JSONObject(http("GET", "/api/notes/$noteId/", null, access)).getJSONArray("attachments")
        assertEquals(0, attachments.length())
    }

    @Test
    fun imageProcessor_shrinksLargePhotos_andAppliesExifRotation() {
        val source = File(context.cacheDir, "big-photo.jpg")
        val output = File(context.cacheDir, "processed.jpg")
        try {
            solidBitmap(3200, 2000, Color.GREEN).also { bitmap ->
                source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            }
            // A camera held upright stores landscape pixels plus "rotate 90 degrees".
            ExifInterface(source.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }

            assertTrue(ImageProcessor.compress(context, Uri.fromFile(source), output))

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.absolutePath, bounds)
            assertEquals("longer side is capped", ImageProcessor.MAX_DIMENSION, max(bounds.outWidth, bounds.outHeight))
            assertTrue("rotated to portrait", bounds.outHeight > bounds.outWidth)
            assertTrue("smaller than the original", output.length() < source.length())
        } finally {
            source.delete()
            output.delete()
        }
    }

    @Test
    fun imageProcessor_rejectsFilesThatAreNotImages() {
        val notAnImage = File(context.cacheDir, "notes.txt").apply { writeText("bukan gambar") }
        val output = File(context.cacheDir, "never.jpg")
        try {
            assertTrue(!ImageProcessor.compress(context, Uri.fromFile(notAnImage), output))
            assertTrue(!output.exists())
        } finally {
            notAnImage.delete()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.eraseColor(color) }

    private fun createNote(title: String): Int {
        val body = JSONObject().put("title", title).put("content", "<p>isi</p>")
        return JSONObject(http("POST", "/api/notes/", body.toString(), access)).getInt("id")
    }

    private fun waitUntil(what: String, timeoutMs: Long = 90_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(300)
        }
        fail("Timed out waiting for: $what")
    }

    private fun open(method: String, path: String, token: String?): HttpURLConnection {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 30_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", "application/json")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        return connection
    }

    private fun http(method: String, path: String, body: String?, token: String?): String {
        val connection = open(method, path, token)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299 && !(method == "DELETE" && code == 404)) fail("$method $path -> $code: $text")
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun httpBytes(path: String, token: String?): ByteArray {
        val connection = open("GET", path, token)
        try {
            assertEquals(200, connection.responseCode)
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpStatus(path: String, token: String?): Int {
        val connection = open("GET", path, token)
        try {
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
