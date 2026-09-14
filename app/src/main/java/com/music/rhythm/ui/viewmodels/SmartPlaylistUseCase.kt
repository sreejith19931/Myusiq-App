package com.music.rhythm.ui.viewmodels

import com.music.rhythm.data.MusicRepository
import com.music.rhythm.domain.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmartPlaylistUseCase(
    private val musicRepository: MusicRepository,
) {
    suspend fun getMorningMix(originalTrackList: List<Song>): List<Song> {
        val songIds = musicRepository.getRecentSongsByTimeRange(5, 11, 30)
        val filtered = if (songIds.isNotEmpty()) {
            originalTrackList.filter { it.id.toString() in songIds }
        } else emptyList()
        return if (filtered.size >= 10) filtered.shuffled() else originalTrackList.shuffled().take(20)
    }

    suspend fun getTopArtistsMix(originalTrackList: List<Song>): List<Song> {
        val topArtists = musicRepository.getTopArtists(5).map { it.artist }
        return originalTrackList.filter { it.artist in topArtists }.shuffled().take(50)
    }

    suspend fun getForgottenFavorites(originalTrackList: List<Song>): List<Song> {
        return withContext(Dispatchers.IO) {
            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
            val counts = musicRepository.getAllPlayCounts().associateBy { it.songId }
            val timestamps = musicRepository.getLastPlayedTimestamps().associateBy { it.songId }
            
            originalTrackList.asSequence().filter { 
                val id = it.id.toString()
                val count = counts[id]?.playCount ?: 0
                val lastPlayed = timestamps[id]?.lastPlayed ?: 0L
                (count >= 10) && (lastPlayed < thirtyDaysAgo)
            }.sortedByDescending { counts[it.id.toString()]?.playCount ?: 0 }.take(50).toList()
        }
    }

    suspend fun getDiscoverMix(originalTrackList: List<Song>): List<Song> {
        return withContext(Dispatchers.IO) {
            val counts = musicRepository.getAllPlayCounts().associateBy { it.songId }
            originalTrackList.filter { (counts[it.id.toString()]?.playCount ?: 0) == 0 }.shuffled().take(50)
        }
    }

    suspend fun getGenreMix(originalTrackList: List<Song>, genre: String): List<Song> {
        return withContext(Dispatchers.IO) {
            originalTrackList.filter { it.album.contains(genre, ignoreCase = true) || it.artist.contains(genre, ignoreCase = true) }.shuffled().take(50)
        }
    }
}
