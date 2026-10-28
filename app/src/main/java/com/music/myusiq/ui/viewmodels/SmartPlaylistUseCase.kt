package com.music.myusiq.ui.viewmodels

import com.music.myusiq.data.MusicRepository
import com.music.myusiq.domain.models.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

class SmartPlaylistUseCase(
    private val musicRepository: MusicRepository,
) {
    private var cachedPlayCounts: Map<String, Int>? = null
    private var cachedLastPlayed: Map<String, Long>? = null
    private var lastCacheTimestamp: Long = 0L

    private suspend fun getPlayCountsMap(): Map<String, Int> {
        val now = System.currentTimeMillis()
        if (cachedPlayCounts != null && (now - lastCacheTimestamp < 30_000L)) {
            return cachedPlayCounts!!
        }
        val map = try {
            musicRepository.getAllPlayCounts().associate { it.songId to it.playCount }
        } catch (_: Exception) {
            emptyMap()
        }
        cachedPlayCounts = map
        lastCacheTimestamp = now
        return map
    }

    private suspend fun getLastPlayedMap(): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (cachedLastPlayed != null && (now - lastCacheTimestamp < 30_000L)) {
            return cachedLastPlayed!!
        }
        val map = try {
            musicRepository.getLastPlayedTimestamps().associate { it.songId to it.lastPlayed }
        } catch (_: Exception) {
            emptyMap()
        }
        cachedLastPlayed = map
        lastCacheTimestamp = now
        return map
    }

    fun invalidateCache() {
        cachedPlayCounts = null
        cachedLastPlayed = null
    }

    suspend fun getMorningMix(originalTrackList: List<Song>): List<Song> {
        return withContext(Dispatchers.IO) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()
            val songIds = musicRepository.getRecentSongsByTimeRange(5, 11, 30)
            val filtered = if (songIds.isNotEmpty()) {
                originalTrackList.filter { it.id.toString() in songIds }
            } else emptyList()
            
            if (filtered.size >= 15) return@withContext filtered.shuffled().take(30)

            val playCounts = getPlayCountsMap()
            val nowMs = System.currentTimeMillis()
            val fourteenDaysMs = 14L * 24 * 60 * 60 * 1000

            // Smart Fallback: Acoustic, calm, or upbeat pop/relaxing songs for morning
            val morningFallback = originalTrackList.filter { song ->
                val genre = song.genre.lowercase()
                val title = song.title.lowercase()
                val isHeavy = genre.contains("metal") || genre.contains("hardcore") || genre.contains("trap")
                !isHeavy && (
                    genre.contains("acoustic") || genre.contains("chill") || genre.contains("pop") ||
                    genre.contains("piano") || genre.contains("soft") || genre.contains("light") ||
                    title.contains("morning") || title.contains("sun") || title.contains("rise") ||
                    title.contains("day") || title.contains("shine")
                )
            }
            val result = (filtered + morningFallback).distinctBy { it.id }
            if (result.isNotEmpty()) {
                return@withContext result.sortedByDescending { song ->
                    val plays = playCounts[song.id.toString()] ?: 0
                    val isNew = (nowMs - (song.dateAdded * 1000L)) < fourteenDaysMs
                    val newBonus = if (isNew) 2.0 else 0.0
                    (plays * 0.5) + newBonus + Random.nextDouble(0.0, 1.5)
                }.take(30)
            }
            val safeFallback = originalTrackList.filter { song ->
                val g = song.genre.lowercase()
                !g.contains("metal") && !g.contains("hardcore")
            }.shuffled()
            (result + safeFallback).distinctBy { it.id }.take(minOf(originalTrackList.size, 30))
        }
    }

    suspend fun getEveningMix(originalTrackList: List<Song>): List<Song> {
        return withContext(Dispatchers.IO) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()
            val songIds = musicRepository.getRecentSongsByTimeRange(20, 2, 30)
            val filtered = if (songIds.isNotEmpty()) {
                originalTrackList.filter { it.id.toString() in songIds }
            } else emptyList()

            if (filtered.size >= 15) return@withContext filtered.shuffled().take(30)

            // Late Night / Evening Chill Fallback
            val eveningFallback = getMoodMix(originalTrackList, "relax")
            val result = (filtered + eveningFallback).distinctBy { it.id }.shuffled()
            if (result.isNotEmpty()) {
                result.take(30)
            } else {
                originalTrackList.shuffled().take(minOf(originalTrackList.size, 30))
            }
        }
    }

    suspend fun getTopArtistsMix(originalTrackList: List<Song>): List<Song> {
        return withContext(Dispatchers.IO) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()
            var topArtists = musicRepository.getTopArtists(5).map { it.artist }
            if (topArtists.isEmpty() && originalTrackList.isNotEmpty()) {
                // Fallback: Top 5 artists with highest track count in local library
                topArtists = originalTrackList.groupBy { it.artist }
                    .entries.sortedByDescending { it.value.size }
                    .take(5).map { it.key }
            }
            val matched = originalTrackList.filter { it.artist in topArtists }.shuffled()
            if (matched.isNotEmpty()) {
                matched.take(50)
            } else {
                originalTrackList.shuffled().take(minOf(originalTrackList.size, 30))
            }
        }
    }

    suspend fun getForgottenFavorites(originalTrackList: List<Song>): List<Song> {
        return withContext(Dispatchers.IO) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()
            val fourteenDaysAgo = System.currentTimeMillis() - (14L * 24 * 60 * 60 * 1000)
            val counts = getPlayCountsMap()
            val timestamps = getLastPlayedMap()
            
            val matched = originalTrackList.asSequence().filter { 
                val id = it.id.toString()
                val count = counts[id] ?: 0
                val lastPlayed = timestamps[id] ?: 0L
                (count >= 2) && (lastPlayed < fourteenDaysAgo)
            }.sortedByDescending { counts[it.id.toString()] ?: 0 }.toList()

            if (matched.isNotEmpty()) {
                matched.take(50)
            } else {
                originalTrackList.shuffled().take(minOf(originalTrackList.size, 25))
            }
        }
    }

    suspend fun getDiscoverMix(originalTrackList: List<Song>): List<Song> {
        return withContext(Dispatchers.IO) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()
            val counts = getPlayCountsMap()
            val unplayed = originalTrackList.filter { (counts[it.id.toString()] ?: 0) == 0 }.shuffled()
            if (unplayed.isNotEmpty()) {
                unplayed.take(50)
            } else {
                originalTrackList.shuffled().take(minOf(originalTrackList.size, 30))
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
            if (matched.isNotEmpty()) {
                matched.take(50)
            } else {
                originalTrackList.shuffled().take(minOf(originalTrackList.size, 25))
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
            if (matched.isNotEmpty()) {
                matched.take(50)
            } else {
                originalTrackList.shuffled().take(minOf(originalTrackList.size, 25))
            }
        }
    }

    suspend fun getMoodMix(originalTrackList: List<Song>, mood: String): List<Song> {
        return withContext(Dispatchers.Default) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()

            val targetMood = mood.lowercase()
            val targetCount = minOf(originalTrackList.size, 35)

            val matched = originalTrackList.asSequence().filter { song ->
                val genre = song.genre.lowercase()
                val title = song.title.lowercase()

                val isHeavyGenre = genre.contains("rock") || genre.contains("metal") || 
                    genre.contains("dance") || genre.contains("edm") || genre.contains("rap") || 
                    genre.contains("hip hop") || genre.contains("house") || genre.contains("techno") ||
                    genre.contains("party") || genre.contains("bhangra") || genre.contains("hardcore") ||
                    genre.contains("phonk") || genre.contains("dubstep") || genre.contains("trap")

                val isRelaxGenre = genre.contains("chill") || genre.contains("ambient") || 
                    genre.contains("acoustic") || genre.contains("classical") || genre.contains("jazz") || 
                    genre.contains("piano") || genre.contains("lofi") || genre.contains("soft") ||
                    genre.contains("meditation") || genre.contains("instrumental") || genre.contains("sleep")

                val isUpbeatTitleOrDance = title.contains("party") || title.contains("dance") || title.contains("workout") ||
                    title.contains("gym") || title.contains("hype") || title.contains("upbeat") ||
                    title.contains("club")

                val isQuietTitle = title.contains("sleep") || title.contains("lullaby") || title.contains("relax") ||
                    title.contains("ambient") || title.contains("peaceful") || title.contains("meditation")

                when (targetMood) {
                    "energize", "party" -> isHeavyGenre || isUpbeatTitleOrDance || genre.contains("pop")
                    "relax" -> !isHeavyGenre && !isUpbeatTitleOrDance && (isRelaxGenre || isQuietTitle || genre.contains("pop") || genre.isBlank())
                    "focus" -> !isHeavyGenre && !isUpbeatTitleOrDance && (isRelaxGenre || genre.contains("study") || genre.contains("focus"))
                    "romance" -> genre.contains("romance") || genre.contains("r&b") || genre.contains("soul") || title.contains("love") || title.contains("heart")
                    "drive" -> genre.contains("pop") || genre.contains("rock") || genre.contains("indie") || genre.contains("synth") || isUpbeatTitleOrDance
                    else -> true
                }
            }.shuffled().toList()

            if (matched.isNotEmpty()) {
                matched.take(targetCount)
            } else {
                originalTrackList.shuffled().take(targetCount)
            }
        }
    }
}
