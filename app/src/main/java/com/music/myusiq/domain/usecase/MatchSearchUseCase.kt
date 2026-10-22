package com.music.myusiq.domain.usecase

import android.content.Context
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.models.SuggestedMetadataItem
import com.music.myusiq.utils.CloudMetadataResolver
import com.music.myusiq.utils.YouTubeTitleCleaner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Use case for searching metadata matches across external databases (iTunes, Deezer, MusicBrainz).
 * Matches are evaluated against acoustic fingerprint properties (e.g. track duration, waveform signature)
 * and sanitized title/artist text.
 */
import kotlin.math.abs

class MatchSearchUseCase {

    /**
     * Searches for close metadata matches for a track.
     *
     * @param song The original song model.
     * @param fingerprint The acoustic waveform fingerprint string.
     * @param suggestedYear Fallback year hint.
     * @param suggestedGenre Fallback genre hint.
     * @param context Optional Android Context for cache/download operations.
     * @return A Flow emitting candidate SuggestedMetadataItem matches sorted by confidence score.
     */
    fun findMatches(
        song: Song,
        fingerprint: String,
        suggestedYear: String?,
        suggestedGenre: String?,
        context: Context? = null
    ): Flow<SuggestedMetadataItem> = flow {
        val (extractedArtist, extractedTitle) = YouTubeTitleCleaner.cleanAndSplit(song.title)
        val cleanTitle = extractedTitle.ifBlank { YouTubeTitleCleaner.cleanTitle(song.title) }.ifBlank { YouTubeTitleCleaner.cleanFilename(song.path) }
        val cleanArtist = if (!CloudMetadataResolver.isGenericArtist(song.artist)) {
            song.artist
        } else if (extractedArtist != "Unknown Artist" && !CloudMetadataResolver.isGenericArtist(extractedArtist)) {
            extractedArtist
        } else ""

        val searchQuery = if (cleanArtist.isNotBlank()) "$cleanArtist $cleanTitle" else cleanTitle

        val durationSec = (song.duration / 1000).coerceAtLeast(0)
        val acoustIdMatches = if (durationSec > 0 && fingerprint.isNotBlank()) {
            com.music.myusiq.utils.AcoustIdClient.lookup(durationSec, fingerprint)
        } else emptyList()

        val acoustIdCandidates = mutableListOf<com.music.myusiq.utils.CloudMetadata>()
        for (match in acoustIdMatches.take(2)) {
            val meta = CloudMetadataResolver.fetchMusicBrainzByMbid(match.recordingId)
            if (meta != null) {
                acoustIdCandidates.add(meta.copy(matchConfidence = maxOf(meta.matchConfidence, match.score)))
            }
        }

        var candidateList = acoustIdCandidates + CloudMetadataResolver.searchMultiple(
            query = searchQuery,
            cleanTitle = cleanTitle,
            cleanArtist = cleanArtist
        )

        if (candidateList.isEmpty() && cleanTitle.isNotBlank()) {
            val fallbackQuery = File(song.path).nameWithoutExtension
            candidateList = CloudMetadataResolver.searchMultiple(
                query = fallbackQuery,
                cleanTitle = cleanTitle,
                cleanArtist = cleanArtist
            )
        }

        if (candidateList.isEmpty()) {
            val single = CloudMetadataResolver.resolve(cleanTitle, cleanArtist)
            if (single != null) {
                candidateList = listOf(single)
            } else {
                val offlineFallback = CloudMetadataResolver.extractOfflineFallbackMetadata(song.path, song.title, song.artist, song.album)
                candidateList = listOf(offlineFallback)
            }
        }

        val hasAcousticProfile = fingerprint.contains("energy:")
        val localDuration = song.duration

        candidateList.take(5).forEachIndexed { index, meta ->
            val isMatch = meta.foundTitle != null && CloudMetadataResolver.isAcceptableMatch(
                cleanTitle,
                cleanArtist,
                meta.foundTitle,
                meta.foundArtist ?: ""
            )

            var score = meta.matchConfidence.toFloat()
            if (isMatch) {
                score = (score + 0.15f).coerceAtMost(1.0f)
            }

            // Duration accuracy validation (prevents matching wrong song, remix, or live version)
            if (localDuration > 0 && meta.durationMs != null && meta.durationMs > 0) {
                val diffMs = abs(localDuration - meta.durationMs)
                if (diffMs <= 3000L) {
                    score = (score + 0.15f).coerceAtMost(1.0f) // Exact / near-exact duration
                } else if (diffMs <= 10000L) {
                    score = (score + 0.05f).coerceAtMost(1.0f) // Acceptable duration difference
                } else if (diffMs > 25000L) {
                    score -= 0.40f // Significant duration mismatch -> heavy penalty
                }
            }

            if (hasAcousticProfile && isMatch) {
                score = (score + 0.05f).coerceAtMost(1.0f)
            }

            val finalScore = score.coerceIn(0.0f, 1.0f)
            if (finalScore < 0.45f) return@forEachIndexed // Skip poor matches

            val item = SuggestedMetadataItem(
                matchId = "match-${index + 1}-${meta.foundTitle?.hashCode() ?: song.id}",
                originalSong = song,
                suggestedTitle = meta.foundTitle ?: cleanTitle,
                suggestedArtist = meta.foundArtist ?: cleanArtist,
                suggestedAlbum = meta.album ?: song.album,
                suggestedYear = meta.year ?: suggestedYear,
                suggestedGenre = meta.genre ?: suggestedGenre,
                suggestedArtUri = meta.artworkUrl,
                confidenceScore = finalScore
            )
            emit(item)
        }
    }
}
