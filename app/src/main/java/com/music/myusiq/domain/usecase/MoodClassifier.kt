package com.music.myusiq.domain.usecase

import com.music.myusiq.domain.models.PlaylistProfile
import kotlin.math.abs
import kotlin.math.exp

/**
 * Pure mood-classification logic for the Mood Engine.
 *
 * A track is scored against six moods (matching the existing mood chips) by
 * blending three independent signal families:
 *
 *  1. AUDIO      — waveform energy score (1-10) and estimated BPM, mapped with
 *                  gaussian proximity to a per-mood archetype.
 *  2. TAGS       — genre tokens (local tags and/or online lookups).
 *  3. KEYWORDS   — title and lyric keywords.
 *
 * Learning: [learn] aggregates the tracks the user actually placed in a
 * playlist into a [PlaylistProfile]; [learnedFit] scores how well any track
 * matches that learned taste, and the engine blends it into the base score.
 *
 * Kept free of Android dependencies so it is fully unit-testable.
 */
object MoodClassifier {

    const val MOOD_ENERGIZE = "energize"
    const val MOOD_PARTY = "party"
    const val MOOD_RELAX = "relax"
    const val MOOD_FOCUS = "focus"
    const val MOOD_ROMANCE = "romance"
    const val MOOD_DRIVE = "drive"

    val ALL_MOODS = listOf(MOOD_ENERGIZE, MOOD_PARTY, MOOD_RELAX, MOOD_FOCUS, MOOD_ROMANCE, MOOD_DRIVE)

    /** Minimum best-score for a primary mood to be assigned at all. */
    const val CONFIDENCE_THRESHOLD = 0.40

    data class TrackSignals(
        val energyScore: Int,          // 1..10, -1 = unknown
        val bpm: Int,                  // -1 = unknown
        val genre: String,             // Raw genre string, may be blank / "Unknown Genre"
        val title: String,
        val lyricsText: String = "",   // Plain lyric text, may be blank
        val extraGenreTags: Set<String> = emptySet(), // Genre tokens from online lookup
    )

    data class MoodScoreboard(
        val scores: Map<String, Double>,
        val primaryMood: String,
        val confidence: Double,
    )

