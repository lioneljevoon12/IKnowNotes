package com.example.iknownotes.util

import android.graphics.Typeface
import android.text.Editable
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.widget.EditText

object RichTextHelper {

    fun applyBold(editText: EditText) {
        val (start, end) = getSelectionOrWordRange(editText)
        if (start < end) {
            toggleBold(editText.text, start, end)
        }
    }

    fun applyItalic(editText: EditText) {
        val (start, end) = getSelectionOrWordRange(editText)
        if (start < end) {
            toggleItalic(editText.text, start, end)
        }
    }

    fun applyUnderline(editText: EditText) {
        val (start, end) = getSelectionOrWordRange(editText)
        if (start < end) {
            toggleUnderline(editText.text, start, end)
        }
    }

    fun applyStrikethrough(editText: EditText) {
        val (start, end) = getSelectionOrWordRange(editText)
        if (start < end) {
            toggleStrikethrough(editText.text, start, end)
        }
    }

    fun applyBulletList(editText: EditText) {
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(start)
        toggleBulletList(editText.text, start, end)
    }

    fun applyNumberedList(editText: EditText) {
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(start)
        toggleNumberedList(editText.text, start, end)
    }

    private fun getSelectionOrWordRange(editText: EditText): Pair<Int, Int> {
        val start = editText.selectionStart
        val end = editText.selectionEnd
        if (start != end && start >= 0 && end >= 0) {
            return Pair(Math.min(start, end), Math.max(start, end))
        }

        // If no selection, expand to word boundaries around cursor
        val text = editText.text.toString()
        if (text.isEmpty() || start < 0 || start > text.length) return Pair(0, 0)

        var wordStart = (start - 1).coerceAtLeast(0)
        while (wordStart > 0 && !text[wordStart - 1].isWhitespace()) {
            wordStart--
        }

        var wordEnd = start.coerceAtMost(text.length)
        while (wordEnd < text.length && !text[wordEnd].isWhitespace()) {
            wordEnd++
        }

        return if (wordStart < wordEnd) Pair(wordStart, wordEnd) else Pair(start, start)
    }

    private fun toggleBold(editable: Editable, start: Int, end: Int) {
        val spans = editable.getSpans(start, end, StyleSpan::class.java)
        val existingBold = spans.firstOrNull { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC }

        if (existingBold != null) {
            val spanStart = editable.getSpanStart(existingBold)
            val spanEnd = editable.getSpanEnd(existingBold)
            val isBoldItalic = existingBold.style == Typeface.BOLD_ITALIC
            editable.removeSpan(existingBold)
            if (isBoldItalic) {
                editable.setSpan(StyleSpan(Typeface.ITALIC), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            val existingItalic = spans.firstOrNull { it.style == Typeface.ITALIC }
            if (existingItalic != null) {
                val spanStart = editable.getSpanStart(existingItalic)
                val spanEnd = editable.getSpanEnd(existingItalic)
                editable.removeSpan(existingItalic)
                editable.setSpan(StyleSpan(Typeface.BOLD_ITALIC), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                editable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun toggleItalic(editable: Editable, start: Int, end: Int) {
        val spans = editable.getSpans(start, end, StyleSpan::class.java)
        val existingItalic = spans.firstOrNull { it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC }

        if (existingItalic != null) {
            val spanStart = editable.getSpanStart(existingItalic)
            val spanEnd = editable.getSpanEnd(existingItalic)
            val isBoldItalic = existingItalic.style == Typeface.BOLD_ITALIC
            editable.removeSpan(existingItalic)
            if (isBoldItalic) {
                editable.setSpan(StyleSpan(Typeface.BOLD), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else {
            val existingBold = spans.firstOrNull { it.style == Typeface.BOLD }
            if (existingBold != null) {
                val spanStart = editable.getSpanStart(existingBold)
                val spanEnd = editable.getSpanEnd(existingBold)
                editable.removeSpan(existingBold)
                editable.setSpan(StyleSpan(Typeface.BOLD_ITALIC), spanStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                editable.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun toggleUnderline(editable: Editable, start: Int, end: Int) {
        val spans = editable.getSpans(start, end, UnderlineSpan::class.java)
        if (spans.isNotEmpty()) {
            spans.forEach { editable.removeSpan(it) }
        } else {
            editable.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun toggleStrikethrough(editable: Editable, start: Int, end: Int) {
        val spans = editable.getSpans(start, end, StrikethroughSpan::class.java)
        if (spans.isNotEmpty()) {
            spans.forEach { editable.removeSpan(it) }
        } else {
            editable.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun toggleBulletList(editable: Editable, start: Int, end: Int) {
        val text = editable.toString()
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', end).let { if (it == -1) text.length else it }
        val lines = text.substring(lineStart, lineEnd).split('\n')

        val allBulleted = lines.all { it.startsWith("• ") }
        val newLines = if (allBulleted) {
            lines.map { it.removePrefix("• ") }
        } else {
            lines.map { line ->
                val cleaned = line.replace(Regex("""^\d+\.\s"""), "").removePrefix("• ")
                "• $cleaned"
            }
        }
        val replacement = newLines.joinToString("\n")
        editable.replace(lineStart, lineEnd, replacement)
    }

    private fun toggleNumberedList(editable: Editable, start: Int, end: Int) {
        val text = editable.toString()
        val lineStart = text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (it == -1) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', end).let { if (it == -1) text.length else it }
        val lines = text.substring(lineStart, lineEnd).split('\n')

        val numberRegex = Regex("""^\d+\.\s""")
        val allNumbered = lines.all { numberRegex.containsMatchIn(it) }
        val newLines = if (allNumbered) {
            lines.map { it.replace(numberRegex, "") }
        } else {
            lines.mapIndexed { index, line ->
                val cleaned = line.replace(numberRegex, "").removePrefix("• ")
                "${index + 1}. $cleaned"
            }
        }
        val replacement = newLines.joinToString("\n")
        editable.replace(lineStart, lineEnd, replacement)
    }

    fun toHtml(spanned: Spanned): String {
        return Html.toHtml(spanned, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE).trim()
    }

    fun fromHtml(html: String?): Spanned {
        if (html.isNullOrBlank()) return SpannableStringBuilder("")
        return if (html.contains("<") && html.contains(">")) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        } else {
            SpannableStringBuilder(html)
        }
    }
}
