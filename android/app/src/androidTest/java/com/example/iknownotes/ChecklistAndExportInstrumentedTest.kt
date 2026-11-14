package com.example.iknownotes

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.iknownotes.util.ChecklistEnterWatcher
import com.example.iknownotes.util.NoteExporter
import com.example.iknownotes.util.RichTextHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Runs on a device/emulator: these touch real Android text and PDF classes. */
@RunWith(AndroidJUnit4::class)
class ChecklistAndExportInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val box = "☐"
    private val done = "☑"

    @Test
    fun checklistCharacters_surviveTheHtmlRoundTrip() {
        val source = SpannableStringBuilder("$box beli susu\n$done sudah")
        val html = RichTextHelper.toHtml(source)
        assertEquals("$box beli susu\n$done sudah", RichTextHelper.fromHtml(html).toString().trim())
    }

    @Test
    fun applyChecklist_prefixesTheCurrentLine() {
        var result = ""
        instrumentation.runOnMainSync {
            val editText = EditText(context)
            editText.setText("satu\ndua")
            editText.setSelection(0, editText.text.length)
            RichTextHelper.applyChecklist(editText)
            result = editText.text.toString()
        }
        assertEquals("$box satu\n$box dua", result)
    }

    @Test
    fun pressingEnterOnAnItem_startsTheNextItem_andEnterOnEmptyItemEndsTheList() {
        var afterFirstEnter = ""
        var cursorAfterFirstEnter = -1
        var afterSecondEnter = ""
        instrumentation.runOnMainSync {
            val editText = EditText(context)
            editText.addTextChangedListener(ChecklistEnterWatcher(editText))
            editText.setText("$box beli susu")
            editText.setSelection(editText.text.length)

            editText.text.insert(editText.selectionStart, "\n")
            afterFirstEnter = editText.text.toString()
            cursorAfterFirstEnter = editText.selectionStart

            // The new item is empty, so another Enter ends the list.
            editText.text.insert(editText.selectionStart, "\n")
            afterSecondEnter = editText.text.toString()
        }
        assertEquals("$box beli susu\n$box ", afterFirstEnter)
        assertEquals(afterFirstEnter.length, cursorAfterFirstEnter)
        assertEquals("$box beli susu\n", afterSecondEnter)
    }

    @Test
    fun pdfExport_spansSeveralPages_andRendersVisibleText() {
        val body = SpannableStringBuilder()
        repeat(120) { index ->
            val start = body.length
            body.append("Baris nomor $index: $box tugas penting yang cukup panjang supaya teks perlu dibungkus\n")
            if (index % 10 == 0) {
                body.setSpan(StyleSpan(Typeface.BOLD), start, body.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        val file = File(context.cacheDir, "export-test.pdf")
        FileOutputStream(file).use { NoteExporter.writePdf(it, "Judul Uji", body, "Terakhir diubah hari ini") }

        assertTrue(file.length() > 0)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                assertTrue("expected a multi-page PDF but got ${renderer.pageCount}", renderer.pageCount >= 2)

                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    var darkPixels = 0
                    for (y in 0 until bitmap.height step 2) {
                        for (x in 0 until bitmap.width step 2) {
                            if (Color.red(bitmap.getPixel(x, y)) < 128) darkPixels++
                        }
                    }
                    assertTrue("page 0 looks blank", darkPixels > 200)
                }
            }
        }
        file.delete()
    }

    @Test
    fun pdfExport_ofEmptyNote_stillProducesAPage() {
        val file = File(context.cacheDir, "empty-test.pdf")
        FileOutputStream(file).use { NoteExporter.writePdf(it, "", SpannableStringBuilder(""), null) }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { assertEquals(1, it.pageCount) }
        }
        file.delete()
    }
}
