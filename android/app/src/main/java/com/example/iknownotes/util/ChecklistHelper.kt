package com.example.iknownotes.util

/**
 * Checklist items are ordinary text lines that start with a box character and a
 * space ("☐ buy milk" / "☑ buy milk"). Keeping them as plain characters means they
 * survive the HTML round-trip, sync, search, sharing and export without any
 * extra storage format. All functions work on plain strings so they are easy to test.
 */
object ChecklistHelper {

    const val UNCHECKED = '☐' // ☐
    const val CHECKED = '☑' // ☑

    private const val PREFIX = "☐ "
    private val numberPrefix = Regex("^\\d+\\.\\s")

    /** A change to apply to the editor text: replace [start, end) with [replacement]. */
    data class TextEdit(val start: Int, val end: Int, val replacement: String)

    /** A [TextEdit] plus where the cursor should end up afterwards. */
    data class EnterEdit(val edit: TextEdit, val cursor: Int)

    data class Item(val lineStart: Int, val lineEnd: Int, val checked: Boolean)

    private fun isBox(c: Char) = c == UNCHECKED || c == CHECKED

    /** True when [line] starts with a box and a space. */
    fun hasChecklistPrefix(line: String): Boolean =
        line.length >= 2 && isBox(line[0]) && line[1] == ' '

    /** True when the paragraph beginning at [lineStart] is a checklist item. */
    fun isChecklistLine(text: String, lineStart: Int): Boolean {
        if (lineStart < 0 || lineStart + 1 >= text.length) return false
        if (lineStart > 0 && text[lineStart - 1] != '\n') return false
        return isBox(text[lineStart]) && text[lineStart + 1] == ' '
    }

    fun lineStart(text: String, position: Int): Int =
        text.lastIndexOf('\n', position.coerceIn(0, text.length) - 1) + 1

    fun lineEnd(text: String, position: Int): Int =
        text.indexOf('\n', position.coerceIn(0, text.length)).let { if (it == -1) text.length else it }

    fun items(text: String): List<Item> {
        val result = mutableListOf<Item>()
        var index = 0
        for (line in text.split('\n')) {
            if (isChecklistLine(text, index)) {
                result += Item(index, index + line.length, text[index] == CHECKED)
            }
            index += line.length + 1
        }
        return result
    }

    /** (checked, total) over all items, e.g. for a progress label. */
    fun progress(text: String): Pair<Int, Int> {
        val all = items(text)
        return all.count { it.checked } to all.size
    }

    fun stripPrefix(line: String): String = if (hasChecklistPrefix(line)) line.substring(2) else line

    /**
     * Toolbar button: turns the lines touched by the selection into checklist items,
     * or back into plain lines when they all already are. Edits come back last-first
     * so they can be applied one after another without shifting each other.
     */
    fun toggleChecklist(text: String, selStart: Int, selEnd: Int): List<TextEdit> {
        val from = lineStart(text, minOf(selStart, selEnd))
        val to = lineEnd(text, maxOf(selStart, selEnd))
        val lines = text.substring(from, to).split('\n')
        val singleLine = lines.size == 1

        var offset = from
        val targets = mutableListOf<Pair<Int, String>>()
        for (line in lines) {
            // Blank lines in a multi-line selection are left alone.
            if (singleLine || line.isNotBlank()) targets += offset to line
            offset += line.length + 1
        }
        if (targets.isEmpty()) return emptyList()

        val edits = mutableListOf<TextEdit>()
        val allChecklist = targets.all { hasChecklistPrefix(it.second) }
        for ((start, line) in targets) {
            when {
                allChecklist -> edits += TextEdit(start, start + 2, "")
                hasChecklistPrefix(line) -> Unit
                line.startsWith("• ") -> edits += TextEdit(start, start + 2, PREFIX)
                else -> {
                    val number = numberPrefix.find(line)
                    edits += if (number != null) {
                        TextEdit(start, start + number.value.length, PREFIX)
                    } else {
                        TextEdit(start, start, PREFIX)
                    }
                }
            }
        }
        return edits.sortedByDescending { it.start }
    }

    /** Flips ☐/☑ on the item starting at [lineStart]; null if it isn't an item. */
    fun toggleItem(text: String, lineStart: Int): TextEdit? {
        if (!isChecklistLine(text, lineStart)) return null
        val flipped = if (text[lineStart] == UNCHECKED) CHECKED else UNCHECKED
        return TextEdit(lineStart, lineStart + 1, flipped.toString())
    }

    /**
     * Called after the user typed a newline at [newlineIndex]. On a checklist item the
     * next line gets a fresh "☐ "; pressing Enter on an empty item ends the list.
     */
    fun continueOnEnter(text: String, newlineIndex: Int): EnterEdit? {
        if (newlineIndex !in text.indices || text[newlineIndex] != '\n') return null
        val start = lineStart(text, newlineIndex)
        if (!isChecklistLine(text, start)) return null
        val previous = text.substring(start, newlineIndex)
        return if (previous.length == 2) {
            EnterEdit(TextEdit(start, newlineIndex + 1, ""), start)
        } else {
            EnterEdit(TextEdit(newlineIndex + 1, newlineIndex + 1, PREFIX), newlineIndex + 1 + PREFIX.length)
        }
    }
}