    // ---------------------------------------------------------------------
    // Genre affinity: token -> per-mood weight (0..1)
    // ---------------------------------------------------------------------
    private val GENRE_AFFINITY: Map<String, Map<String, Double>> = mapOf(
        // Heavy / rhythmic → energize & party
        "metal" to mapOf(MOOD_ENERGIZE to 1.0, MOOD_PARTY to 0.5, MOOD_DRIVE to 0.5),
        "hardcore" to mapOf(MOOD_ENERGIZE to 1.0, MOOD_PARTY to 0.6),
        "rock" to mapOf(MOOD_ENERGIZE to 0.9, MOOD_DRIVE to 0.9, MOOD_PARTY to 0.5),
        "punk" to mapOf(MOOD_ENERGIZE to 1.0, MOOD_DRIVE to 0.7, MOOD_PARTY to 0.6),
        "grunge" to mapOf(MOOD_ENERGIZE to 0.8, MOOD_DRIVE to 0.7),
        "edm" to mapOf(MOOD_ENERGIZE to 1.0, MOOD_PARTY to 1.0, MOOD_DRIVE to 0.6),
        "electro" to mapOf(MOOD_ENERGIZE to 0.9, MOOD_PARTY to 0.9, MOOD_DRIVE to 0.6),
        "house" to mapOf(MOOD_PARTY to 1.0, MOOD_ENERGIZE to 0.8, MOOD_DRIVE to 0.6),
        "techno" to mapOf(MOOD_PARTY to 0.9, MOOD_ENERGIZE to 0.9, MOOD_DRIVE to 0.5),
        "trance" to mapOf(MOOD_PARTY to 0.9, MOOD_ENERGIZE to 0.9, MOOD_DRIVE to 0.7),
        "dubstep" to mapOf(MOOD_ENERGIZE to 1.0, MOOD_PARTY to 0.9),
        "drum and bass" to mapOf(MOOD_ENERGIZE to 1.0, MOOD_DRIVE to 0.7),
        "jungle" to mapOf(MOOD_ENERGIZE to 0.9, MOOD_PARTY to 0.6),
        "dance" to mapOf(MOOD_PARTY to 1.0, MOOD_ENERGIZE to 0.9, MOOD_DRIVE to 0.6),
        "club" to mapOf(MOOD_PARTY to 1.0, MOOD_ENERGIZE to 0.8),
        "party" to mapOf(MOOD_PARTY to 1.0, MOOD_ENERGIZE to 0.8),
        "phonk" to mapOf(MOOD_ENERGIZE to 0.9, MOOD_DRIVE to 0.8, MOOD_PARTY to 0.5),
        "trap" to mapOf(MOOD_ENERGIZE to 0.9, MOOD_PARTY to 0.8, MOOD_DRIVE to 0.6),
        "hip hop" to mapOf(MOOD_PARTY to 0.6, MOOD_ENERGIZE to 0.6, MOOD_DRIVE to 0.7),
        "hiphop" to mapOf(MOOD_PARTY to 0.6, MOOD_ENERGIZE to 0.6, MOOD_DRIVE to 0.7),
        "hip-hop" to mapOf(MOOD_PARTY to 0.6, MOOD_ENERGIZE to 0.6, MOOD_DRIVE to 0.7),
        "rap" to mapOf(MOOD_PARTY to 0.6, MOOD_ENERGIZE to 0.6, MOOD_DRIVE to 0.7),
        "bhangra" to mapOf(MOOD_PARTY to 1.0, MOOD_ENERGIZE to 0.9),
        "bollywood" to mapOf(MOOD_PARTY to 0.6, MOOD_ROMANCE to 0.5, MOOD_DRIVE to 0.5),
        "workout" to mapOf(MOOD_ENERGIZE to 1.0, MOOD_PARTY to 0.6),
        "gym" to mapOf(MOOD_ENERGIZE to 1.0),
        "sport" to mapOf(MOOD_ENERGIZE to 0.9, MOOD_DRIVE to 0.6),
        "pop" to mapOf(MOOD_PARTY to 0.5, MOOD_ENERGIZE to 0.5, MOOD_DRIVE to 0.6, MOOD_ROMANCE to 0.4),
        "synth" to mapOf(MOOD_DRIVE to 0.9, MOOD_FOCUS to 0.4),
        "synthwave" to mapOf(MOOD_DRIVE to 1.0, MOOD_FOCUS to 0.4),
        "retrowave" to mapOf(MOOD_DRIVE to 0.9),
        "indie" to mapOf(MOOD_DRIVE to 0.8, MOOD_FOCUS to 0.5, MOOD_RELAX to 0.4),
        "alternative" to mapOf(MOOD_DRIVE to 0.7, MOOD_FOCUS to 0.4),
        "road" to mapOf(MOOD_DRIVE to 0.9),
        "highway" to mapOf(MOOD_DRIVE to 0.9),
        // Calm → relax & focus
        "chill" to mapOf(MOOD_RELAX to 1.0, MOOD_FOCUS to 0.7),
        "chillout" to mapOf(MOOD_RELAX to 1.0, MOOD_FOCUS to 0.6),
        "chillhop" to mapOf(MOOD_RELAX to 0.9, MOOD_FOCUS to 0.8),
        "lofi" to mapOf(MOOD_RELAX to 0.9, MOOD_FOCUS to 1.0),
        "lo-fi" to mapOf(MOOD_RELAX to 0.9, MOOD_FOCUS to 1.0),
        "ambient" to mapOf(MOOD_RELAX to 1.0, MOOD_FOCUS to 0.9),
        "acoustic" to mapOf(MOOD_RELAX to 0.8, MOOD_ROMANCE to 0.6, MOOD_FOCUS to 0.5),
        "soft" to mapOf(MOOD_RELAX to 0.9, MOOD_ROMANCE to 0.5),
        "smooth" to mapOf(MOOD_RELAX to 0.8, MOOD_ROMANCE to 0.7),
        "lounge" to mapOf(MOOD_RELAX to 0.9, MOOD_DRIVE to 0.4),
        "downtempo" to mapOf(MOOD_RELAX to 0.9, MOOD_FOCUS to 0.7),
        "sleep" to mapOf(MOOD_RELAX to 1.0),
        "lullaby" to mapOf(MOOD_RELAX to 1.0),
        "meditation" to mapOf(MOOD_RELAX to 1.0, MOOD_FOCUS to 0.6),
        "piano" to mapOf(MOOD_RELAX to 0.7, MOOD_FOCUS to 0.8, MOOD_ROMANCE to 0.6),
        "classical" to mapOf(MOOD_FOCUS to 0.9, MOOD_RELAX to 0.8),
        "orchestral" to mapOf(MOOD_FOCUS to 0.8, MOOD_RELAX to 0.5),
        "instrumental" to mapOf(MOOD_FOCUS to 0.9, MOOD_RELAX to 0.7),
        "study" to mapOf(MOOD_FOCUS to 1.0),
        "focus" to mapOf(MOOD_FOCUS to 1.0),
        "jazz" to mapOf(MOOD_FOCUS to 0.6, MOOD_RELAX to 0.7, MOOD_ROMANCE to 0.5),
        "blues" to mapOf(MOOD_ROMANCE to 0.6, MOOD_RELAX to 0.5, MOOD_DRIVE to 0.4),
        "flamenco" to mapOf(MOOD_DRIVE to 0.5, MOOD_ROMANCE to 0.4),
        // Warm → romance
        "r&b" to mapOf(MOOD_ROMANCE to 1.0, MOOD_RELAX to 0.5),
        "rnb" to mapOf(MOOD_ROMANCE to 1.0, MOOD_RELAX to 0.5),
        "soul" to mapOf(MOOD_ROMANCE to 0.9, MOOD_RELAX to 0.6),
        "ballad" to mapOf(MOOD_ROMANCE to 1.0, MOOD_RELAX to 0.5),
        "love" to mapOf(MOOD_ROMANCE to 1.0),
        "romance" to mapOf(MOOD_ROMANCE to 1.0),
        "romantic" to mapOf(MOOD_ROMANCE to 1.0),
        "wedding" to mapOf(MOOD_ROMANCE to 0.9),
        "sensual" to mapOf(MOOD_ROMANCE to 0.9),
        "country" to mapOf(MOOD_DRIVE to 0.5, MOOD_RELAX to 0.4, MOOD_ROMANCE to 0.3),
        "folk" to mapOf(MOOD_RELAX to 0.6, MOOD_DRIVE to 0.4, MOOD_FOCUS to 0.4),
    )

