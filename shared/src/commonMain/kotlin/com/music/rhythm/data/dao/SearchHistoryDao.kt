package com.music.rhythm.data.dao

import androidx.room.*
import com.music.rhythm.domain.models.SearchHistory

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearch(search: SearchHistory)

    @Query("SELECT `query` FROM search_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentSearches(limit: Int): List<String>

    @Query("DELETE FROM search_history")
    suspend fun clearHistory()
}
