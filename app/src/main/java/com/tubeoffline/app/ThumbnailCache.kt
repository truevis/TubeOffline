package com.tubeoffline.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * App-private thumbnail files keyed by YouTube video id (`files/thumbs/<id>.jpg`).
 * Downloaded once when a video is saved (or first shown online), then read offline.
 */
object ThumbnailCache {
    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "thumbs").also { it.mkdirs() }

    private fun file(context: Context, videoId: String): File =
        File(dir(context), "$videoId.jpg")

    fun fileIfPresent(context: Context, videoId: String): File? {
        val f = file(context, videoId)
        return f.takeIf { it.isFile && it.length() > 0L }
    }

    /** Ensures a local copy exists; no-ops when already cached. Returns the file or null. */
    fun ensureCached(context: Context, videoId: String): File? {
        fileIfPresent(context, videoId)?.let { return it }
        val dest = file(context, videoId)
        val tmp = File(dest.parentFile, "${dest.name}.part")
        return runCatching {
            val conn = (URL("https://i.ytimg.com/vi/$videoId/hqdefault.jpg").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 16 * 1024) }
            }
            conn.disconnect()
            if (tmp.length() <= 0L) {
                tmp.delete()
                return@runCatching null
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            dest.takeIf { it.isFile && it.length() > 0L }
        }.getOrElse {
            tmp.delete()
            null
        }
    }

    fun loadBitmap(context: Context, videoId: String): Bitmap? {
        fileIfPresent(context, videoId)?.let { local ->
            BitmapFactory.decodeFile(local.absolutePath)?.let { return it }
        }
        ensureCached(context, videoId)?.let { local ->
            return BitmapFactory.decodeFile(local.absolutePath)
        }
        return null
    }

    fun delete(context: Context, videoId: String?) {
        if (videoId.isNullOrBlank()) return
        file(context, videoId).delete()
        File(dir(context), "$videoId.jpg.part").delete()
    }
}
