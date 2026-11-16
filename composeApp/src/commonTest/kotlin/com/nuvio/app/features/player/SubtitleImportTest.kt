package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleImportTest {
    @Test
    fun acceptsDirectHttpSubtitleUrlsOnly() {
        assertTrue(isHttpSubtitleUrl("https://example.org/captions/movie.srt"))
        assertTrue(isHttpSubtitleUrl("http://example.org/captions/movie.vtt?token=abc"))
        assertFalse(isHttpSubtitleUrl("file:///captions/movie.srt"))
        assertFalse(isHttpSubtitleUrl("https:///captions/movie.srt"))
        assertFalse(isHttpSubtitleUrl("https://example.org/captions/my captions.srt"))
        assertFalse(isHttpSubtitleUrl("https://user@example.org/captions/movie.srt"))
    }

    @Test
    fun validatesSubtitleContentAndNormalizesLanguage() {
        val subtitle = importedSubtitleFromText(
            id = "url:https://example.org/movie.srt",
            name = "movie.srt",
            url = "https://example.org/movie.srt",
            language = "ar",
            text = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا\n",
        )

        assertNotNull(subtitle)
        assertEquals("ar", subtitle.language)
        assertEquals("movie.srt", subtitle.display)
    }

    @Test
    fun rejectsNonSubtitleContent() {
        assertNull(
            importedSubtitleFromText(
                id = "url:https://example.org/page",
                name = "page",
                url = "https://example.org/page",
                language = "en",
                text = "<html><body>not a subtitle</body></html>",
            ),
        )
    }
}