    /** Mood -> title keyword set (case-insensitive substrings). */
    val TITLE_KEYWORDS: Map<String, Set<String>> = mapOf(
        MOOD_ENERGIZE to setOf("workout", "gym", "hype", "energy", "power", "warrior", "fight", "rise", "unstoppable", "thunder", "fire", "beast", "stronger", "alive", "sher", "singham", "veeram"),
        MOOD_PARTY to setOf("party", "dance", "club", "night out", "celebrate", "weekend", " friday", " fiesta", "banger", "get loud", "turn up", "disco", "kuthu", "gaana", "gana", "dappan", "dappanguthu", "jalsa", "jatar", "bangaraju"),
        MOOD_RELAX to setOf("sleep", "lullaby", "relax", "peaceful", "calm", "quiet", "chill", "cozy", "serenity", "breathe", "sunset", "slow down", "unwind"),
        MOOD_FOCUS to setOf("study", "focus", "deep", "flow", "concentration", "work", "rain", "waves", "river", "window"),
        // Romance vocabulary spans Indian film-song languages: Urdu/Hindi
        // (ishq, pyaar, mohabbat, sanam...), Tamil (kadhal, anbe, kanne...),
        // Telugu/Malayalam (prema, priya, kanne...).
        MOOD_ROMANCE to setOf("love", "heart", "kiss", "forever", "you & i", "only you", "beautiful", "angel", "honey", "baby i", "soulmate", "crush", "valentine", "ishq", "pyaar", "pyar", "mohabbat", "sanam", "deewana", "deewani", "dhadkan", "dilbar", "dildara", "prem", "prema", "kadhal", "kaadhal", "anbe", "anbu", "kanne", "kanmani", "priya", "piya", "sajna", "sajan", "chalun"),
        MOOD_DRIVE to setOf("road", "highway", "drive", "driving", "car", "city lights", "midnight drive", "route", "ride", "raftaar"),
    )

