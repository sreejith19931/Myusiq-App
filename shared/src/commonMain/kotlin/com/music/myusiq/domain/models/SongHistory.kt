package com.music.myusiq.domain.models

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.music.myusiq.utils.PlatformUtils

@Entity(
    tableName = "song_history",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["songId"]),
    ]
)
data class SongHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val timestamp: Long = PlatformUtils.getCurrentTimeMillis(),
)

data class TopTrack(val songId: String, val title: String, val artist: String, val playCount: Int)
data class TopArtist(val artist: String, val playCount: Int)
data class LastPlayedInfo(val songId: String, val lastPlayed: Long)
data class PlayCountInfo(val songId: String, val playCount: Int)
