package com.tubeoffline.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlTest {
    private val expected = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    @Test fun watchUrl() = assertEquals(expected, YouTubeUrl.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
    @Test fun mobileUrl() = assertEquals(expected, YouTubeUrl.extract("https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
    @Test fun shortUrl() = assertEquals(expected, YouTubeUrl.extract("https://youtu.be/dQw4w9WgXcQ?si=abcdEFGH"))
    @Test fun shorts() = assertEquals(expected, YouTubeUrl.extract("https://youtube.com/shorts/dQw4w9WgXcQ?si=x"))
    @Test fun titleThenUrl() = assertEquals(
        expected,
        YouTubeUrl.extract("My favourite video title | Some Channel\nhttps://youtu.be/dQw4w9WgXcQ"),
    )
    @Test fun playlistParamIgnored() = assertEquals(
        expected,
        YouTubeUrl.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123&index=2"),
    )
    @Test fun notYouTube() = assertNull(YouTubeUrl.extract("look https://example.com/watch?v=dQw4w9WgXcQ"))
    @Test fun noUrl() = assertNull(YouTubeUrl.extract("just some text"))
}