    /** Mood -> lyric keyword set (word-level, weighted same as title keywords). */
    val LYRIC_KEYWORDS: Map<String, Set<String>> = mapOf(
        MOOD_ENERGIZE to setOf("fight", "stronger", "alive", "run faster", "push", "never give up", "unstoppable", "warrior", "rise up", "burn", "winner"),
        MOOD_PARTY to setOf("dance all night", "hands up", "everybody", "celebrate", "tonight we", "get down", "put your hands", "move your body", "drinks", "shots"),
        MOOD_RELAX to setOf("drift away", "fall asleep", "let go", "slow down", "breathe", "peaceful", "gentle", "floating", "dream", "rest"),
        MOOD_FOCUS to setOf("concentrate", "flow", "silence", "study", "one more hour", "deep end"),
        MOOD_ROMANCE to setOf("i love you", "my heart", "your eyes", "kiss", "hold me", "forever you", "my love", "in love", "marry", "valentine"),
        MOOD_DRIVE to setOf("hit the road", "driving", "open road", "highway", "miles", "engine", "radio loud", "wheels"),
    )

    /** Playlist-name words → mood (for the learning pass). */
    val MOOD_NAME_HINTS: Map<String, List<String>> = mapOf(
        MOOD_ENERGIZE to listOf("gym", "workout", "work out", "training", "sport", "running", "energy", "hype", "motivation", "power", "grind"),
        MOOD_PARTY to listOf("party", "dance", "club", "banger", "fiesta", "weekend", "turn up", "disco"),
        MOOD_RELAX to listOf("chill", "relax", "calm", "sleep", "cozy", "mellow", "unwind", "sunday", "evening", "peaceful", "soft"),
        MOOD_FOCUS to listOf("focus", "study", "studying", "deep work", "concentration", "coding", "reading", "flow"),
        MOOD_ROMANCE to listOf("love", "romantic", "romance", "heart", "valentine", "wedding", "crush", "soulful", "slow"),
        MOOD_DRIVE to listOf("drive", "driving", "road trip", "roadtrip", "highway", "car", "cruise", "ride"),
    )

    private data class MoodAudioArchetype(val energyTarget: Double, val energySpread: Double, val bpmTarget: Double, val bpmSpread: Double)

    private val AUDIO_ARCHETYPES: Map<String, MoodAudioArchetype> = mapOf(
        MOOD_ENERGIZE to MoodAudioArchetype(8.5, 1.6, 128.0, 25.0),
        MOOD_PARTY to MoodAudioArchetype(8.0, 1.8, 122.0, 28.0),
        MOOD_DRIVE to MoodAudioArchetype(6.5, 2.0, 108.0, 30.0),
        MOOD_ROMANCE to MoodAudioArchetype(4.5, 2.0, 88.0, 30.0),
        MOOD_FOCUS to MoodAudioArchetype(3.5, 1.8, 95.0, 30.0),
        MOOD_RELAX to MoodAudioArchetype(3.0, 1.8, 84.0, 28.0),
    )

