package com.music.rhythm.domain.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.music.rhythm.utils.PlatformUtils

@Entity(tableName = "search_history")
data class SearchHistory(
    @PrimaryKey val query: String,
    val timestamp: Long = PlatformUtils.getCurrentTimeMillis(),
)
