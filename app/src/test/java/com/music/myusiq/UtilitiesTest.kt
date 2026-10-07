package com.music.myusiq

import com.music.myusiq.utils.YouTubeTitleCleaner
import com.music.myusiq.utils.TransliterationUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class UtilitiesTest {

    @Test
    fun testYouTubeTitleCleaner_CleanAndSplit() {
        val rawTitle = "Alan Walker - Faded (Official Music Video) [4K]"
        val (artist, title) = YouTubeTitleCleaner.cleanAndSplit(rawTitle)

        assertEquals("Alan Walker", artist)
        assertEquals("Faded", title)
    }

    @Test
    fun testYouTubeTitleCleaner_CleanTitleOnly() {
        val rawTitle = "Song Title (Official Lyric Video).mp3"
        val cleaned = YouTubeTitleCleaner.cleanTitle(rawTitle)

        assertEquals("Song Title", cleaned)
    }

    @Test
    fun testTransliterationUtil_FallbackOrTransliterate() {
        val input = "Namaste"
        val output = TransliterationUtil.transliterate(input)
        assertNotNull(output)
    }

    @Test
    fun testTransliterationUtil_EnglishLyricsRemainUnchanged() {
        val englishLyrics = "[00:10.00]Hello world\n[00:15.00]I love music"
        val output = TransliterationUtil.transliterateLyrics(englishLyrics)
        assertEquals(englishLyrics, output)
    }

    @Test
    fun testTransliterationUtil_TransliteratedLyricsReplaceOriginals() {
        val rawLrc = "[00:10.00]Hello world"
        val transliterated = TransliterationUtil.transliterateLyrics(rawLrc)
        // Ensure format remains timestamped line
        assertNotNull(transliterated)
        assertEquals(true, transliterated.startsWith("[00:10.00]"))
    }
}
