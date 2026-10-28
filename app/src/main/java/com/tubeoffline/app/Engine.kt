package com.tubeoffline.app

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** Wraps youtubedl-android (bundled Python + yt-dlp + ffmpeg + QuickJS). */
object Engine {
    @Volatile private var initialized = false
    @Volatile private var refreshAttempted = false

    /** yt-dlp builds older than this get refused by YouTube (403 / SABR) and need a GitHub update. */
    private const val MAX_ENGINE_AGE_DAYS = 60L

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
     * Downloads a current yt-dlp when this install has never been updated, or the recorded
     * version is older than [MAX_ENGINE_AGE_DAYS]. One attempt per process; a failed update
     * does not block the download. [onUpdating] runs only when a download is about to start.
     */
    fun refreshIfStale(context: Context, onUpdating: () -> Unit = {}) {
        ensureInit(context)
        if (refreshAttempted) return
        refreshAttempted = true
        val name = version(context)
        if (name != null && !isStaleVersion(name)) return
        onUpdating()
        try {
            update(context)
        } catch (_: Throwable) {
        }
    }

    /** True when [versionName] has no yt-dlp date, or that date is older than [MAX_ENGINE_AGE_DAYS]. */
    internal fun isStaleVersion(versionName: String, today: LocalDate = LocalDate.now()): Boolean {
        val match = Regex("""(20\d{2})\.(\d{2})\.(\d{2})""").find(versionName) ?: return true
        val released = try {
            LocalDate.parse("${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}")
        } catch (_: DateTimeParseException) {
            return true
        }
        return released.isBefore(today.minusDays(MAX_ENGINE_AGE_DAYS))
    }

    /**
     * Smallest file that still has picture and English speech.
     * `worstaudio` alone prefers dubbed tracks, because yt-dlp sorts those as worse than the original.
     * 1) smallest mp4 video + smallest English m4a (merged to mp4)
     * 2) smallest video + smallest English audio of any container
     * 3) same, but the original / YouTube-default track when the video has no English audio
     * 4) any audio, then a pre-muxed file, for videos that don't label a language
     */
    const val FORMAT_LOWEST =
        "worstvideo[ext=mp4]+worstaudio[ext=m4a][language^=en][language!*=desc]/" +
            "worstvideo+worstaudio[language^=en][language!*=desc]/" +
            "worstvideo[ext=mp4]+worstaudio[ext=m4a][language_preference>=5]/" +
            "worstvideo+worstaudio[language_preference>=5]/" +
            "worstvideo[ext=mp4]+worstaudio[ext=m4a]/" +
            "worstvideo+worstaudio/" +
            "worst[ext=mp4][vcodec!=none][acodec!=none]/" +
            "worst[vcodec!=none][acodec!=none]"

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
            // 403 text also contains "unable to download", so it has to be matched first.
            "http error 403" in lower || "sabr streaming" in lower ->
                "YouTube refused the download (403). Tap 'Update download engine' and try again."
            "no address associated" in lower || "failed to resolve" in lower ||
                "network is unreachable" in lower || "timed out" in lower ||
                "connection refused" in lower || "failed to establish a new connection" in lower ->
                "Network problem – check your connection and retry."
            "no space left" in lower -> "Phone storage is full."
            else -> null
        }
        return listOfNotNull(hint, errorLine?.take(300)).distinct().joinToString("\n")
            .ifBlank { text.trim().lines().lastOrNull()?.take(300) ?: "Unknown error" }
    }
}
