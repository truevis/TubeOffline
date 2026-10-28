package com.tubeoffline.app

import android.content.Context
import android.net.Uri

/** Resume point and played state per downloaded video (MediaStore content Uri). */
object PlaybackPositionStore {
    private const val PREFS = "playback_positions"

    fun getPositionMs(context: Context, uri: Uri): Long {
        val ms = prefs(context).getLong(positionKey(uri), 0L)
        return ms.coerceAtLeast(0L)
    }

    fun wasPaused(context: Context, uri: Uri): Boolean =
        prefs(context).getBoolean(pausedKey(uri), false)

    fun isCompleted(context: Context, uri: Uri): Boolean =
        prefs(context).getBoolean(completedKey(uri), false)

    fun save(context: Context, uri: Uri, positionMs: Long, paused: Boolean) {
        prefs(context).edit()
            .putLong(positionKey(uri), positionMs.coerceAtLeast(0L))
            .putBoolean(pausedKey(uri), paused)
            .apply()
    }

    /**
     * Keep a resume point, or mark the video played when playback has finished
     * (or stopped within the last few seconds). A played video stays marked
     * if it is opened again.
     */
    fun record(
        context: Context,
        uri: Uri,
        positionMs: Long,
        durationMs: Long,
        paused: Boolean,
        ended: Boolean = false,
    ) {
        if (ended || isFinished(positionMs, durationMs)) markCompleted(context, uri)
        else save(context, uri, positionMs, paused)
    }

    fun markCompleted(context: Context, uri: Uri) {
        prefs(context).edit()
            .remove(positionKey(uri))
            .remove(pausedKey(uri))
            .putBoolean(completedKey(uri), true)
            .apply()
    }

    fun clear(context: Context, uri: Uri) {
        prefs(context).edit()
            .remove(positionKey(uri))
            .remove(pausedKey(uri))
            .remove(completedKey(uri))
            .apply()
    }

    private fun isFinished(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        return positionMs >= durationMs - END_MARGIN_MS
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun positionKey(uri: Uri) = "pos:${uri}"
    private fun pausedKey(uri: Uri) = "paused:${uri}"
    private fun completedKey(uri: Uri) = "done:${uri}"

    private const val END_MARGIN_MS = 5_000L
}
