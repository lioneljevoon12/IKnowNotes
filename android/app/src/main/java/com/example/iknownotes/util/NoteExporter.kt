package com.example.iknownotes.util

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import java.io.OutputStream

/** Turns a note into shareable text, a .txt file or a .pdf file. */
object NoteExporter {

    // A4 in PDF points (1/72 inch); the canvas unit of PdfDocument.
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48

    private const val TITLE_SIZE = 20
    private const val META_SIZE = 9
    private const val BODY_SIZE = 12

    private val invalidFileNameChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** Title and body separated by a blank line; either may be empty. */
    fun shareText(title: String, body: String): String =
        listOf(title.trim(), body.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")

    /** A file name (without extension) that every file system accepts. */
    fun safeFileName(title: String, fallback: String = "catatan"): String {
        val cleaned = title.replace(invalidFileNameChars, "_").trim().take(60).trim('.', ' ')
        return cleaned.ifEmpty { fallback }
    }

    fun writeText(out: OutputStream, title: String, body: String) {
        out.write(shareText(title, body).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /**
     * Lays the note out on A4 pages. [body] keeps its bold/italic/underline/strike
     * spans; [meta] is a small grey line under the title (e.g. the edit date).
     */
    fun writePdf(out: OutputStream, title: String, body: CharSequence, meta: String?) {
        val document = SpannableStringBuilder()

        val titleText = title.trim().ifEmpty { "(Tanpa judul)" }
        document.append(titleText)
        document.setSpan(AbsoluteSizeSpan(TITLE_SIZE), 0, document.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        document.setSpan(StyleSpan(Typeface.BOLD), 0, document.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        document.append("\n")

        if (!meta.isNullOrBlank()) {
            val metaStart = document.length
            document.append(meta)
            document.setSpan(AbsoluteSizeSpan(META_SIZE), metaStart, document.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            document.setSpan(ForegroundColorSpan(Color.GRAY), metaStart, document.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            document.append("\n")
        }
        document.append("\n")
        // append(text, start, end) keeps the spans; trailing blank lines are dropped.
        document.append(body, 0, body.toString().trimEnd().length)

        val contentWidth = PAGE_WIDTH - 2 * MARGIN
        val contentHeight = PAGE_HEIGHT - 2 * MARGIN
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = BODY_SIZE.toFloat()
        }
        val layout = StaticLayout.Builder
            .obtain(document, 0, document.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(3f, 1f)
            .build()

        val pdf = PdfDocument()
        try {
            var firstLine = 0
            var pageNumber = 1
            while (firstLine < layout.lineCount) {
                val top = layout.getLineTop(firstLine)
                var endLine = firstLine
                while (endLine < layout.lineCount && layout.getLineBottom(endLine) - top <= contentHeight) {
                    endLine++
                }
                if (endLine == firstLine) endLine++ // a single line taller than the page
                val bottom = layout.getLineBottom(endLine - 1)

                val page = pdf.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                )
                val canvas = page.canvas
                canvas.save()
                canvas.translate(MARGIN.toFloat(), MARGIN.toFloat())
                canvas.clipRect(0f, 0f, contentWidth.toFloat(), (bottom - top).toFloat())
                canvas.translate(0f, -top.toFloat())
                layout.draw(canvas)
                canvas.restore()
                pdf.finishPage(page)

                firstLine = endLine
                pageNumber++
            }
            pdf.writeTo(out)
            out.flush()
        } finally {
            pdf.close()
        }
    }
}
