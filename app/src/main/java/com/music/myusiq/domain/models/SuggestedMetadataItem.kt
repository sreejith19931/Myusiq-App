package com.music.myusiq.domain.models

import com.music.myusiq.domain.models.Song

/**
 * Represents a potential metadata match found by the system.
 */
data class SuggestedMetadataItem(
    val matchId: String,
    val originalSong: Song,
    val suggestedTitle: String,
    val suggestedArtist: String,
    val suggestedAlbum: String,
    val suggestedYear: String?,
    val suggestedGenre: String?,
    val suggestedArtUri: String?,
    val confidenceScore: Float
)

/**
 * Defines the different types of actions the Janitor Scan can take on a track.
 */
enum class JanitorItemType {
    /** Automatic fix: Metadata is corrected based on high confidence or system rules. */
    AUTO_FIX,
    /** Suggestion: Metadata requires user review and confirmation. */
    SUGGESTION,
    /** No action needed: Track is clean or irrelevant. */
    NONE
}