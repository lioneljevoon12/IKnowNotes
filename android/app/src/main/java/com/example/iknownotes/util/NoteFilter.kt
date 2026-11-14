package com.example.iknownotes.util

import com.example.iknownotes.data.model.Note
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

enum class SortOrder { UPDATED_DESC, UPDATED_ASC, TITLE_ASC, TITLE_DESC }

enum class DateRange { ANY, TODAY, LAST_7_DAYS, LAST_30_DAYS }

/** Turns the HTML we store for note content into plain text. */
object NoteText {
    private val tag = Regex("<[^>]*>")
    private val numericEntity = Regex("&#(x?)([0-9a-fA-F]+);")
    private val whitespace = Regex("\\s+")

    fun plain(html: String): String {
        if (!html.contains('<') && !html.contains('&')) return html
        var text = html.replace(tag, " ")
        text = numericEntity.replace(text) { match ->
            val radix = if (match.groupValues[1].isEmpty()) 10 else 16
            val codePoint = match.groupValues[2].toIntOrNull(radix)
            if (codePoint != null && Character.isValidCodePoint(codePoint)) {
                String(Character.toChars(codePoint))
            } else {
                match.value
            }
        }
        text = text
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
        return text.replace(whitespace, " ").trim()
    }
}

/**
 * Everything the notes list can narrow down or reorder by. Applied on the
 * device so it also works offline and reacts instantly while typing.
 */
data class NoteFilter(
    val query: String = "",
    val folderId: Int? = null,
    /** Palette keys ("yellow", "default", ...); empty means any colour. */
    val colors: Set<String> = emptySet(),
    val onlyWithReminder: Boolean = false,
    val dateRange: DateRange = DateRange.ANY,
    val sort: SortOrder = SortOrder.UPDATED_DESC
) {
    /** Number of filter groups set in the filter sheet (colour, reminder, date). */
    val advancedCount: Int
        get() = (if (colors.isNotEmpty()) 1 else 0) +
            (if (onlyWithReminder) 1 else 0) +
            (if (dateRange != DateRange.ANY) 1 else 0)

    /** True when something narrows the list (as opposed to only sorting it). */
    val isNarrowing: Boolean
        get() = query.isNotBlank() || folderId != null || advancedCount > 0

    /** Clears the filter-sheet groups but keeps search text, folder and sort order. */
    fun withoutAdvancedFilters() =
        copy(colors = emptySet(), onlyWithReminder = false, dateRange = DateRange.ANY)

    fun apply(
        notes: List<Note>,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault()
    ): List<Note> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val cutoff = cutoffMillis(now, zone)

        val matching = notes.filter { note ->
            (folderId == null || note.folder == folderId) &&
                (colors.isEmpty() || (note.color ?: "default") in colors) &&
                (!onlyWithReminder || note.reminderTime != null) &&
                (cutoff == null || (updatedMillis(note) >= cutoff)) &&
                matchesWords(note, words)
        }
        return matching.sortedWith(comparator())
    }

    private fun matchesWords(note: Note, words: List<String>): Boolean {
        if (words.isEmpty()) return true
        val haystack = (note.title + " " + NoteText.plain(note.content)).lowercase()
        return words.all { it in haystack }
    }

    private fun cutoffMillis(now: Long, zone: TimeZone): Long? = when (dateRange) {
        DateRange.ANY -> null
        DateRange.TODAY -> Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        DateRange.LAST_7_DAYS -> now - TimeUnit.DAYS.toMillis(7)
        DateRange.LAST_30_DAYS -> now - TimeUnit.DAYS.toMillis(30)
    }

    /** Pinned notes always stay on top, then the chosen order. */
    private fun comparator(): Comparator<Note> {
        val byUpdatedDesc = compareByDescending<Note> { updatedMillis(it) }
        val byOrder: Comparator<Note> = when (sort) {
            SortOrder.UPDATED_DESC -> byUpdatedDesc
            SortOrder.UPDATED_ASC -> compareBy<Note> { updatedMillis(it) }
            SortOrder.TITLE_ASC -> Comparator<Note> { a, b ->
                String.CASE_INSENSITIVE_ORDER.compare(a.title, b.title)
            }.then(byUpdatedDesc)
            SortOrder.TITLE_DESC -> Comparator<Note> { a, b ->
                String.CASE_INSENSITIVE_ORDER.compare(b.title, a.title)
            }.then(byUpdatedDesc)
        }
        return compareByDescending<Note> { it.isPinned }.then(byOrder)
    }

    private fun updatedMillis(note: Note): Long = ReminderHelper.parseIso(note.updatedAt) ?: 0L
}
