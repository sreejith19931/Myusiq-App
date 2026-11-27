package com.music.myusiq.domain.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-track mood analysis profile produced by the Mood Engine.
 *
 * Classification blends three signals:
 *  - AUDIO   : waveform energy score (1-10) + estimated BPM (existing waveform pipeline)
 *  - TAGS    : genre / title keywords (local tags, plus online genre lookup)
 *  - LEARNED : user playlist behaviour folded back in by the learning pass
 *
 * [moodScores] uses a compact "mood:score|mood:score" format (no JSON dependency in shared).
 */
@Entity(tableName = "mood_profiles")
data class MoodProfile(
    @PrimaryKey val trackId: String,           // Song id as string (matches library_tracks)
    val energyScore: Int = -1,                 // 1-10 audio energy, -1 unknown
    val bpm: Int = -1,                         // Estimated tempo, -1 unknown
    val primaryMood: String = "",              // Best-matching mood ("" when not confident)
    val moodScores: String = "",               // "energize:0.62|relax:0.18|..."
    val genreTags: String = "",                // Comma-separated genre tokens (local + online)
    val keywords: String = "",                 // Comma-separated title/lyric keywords
    val sources: String = "",                  // Comma-separated: AUDIO,TAGS,ONLINE,LYRICS
    val listenFeedback: Double = 0.0,          // -1..1 EMA of listening outcome (skip=-, full listen=+)
    val listenSamples: Int = 0,                // Number of listening events folded into the feedback
    val updatedAt: Long = 0,
)

/**
 * Learned aggregate profile of a playlist's content, used to (a) refine mood
 * classification towards the user's own taste and (b) suggest tracks that
 * match a playlist's sound.
 */
@Entity(tableName = "playlist_profiles")
data class PlaylistProfile(
    @PrimaryKey val profileKey: String,        // "playlist:<id>" or "learned:<mood>"
    val meanEnergy: Double = 0.0,              // 1-10 scale
    val meanBpm: Double = 0.0,
    val genreTokens: String = "",              // "rock:12,metal:8,..." token:count
    val keywordTokens: String = "",            // "love:6,night:3,..."
    val sampleSize: Int = 0,                   // Number of tracks the profile was learned from
    val updatedAt: Long = 0,
)
