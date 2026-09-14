package com.music.rhythm.data.dao

import androidx.room.*
import com.music.rhythm.domain.models.Playlist
import com.music.rhythm.domain.models.PlaylistTrackCrossRef
import com.music.rhythm.domain.models.PlaylistWithTracks
import com.music.rhythm.domain.models.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM library_tracks WHERE isFavorite = 1")
    fun getFavoriteTracks(): Flow<List<TrackEntity>>
    @Query("UPDATE library_tracks SET isFavorite = :isFav WHERE id = :trackId")
    suspend fun updateFavoriteStatus(trackId: String, isFav: Boolean)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM library_tracks WHERE id = :trackId AND isFavorite = 1)")
    suspend fun isTrackFavorite(trackId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createPlaylist(playlist: Playlist): Long
    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTrackToPlaylist(crossRef: PlaylistTrackCrossRef)
    @Query("DELETE FROM playlist_track_cross_ref WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String)
    @Query("SELECT * FROM playlists ORDER BY name ASC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Transaction
    @Query("SELECT * FROM playlists")
    fun getPlaylistsWithTracks(): Flow<List<PlaylistWithTracks>>

    @Query("SELECT * FROM library_tracks WHERE id = :trackId")
    suspend fun getTrackByIdSync(trackId: String): TrackEntity?

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun getPlaylistWithTracks(playlistId: Long): Flow<PlaylistWithTracks?>

    @Transaction
    @Query("SELECT * FROM playlists")
    suspend fun getAllPlaylistsWithTracksSync(): List<PlaylistWithTracks>

    @Query("SELECT * FROM library_tracks")
    suspend fun getAllLibraryTracksSync(): List<TrackEntity>

    @Query("SELECT * FROM library_tracks")
    fun getAllLibraryTracks(): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLibraryTracks(tracks: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylists(playlists: List<Playlist>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistTrackCrossRefs(refs: List<PlaylistTrackCrossRef>)
}
