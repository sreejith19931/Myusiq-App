package com.music.myusiq.data

import com.music.myusiq.domain.models.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppDatabaseMigrationTest {

    @Test
    fun testAppDatabase_StructureAndModels() {
        val databaseClass = AppDatabase::class.java
        assertNotNull("AppDatabase class must exist", databaseClass)
        assertEquals("AppDatabase", databaseClass.simpleName)

        val daos = databaseClass.declaredMethods.map { it.name }
        assertEquals(true, daos.contains("songDao"))
        assertEquals(true, daos.contains("playlistDao"))
        assertEquals(true, daos.contains("songHistoryDao"))
        assertEquals(true, daos.contains("searchHistoryDao"))
    }

    @Test
    fun testSongModel_SchemaDefaults() {
        val song = Song(
            id = 101L,
            title = "Test Track",
            artist = "Test Artist",
            path = "/storage/emulated/0/Music/test.mp3",
            duration = 180000,
            albumId = 55L,
            album = "Test Album",
            genre = "Rock",
            dateAdded = System.currentTimeMillis(),
            size = 5000000L
        )

        assertEquals(101L, song.id)
        assertEquals("Test Track", song.title)
        assertEquals("Test Artist", song.artist)
        assertEquals("/storage/emulated/0/Music/test.mp3", song.path)
    }

    @Test
    fun testSongOverrideModel_Instantiation() {
        val override = SongOverride(
            path = "/storage/emulated/0/Music/test.mp3",
            title = "Overridden Title",
            artist = "Overridden Artist",
            album = "Overridden Album",
            year = "2024",
            genre = "Pop"
        )

        assertEquals("/storage/emulated/0/Music/test.mp3", override.path)
        assertEquals("Overridden Title", override.title)
        assertEquals("Overridden Artist", override.artist)
    }

    @Test
    fun testLyricsOverrideModel_Instantiation() {
        val lyricsOverride = LyricsOverride(
            songPath = "/storage/emulated/0/Music/test.mp3",
            rawLyrics = "[00:10.00]Hello world\n[00:15.00]I love music"
        )

        assertEquals("/storage/emulated/0/Music/test.mp3", lyricsOverride.songPath)
        assertEquals("[00:10.00]Hello world\n[00:15.00]I love music", lyricsOverride.rawLyrics)
    }

    @Test
    fun testDatabaseMigrations_VersionsDefined() {
        val migration11To12 = com.music.myusiq.DatabaseProvider.MIGRATION_11_12
        assertEquals(11, migration11To12.startVersion)
        assertEquals(12, migration11To12.endVersion)

        val migration12To13 = com.music.myusiq.DatabaseProvider.MIGRATION_12_13
        assertEquals(12, migration12To13.startVersion)
        assertEquals(13, migration12To13.endVersion)

        val migration11To13 = com.music.myusiq.DatabaseProvider.MIGRATION_11_13
        assertEquals(11, migration11To13.startVersion)
        assertEquals(13, migration11To13.endVersion)
    }
}
