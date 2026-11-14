package com.example.iknownotes

import com.example.iknownotes.data.model.Note
import com.example.iknownotes.util.DateRange
import com.example.iknownotes.util.NoteFilter
import com.example.iknownotes.util.NoteText
import com.example.iknownotes.util.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class NoteFilterTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    // 2026-11-14T12:00:00Z
    private val now = 1_794_657_600_000L

    private fun note(
        id: Int,
        title: String,
        content: String = "isi",
        color: String? = null,
        folder: Int? = null,
        pinned: Boolean = false,
        reminder: String? = null,
        updated: String = "2026-11-14T10:00:00Z"
    ) = Note(
        id = id, folder = folder, title = title, content = content, color = color,
        isPinned = pinned, isArchived = false, reminderTime = reminder,
        createdAt = updated, updatedAt = updated
    )

    private fun titles(filter: NoteFilter, notes: List<Note>) =
        filter.apply(notes, now, utc).map { it.title }

    @Test
    fun plainText_stripsTagsAndDecodesEntities() {
        assertEquals(
            "Halo dunia & kawan café",
            NoteText.plain("<p dir=\"ltr\">Halo <b>dunia</b> &amp; kawan caf&#233;</p>")
        )
        assertEquals("tanpa tag", NoteText.plain("tanpa tag"))
    }

    @Test
    fun search_ignoresHtmlTagsInContent() {
        val notes = listOf(note(1, "Rapat", content = "<p><b>belanja</b> bulanan</p>"))

        assertEquals(listOf("Rapat"), titles(NoteFilter(query = "belanja bulanan"), notes))
        assertTrue("tag names must not match", titles(NoteFilter(query = "dir"), notes).isEmpty())
    }

    @Test
    fun search_requiresEveryWord_inAnyOrder_caseInsensitive() {
        val notes = listOf(
            note(1, "Belanja", content = "susu dan telur"),
            note(2, "Belanja", content = "susu saja")
        )
        assertEquals(1, NoteFilter(query = "TELUR susu").apply(notes, now, utc).size)
    }

    @Test
    fun colorFilter_treatsNullAsDefault_andAllowsSeveral() {
        val notes = listOf(
            note(1, "polos", color = null),
            note(2, "kuning", color = "yellow"),
            note(3, "hijau", color = "green")
        )
        assertEquals(
            setOf("polos", "kuning"),
            titles(NoteFilter(colors = setOf("default", "yellow")), notes).toSet()
        )
    }

    @Test
    fun reminderFilter_keepsOnlyNotesWithReminder() {
        val notes = listOf(note(1, "a", reminder = "2026-12-01T00:00:00Z"), note(2, "b"))
        assertEquals(listOf("a"), titles(NoteFilter(onlyWithReminder = true), notes))
    }

    @Test
    fun dateRange_today_usesStartOfDayInGivenZone() {
        val notes = listOf(
            note(1, "pagi ini", updated = "2026-11-14T00:30:00Z"),
            note(2, "kemarin", updated = "2026-11-13T23:59:00Z")
        )
        assertEquals(listOf("pagi ini"), titles(NoteFilter(dateRange = DateRange.TODAY), notes))
    }

    @Test
    fun dateRange_lastWeekAndMonth() {
        val notes = listOf(
            note(1, "3 hari", updated = "2026-11-11T12:00:00Z"),
            note(2, "20 hari", updated = "2026-10-25T12:00:00Z"),
            note(3, "60 hari", updated = "2026-09-15T12:00:00Z")
        )
        assertEquals(listOf("3 hari"), titles(NoteFilter(dateRange = DateRange.LAST_7_DAYS), notes))
        assertEquals(
            listOf("3 hari", "20 hari"),
            titles(NoteFilter(dateRange = DateRange.LAST_30_DAYS), notes)
        )
    }

    @Test
    fun folderFilter_matchesFolderId() {
        val notes = listOf(note(1, "a", folder = 5), note(2, "b", folder = 6), note(3, "c"))
        assertEquals(listOf("a"), titles(NoteFilter(folderId = 5), notes))
    }

    @Test
    fun sorting_keepsPinnedOnTop_forEveryOrder() {
        val notes = listOf(
            note(1, "Bebek", updated = "2026-11-10T00:00:00Z"),
            note(2, "apel", updated = "2026-11-12T00:00:00Z"),
            note(3, "Cabai", updated = "2026-11-11T00:00:00Z", pinned = true)
        )
        assertEquals(listOf("Cabai", "apel", "Bebek"), titles(NoteFilter(sort = SortOrder.UPDATED_DESC), notes))
        assertEquals(listOf("Cabai", "Bebek", "apel"), titles(NoteFilter(sort = SortOrder.UPDATED_ASC), notes))
        assertEquals(listOf("Cabai", "apel", "Bebek"), titles(NoteFilter(sort = SortOrder.TITLE_ASC), notes))
        assertEquals(listOf("Cabai", "Bebek", "apel"), titles(NoteFilter(sort = SortOrder.TITLE_DESC), notes))
    }

    @Test
    fun counters_andReset() {
        val filter = NoteFilter(
            query = "x", folderId = 1, colors = setOf("blue"), onlyWithReminder = true,
            dateRange = DateRange.TODAY, sort = SortOrder.TITLE_ASC
        )
        assertEquals(3, filter.advancedCount)
        assertTrue(filter.isNarrowing)

        val reset = filter.withoutAdvancedFilters()
        assertEquals(0, reset.advancedCount)
        assertEquals("x", reset.query)
        assertEquals(1, reset.folderId)
        assertEquals(SortOrder.TITLE_ASC, reset.sort)

        assertFalse(NoteFilter(sort = SortOrder.TITLE_DESC).isNarrowing)
    }
}
