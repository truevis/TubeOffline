package com.tubeoffline.app

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import java.io.File

data class SavedVideo(
    val uri: Uri,
    val name: String,
    val youTubeId: String?,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSec: Long,
    /** YouTube upload time in unix seconds. Null for downloads saved before this was recorded. */
    val uploadedEpochSec: Long? = null,
)

/** Movies/TubeOffline via MediaStore (scoped storage, no storage permission needed to write). */
object VideoStore {
    val RELATIVE_DIR = Environment.DIRECTORY_MOVIES + "/TubeOffline/"

    /**
     * Thrown when MediaStore.Video rejects the file's MIME/container.
     * DownloadService catches this and retries with a safer mp4 format.
     */
    class UnsupportedMimeException(
        message: String,
        cause: Throwable? = null,
    ) : IllegalArgumentException(message, cause)

    private fun collection(): Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Makes a title safe to use as a file name on Android (and Windows, if copied to a PC). */
    fun sanitizeFileName(title: String, maxLength: Int = 120): String {
        val cleaned = title
            .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.', ' ')
        val cut = if (cleaned.length > maxLength) cleaned.take(maxLength).trimEnd('.', ' ') else cleaned
        return cut.ifBlank { "video" }
    }

    /**
     * MIME for MediaStore.Video / ExoPlayer.
     * Never returns a wildcard MIME (video slash-star) — MediaStore throws
     * IllegalArgumentException("Unsupported MIME type") for those.
     * Unknown / audio-only / matroska extensions fall back to video/mp4 so a remuxed
     * retry can save; [save] still rejects unsafe containers before insert when possible.
     */
    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "3gp", "3gpp" -> "video/3gpp"
        "mpeg", "mpg" -> "video/mpeg"
        // Many devices reject video/x-matroska on Video.Media; treat as needing remux.
        else -> "video/mp4"
    }

    /** Extensions MediaStore.Video reliably accepts on current Pixel / AOSP. */
    fun isMediaStoreSafeExt(ext: String): Boolean = when (ext.lowercase()) {
        "mp4", "m4v", "webm", "3gp", "3gpp", "mpeg", "mpg" -> true
        else -> false
    }

    /** Copies [source] into Movies/TubeOffline and returns its content Uri. */
    fun save(
        context: Context,
        source: File,
        title: String,
        youTubeId: String? = null,
        uploadedEpochSec: Long? = null,
    ): Uri {
        val rawExt = source.extension.ifBlank { "mp4" }.lowercase()
        if (!isMediaStoreSafeExt(rawExt)) {
            throw UnsupportedMimeException(
                "Unsupported container .$rawExt for Movies/TubeOffline (MediaStore)",
            )
        }
        val ext = rawExt
        val mime = mimeFor(ext)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${sanitizeFileName(title)}.$ext")
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Video.Media.TITLE, title)
            UploadLabel.description(youTubeId, uploadedEpochSec)?.let {
                put(MediaStore.Video.Media.DESCRIPTION, it)
            }
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection(), values)
                ?: throw IllegalStateException("Could not create file in Movies/TubeOffline")
        } catch (e: IllegalArgumentException) {
            // MediaStore: "Unsupported MIME type …"
            if (e.message?.contains("mime", ignoreCase = true) == true) {
                throw UnsupportedMimeException(e.message ?: "Unsupported MIME type", e)
            }
            throw e
        }
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out, 1 shl 16) }
            } ?: throw IllegalStateException("Could not open output file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Throwable) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun list(context: Context): List<SavedVideo> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DESCRIPTION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val result = mutableListOf<SavedVideo>()
        context.contentResolver.query(
            collection(),
            projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_DIR%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val descriptionCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DESCRIPTION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val description = c.getString(descriptionCol)
                result += SavedVideo(
                    uri = ContentUris.withAppendedId(collection(), c.getLong(idCol)),
                    name = c.getString(nameCol) ?: "video",
                    youTubeId = UploadLabel.youTubeId(description),
                    sizeBytes = c.getLong(sizeCol),
                    durationMs = c.getLong(durCol),
                    dateAddedSec = c.getLong(dateCol),
                    uploadedEpochSec = UploadLabel.uploadedEpochSec(description),
                )
            }
        }
        return result
    }

    /**
     * Next library row after [uri] (same order as the main list: newest first).
     * [skip] can omit already-watched items for autoplay; if every later row is
     * skipped, returns null so playback stops instead of looping.
     */
    fun nextAfter(
        context: Context,
        uri: Uri,
        skip: (SavedVideo) -> Boolean = { false },
    ): SavedVideo? {
        val videos = list(context)
        val index = videos.indexOfFirst { it.uri == uri }
        if (index < 0) return null
        return videos.asSequence().drop(index + 1).firstOrNull { !skip(it) }
    }

    /**
     * Previous library row before [uri] (same order as the main list: newest first).
     * Does not wrap: returns null when [uri] is first or missing. [skip] mirrors
     * [nextAfter] for symmetry; manual prev/next leave it at the default.
     */
    fun previousBefore(
        context: Context,
        uri: Uri,
        skip: (SavedVideo) -> Boolean = { false },
    ): SavedVideo? {
        val videos = list(context)
        val index = videos.indexOfFirst { it.uri == uri }
        if (index <= 0) return null
        return videos.asSequence().take(index).toList().asReversed().firstOrNull { !skip(it) }
    }

    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        /** File belongs to an older install: the user has to confirm via this system dialog. */
        data class NeedsConfirmation(val intentSender: IntentSender) : DeleteResult
        data class Failed(val message: String) : DeleteResult
    }

    /**
     * Permanently removes the video. On Android 11+, MediaStore's first
     * [ContentResolver.delete] often only moves the row to trash; a second
     * delete that includes trashed rows purges it. [MediaStore.createDeleteRequest]
     * is used when we lack write access (also permanent, never trash).
     */
    fun delete(context: Context, uri: Uri): DeleteResult = try {
        permanentlyDelete(context, uri)
        DeleteResult.Deleted
    } catch (e: SecurityException) {
        when {
            Build.VERSION.SDK_INT >= 30 -> DeleteResult.NeedsConfirmation(
                MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
            )
            e is RecoverableSecurityException -> DeleteResult.NeedsConfirmation(e.userAction.actionIntent.intentSender)
            else -> DeleteResult.Failed(e.message ?: "Permission denied")
        }
    } catch (e: Exception) {
        DeleteResult.Failed(e.message ?: "Delete failed")
    }

    private fun permanentlyDelete(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        // Owned content is removed for good on the first delete. A blind second delete
        // against the now-missing Uri throws on Pixel / Android 11+ and was crashing the UI.
        resolver.delete(uri, null, null)
        if (Build.VERSION.SDK_INT < 30) return
        if (!existsIncludingTrashed(context, uri)) return
        val extras = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        runCatching { resolver.delete(uri, extras) }
    }

    private fun existsIncludingTrashed(context: Context, uri: Uri): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val extras = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Video.Media._ID),
                    extras,
                    null,
                )?.use { it.moveToFirst() } == true
            } else {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Video.Media._ID),
                    null,
                    null,
                    null,
                )?.use { it.moveToFirst() } == true
            }
        }.getOrDefault(false)
    }
}
