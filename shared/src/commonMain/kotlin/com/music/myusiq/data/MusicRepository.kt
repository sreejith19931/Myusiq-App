package com.music.myusiq.data

import com.music.myusiq.data.dao.PlaylistDao
import com.music.myusiq.data.dao.SongDao
import com.music.myusiq.data.dao.SongHistoryDao
import com.music.myusiq.domain.models.*
import com.music.myusiq.utils.PlatformUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MusicRepository(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val songHistoryDao: SongHistoryDao,
    private val musicScanner: MusicScanner,
) {
    suspend fun getSongs(filterShortAudio: Boolean): List<Song> = withContext(Dispatchers.Default) {
        val rawSongs = musicScanner.scanAllSongs()
        val blacklistedPaths = musicScanner.getBlacklistedFolderPaths().asSequence().map { PlatformUtils.normalizePath(it).lowercase() }.toSet()
        val allowedPaths = musicScanner.getAllowedFolderUris()
        
        val overrides = songDao.getAllOverrides().associateBy { it.path }
        
        rawSongs.asSequence().filter { raw ->
            val normPath = PlatformUtils.normalizePath(raw.path)
            val normPathLower = normPath.lowercase()
            
            // Filter short audio
            if (filterShortAudio && (raw.duration in 1..29999)) return@filter false
            
            // Check allowed folders
            val isAllowed = allowedPaths.isEmpty() || allowedPaths.any { normPathLower.startsWith(it) }
            if (!isAllowed) return@filter false
            
            // Check blacklist
            val parentFolder = normPath.substringBeforeLast('/', "").lowercase()
            if (blacklistedPaths.contains(parentFolder)) return@filter false
            
            true
        }.map { raw ->
            val normPath = PlatformUtils.normalizePath(raw.path)
            val override = overrides[normPath]
            
            Song(
                id = raw.id,
                title = override?.title ?: raw.title.ifEmpty { "Unknown Title" },
                artist = override?.artist ?: raw.artist.ifEmpty { "Unknown Artist" },
                path = normPath,
                duration = raw.duration,
                albumId = raw.albumId,
                album = override?.album ?: raw.album.ifEmpty { "Unknown Album" },
                genre = override?.genre ?: raw.genre.ifEmpty { "Unknown Genre" },
                dateAdded = raw.dateAdded,
                size = raw.size
            )
        }.toList()
    }

    suspend fun getFolderItems(): List<FolderItem> = withContext(Dispatchers.Default) {
        val songs = musicScanner.scanAllSongs()
        val blacklistedPaths = musicScanner.getBlacklistedFolderPaths()
        val allowedPaths = musicScanner.getAllowedFolderUris()

        val folderMap = mutableMapOf<String, Int>()
        songs.forEach { song ->
            val lastSlash = song.path.lastIndexOf('/')
            val parent = if (lastSlash != -1) song.path.substring(0, lastSlash) else ""
            if (parent.isNotEmpty()) {
                folderMap[parent] = (folderMap[parent] ?: 0) + 1
            }
        }

        folderMap.map { (path, count) ->
            val isAllowed = allowedPaths.isEmpty() || allowedPaths.any { path.lowercase().startsWith(it.lowercase()) }
            val isBlacklisted = blacklistedPaths.contains(path)
            FolderItem(path, count, isAllowed && !isBlacklisted)
        }.sortedBy { it.folderName.lowercase() }
    }

    // DAO delegation methods
    suspend fun getAllOverrides() = songDao.getAllOverrides()
    suspend fun deleteOverride(path: String) = songDao.deleteOverride(path)
    suspend fun upsertOverride(override: SongOverride) = songDao.upsertOverride(override)
    suspend fun getLyricsForPath(path: String) = songDao.getLyricsForPath(path)
    suspend fun upsertLyrics(lyrics: LyricsOverride) = songDao.upsertLyrics(lyrics)
    suspend fun deleteLyrics(path: String) = songDao.deleteLyrics(path)
    suspend fun deleteAllLyrics() = songDao.deleteAllLyricsOverrides()
    suspend fun getAllLyricsOverridesSync() = songDao.getAllLyricsOverridesSync()
    suspend fun updateLyricsPath(oldPath: String, newPath: String) = songDao.updateLyricsPath(oldPath, newPath)
    suspend fun upsertTrack(track: TrackEntity) = playlistDao.insertTrack(track)
    fun getFavoriteTracks() = playlistDao.getFavoriteTracks()

    fun getAllPlaylists() = playlistDao.getAllPlaylists()
    fun getPlaylistsWithTracks() = playlistDao.getPlaylistsWithTracks()
    suspend fun isTrackFavorite(trackId: String) = playlistDao.isTrackFavorite(trackId)
    suspend fun getTrackByIdSync(trackId: String) = playlistDao.getTrackByIdSync(trackId)
    suspend fun updateFavoriteStatus(trackId: String, isFavorite: Boolean) = playlistDao.updateFavoriteStatus(trackId, isFavorite)
    suspend fun createPlaylist(playlist: Playlist) = playlistDao.createPlaylist(playlist)
    suspend fun addTrackToPlaylist(crossRef: PlaylistTrackCrossRef) = playlistDao.addTrackToPlaylist(crossRef)
    suspend fun deletePlaylist(playlistId: Long) = playlistDao.deletePlaylist(playlistId)
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) = playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    fun getPlaylistWithTracks(playlistId: Long) = playlistDao.getPlaylistWithTracks(playlistId)

    suspend fun getLastPlayedTimestamps() = songHistoryDao.getLastPlayedTimestamps()
    suspend fun getAllPlayCounts() = songHistoryDao.getAllPlayCounts()
    suspend fun getTopArtists(limit: Int): List<TopArtist> = songHistoryDao.getTopArtists(limit)
    suspend fun clearHistory() = songHistoryDao.deleteAllHistory()

    suspend fun getRecentSongsByTimeRange(startHour: Int, endHour: Int, limit: Int = 20): List<String> {
        val s = startHour.toString().padStart(2, '0')
        val e = endHour.toString().padStart(2, '0')
        return songHistoryDao.getSongIdsByTimeRange(s, e, limit).map { it.songId }
    }

    // Folder management
    fun toggleFolderBlacklist(path: String, isIncluded: Boolean) = musicScanner.toggleFolderBlacklist(path, isIncluded)
    fun getAllowedFolderUris() = musicScanner.getAllowedFolderUris()
    fun addAllowedFolderUri(path: String) = musicScanner.addAllowedFolderUri(path)
    fun isPathInAllowedFolders(path: String, allowedUris: Set<String>): Boolean {
        if (allowedUris.isEmpty()) return true
        val normalizedPath = PlatformUtils.normalizePath(path).lowercase()
        return allowedUris.any { normalizedPath.startsWith(it) }
    }
}
