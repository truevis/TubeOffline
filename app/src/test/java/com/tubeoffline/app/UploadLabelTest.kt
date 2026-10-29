package com.tubeoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UploadLabelTest {
    @Test fun descriptionRoundTrip() {
        val text = UploadLabel.description("dQw4w9WgXcQ", 1_700_000_000L)
        assertEquals("youtube:dQw4w9WgXcQ uploaded:1700000000", text)
        assertEquals("dQw4w9WgXcQ", UploadLabel.youTubeId(text))
        assertEquals(1_700_000_000L, UploadLabel.uploadedEpochSec(text))
    }

    @Test fun idOnlyWhenNoDate() {
        val text = UploadLabel.description("abc", null)
        assertEquals("youtube:abc", text)
        assertEquals("abc", UploadLabel.youTubeId(text))
        assertNull(UploadLabel.uploadedEpochSec(text))
    }

    @Test fun legacyDescriptionHasNoDate() {
        assertEquals("oldId", UploadLabel.youTubeId("youtube:oldId"))
        assertNull(UploadLabel.uploadedEpochSec("youtube:oldId"))
        assertNull(UploadLabel.youTubeId(null))
        assertNull(UploadLabel.youTubeId("not youtube"))
    }

    @Test fun prefersTimestampThenReleaseThenDate() {
        assertEquals(100L, UploadLabel.epochFromYoutube(100L, 200L, "20200101"))
        assertEquals(200L, UploadLabel.epochFromYoutube(0L, 200L, "20200101"))
        assertEquals(200L, UploadLabel.epochFromYoutube(null, 200L, null))
        // 2020-01-02 00:00:00 UTC
        assertEquals(1_577_923_200L, UploadLabel.epochFromYoutube(null, null, "20200102"))
        assertNull(UploadLabel.epochFromYoutube(null, null, "nope"))
        assertNull(UploadLabel.epochFromYoutube(null, 0L, null))
    }

    @Test fun relativeBuckets() {
        val now = 10_000_000L
        assertEquals("0m ago", UploadLabel.relative(now, now))
        assertEquals("5m ago", UploadLabel.relative(now - 5 * 60, now))
        assertEquals("6h ago", UploadLabel.relative(now - 6 * 3600, now))
        assertEquals("1d ago", UploadLabel.relative(now - 86400, now))
        assertEquals("2w ago", UploadLabel.relative(now - 14 * 86400, now))
        assertEquals("3mo ago", UploadLabel.relative(now - 90 * 86400, now))
        assertEquals("1y ago", UploadLabel.relative(now - 365 * 86400, now))
        assertEquals("0m ago", UploadLabel.relative(now + 50, now))
    }
}
