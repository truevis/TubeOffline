package com.tubeoffline.app

import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * YouTube upload time stored beside the video id in MediaStore DESCRIPTION:
 * "youtube:<id>" or "youtube:<id> uploaded:<unix seconds>".
 * Older downloads only have the id, so they have no upload time.
 */
object UploadLabel {
    private const val PREFIX = "youtube:"
    private const val UPLOADED = "uploaded:"

    fun description(youTubeId: String?, uploadedEpochSec: Long?): String? {
        val id = youTubeId?.takeIf { it.isNotBlank() } ?: return null
        val stamp = uploadedEpochSec?.takeIf { it > 0L } ?: return "$PREFIX$id"
        return "$PREFIX$id $UPLOADED$stamp"
    }

    fun youTubeId(description: String?): String? {
        if (description == null || !description.startsWith(PREFIX)) return null
        return description.removePrefix(PREFIX)
            .substringBefore(' ')
            .substringBefore('\n')
            .takeIf { it.isNotBlank() }
    }

    fun uploadedEpochSec(description: String?): Long? {
        if (description == null) return null
        val raw = description.substringAfter(" $UPLOADED", "")
            .substringBefore(' ')
            .substringBefore('\n')
        return raw.toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * Unix seconds from yt-dlp. Prefers [timestamp], then [releaseTimestamp],
     * then [uploadDate] (`YYYYMMDD`, start of that UTC day).
     */
    fun epochFromYoutube(timestamp: Long?, releaseTimestamp: Long?, uploadDate: String?): Long? {
        timestamp?.takeIf { it > 0L }?.let { return it }
        releaseTimestamp?.takeIf { it > 0L }?.let { return it }
        if (uploadDate == null || uploadDate.length != 8 || !uploadDate.all { it.isDigit() }) return null
        return try {
            LocalDate.parse(uploadDate, DateTimeFormatter.BASIC_ISO_DATE)
                .atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond()
        } catch (_: Throwable) {
            null
        }
    }

    /** Compact relative age, YouTube-style: `6h ago`, `1d ago`, `2w ago`, `3mo ago`, `1y ago`. */
    fun relative(uploadedEpochSec: Long, nowEpochSec: Long): String {
        val delta = (nowEpochSec - uploadedEpochSec).coerceAtLeast(0L)
        val minute = 60L
        val hour = 60 * minute
        val day = 24 * hour
        val week = 7 * day
        val month = 30 * day
        val year = 365 * day
        return when {
            delta >= year -> "${delta / year}y ago"
            delta >= month -> "${delta / month}mo ago"
            delta >= week -> "${delta / week}w ago"
            delta >= day -> "${delta / day}d ago"
            delta >= hour -> "${delta / hour}h ago"
            else -> "${delta / minute}m ago"
        }
    }
}
