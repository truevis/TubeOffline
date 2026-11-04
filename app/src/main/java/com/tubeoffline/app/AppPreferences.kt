package com.tubeoffline.app

import android.content.Context

/** Small persisted settings (autoplay, etc.). Defaults stay off until the user opts in. */
object AppPreferences {
    private const val PREFS = "app_prefs"
    private const val KEY_AUTOPLAY_NEXT = "autoplay_next"
    private const val KEY_LIBRARY_SEARCH = "library_search_query"

    fun isAutoplayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOPLAY_NEXT, false)

    fun setAutoplayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOPLAY_NEXT, enabled).apply()
    }

    /**
     * Live library search text. While non-blank, autoplay and previous/next only
     * walk matching downloads (same name contains filter as the main screen).
     * Cleared when the search box is empty. Survives process death so the player
     * and media notification stay scoped while the app is backgrounded or in PiP.
     */
    fun getLibrarySearchQuery(context: Context): String =
        prefs(context).getString(KEY_LIBRARY_SEARCH, "") ?: ""

    fun setLibrarySearchQuery(context: Context, query: String) {
        prefs(context).edit().putString(KEY_LIBRARY_SEARCH, query).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
