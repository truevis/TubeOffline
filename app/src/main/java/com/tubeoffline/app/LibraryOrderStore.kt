package com.tubeoffline.app

import android.content.Context
import android.net.Uri

/**
 * Persisted custom library order (drag-to-reorder on the main screen).
 * Empty order means "use MediaStore newest-first". Videos not listed yet
 * (new downloads) are shown above the saved order.
 */
object LibraryOrderStore {
    private const val PREFS = "library_order"
    private const val KEY_ORDER = "uri_order"
    private const val SEP = "\n"

    /** Saved URI strings in display order, or empty when the user has never reordered. */
    fun getOrder(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_ORDER, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split(SEP).filter { it.isNotEmpty() }
    }

    fun saveOrder(context: Context, uris: List<Uri>) {
        prefs(context).edit()
            .putString(KEY_ORDER, uris.joinToString(SEP) { it.toString() })
            .apply()
    }

    fun remove(context: Context, uri: Uri) {
        val key = uri.toString()
        val current = getOrder(context)
        if (current.isEmpty()) return
        val next = current.filter { it != key }
        if (next.size == current.size) return
        prefs(context).edit()
            .putString(KEY_ORDER, next.joinToString(SEP))
            .apply()
    }

    /**
     * Apply [order] to [videos]. When [order] is empty, returns [videos] unchanged
     * (caller already sorted newest-first). Entries in [videos] that are missing
     * from [order] are prepended in their relative order (new downloads stay on top).
     */
    fun apply(videos: List<SavedVideo>, order: List<String>): List<SavedVideo> {
        if (order.isEmpty() || videos.isEmpty()) return videos
        val byKey = LinkedHashMap<String, SavedVideo>(videos.size)
        for (v in videos) byKey[v.uri.toString()] = v
        val mergedKeys = mergeLibraryOrder(byKey.keys.toList(), order)
        return mergedKeys.mapNotNull { byKey[it] }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Pure merge: [mediaStoreKeysNewestFirst] is the MediaStore order; [savedOrder] is the
 * user's drag order. Keys only in the media list (new downloads) stay on top;
 * remaining keys follow [savedOrder]. Stale saved keys are dropped.
 */
internal fun mergeLibraryOrder(
    mediaStoreKeysNewestFirst: List<String>,
    savedOrder: List<String>,
): List<String> {
    if (savedOrder.isEmpty() || mediaStoreKeysNewestFirst.isEmpty()) {
        return mediaStoreKeysNewestFirst
    }
    val mediaSet = mediaStoreKeysNewestFirst.toHashSet()
    val savedSet = savedOrder.toHashSet()
    val result = ArrayList<String>(mediaStoreKeysNewestFirst.size)
    for (key in mediaStoreKeysNewestFirst) {
        if (key !in savedSet) result += key
    }
    for (key in savedOrder) {
        if (key in mediaSet) result += key
    }
    return result
}
