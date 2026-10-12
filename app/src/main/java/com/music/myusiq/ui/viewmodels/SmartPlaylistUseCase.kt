package com.music.myusiq.ui.viewmodels

import com.music.myusiq.data.MusicRepository
import com.music.myusiq.domain.models.Song
import com.music.myusiq.utils.MusicEnergyAnalyzer
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
        
        if (filtered.size >= 10) return filtered.shuffled()

        // Smart Fallback: Acoustic, calm, or upbeat pop/relaxing songs for morning
        val morningFallback = originalTrackList.filter { song ->
            val genre = song.genre.lowercase()
            genre.contains("acoustic") || genre.contains("chill") || genre.contains("pop") ||
            genre.contains("piano") || genre.contains("soft") || genre.contains("light")
        }
        return (filtered + morningFallback).distinctBy { it.id }.shuffled().take(20).ifEmpty {
            originalTrackList.shuffled().take(20)
        }
    }

    suspend fun getEveningMix(originalTrackList: List<Song>): List<Song> {
        val songIds = musicRepository.getRecentSongsByTimeRange(20, 2, 30)
        val filtered = if (songIds.isNotEmpty()) {
            originalTrackList.filter { it.id.toString() in songIds }
        } else emptyList()

        if (filtered.size >= 10) return filtered.shuffled()

        // Late Night / Evening Chill Fallback
        val eveningFallback = getMoodMix(originalTrackList, "relax")
        return (filtered + eveningFallback).distinctBy { it.id }.shuffled().take(25).ifEmpty {
            originalTrackList.shuffled().take(20)
        }
    }

    suspend fun getTopArtistsMix(originalTrackList: List<Song>): List<Song> {
        var topArtists = musicRepository.getTopArtists(5).map { it.artist }
        if (topArtists.isEmpty() && originalTrackList.isNotEmpty()) {
            // Fallback: Top 5 artists with highest track count in local library
            topArtists = originalTrackList.groupBy { it.artist }
                .entries.sortedByDescending { it.value.size }
                .take(5).map { it.key }
        }
        return originalTrackList.filter { it.artist in topArtists }.shuffled().take(50)
    }

    suspend fun getForgottenFavorites(originalTrackList: List<Song>): List<Song> {
        return withContext(Dispatchers.IO) {
            val fourteenDaysAgo = System.currentTimeMillis() - (14L * 24 * 60 * 60 * 1000)
            val counts = musicRepository.getAllPlayCounts().associateBy { it.songId }
            val timestamps = musicRepository.getLastPlayedTimestamps().associateBy { it.songId }
            
            originalTrackList.asSequence().filter { 
                val id = it.id.toString()
                val count = counts[id]?.playCount ?: 0
                val lastPlayed = timestamps[id]?.lastPlayed ?: 0L
                (count >= 3) && (lastPlayed < fourteenDaysAgo)
            }.sortedByDescending { counts[it.id.toString()]?.playCount ?: 0 }.take(50).toList()
        }
    }

    suspend fun getDiscoverMix(originalTrackList: List<Song>): List<Song> {
        return withContext(Dispatchers.IO) {
            val counts = musicRepository.getAllPlayCounts().associateBy { it.songId }
            originalTrackList.filter { (counts[it.id.toString()]?.playCount ?: 0) == 0 }.shuffled().take(50)
        }
    }

    suspend fun getDecadeMix(originalTrackList: List<Song>, decadePrefix: String): List<Song> {
        return withContext(Dispatchers.IO) {
            originalTrackList.filter { song ->
                val year = song.dateAdded.toString()
                year.contains(decadePrefix)
            }.shuffled().take(50)
        }
    }

    suspend fun getGenreMix(originalTrackList: List<Song>, genre: String): List<Song> {
        return withContext(Dispatchers.IO) {
            originalTrackList.filter {
                it.genre.contains(genre, ignoreCase = true) ||
                it.album.contains(genre, ignoreCase = true) ||
                it.artist.contains(genre, ignoreCase = true) ||
                it.title.contains(genre, ignoreCase = true)
            }.shuffled().take(50)
        }
    }

    suspend fun getMoodMix(originalTrackList: List<Song>, mood: String): List<Song> {
        return withContext(Dispatchers.IO) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()

            // Retrieve stored library track entities with waveform energy scores
            val libraryTrackEntities = try {
                musicRepository.getAllLibraryTracksSync().associateBy { it.id }
            } catch (_: Exception) {
                emptyMap()
            }

            val targetMood = mood.lowercase()

            val matchedSongs = originalTrackList.filter { song ->
                val trackEntity = libraryTrackEntities[song.id.toString()]
                
                // 1. Determine physical audio waveform energy score (1-10)
                var energy = trackEntity?.energyScore ?: -1
                if (energy == -1 && !trackEntity?.waveformAmplitudes.isNullOrBlank()) {
                    energy = MusicEnergyAnalyzer.analyze(trackEntity.waveformAmplitudes)
                }

                // 2. Strict Physical Audio Waveform Energy Boundaries
                if (energy != -1) {
                    when (targetMood) {
                        "energize", "party" -> energy in 7..10
                        "relax" -> energy in 1..4
                        "focus" -> energy in 2..5
                        "romance" -> energy in 2..6 // Hard rejection: Energy >= 7 is strictly excluded from Romance!
                        else -> true
                    }
                } else {
                    // Fallback for unanalyzed tracks based on genre acousticness heuristics
                    val genre = song.genre.lowercase()
                    val title = song.title.lowercase()
                    val isHeavyOrFastGenre = genre.contains("rock") || genre.contains("metal") || 
                        genre.contains("dance") || genre.contains("edm") || genre.contains("rap") || 
                        genre.contains("hip hop") || genre.contains("house") || genre.contains("techno")

                    when (targetMood) {
                        "energize", "party" -> isHeavyOrFastGenre || genre.contains("pop") || title.contains("bhangra") || title.contains("party")
                        "relax", "focus" -> !isHeavyOrFastGenre && (genre.contains("chill") || genre.contains("ambient") || genre.contains("acoustic") || genre.contains("classical") || genre.contains("jazz") || genre.contains("piano") || genre.contains("lofi"))
                        "romance" -> !isHeavyOrFastGenre && (genre.contains("romance") || genre.contains("r&b") || genre.contains("ballad") || genre.contains("soul") || genre.contains("love") || genre.contains("romantic"))
                        else -> true
                    }
                }
            }

            matchedSongs.shuffled().take(50)
        }
    }
}
