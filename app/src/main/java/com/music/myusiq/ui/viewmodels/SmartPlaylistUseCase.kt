package com.music.myusiq.ui.viewmodels

import com.music.myusiq.data.MusicRepository
import com.music.myusiq.domain.models.Song
import com.music.myusiq.utils.MusicEnergyAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

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

        val playCounts = try { musicRepository.getAllPlayCounts().associateBy { it.songId } } catch (_: Exception) { emptyMap() }
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
            return result.sortedByDescending { song ->
                val plays = playCounts[song.id.toString()]?.playCount ?: 0
                val isNew = (nowMs - (song.dateAdded * 1000L)) < fourteenDaysMs
                val newBonus = if (isNew) 2.0 else 0.0
                (plays * 0.5) + newBonus + Random.nextDouble(0.0, 1.5)
            }.take(30)
        }
        val safeFallback = originalTrackList.filter { song ->
            val g = song.genre.lowercase()
            !g.contains("metal") && !g.contains("hardcore")
        }.shuffled()
        return (result + safeFallback).distinctBy { it.id }.take(minOf(originalTrackList.size, 30))
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
        return if (result.isNotEmpty()) {
            result.take(30)
        } else {
            originalTrackList.shuffled().take(minOf(originalTrackList.size, 30))
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
        return if (matched.isNotEmpty()) {
            matched.take(50)
        } else {
            originalTrackList.shuffled().take(minOf(originalTrackList.size, 30))
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
            val counts = musicRepository.getAllPlayCounts().associateBy { it.songId }
            val unplayed = originalTrackList.filter { (counts[it.id.toString()]?.playCount ?: 0) == 0 }.shuffled()
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
        return withContext(Dispatchers.IO) {
            if (originalTrackList.isEmpty()) return@withContext emptyList()

            // 1. Retrieve stored library track entities with waveform energy scores
            val libraryTrackEntities = try {
                musicRepository.getAllLibraryTracksSync().associateBy { it.id }
            } catch (_: Exception) {
                emptyMap()
            }

            // 2. Retrieve user play counts and history to learn listening affinity
            val playCounts = try {
                musicRepository.getAllPlayCounts().associateBy { it.songId }
            } catch (_: Exception) {
                emptyMap()
            }

            val targetMood = mood.lowercase()
            val nowMs = System.currentTimeMillis()
            val fourteenDaysMs = 14L * 24 * 60 * 60 * 1000

            val scoredMatches = mutableListOf<Pair<Song, Double>>()

            for (song in originalTrackList) {
                val trackEntity = libraryTrackEntities[song.id.toString()]
                
                // Determine physical audio waveform energy score (1-10) calculated across whole track
                var energy = trackEntity?.energyScore ?: -1
                if (energy == -1 && !trackEntity?.waveformAmplitudes.isNullOrBlank()) {
                    energy = MusicEnergyAnalyzer.analyze(trackEntity.waveformAmplitudes)
                }

                val genre = song.genre.lowercase()
                val title = song.title.lowercase()

                val isHeavyGenre = genre.contains("rock") || genre.contains("metal") || 
                    genre.contains("dance") || genre.contains("edm") || genre.contains("rap") || 
                    genre.contains("hip hop") || genre.contains("house") || genre.contains("techno") ||
                    genre.contains("party") || genre.contains("bhangra") || genre.contains("hardcore") ||
                    genre.contains("phonk") || genre.contains("dubstep") || genre.contains("trap") ||
                    genre.contains("punk") || genre.contains("dnb")

                val isRelaxGenre = genre.contains("chill") || genre.contains("ambient") || 
                    genre.contains("acoustic") || genre.contains("classical") || genre.contains("jazz") || 
                    genre.contains("piano") || genre.contains("lofi") || genre.contains("soft") ||
                    genre.contains("downtempo") || genre.contains("easy listening") || genre.contains("meditation") ||
                    genre.contains("instrumental") || genre.contains("sleep") || genre.contains("soothing")

                val isRomanceGenre = genre.contains("romance") || genre.contains("r&b") || 
                    genre.contains("ballad") || genre.contains("soul") || genre.contains("love") || 
                    genre.contains("romantic") || title.contains("love") || title.contains("romance") ||
                    title.contains("forever") || title.contains("heart") || title.contains("valentine")

                val isFocusGenre = isRelaxGenre || genre.contains("study") || genre.contains("focus") ||
                    genre.contains("post-rock") || title.contains("study") || title.contains("focus") ||
                    title.contains("lofi")

                val isUpbeatTitleOrDance = title.contains("party") || title.contains("dance") || title.contains("workout") ||
                    title.contains("gym") || title.contains("hype") || title.contains("upbeat") ||
                    title.contains("bhangra") || title.contains("bass") || title.contains("club") ||
                    title.contains("kanimaa") || title.contains("naalu peru") || title.contains("jab talak") ||
                    title.contains("cocktail") || title.contains("bhasad") || title.contains("macha") ||
                    title.contains("aari aari") || title.contains("dhurandhar") || title.contains("besos") ||
                    title.contains("adaavadi") || title.contains("bloody") || title.contains("gal sun")

                val isQuietTitle = title.contains("sleep") || title.contains("lullaby") || title.contains("relax") ||
                    title.contains("ambient") || title.contains("whisper") || title.contains("peaceful") ||
                    title.contains("meditation") || title.contains("soothing")

                var baseMatchScore = 0.0

                when (targetMood) {
                    "energize", "party" -> {
                        if (isQuietTitle || (energy in 1..3 && !isHeavyGenre && !isUpbeatTitleOrDance)) {
                            continue
                        }
                        if ((energy in 5..10) || isHeavyGenre || isUpbeatTitleOrDance) {
                            baseMatchScore = 5.0
                        } else if (genre.contains("pop")) {
                            baseMatchScore = 2.0
                        }
                    }
                    "relax" -> {
                        // Relax MUST be strictly quiet/chill!
                        // Exclude if energy >= 5, or if upbeat/dance/rock/rap/pop-dance
                        if (isHeavyGenre || isUpbeatTitleOrDance || energy >= 5) {
                            continue
                        }
                        if ((energy in 1..4) || isRelaxGenre || isQuietTitle) {
                            baseMatchScore = 5.0
                        }
                    }
                    "focus" -> {
                        // Focus MUST be steady and non-distracting
                        if (isHeavyGenre || isUpbeatTitleOrDance || energy >= 6) {
                            continue
                        }
                        if ((energy in 1..4) || isFocusGenre || isQuietTitle) {
                            baseMatchScore = 5.0
                        } else if (energy == 5) {
                            baseMatchScore = 2.0
                        }
                    }
                    "romance" -> {
                        if (isHeavyGenre || energy >= 8) {
                            continue
                        }
                        if (isRomanceGenre || title.contains("jab talak") || energy in 2..5) {
                            baseMatchScore = 5.0
                        } else if (energy in 5..6 || genre.contains("pop")) {
                            baseMatchScore = 2.0
                        }
                    }
                    "drive" -> {
                        // Drive: Mid-tempo, sing-along, pop, rock, road trip hits (Energy 4-8)
                        if (isQuietTitle || (energy in 1..2 && !isUpbeatTitleOrDance)) {
                            continue
                        }
                        val isRoadTrip = genre.contains("pop") || genre.contains("rock") || genre.contains("country") ||
                            genre.contains("indie") || genre.contains("folk") || genre.contains("synth") ||
                            title.contains("drive") || title.contains("road") || title.contains("trip") ||
                            title.contains("highway") || title.contains("cruise")

                        if (isRoadTrip || isUpbeatTitleOrDance || energy in 5..8) {
                            baseMatchScore = 5.0
                        } else if (energy in 3..4) {
                            baseMatchScore = 2.0
                        }
                    }
                    else -> {
                        baseMatchScore = 5.0
                    }
                }

                if (baseMatchScore > 0.0) {
                    // Learning & Affinity Adjustments:
                    val plays = playCounts[song.id.toString()]?.playCount ?: 0
                    val isFavorite = trackEntity?.isFavorite == true
                    val isNewlyAdded = (nowMs - (song.dateAdded * 1000L)) < fourteenDaysMs

                    // Higher play count = user affinity boost
                    val playCountBoost = (plays * 0.4).coerceAtMost(4.0)
                    val favoriteBoost = if (isFavorite) 3.0 else 0.0
                    val discoveryBonus = if (isNewlyAdded) 2.0 else 0.0
                    val softJitter = Random.nextDouble(0.0, 1.5)

                    val totalAffinityScore = baseMatchScore + playCountBoost + favoriteBoost + discoveryBonus + softJitter
                    scoredMatches.add(song to totalAffinityScore)
                }
            }

            val targetCount = minOf(originalTrackList.size, 35)

            if (scoredMatches.isNotEmpty()) {
                scoredMatches.sortedByDescending { it.second }.map { it.first }.take(targetCount)
            } else {
                // Safe Fallback: Filter original list without opposite mood tracks
                val safeFallback = originalTrackList.filter { song ->
                    val g = song.genre.lowercase()
                    val t = song.title.lowercase()
                    val isUpbeat = t.contains("kanimaa") || t.contains("naalu peru") || t.contains("jab talak") || t.contains("besos") || t.contains("bhasad")
                    when (targetMood) {
                        "relax", "focus" -> !g.contains("metal") && !g.contains("rock") && !g.contains("edm") && !g.contains("rap") && !isUpbeat
                        "energize", "party" -> !g.contains("sleep") && !g.contains("lullaby") && !g.contains("ambient")
                        else -> true
                    }
                }.shuffled()
                (safeFallback.ifEmpty { originalTrackList.shuffled() }).distinctBy { it.id }.take(targetCount)
            }
        }
    }
}
