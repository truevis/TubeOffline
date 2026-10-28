package com.tubeoffline.app

import android.content.Context
import android.net.Uri

/** Saved timeline position per downloaded video (MediaStore content Uri). */
object PlaybackPositionStore {
    private const val PREFS = "playback_positions"

    fun getPositionMs(context: Context, uri: Uri): Long {
        val ms = prefs(context).getLong(positionKey(uri), 0L)
        return ms.coerceAtLeast(0L)
    }

    fun wasPaused(context: Context, uri: Uri): Boolean =
        prefs(context).getBoolean(pausedKey(uri), false)

    fun save(context: Context, uri: Uri, positionMs: Long, paused: Boolean) {
        prefs(context).edit()
            .putLong(positionKey(uri), positionMs.coerceAtLeast(0L))
            .putBoolean(pausedKey(uri), paused)
            .apply()
    }

    fun clear(context: Context, uri: Uri) {
        prefs(context).edit()
            .remove(positionKey(uri))
            .remove(pausedKey(uri))
            .apply()
    }

    /** Drop bookmark when the user finishes (or is within the last few seconds). */
    fun clearIfNearEnd(context: Context, uri: Uri, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0L) return
        if (positionMs >= durationMs - END_MARGIN_MS) clear(context, uri)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun positionKey(uri: Uri) = "pos:${uri}"
    private fun pausedKey(uri: Uri) = "paused:${uri}"

    private const val END_MARGIN_MS = 5_000L
}
