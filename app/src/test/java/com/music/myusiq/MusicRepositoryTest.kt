package com.music.myusiq

import com.music.myusiq.data.MusicRepository
import com.music.myusiq.data.MusicScanner
import com.music.myusiq.data.RawSong
import com.music.myusiq.data.dao.PlaylistDao
import com.music.myusiq.data.dao.SongDao
import com.music.myusiq.data.dao.SongHistoryDao
import com.music.myusiq.domain.models.FolderItem
import com.music.myusiq.domain.models.SongOverride
import com.music.myusiq.utils.PlatformUtils
import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [MusicRepository] library filtering and folder logic.
 *
 * [PlatformUtils.normalizePath] performs Android-specific path resolution that is
 * not reproducible on the JVM (drive resolution, storage aliases), so it is mocked
 * to an identity function. All test paths are POSIX-style to keep comparisons
 * deterministic across host operating systems.
 */
class MusicRepositoryTest {

    @MockK
    lateinit var songDao: SongDao

    @MockK(relaxed = true)
    lateinit var playlistDao: PlaylistDao

    @MockK(relaxed = true)
    lateinit var songHistoryDao: SongHistoryDao

    @MockK
    lateinit var scanner: MusicScanner

    private lateinit var repository: MusicRepository

    private val storage = "/storage/emulated/0"

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        mockkObject(PlatformUtils)
        every { PlatformUtils.normalizePath(any()) } answers { firstArg() }

        every { scanner.getBlacklistedFolderPaths() } returns emptySet()
        every { scanner.getAllowedFolderUris() } returns emptySet()
        coEvery { scanner.scanAllSongs() } returns emptyList()
        coEvery { songDao.getAllOverrides() } returns emptyList()

        repository = MusicRepository(songDao, playlistDao, songHistoryDao, scanner)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun song(
        id: Long,
        title: String,
        artist: String,
        path: String,
        duration: Int = 200_000,
        album: String = "Album",
        genre: String = "Rock",
    ) = RawSong(
        id = id,
        title = title,
        artist = artist,
        path = path,
        duration = duration,
        albumId = 1L,
        album = album,
        genre = genre,
        dateAdded = 0L,
        size = 1_000L,
    )

    // ---------------------------------------------------------------- short audio

