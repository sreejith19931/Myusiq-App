package com.music.rhythm.domain.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val duration: Int,
    val albumId: Long,
    val album: String,
    val genre: String = "",
    val dateAdded: Long = 0,
    val size: Long = 0,
)