    private fun gaussian(value: Double, target: Double, spread: Double): Double {
        if (value.isNaN()) return 0.0
        val d = (value - target) / spread
        return exp(-0.5 * d * d)
    }

    fun tokenizeGenre(genre: String, extraTags: Set<String> = emptySet()): List<String> {
        val raw = (genre.takeIf { it.isNotBlank() && !it.equals("unknown genre", true) } ?: "") +
            extraTags.joinToString(" ")
        if (raw.isBlank()) return emptyList()
        return raw.lowercase()
            .split(",", "/", "&", ";", "|")
            .flatMap { it.trim().split(" ", "-", "_") }
            .map { it.trim() }
            .filter { it.length >= 3 }
    }

    private fun matchedTokenWeights(tokens: List<String>): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        for (token in tokens) {
            // Match either the exact token or a genre phrase containing it
            for ((genre, weights) in GENRE_AFFINITY) {
                if (genre == token || (genre.contains(token) && token.length >= 4)) {
                    for ((mood, w) in weights) {
                        result[mood] = maxOf(result[mood] ?: 0.0, w)
                    }
                }
            }
        }
        return result
    }

    private fun keywordMatches(text: String, keywords: Set<String>): Int =
        keywords.count { text.contains(it, ignoreCase = true) }

    /**
     * Exponentially-weighted listening feedback: +1 for a full listen (track
     * kept to >= 70%), -1 for an early skip (<= 25%). The EMA (70% old / 30%
     * new) tolerates occasional flukes while letting repeated skips drag a
     * profile's trust down.
     */
    /**
     * Dampens a track's penalized mood (the one the user kept skipping) and
     * re-picks the primary mood from the remaining scores. This is how
     * listening feedback changes a track's CATEGORY, not just its ranking:
     * repeated early skips of a track classified "drive" let the next-best
     * mood (e.g. romance from title keywords) take over. Returns the updated
     * serialized scores and the new primary mood ("" when nothing is left
     * above the confidence threshold).
     */
    fun demoteMood(moodScores: String, penalizedMood: String): Pair<String, String> {
        val scores = parseMoodScores(moodScores).toMutableMap()
        if (penalizedMood !in scores) {
            val current = scores.entries.filter { it.value >= CONFIDENCE_THRESHOLD }
                .maxByOrNull { it.value }?.key ?: ""
            return moodScores to current
        }
        scores[penalizedMood] = (scores[penalizedMood] ?: 0.0) * 0.5
        val newScores = serializeMoodScores(scores)
        val newPrimary = scores.entries
            .filter { it.value >= CONFIDENCE_THRESHOLD }
            .maxByOrNull { it.value }?.key ?: ""
        return newScores to newPrimary
    }

    fun nextListenFeedback(oldFeedback: Double, listenedFraction: Float): Double {
        val sample = when {
            listenedFraction >= 0.70f -> 1.0
            listenedFraction <= 0.25f -> -1.0
            else -> 0.0
        }
        if (sample == 0.0) return oldFeedback
        return ((oldFeedback * 0.7) + (sample * 0.3)).coerceIn(-1.0, 1.0)
    }

    /**
     * Moods for which the track's title (and optional lyrics) carry explicit
     * keyword evidence, e.g. "Aakhri Ishq" is direct romance evidence while
     * "Didi (Sher-E-Baloch)" carries none. Used by the mood mixes to separate
     * keyword-confirmed tracks from weak genre-only candidates.
     */
    fun keywordMoodEvidence(title: String, lyrics: String? = null): Set<String> {
        val titleLower = title.lowercase()
        val lyricsLower = lyrics?.lowercase()?.take(4000)
        return ALL_MOODS.filter { mood ->
            TITLE_KEYWORDS.getValue(mood).any { titleLower.contains(it, ignoreCase = true) } ||
                (lyricsLower != null && LYRIC_KEYWORDS.getValue(mood).any { lyricsLower.contains(it, ignoreCase = true) })
        }.toSet()
    }

    /**
     * Scores the track against every mood. Signal weights are re-normalised
     * according to which signals are actually available.
     */
    fun classify(signals: TrackSignals): MoodScoreboard {
        val hasAudio = signals.energyScore in 1..10
        val hasBpm = signals.bpm in 40..220
        val genreTokens = tokenizeGenre(signals.genre, signals.extraGenreTags)
        val tokenWeights = matchedTokenWeights(genreTokens)
        val hasTags = tokenWeights.isNotEmpty()
        val titleLower = signals.title.lowercase()
        val lyricsLower = signals.lyricsText.lowercase().take(4000) // Bound scanning cost
        val hasLyrics = lyricsLower.isNotBlank()

        var wAudio = 0.50
        var wTags = 0.30
        var wKeywords = 0.20
        if (!hasAudio) wAudio = 0.0
        if (!hasTags) wTags = 0.0
        if (wAudio == 0.0 && wTags == 0.0 && wKeywords > 0.0) wKeywords = 1.0
        val weightSum = wAudio + wTags + wKeywords
        if (weightSum <= 0.0) {
            return MoodScoreboard(ALL_MOODS.associateWith { 0.0 }, "", 0.0)
        }

        val scores = ALL_MOODS.associateWith { mood ->
            var score = 0.0

            if (hasAudio) {
                val arch = AUDIO_ARCHETYPES.getValue(mood)
                val energyFit = gaussian(signals.energyScore.toDouble(), arch.energyTarget, arch.energySpread)
                val bpmFit = if (hasBpm) gaussian(signals.bpm.toDouble(), arch.bpmTarget, arch.bpmSpread) else 0.55
                score += wAudio * (0.65 * energyFit + 0.35 * bpmFit) / weightSum * 1.0
            }

            if (hasTags) {
                // Affinity weight of the best-matching genre token for this mood,
                // softened by the presence of contradictory tokens.
                val best = tokenWeights[mood] ?: 0.0
                score += wTags * best / weightSum
            }

            var kwScore = 0.0
            val titleSets = TITLE_KEYWORDS.getValue(mood)
            val lyricSets = LYRIC_KEYWORDS.getValue(mood)
            val titleHits = keywordMatches(titleLower, titleSets)
            kwScore += 0.6 * (titleHits.coerceAtMost(2) / 2.0)
            if (hasLyrics) {
                val lyricHits = keywordMatches(lyricsLower, lyricSets)
                kwScore += 0.4 * (lyricHits.coerceAtMost(2) / 2.0)
            } else {
                kwScore += 0.4 * (titleHits.coerceAtMost(3) / 3.0)
            }
            score += wKeywords * kwScore / weightSum

            score.coerceIn(0.0, 1.0)
        }

        val primary = scores.maxByOrNull { it.value }
        val primaryMood = if (primary != null && primary.value >= CONFIDENCE_THRESHOLD) primary.key else ""
        return MoodScoreboard(scores, primaryMood, primary?.value ?: 0.0)
    }

    // ---------------------------------------------------------------------
    // Learning
    // ---------------------------------------------------------------------

    /**
     * Aggregates the signals of the tracks a playlist actually contains into a
     * learned taste profile. Returns null for empty playlists.
     */
    fun learn(signalsList: List<TrackSignals>): PlaylistProfile? {
        val usable = signalsList.filter { it.energyScore in 1..10 || tokenizeGenre(it.genre).isNotEmpty() || it.title.isNotBlank() }
        if (usable.isEmpty()) return null

        val withEnergy = usable.filter { it.energyScore in 1..10 }
        val withBpm = usable.filter { it.bpm in 40..220 }

        val meanEnergy = if (withEnergy.isNotEmpty()) withEnergy.map { it.energyScore.toDouble() }.average() else 0.0
        val meanBpm = if (withBpm.isNotEmpty()) withBpm.map { it.bpm.toDouble() }.average() else 0.0

        val genreCounts = linkedMapOf<String, Int>()
        val keywordCounts = linkedMapOf<String, Int>()
        for (s in usable) {
            tokenizeGenre(s.genre, s.extraGenreTags).forEach { g ->
                genreCounts[g] = (genreCounts[g] ?: 0) + 1
            }
            val text = s.title.lowercase() + " " + s.lyricsText.lowercase().take(2000)
            for (moodKeywords in TITLE_KEYWORDS.values) {
                for (kw in moodKeywords) {
                    if (kw.length >= 4 && text.contains(kw)) {
                        keywordCounts[kw.trim()] = (keywordCounts[kw.trim()] ?: 0) + 1
                    }
                }
            }
        }

        fun serialize(counts: Map<String, Int>, maxTokens: Int): String =
            counts.entries.sortedByDescending { it.value }.take(maxTokens)
                .joinToString(",") { "${it.key}:${it.value}" }

        return PlaylistProfile(
            profileKey = "",
            meanEnergy = meanEnergy,
            meanBpm = meanBpm,
            genreTokens = serialize(genreCounts, 20),
            keywordTokens = serialize(keywordCounts, 15),
            sampleSize = usable.size,
            updatedAt = 0L,
        )
    }

    /** How well a track matches a learned playlist profile (0..1). */
    fun learnedFit(profile: PlaylistProfile, signals: TrackSignals): Double {
        if (profile.sampleSize <= 0) return 0.0
        var fit = 0.0
        var weight = 0.0

        if (signals.energyScore in 1..10 && profile.meanEnergy > 0.0) {
            fit += 0.5 * gaussian(signals.energyScore.toDouble(), profile.meanEnergy, 1.8)
            weight += 0.5
        }
        if (signals.bpm in 40..220 && profile.meanBpm > 0.0) {
            fit += 0.3 * gaussian(signals.bpm.toDouble(), profile.meanBpm, 30.0)
            weight += 0.3
        }

        val profileTokens = parseTokenCounts(profile.genreTokens)
        if (profileTokens.isNotEmpty()) {
            val trackTokens = tokenizeGenre(signals.genre, signals.extraGenreTags).toSet()
            val overlap = trackTokens.count { t -> profileTokens.any { (tok, _) -> tok == t || (tok.contains(t) && t.length >= 4) } }
            val tokenFit = if (trackTokens.isEmpty()) 0.35 else overlap.coerceAtMost(3) / 3.0
            fit += 0.2 * tokenFit
            weight += 0.2
        }

        return if (weight > 0.0) (fit / weight).coerceIn(0.0, 1.0) else 0.0
    }

    fun parseTokenCounts(serialized: String): List<Pair<String, Int>> =
        serialized.split(",").mapNotNull { entry ->
            val idx = entry.lastIndexOf(':')
            if (idx <= 0) null
            else {
                val count = entry.substring(idx + 1).trim().toIntOrNull() ?: return@mapNotNull null
                entry.substring(0, idx).trim().lowercase() to count
            }
        }

    /** Maps a playlist name to the moods it plausibly represents (may be empty). */
    fun moodsForPlaylistName(name: String): List<String> {
        val lower = name.lowercase()
        return ALL_MOODS.filter { mood ->
            MOOD_NAME_HINTS.getValue(mood).any { lower.contains(it) }
        }
    }

    fun serializeMoodScores(scores: Map<String, Double>): String =
        scores.entries.sortedByDescending { it.value }
            .joinToString("|") { "${it.key}:${String.format(java.util.Locale.US, "%.2f", it.value)}" }

    fun parseMoodScores(serialized: String): Map<String, Double> =
        serialized.split("|").mapNotNull { entry ->
            val idx = entry.lastIndexOf(':')
            if (idx <= 0) null else {
                val score = entry.substring(idx + 1).trim().toDoubleOrNull() ?: return@mapNotNull null
                entry.substring(0, idx).trim() to score
            }
        }.toMap()

    private fun maxOf(a: Double, b: Double) = if (a >= b) a else b
}
