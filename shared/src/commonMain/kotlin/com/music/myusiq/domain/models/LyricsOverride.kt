package com.music.myusiq.domain.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics_overrides")
data class LyricsOverride(
    @PrimaryKey val songPath: String,
    val rawLyrics: String
)
