package com.music.myusiq

import com.music.myusiq.utils.YouTubeTitleCleaner
import com.music.myusiq.utils.TransliterationUtil
import com.music.myusiq.utils.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun testYouTubeTitleCleaner_CleanEzmp3Clutter() {
        val rawTitle = "Imagine Dragons - Believer [ ezmp3.cc ].mp3"
        val (artist, title) = YouTubeTitleCleaner.cleanAndSplit(rawTitle)

        assertEquals("Imagine Dragons", artist)
        assertEquals("Believer", title)
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

    @Test
    fun testUpdateManager_IsVersionNewer() {
        assertTrue(UpdateManager.isVersionNewer("1.0.0", "1.1.0"))
        assertTrue(UpdateManager.isVersionNewer("1.0.0", "v1.1.0"))
        assertTrue(UpdateManager.isVersionNewer("1.0.1", "1.1.0"))
        assertFalse(UpdateManager.isVersionNewer("1.1.0", "1.1.0"))
        assertFalse(UpdateManager.isVersionNewer("1.1.0", "v1.1.0"))
        assertFalse(UpdateManager.isVersionNewer("1.2.0", "1.1.0"))
        // Pre-release vs Stable tests
        assertTrue(UpdateManager.isVersionNewer("1.2.0-beta2", "1.2.0"))
        assertTrue(UpdateManager.isVersionNewer("1.2.0-beta1", "1.2.0-beta2"))
        assertFalse(UpdateManager.isVersionNewer("1.2.0", "1.2.0-beta2"))
    }

    @Test
    fun testTrackMetadataHealth_CleanTrackNotFlaggedAsMessy() {
        val cleanSong = com.music.myusiq.domain.models.Song(
            id = 1L,
            title = "Yellow",
            artist = "Coldplay",
            path = "/storage/emulated/0/Music/01 - Coldplay - Yellow.mp3",
            duration = 260000,
            albumId = 1L,
            album = "Parachutes",
            genre = "Rock",
            dateAdded = 0L,
            size = 5000000L
        )
        val isUnknownArtist = com.music.myusiq.utils.CloudMetadataResolver.isGenericArtist(cleanSong.artist)
        val hasFileExt = Regex("(?i)\\.(mp3|m4a|flac|wav|aac|ogg|wma)$").containsMatchIn(cleanSong.title)
        val isGenericTitle = Regex("(?i)^(track|audio|sound|recording)[_\\s\\-]*\\d*$").matches(cleanSong.title) || cleanSong.title.isBlank()
        val hasClutter = YouTubeTitleCleaner.cleanTitle(cleanSong.title) != cleanSong.title

        val isMessy = isUnknownArtist || hasFileExt || isGenericTitle || hasClutter
        assertFalse("Clean track with standard path should NOT be flagged as messy", isMessy)
    }

    @Test
    fun testTrackMetadataHealth_MessyTrackFlagged() {
        val messySong = com.music.myusiq.domain.models.Song(
            id = 2L,
            title = "Track 01 (Official Audio).mp3",
            artist = "Unknown Artist",
            path = "/storage/emulated/0/Music/Track 01.mp3",
            duration = 180000,
            albumId = 2L,
            album = "Unknown Album",
            genre = "",
            dateAdded = 0L,
            size = 3000000L
        )
        val isUnknownArtist = com.music.myusiq.utils.CloudMetadataResolver.isGenericArtist(messySong.artist)
        val hasFileExt = Regex("(?i)\\.(mp3|m4a|flac|wav|aac|ogg|wma)$").containsMatchIn(messySong.title)
        val isGenericTitle = Regex("(?i)^(track|audio|sound|recording)[_\\s\\-]*\\d*$").matches(messySong.title) || messySong.title.isBlank()
        val hasClutter = YouTubeTitleCleaner.cleanTitle(messySong.title) != messySong.title

        val isMessy = isUnknownArtist || hasFileExt || isGenericTitle || hasClutter
        assertTrue("Messy track with unknown artist and .mp3 in title should be flagged as messy", isMessy)
    }

    @Test
    fun testMetadataSanitizerUseCase_SanitizesMessySongAndExtractsArtistFromTitle() {
        val sanitizer = com.music.myusiq.domain.usecase.MetadataSanitizerUseCase()
        val messySong = com.music.myusiq.domain.models.Song(
            id = 3L,
            title = "Coldplay - Viva La Vida (Official Video) [4K] [y2mate.is].mp3",
            artist = "Unknown Artist",
            path = "/storage/emulated/0/Music/01_coldplay_viva_la_vida_y2mate.mp3",
            duration = 240000,
            albumId = 3L,
            album = "<unknown>",
            genre = "",
            dateAdded = 0L,
            size = 4000000L
        )

        val sanitized = sanitizer.sanitize(messySong, "2008", messySong.path, "240", 3L)

        assertEquals("Viva La Vida", sanitized.title)
        assertEquals("Coldplay", sanitized.artist)
        assertEquals("Unknown Album", sanitized.album)
    }

    @Test
    fun testYouTubeTitleCleaner_CleanArtistAndAlbum() {
        val rawArtist = "  Unknown Artist (v.a.)  "
        val rawAlbum = "  Unknown Album [audio]  "

        assertEquals("Unknown Artist", YouTubeTitleCleaner.cleanArtist(rawArtist))
        assertEquals("Unknown Album", YouTubeTitleCleaner.cleanAlbum(rawAlbum))

        val validArtist = " Coldplay_Official_ "
        val validAlbum = " Parachutes_Album_ "

        assertEquals("Coldplay Official", YouTubeTitleCleaner.cleanArtist(validArtist))
        assertEquals("Parachutes Album", YouTubeTitleCleaner.cleanAlbum(validAlbum))
    }

    @Test
    fun testYouTubeTitleCleaner_PreservesUnicodeLetters() {
        val japaneseTitle = "01 - 宇多田ヒカル - First Love (Official Audio).mp3"
        val (artist, title) = YouTubeTitleCleaner.cleanAndSplit(japaneseTitle)

        assertEquals("宇多田ヒカル", artist)
        assertEquals("First Love", title)
    }

    @Test
    fun testYouTubeTitleCleaner_FileSharingAlbumNamesSanitized() {
        val shareMeAlbum = "ShareMe"
        val shareItAlbum = "SHAREit"
        val xenderAlbum = "Xender"
        val snaptubeAlbum = "Snaptube"

        assertTrue(YouTubeTitleCleaner.isGenericAlbumName(shareMeAlbum))
        assertTrue(YouTubeTitleCleaner.isGenericAlbumName(shareItAlbum))
        assertTrue(YouTubeTitleCleaner.isGenericAlbumName(xenderAlbum))
        assertTrue(YouTubeTitleCleaner.isGenericAlbumName(snaptubeAlbum))

        assertEquals("Unknown Album", YouTubeTitleCleaner.cleanAlbum(shareMeAlbum))
        assertEquals("Unknown Album", YouTubeTitleCleaner.cleanAlbum(shareItAlbum))
        assertEquals("Unknown Album", YouTubeTitleCleaner.cleanAlbum(xenderAlbum))
        assertEquals("Unknown Album", YouTubeTitleCleaner.cleanAlbum(snaptubeAlbum))

        val sanitizer = com.music.myusiq.domain.usecase.MetadataSanitizerUseCase()
        val shareMeSong = com.music.myusiq.domain.models.Song(
            id = 4L,
            title = "Shape of You",
            artist = "Ed Sheeran",
            path = "/storage/emulated/0/ShareMe/Shape of You.mp3",
            duration = 233000,
            albumId = 4L,
            album = "ShareMe",
            genre = "",
            dateAdded = 0L,
            size = 3500000L
        )

        val sanitized = sanitizer.sanitize(shareMeSong, "2017", shareMeSong.path, "233", 4L)
        assertEquals("Unknown Album", sanitized.album)
    }
}
