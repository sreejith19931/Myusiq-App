package com.music.myusiq.ui.viewmodels

import com.music.myusiq.data.MusicRepository
import com.music.myusiq.domain.models.Song
import com.music.myusiq.utils.TransliterationUtil
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
                val year = song.dateAdded.toString() // Or override year if parsed
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

            // Multilingual Keyword Dictionaries
            val energizeKeywords = listOf(
                "rock", "pop", "dance", "edm", "metal", "electronic", "hip hop", "rap", "workout", "gym", "power", "energy", "hype", "club", "upbeat", "pump", "fitness", "motivation", "punk", "synth",
                "aaghosham", "kalyanam", "aatam", "kondattam", "bhangra", "gabru", "gaddi", "aata", "veduka", "nacha", "masti", "hungama", "fiesta", "baile"
            )
            val relaxKeywords = listOf(
                "chill", "relax", "sleep", "piano", "acoustic", "lofi", "lo-fi", "ambient", "meditation", "calm", "soft", "quiet", "peace", "jazz", "classical", "instrumental", "sunset", "spa", "zen", "lullaby",
                "shanthi", "chaarathu", "swapnam", "amaidhi", "nenjame", "prasanthatha", "sukoon", "sueño"
            )
            val partyKeywords = listOf(
                "party", "dance", "club", "house", "disco", "remix", "dj", "edm", "groove", "funk", "celebration", "festival", "boogie", "techno",
                "masti", "bhangra", "aatam", "kondattam", "aaghosham", "aata", "veduka", "fiesta", "baile", "hungama"
            )
            val focusKeywords = listOf(
                "study", "focus", "instrumental", "classical", "ambient", "soundtrack", "ost", "lofi", "lo-fi", "piano", "coding", "chillhop", "concentration",
                "shanthi", "amaidhi", "prasanthatha", "dhyan"
            )
            val romanceKeywords = listOf(
                "love", "romantic", "romance", "heart", "ballad", "duet", "kiss", "forever", "sweet", "darling", "baby", "soul", "r&b", "passion", "valentine", "honey", "wedding", "desire", "affection", "amour", "beloved", "crush", "lovers", "serenade", "sweetheart", "tender",
                "premam", "sneham", "paattu", "kadhal", "anbu", "aasai", "pyaar", "dil", "dosti", "yaari", "kudi", "prema", "manasu", "ishq", "mohabbat", "humsafar", "amor", "corazon", "pasion"
            )

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

            suspend fun scoreSong(song: Song): Int {
                var score = 0
                val title = song.title.lowercase()
                val artist = song.artist.lowercase()
                val album = song.album.lowercase()
                val genre = song.genre.lowercase()

                // Extract lyrics and transliterate non-Latin scripts (Devanagari, Tamil, Malayalam, etc.) to Romanized Latin offline
                val rawLyrics = musicRepository.getLyricsForPath(song.path)?.rawLyrics ?: ""
                val transliteratedLyrics = if (rawLyrics.isNotBlank()) TransliterationUtil.transliterate(rawLyrics).lowercase() else ""

                val fullText = "$title $artist $album $genre $transliteratedLyrics"

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

            matched.shuffled().take(50)
        }
    }
}
