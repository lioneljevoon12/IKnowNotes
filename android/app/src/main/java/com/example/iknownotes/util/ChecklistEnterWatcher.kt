package com.example.iknownotes.util

import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText

/**
 * Keeps a checklist going: Enter on an item starts the next "☐ ", and Enter on an
 * empty item ends the list.
 */
class ChecklistEnterWatcher(private val editText: EditText) : TextWatcher {

    private var newlineIndex = -1
    private var applying = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        // Only a single newline typed (or pasted) on its own counts as "Enter".
        newlineIndex = if (!applying && before == 0 && count == 1 && s != null && s[start] == '\n') start else -1
    }

    override fun afterTextChanged(s: Editable?) {
        val index = newlineIndex
        newlineIndex = -1
        if (applying || index < 0 || s == null) return

        val result = ChecklistHelper.continueOnEnter(s.toString(), index) ?: return
        applying = true
        try {
            s.replace(result.edit.start, result.edit.end, result.edit.replacement)
            editText.setSelection(result.cursor.coerceIn(0, s.length))
        } finally {
            applying = false
        }
    }
}
