package com.music.rhythm.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.music.rhythm.domain.models.*
import com.music.rhythm.data.dao.*

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
    version = 10,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songHistoryDao(): SongHistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun songDao(): SongDao
    abstract fun searchHistoryDao(): SearchHistoryDao
}
