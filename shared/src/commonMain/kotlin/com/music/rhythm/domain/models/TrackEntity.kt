package com.music.rhythm.domain.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "library_tracks")
data class TrackEntity(
    @PrimaryKey val id: String, // Matches your media/song ID format
    val title: String,
    val artist: String,
    val isFavorite: Boolean = false,
    val waveformAmplitudes: String? = null, // Comma-separated floats
    val energyScore: Int = -1 // 1-10, -1 for unanalyzed
)
