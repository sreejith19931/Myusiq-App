package com.music.rhythm.domain.models

import androidx.room.*
import com.music.rhythm.utils.PlatformUtils

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = PlatformUtils.getCurrentTimeMillis(),
)

@Entity(
    tableName = "playlist_track_cross_ref",
    primaryKeys = ["playlistId", "trackId"],
)
data class PlaylistTrackCrossRef(
    val playlistId: Long,
    val trackId: String // Matches your track/media ID type
)

data class PlaylistWithTracks(
    @Embedded val playlist: Playlist,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            PlaylistTrackCrossRef::class,
            parentColumn = "playlistId",
            entityColumn = "trackId"
        )
    )
    val tracks: List<TrackEntity>
)
