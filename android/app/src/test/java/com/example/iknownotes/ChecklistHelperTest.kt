package com.example.iknownotes

import com.example.iknownotes.util.ChecklistHelper
import com.example.iknownotes.util.ChecklistHelper.TextEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistHelperTest {

    private val box = "☐"
    private val done = "☑"

    private fun apply(text: String, edits: List<TextEdit>): String {
        var result = text
        for (e in edits) result = result.substring(0, e.start) + e.replacement + result.substring(e.end)
        return result
    }

    @Test
    fun toggleChecklist_addsBoxToEveryNonBlankSelectedLine() {
        val text = "satu\n\ndua\ntiga"
        val edits = ChecklistHelper.toggleChecklist(text, 0, text.length)
        assertEquals("$box satu\n\n$box dua\n$box tiga", apply(text, edits))
    }

    @Test
    fun toggleChecklist_onSingleBlankLine_stillAddsBox() {
        val edits = ChecklistHelper.toggleChecklist("", 0, 0)
        assertEquals("$box ", apply("", edits))
    }

    @Test
    fun toggleChecklist_removesBoxesWhenAllLinesAlreadyHaveThem() {
        val text = "$box satu\n$done dua"
        val edits = ChecklistHelper.toggleChecklist(text, 0, text.length)
        assertEquals("satu\ndua", apply(text, edits))
    }

    @Test
    fun toggleChecklist_replacesBulletAndNumberPrefixes() {
        val text = "• satu\n2. dua\ntiga"
        val edits = ChecklistHelper.toggleChecklist(text, 0, text.length)
        assertEquals("$box satu\n$box dua\n$box tiga", apply(text, edits))
    }

    @Test
    fun toggleChecklist_onlyTouchesSelectedLines() {
        val text = "a\nb\nc"
        val cursorInB = 2
        val edits = ChecklistHelper.toggleChecklist(text, cursorInB, cursorInB)
        assertEquals("a\n$box b\nc", apply(text, edits))
    }

    @Test
    fun toggleItem_flipsTheBox() {
        val text = "$box satu\n$done dua"
        assertEquals("$done satu\n$done dua", apply(text, listOfNotNull(ChecklistHelper.toggleItem(text, 0))))
        assertEquals("$box satu\n$box dua", apply(text, listOfNotNull(ChecklistHelper.toggleItem(text, 7))))
    }

    @Test
    fun toggleItem_ignoresLinesThatAreNotItems() {
        assertNull(ChecklistHelper.toggleItem("biasa\n$box x", 0))
        assertNull(ChecklistHelper.toggleItem("$box-tanpa-spasi", 0))
        assertNull(ChecklistHelper.toggleItem("a $box b", 2))
    }

    @Test
    fun continueOnEnter_startsNextItem() {
        val text = "$box beli susu\n"
        val result = ChecklistHelper.continueOnEnter(text, text.indexOf('\n'))!!
        assertEquals("$box beli susu\n$box ", apply(text, listOf(result.edit)))
        assertEquals(apply(text, listOf(result.edit)).length, result.cursor)
    }

    @Test
    fun continueOnEnter_afterCheckedItem_startsUncheckedItem() {
        val text = "$done selesai\n"
        val result = ChecklistHelper.continueOnEnter(text, text.indexOf('\n'))!!
        assertEquals("$done selesai\n$box ", apply(text, listOf(result.edit)))
    }

    @Test
    fun continueOnEnter_onEmptyItem_endsTheList() {
        val text = "$box satu\n$box \n"
        val newline = text.lastIndexOf('\n')
        val result = ChecklistHelper.continueOnEnter(text, newline)!!
        assertEquals("$box satu\n", apply(text, listOf(result.edit)))
        assertEquals(text.indexOf('\n') + 1, result.cursor)
    }

    @Test
    fun continueOnEnter_ignoresOrdinaryLines() {
        assertNull(ChecklistHelper.continueOnEnter("biasa\n", 5))
    }

    @Test
    fun itemsAndProgress() {
        val text = "judul\n$box a\n$done b\n$done c\nakhir"
        assertEquals(3, ChecklistHelper.items(text).size)
        assertEquals(2 to 3, ChecklistHelper.progress(text))
        assertTrue(ChecklistHelper.items(text)[1].checked)
        assertFalse(ChecklistHelper.items(text)[0].checked)
    }
}