    @Test
    fun `short audio is filtered when the smart noise filter is on`() = runTest {
        coEvery { scanner.scanAllSongs() } returns listOf(
            song(1, "Ringtone", "A", "$storage/Music/short.mp3", duration = 5_000),
            song(2, "Track", "A", "$storage/Music/long.mp3"),
        )

        val result = repository.getSongs(filterShortAudio = true)

        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun `short audio is kept when the smart noise filter is off`() = runTest {
        coEvery { scanner.scanAllSongs() } returns listOf(
            song(1, "Ringtone", "A", "$storage/Music/short.mp3", duration = 5_000),
        )

        val result = repository.getSongs(filterShortAudio = false)

        assertEquals(1, result.size)
    }

    // ---------------------------------------------------------------- metadata overrides & fallbacks

    @Test
    fun `saved overrides replace scanned metadata`() = runTest {
        val songPath = "$storage/Music/song.mp3"
        coEvery { scanner.scanAllSongs() } returns listOf(song(1, "youtube garbage", "", songPath, album = ""))
        coEvery { songDao.getAllOverrides() } returns listOf(
            SongOverride(
                path = songPath.lowercase(),
                title = "Real Title",
                artist = "Real Artist",
                album = "Real Album",
                genre = "Jazz",
            ),
        )

        val result = repository.getSongs(filterShortAudio = false)

        assertEquals("Real Title", result[0].title)
        assertEquals("Real Artist", result[0].artist)
        assertEquals("Real Album", result[0].album)
        assertEquals("Jazz", result[0].genre)
    }

    @Test
    fun `empty scanned metadata falls back to unknown placeholders`() = runTest {
        coEvery { scanner.scanAllSongs() } returns listOf(
            song(1, "", "", "$storage/Music/song.mp3", album = "", genre = ""),
        )

        val result = repository.getSongs(filterShortAudio = false)

        assertEquals("Unknown Title", result[0].title)
        assertEquals("Unknown Artist", result[0].artist)
        assertEquals("Unknown Album", result[0].album)
        assertEquals("Unknown Genre", result[0].genre)
    }

    // ---------------------------------------------------------------- blacklisting

    @Test
    fun `songs inside a blacklisted folder are excluded`() = runTest {
        every { scanner.getBlacklistedFolderPaths() } returns setOf("$storage/Music/Hidden")

        coEvery { scanner.scanAllSongs() } returns listOf(
            song(1, "Hidden Song", "A", "$storage/Music/Hidden/secret.mp3"),
            song(2, "Visible Song", "A", "$storage/Music/public.mp3"),
        )

        val result = repository.getSongs(filterShortAudio = false)

        assertEquals(listOf(2L), result.map { it.id })
    }

    // ---------------------------------------------------------------- allowed folders

    @Test
    fun `songs outside allowed folders are excluded when allowed folders are set`() = runTest {
        // Allowlist entries are compared case-sensitively against the lowercased song path
        every { scanner.getAllowedFolderUris() } returns setOf("$storage/music")

        coEvery { scanner.scanAllSongs() } returns listOf(
            song(1, "In Music", "A", "$storage/Music/good.mp3"),
            song(2, "In Downloads", "A", "$storage/Download/other.mp3"),
        )

        val result = repository.getSongs(filterShortAudio = false)

        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `all songs pass when no scanned song matches the allowed folders`() = runTest {
        // Repository treats a fully unmatched allowlist as "not configured" and does not hide the library
        every { scanner.getAllowedFolderUris() } returns setOf("$storage/Nope")

        coEvery { scanner.scanAllSongs() } returns listOf(
            song(1, "Track", "A", "$storage/Music/good.mp3"),
        )

        val result = repository.getSongs(filterShortAudio = false)

        assertEquals(1, result.size)
    }

    // ---------------------------------------------------------------- folder items

    @Test
    fun `folder items aggregate per parent folder and sort by name`() = runTest {
        coEvery { scanner.scanAllSongs() } returns listOf(
            song(1, "A1", "A", "$storage/Music/Alpha/one.mp3"),
            song(2, "A2", "A", "$storage/Music/Alpha/two.mp3"),
            song(3, "B1", "B", "$storage/Music/beta/one.mp3"),
        )

        val items: List<FolderItem> = repository.getFolderItems()

        assertEquals(listOf("Alpha", "beta"), items.map { it.folderName })
        assertEquals(2, items[0].songCount)
        assertEquals(1, items[1].songCount)
    }

    @Test
    fun `folder item included flag respects allowlist and blacklist`() = runTest {
        every { scanner.getAllowedFolderUris() } returns setOf("$storage/music")
        every { scanner.getBlacklistedFolderPaths() } returns setOf("$storage/Music/Hidden")

        coEvery { scanner.scanAllSongs() } returns listOf(
            song(1, "A1", "A", "$storage/Music/Alpha/one.mp3"),
            song(2, "H1", "H", "$storage/Music/Hidden/one.mp3"),
        )

        val items = repository.getFolderItems().associateBy { it.absolutePath }

        assertTrue(items["$storage/Music/Alpha"]!!.isIncluded)
        assertFalse(items["$storage/Music/Hidden"]!!.isIncluded)
    }

    // ---------------------------------------------------------------- path helpers

    @Test
    fun `isPathInAllowedFolders passes everything when the allowlist is empty`() {
        assertTrue(repository.isPathInAllowedFolders("$storage/Anywhere/x.mp3", emptySet()))
    }

    @Test
    fun `isPathInAllowedFolders matches by prefix`() {
        val allowed = setOf("$storage/music")
        assertTrue(repository.isPathInAllowedFolders("$storage/Music/x.mp3", allowed))
        assertFalse(repository.isPathInAllowedFolders("$storage/Download/x.mp3", allowed))
    }
}
