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
        if (originalTrackList.isEmpty()) return emptyList()
        val songIds = musicRepository.getRecentSongsByTimeRange(5, 11, 30)
        val filtered = if (songIds.isNotEmpty()) {
            originalTrackList.filter { it.id.toString() in songIds }
        } else emptyList()
        
        if (filtered.size >= 15) return filtered.shuffled().take(30)

        // Smart Fallback: Acoustic, calm, or upbeat pop/relaxing songs for morning
        val morningFallback = originalTrackList.filter { song ->
            val genre = song.genre.lowercase()
            val title = song.title.lowercase()
            genre.contains("acoustic") || genre.contains("chill") || genre.contains("pop") ||
            genre.contains("piano") || genre.contains("soft") || genre.contains("light") ||
            title.contains("morning") || title.contains("sun") || title.contains("rise")
        }
        val result = (filtered + morningFallback).distinctBy { it.id }.shuffled()
        return if (result.size >= 15) {
            result.take(30)
        } else {
            (result + originalTrackList.shuffled()).distinctBy { it.id }.take(minOf(originalTrackList.size, 30))
        }
    }

    suspend fun getEveningMix(originalTrackList: List<Song>): List<Song> {
        if (originalTrackList.isEmpty()) return emptyList()
        val songIds = musicRepository.getRecentSongsByTimeRange(20, 2, 30)
        val filtered = if (songIds.isNotEmpty()) {
            originalTrackList.filter { it.id.toString() in songIds }
        } else emptyList()

        if (filtered.size >= 15) return filtered.shuffled().take(30)

        // Late Night / Evening Chill Fallback
        val eveningFallback = getMoodMix(originalTrackList, "relax")
        val result = (filtered + eveningFallback).distinctBy { it.id }.shuffled()
        return if (result.size >= 15) {
            result.take(30)
        } else {
            (result + originalTrackList.shuffled()).distinctBy { it.id }.take(minOf(originalTrackList.size, 30))
        }
    }

    suspend fun getTopArtistsMix(originalTrackList: List<Song>): List<Song> {
        if (originalTrackList.isEmpty()) return emptyList()
        var topArtists = musicRepository.getTopArtists(5).map { it.artist }
        if (topArtists.isEmpty() && originalTrackList.isNotEmpty()) {
            // Fallback: Top 5 artists with highest track count in local library
            topArtists = originalTrackList.groupBy { it.artist }
                .entries.sortedByDescending { it.value.size }
                .take(5).map { it.key }
        }
        val matched = originalTrackList.filter { it.artist in topArtists }.shuffled()
        return if (matched.size >= 15) {
            matched.take(50)
        } else {
            (matched + originalTrackList.shuffled()).distinctBy { it.id }.take(minOf(originalTrackList.size, 30))
        }
    }

    suspend fun getForgottenFavorites(originalTrackList: List<Song>): List<Song> {
        return withContext(Dispatchers.IO) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()
            val fourteenDaysAgo = System.currentTimeMillis() - (14L * 24 * 60 * 60 * 1000)
            val counts = musicRepository.getAllPlayCounts().associateBy { it.songId }
            val timestamps = musicRepository.getLastPlayedTimestamps().associateBy { it.songId }
            
            val matched = originalTrackList.asSequence().filter { 
                val id = it.id.toString()
                val count = counts[id]?.playCount ?: 0
                val lastPlayed = timestamps[id]?.lastPlayed ?: 0L
                (count >= 2) && (lastPlayed < fourteenDaysAgo)
            }.sortedByDescending { counts[it.id.toString()]?.playCount ?: 0 }.toList()

            if (matched.size >= 15) {
                matched.take(50)
            } else {
                (matched + originalTrackList.shuffled()).distinctBy { it.id }.take(minOf(originalTrackList.size, 25))
            }
        }
    }

    suspend fun getDiscoverMix(originalTrackList: List<Song>): List<Song> {
        return withContext(Dispatchers.IO) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()
            val counts = musicRepository.getAllPlayCounts().associateBy { it.songId }
            val unplayed = originalTrackList.filter { (counts[it.id.toString()]?.playCount ?: 0) == 0 }.shuffled()
            if (unplayed.size >= 15) {
                unplayed.take(50)
            } else {
                (unplayed + originalTrackList.shuffled()).distinctBy { it.id }.take(minOf(originalTrackList.size, 30))
            }
        }
    }

    suspend fun getDecadeMix(originalTrackList: List<Song>, decadePrefix: String): List<Song> {
        return withContext(Dispatchers.IO) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()
            val matched = originalTrackList.filter { song ->
                val year = song.dateAdded.toString()
                year.contains(decadePrefix)
            }.shuffled()
            if (matched.size >= 15) {
                matched.take(50)
            } else {
                (matched + originalTrackList.shuffled()).distinctBy { it.id }.take(minOf(originalTrackList.size, 25))
            }
        }
    }

    suspend fun getGenreMix(originalTrackList: List<Song>, genre: String): List<Song> {
        return withContext(Dispatchers.IO) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()
            val matched = originalTrackList.filter {
                it.genre.contains(genre, ignoreCase = true) ||
                it.album.contains(genre, ignoreCase = true) ||
                it.artist.contains(genre, ignoreCase = true) ||
                it.title.contains(genre, ignoreCase = true)
            }.shuffled()
            if (matched.size >= 15) {
                matched.take(50)
            } else {
                (matched + originalTrackList.shuffled()).distinctBy { it.id }.take(minOf(originalTrackList.size, 25))
            }
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

            val primaryMatches = mutableListOf<Song>()
            val secondaryMatches = mutableListOf<Song>()

            for (song in originalTrackList) {
                val trackEntity = libraryTrackEntities[song.id.toString()]
                
                // 1. Determine physical audio waveform energy score (1-10) calculated across whole track
                var energy = trackEntity?.energyScore ?: -1
                if (energy == -1 && !trackEntity?.waveformAmplitudes.isNullOrBlank()) {
                    energy = MusicEnergyAnalyzer.analyze(trackEntity.waveformAmplitudes)
                }

                val genre = song.genre.lowercase()
                val title = song.title.lowercase()
                val isHeavyOrFastGenre = genre.contains("rock") || genre.contains("metal") || 
                    genre.contains("dance") || genre.contains("edm") || genre.contains("rap") || 
                    genre.contains("hip hop") || genre.contains("house") || genre.contains("techno") ||
                    genre.contains("party") || genre.contains("pop")

                if (energy != -1) {
                    val isPrimary = when (targetMood) {
                        "energize", "party" -> energy in 7..10
                        "relax" -> energy in 1..4
                        "focus" -> energy in 2..5
                        "romance" -> energy in 2..6
                        else -> true
                    }
                    val isSecondary = when (targetMood) {
                        "energize", "party" -> energy in 5..10 || isHeavyOrFastGenre
                        "relax" -> energy in 1..6 || !isHeavyOrFastGenre
                        "focus" -> energy in 1..6
                        "romance" -> energy in 1..7 && !isHeavyOrFastGenre
                        else -> true
                    }

                    if (isPrimary) {
                        primaryMatches.add(song)
                    } else if (isSecondary) {
                        secondaryMatches.add(song)
                    }
                } else {
                    // Fallback heuristics for unanalyzed tracks
                    val isPrimary = when (targetMood) {
                        "energize", "party" -> isHeavyOrFastGenre || title.contains("party") || title.contains("bhangra")
                        "relax", "focus" -> !isHeavyOrFastGenre && (genre.contains("chill") || genre.contains("ambient") || genre.contains("acoustic") || genre.contains("classical") || genre.contains("jazz") || genre.contains("piano") || genre.contains("lofi"))
                        "romance" -> !isHeavyOrFastGenre && (genre.contains("romance") || genre.contains("r&b") || genre.contains("ballad") || genre.contains("soul") || genre.contains("love") || genre.contains("romantic"))
                        else -> true
                    }

                    if (isPrimary) {
                        primaryMatches.add(song)
                    } else {
                        secondaryMatches.add(song)
                    }
                }
            }

            val targetCount = minOf(originalTrackList.size, 35)
            val combined = (primaryMatches.shuffled() + secondaryMatches.shuffled() + originalTrackList.shuffled()).distinctBy { it.id }
            combined.take(targetCount)
        }
    }
}
