package com.music.myusiq.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.music.myusiq.domain.models.*
import com.music.myusiq.data.dao.*

@Database(
    entities = [
        Song::class,
        Playlist::class,
        SongHistory::class,
        TrackEntity::class,
        PlaylistTrackCrossRef::class,
        SongOverride::class,
        LyricsOverride::class,
        SearchHistory::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songHistoryDao(): SongHistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun songDao(): SongDao
    abstract fun searchHistoryDao(): SearchHistoryDao
}
