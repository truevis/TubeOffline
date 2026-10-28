package com.tubeoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class EngineTest {
    private val today = LocalDate.of(2026, 10, 28)

    @Test fun currentReleaseIsNotStale() {
        assertFalse(Engine.isStaleVersion("yt-dlp 2026.10.26", today))
    }

    @Test fun bundledReleaseIsStale() {
        assertTrue(Engine.isStaleVersion("2025.11.12", today))
    }

    @Test fun missingDateIsStale() {
        assertTrue(Engine.isStaleVersion("unknown", today))
    }

    @Test fun forbiddenIsNotReportedAsNetwork() {
        val raw = """
            WARNING: [youtube] Some web client https formats have been skipped as they are missing a url. YouTube is forcing SABR streaming for this client.
            ERROR: unable to download video data: HTTP Error 403: Forbidden
        """.trimIndent()
        assertEquals(
            "YouTube refused the download (403). Try again later.\n" +
                "unable to download video data: HTTP Error 403: Forbidden",
            Engine.readableError(raw),
        )
    }

    @Test fun formatPrefersEnglishBeforeAnyAudio() {
        val english = Engine.FORMAT_LOWEST.indexOf("language^=en")
        val anyAudio = Engine.FORMAT_LOWEST.indexOf("worstaudio[ext=m4a]/")
        assertTrue(english >= 0)
        assertTrue(anyAudio > english)
    }

    @Test fun dnsFailureIsNetwork() {
        val raw = "ERROR: [youtube] abc: Unable to download API page: [Errno 7] No address associated with hostname"
        assertEquals(
            "Network problem – check your connection and retry.\n" +
                "Unable to download API page: [Errno 7] No address associated with hostname",
            Engine.readableError(raw),
        )
    }
}
