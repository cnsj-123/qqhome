package com.qq.closie.ui.closet

import android.content.Context

/**
 * Lightweight SharedPreferences persistence for Closet view preferences (grid size and sort).
 * These are UI preferences, deliberately kept out of [com.qq.closie.data.model.ClothingItem].
 */
object ClosetPrefs {
    private const val PREF = "closet_prefs"
    private const val KEY_COLUMNS = "grid_columns"
    private const val KEY_SORT_FIELD = "sort_field"
    private const val KEY_ASCENDING = "sort_ascending"

    fun gridColumns(context: Context): Int =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(KEY_COLUMNS, 2)

    fun saveGridColumns(context: Context, columns: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(KEY_COLUMNS, columns).apply()
    }

    fun sortField(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_SORT_FIELD, SortField.RECENT_EDIT.name)
            ?: SortField.RECENT_EDIT.name

    fun saveSortField(context: Context, field: SortField) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_SORT_FIELD, field.name).apply()
    }

    fun ascending(context: Context): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ASCENDING, false)

    fun saveAscending(context: Context, ascending: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ASCENDING, ascending).apply()
    }
}
