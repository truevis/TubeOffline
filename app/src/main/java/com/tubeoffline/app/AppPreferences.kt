package com.tubeoffline.app

import android.content.Context

/** Small persisted settings (autoplay, etc.). Defaults stay off until the user opts in. */
object AppPreferences {
    private const val PREFS = "app_prefs"
    private const val KEY_AUTOPLAY_NEXT = "autoplay_next"

    fun isAutoplayEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOPLAY_NEXT, false)

    fun setAutoplayEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOPLAY_NEXT, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
