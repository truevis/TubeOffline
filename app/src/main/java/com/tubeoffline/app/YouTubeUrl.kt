package com.tubeoffline.app

import java.net.URI

/**
 * Pulls a YouTube video link out of whatever the share sheet hands us
 * (often "Video title https://youtu.be/xyz?si=..." or just the URL) and normalises it.
 */
object YouTubeUrl {

    private val URL_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")

    private val YT_HOSTS = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com",
    )

    /** Returns the canonical watch URL (https://www.youtube.com/watch?v=ID), or null if none found. */
    fun extract(sharedText: String?): String? {
        if (sharedText.isNullOrBlank()) return null
        for (match in URL_REGEX.findAll(sharedText)) {
            val raw = match.value.trimEnd('.', ',', ')', ']', '!', '?', ';')
            val id = videoId(raw) ?: continue
            return "https://www.youtube.com/watch?v=$id"
        }
        return null
    }

    /** Extracts the 11-char video id from a YouTube URL, or null. */
    fun videoId(url: String): String? {
        val uri = try { URI(url) } catch (_: Exception) { return null }
        val host = uri.host?.lowercase() ?: return null
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }

        val candidate: String? = when {
            host == "youtu.be" || host == "www.youtu.be" -> segments.firstOrNull()
            host in YT_HOSTS -> when (segments.firstOrNull()) {
                "watch" -> queryParam(uri.rawQuery, "v")
                "shorts", "live", "embed", "v", "e" -> segments.getOrNull(1)
                null -> queryParam(uri.rawQuery, "v")
                else -> null
            }
            else -> null
        }
        return candidate?.takeIf { ID_REGEX.matches(it) }
    }

    private fun queryParam(rawQuery: String?, name: String): String? =
        rawQuery?.split('&')
            ?.map { it.split('=', limit = 2) }
            ?.firstOrNull { it[0] == name }
            ?.getOrNull(1)
}
