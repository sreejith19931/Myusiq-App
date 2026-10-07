package com.music.myusiq.ui.viewmodels

import com.music.myusiq.data.MusicRepository
import com.music.myusiq.domain.models.Song
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

            val energizeKeywords = listOf("rock", "pop", "dance", "edm", "metal", "electronic", "hip hop", "rap", "workout", "gym", "power", "energy", "hype", "club", "upbeat", "pump", "fitness", "motivation", "punk", "synth")
            val relaxKeywords = listOf("chill", "relax", "sleep", "piano", "acoustic", "lofi", "lo-fi", "ambient", "meditation", "calm", "soft", "quiet", "peace", "jazz", "classical", "instrumental", "sunset", "spa", "zen", "lullaby")
            val partyKeywords = listOf("party", "dance", "club", "house", "disco", "remix", "dj", "edm", "groove", "funk", "celebration", "festival", "boogie", "techno")
            val focusKeywords = listOf("study", "focus", "instrumental", "classical", "ambient", "soundtrack", "ost", "lofi", "lo-fi", "piano", "coding", "chillhop", "concentration")
            val romanceKeywords = listOf("love", "romantic", "romance", "heart", "ballad", "duet", "kiss", "forever", "sweet", "darling", "baby", "soul", "r&b", "passion", "valentine", "honey", "wedding", "desire", "affection", "amour", "beloved", "crush", "lovers", "serenade", "sweetheart", "tender")

            val targetKeywords = when (mood.lowercase()) {
                "energize" -> energizeKeywords
                "relax" -> relaxKeywords
                "party" -> partyKeywords
                "focus" -> focusKeywords
                "romance" -> romanceKeywords
                else -> emptyList()
            }

            fun matchesKeyword(text: String, keyword: String): Boolean {
                return if (keyword.contains("-") || keyword.contains(" ")) {
                    text.contains(keyword, ignoreCase = true)
                } else {
                    val regex = Regex("\\b${Regex.escape(keyword)}\\b", RegexOption.IGNORE_CASE)
                    regex.containsMatchIn(text)
                }
            }

            fun scoreSong(song: Song): Int {
                var score = 0
                val title = song.title.lowercase()
                val artist = song.artist.lowercase()
                val album = song.album.lowercase()
                val genre = song.genre.lowercase()
                val fullText = "$title $artist $album $genre"

                targetKeywords.forEach { kw ->
                    if (matchesKeyword(fullText, kw)) {
                        score += 3
                    }
                }

                when (mood.lowercase()) {
                    "energize" -> {
                        if (genre.contains("rock") || genre.contains("metal") || genre.contains("dance") || genre.contains("edm") || genre.contains("pop") || genre.contains("rap") || genre.contains("hip hop")) score += 4
                    }
                    "relax" -> {
                        if (genre.contains("chill") || genre.contains("ambient") || genre.contains("acoustic") || genre.contains("classical") || genre.contains("jazz") || genre.contains("piano") || genre.contains("lo-fi") || genre.contains("lofi")) score += 4
                    }
                    "party" -> {
                        if (genre.contains("dance") || genre.contains("house") || genre.contains("disco") || genre.contains("club") || genre.contains("edm") || genre.contains("techno")) score += 4
                    }
                    "focus" -> {
                        if (genre.contains("instrumental") || genre.contains("soundtrack") || genre.contains("ost") || genre.contains("classical") || genre.contains("ambient") || genre.contains("lofi")) score += 4
                    }
                    "romance" -> {
                        if (genre.contains("romance") || genre.contains("r&b") || genre.contains("ballad") || genre.contains("soul") || genre.contains("love") || genre.contains("romantic")) score += 4
                    }
                }

                return score
            }

            val scored = originalTrackList.map { song -> song to scoreSong(song) }
            val matched = scored.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }

            // Only return songs that actually match the mood criteria! Do not append random unrelated tracks!
            matched.shuffled().take(50)
        }
    }
}
