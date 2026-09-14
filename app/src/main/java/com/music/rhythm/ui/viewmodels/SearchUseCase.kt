package com.music.rhythm.ui.viewmodels

import android.content.Context
import com.music.rhythm.DatabaseProvider
import com.music.rhythm.domain.models.SearchHistory
import com.music.rhythm.domain.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchUseCase(context: Context) {
    private val searchDao = DatabaseProvider.getDatabase(context).searchHistoryDao()

    suspend fun getRecentSearches(limit: Int): List<String> = withContext(Dispatchers.IO) {
        searchDao.getRecentSearches(limit)
    }

    suspend fun saveSearchQuery(query: String) = withContext(Dispatchers.IO) {
        if (query.length >= 3) {
            searchDao.upsertSearch(SearchHistory(query))
        }
    }

    fun filterSongs(songs: List<Song>, query: String): List<Song> {
        if (query.isBlank()) return songs
        return songs.filter { song ->
            song.title.contains(query, ignoreCase = true) ||
            song.artist.contains(query, ignoreCase = true) ||
            song.album.contains(query, ignoreCase = true)
        }
    }
}
