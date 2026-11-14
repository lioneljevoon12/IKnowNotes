package com.example.iknownotes.data.local

import android.content.Context
import com.example.iknownotes.util.SortOrder

/** Per-device display preferences for the notes list (not tied to the account). */
class UiPrefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** true = two-column grid (default), false = single-column list. */
    var gridView: Boolean
        get() = prefs.getBoolean(KEY_GRID, true)
        set(value) = prefs.edit().putBoolean(KEY_GRID, value).apply()

    var sortOrder: SortOrder
        get() = prefs.getString(KEY_SORT, null)
            ?.let { name -> SortOrder.values().firstOrNull { it.name == name } }
            ?: SortOrder.UPDATED_DESC
        set(value) = prefs.edit().putString(KEY_SORT, value.name).apply()

    companion object {
        private const val PREFS_NAME = "iknownotes_ui"
        private const val KEY_GRID = "grid_view"
        private const val KEY_SORT = "sort_order"
    }
}
