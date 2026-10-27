package com.tubeoffline.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

/** Wraps youtubedl-android (bundled Python + yt-dlp + ffmpeg + QuickJS). */
object Engine {
    @Volatile private var initialized = false

    /** First call unpacks Python/ffmpeg (a few seconds). Must be called off the main thread. */
    @Synchronized
    fun ensureInit(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        initialized = true
    }

    /** Updates the bundled yt-dlp to the newest stable release from GitHub. Blocking. */
    fun update(context: Context): String {
        ensureInit(context)
        val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
        val version = YoutubeDL.getInstance().versionName(context.applicationContext) ?: "unknown"
        return when (status) {
            YoutubeDL.UpdateStatus.DONE -> "Download engine updated to yt-dlp $version"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date (yt-dlp $version)"
            null -> "Update finished (yt-dlp $version)"
        }
    }

    fun version(context: Context): String? = try {
        YoutubeDL.getInstance().versionName(context.applicationContext)
    } catch (_: Throwable) {
        null
    }

    /**
     * Smallest file that still has BOTH picture and sound.
     * 1) worst video-only mp4 + worst m4a audio (merged with ffmpeg into mp4) – plays everywhere
     * 2) worst video + worst audio of any container (still merged to mp4)
     * 3) worst pre-muxed format that has both streams
     */
    const val FORMAT_LOWEST =
        "worstvideo[ext=mp4]+worstaudio[ext=m4a]/worstvideo+worstaudio/worst[ext=mp4][vcodec!=none][acodec!=none]/worst[vcodec!=none][acodec!=none]"

    /** Turns yt-dlp's stderr into something readable for a notification. */
    fun readableError(raw: String?): String {
        val text = raw.orEmpty()
        val errorLine = text.lines().lastOrNull { it.contains("ERROR:") }
            ?.substringAfter("ERROR:")?.trim()
            ?.replace(Regex("""^\[[^\]]+]\s*[A-Za-z0-9_-]+:\s*"""), "")
        val lower = text.lowercase()
        val hint = when {
            "sign in to confirm" in lower && "bot" in lower ->
                "YouTube is asking for a bot check. Tap 'Update download engine' and try again later."
            "sign in to confirm your age" in lower || "age-restricted" in lower ->
                "Age-restricted video – can't be downloaded without signing in."
            "private video" in lower -> "This video is private."
            "members-only" in lower || "join this channel" in lower -> "Members-only video."
            "video unavailable" in lower -> "Video unavailable (removed or region-blocked)."
            "requested format is not available" in lower ->
                "No downloadable format found. Try 'Update download engine'."
            "unable to download" in lower || "timed out" in lower || "network is unreachable" in lower ||
                "failed to resolve" in lower || "connection" in lower ->
                "Network problem – check your connection and retry."
            "http error 403" in lower -> "YouTube refused the download (403). Try 'Update download engine'."
            "no space left" in lower -> "Phone storage is full."
            else -> null
        }
        return listOfNotNull(hint, errorLine?.take(300)).distinct().joinToString("\n")
            .ifBlank { text.trim().lines().lastOrNull()?.take(300) ?: "Unknown error" }
    }
}
