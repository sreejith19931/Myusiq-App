package com.music.rhythm.domain.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "song_overrides")
data class SongOverride(
    @PrimaryKey val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val year: String = "",
    val genre: String = "",
    val hasManualArtwork: Boolean = false
)
