package com.music.myusiq.ui.viewmodels

import android.content.Context
import com.music.myusiq.DatabaseProvider
import com.music.myusiq.domain.models.SearchHistory
import com.music.myusiq.domain.models.Song
import com.music.myusiq.utils.TransliterationUtil
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
        val transliteratedQuery = TransliterationUtil.transliterate(query)
        val hasTransliteration = TransliterationUtil.isAvailable()

        return songs.filter { song ->
            song.title.contains(query, ignoreCase = true) ||
            song.artist.contains(query, ignoreCase = true) ||
            song.album.contains(query, ignoreCase = true) ||
            (hasTransliteration && (
                TransliterationUtil.transliterate(song.title).contains(query, ignoreCase = true) ||
                TransliterationUtil.transliterate(song.artist).contains(query, ignoreCase = true) ||
                TransliterationUtil.transliterate(song.title).contains(transliteratedQuery, ignoreCase = true) ||
                TransliterationUtil.transliterate(song.artist).contains(transliteratedQuery, ignoreCase = true)
            ))
        }
    }
}
