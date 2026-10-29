package com.example.iknownotes.util

import com.example.iknownotes.R

/**
 * The `Note.color` field from the backend is a free-text varchar(20), so we
 * treat it as a palette *key* ("yellow", "green", "pink", "blue") rather than
 * a raw hex value. Anything unrecognized (including null/blank) falls back
 * to the default surface color, matching the plain white/grey notes in the
 * mockup.
 */
object NoteColorPalette {

    data class Swatch(val key: String, val colorRes: Int)

    val swatches = listOf(
        Swatch("default", R.color.surface),
        Swatch("yellow", R.color.note_yellow),
        Swatch("green", R.color.note_green),
        Swatch("pink", R.color.note_pink),
        Swatch("blue", R.color.note_blue)
    )

    fun colorResFor(key: String?): Int {
        return swatches.firstOrNull { it.key == key }?.colorRes ?: R.color.surface
    }
}
