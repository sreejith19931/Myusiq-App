package com.music.myusiq.domain.usecase

import android.util.Log
import com.music.myusiq.data.MusicRepository
import com.music.myusiq.data.dao.MoodDao
import com.music.myusiq.domain.models.MoodProfile
import com.music.myusiq.domain.models.PlaylistProfile
import com.music.myusiq.domain.models.PlaylistWithTracks
import com.music.myusiq.domain.models.Song
import com.music.myusiq.utils.CloudMetadataResolver
import com.music.myusiq.utils.MusicEnergyAnalyzer
import com.music.myusiq.utils.WaveformExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Mood Engine: classifies the library into moods, learns from the user's
 * own playlists, and (re)organizes mood mixes accordingly.
 *
 * Signal pipeline per track:
 *  1. AUDIO  — energy score + estimated BPM from the stored waveform
 *              (computed lazily by playback; tracks without waveforms fall
 *              back to tag/keyword classification until analyzed).
 *  2. TAGS   — local genre + title keywords; lyric text from cached LRC/text.
 *  3. ONLINE — genre lookup via iTunes / Deezer / MusicBrainz for tracks
 *              whose local genre is unknown (rate-limited, capped per run).
 *  4. LEARNED — aggregate profiles of the user's mood-named playlists folded
 *              back into the scoring blend.
 */
class MoodEngine(
    private val repository: MusicRepository,
    private val moodDao: MoodDao,
    private val metadataResolver: CloudMetadataResolver? = null,
) {

    data class OrganizeResult(
        val analyzed: Int,
        val classified: Int,
        val enrichedOnline: Int,
        val playlistsLearned: Int,
    )

    /** True when the local genre field carries no usable information. */
    private fun genreIsUnknown(genre: String): Boolean {
        val g = genre.trim()
        // MediaStore frequently reports junk genres ("music", "other") that
        // are worthless for mood evidence — treat them as unknown so online
        // enrichment can fetch real genres from iTunes/Deezer/MusicBrainz.
        return g.isBlank() || g.equals("Unknown Genre", true) ||
            g.equals("music", true) || g.equals("other", true) || g.equals("unknown", true)
    }

    private fun parseLrcToText(raw: String): String {
        if (raw.isBlank()) return ""
        // Strip LRC timestamps and metadata tags
        return raw.lineSequence()
            .map { it.replace(Regex("\\[[^\\]]*]"), " ") }
            .joinToString(" ")
            .take(6000)
    }

    private suspend fun buildSignals(
        song: Song,
        trackEnergy: Int,
        waveform: String?,
        onlineGenre: String?,
        lyrics: String?,
    ): MoodClassifier.TrackSignals {
        val waveformUsable = !WaveformExtractor.isSaturated(waveform) &&
            !WaveformExtractor.isLegacyPeakEnvelope(waveform)
        val energy = if (waveformUsable) trackEnergy else -1
        // BPM only from envelopes fine enough to pulse with beats; coarse
        // (low-resolution) envelopes would yield arbitrary doubled values.
        val bpm = if (waveform.isNullOrBlank() || !waveformUsable || energy < 1 ||
            WaveformExtractor.isLowResolution(waveform)
        ) -1 else MusicEnergyAnalyzer.estimateBpm(waveform, song.duration.toLong())

        val extraTags = onlineGenre?.let { MoodClassifier.tokenizeGenre(it).toSet() } ?: emptySet()

        return MoodClassifier.TrackSignals(
            energyScore = energy,
            bpm = bpm,
            genre = song.genre,
            title = song.title,
            lyricsText = parseLrcToText(lyrics ?: ""),
            extraGenreTags = extraTags,
        )
    }

    private fun profileFromSignals(
        trackId: String,
        signals: MoodClassifier.TrackSignals,
        sources: String,
        now: Long,
    ): MoodProfile {
        val scoreboard = MoodClassifier.classify(signals)
        val genreTokens = MoodClassifier.tokenizeGenre(signals.genre, signals.extraGenreTags).distinct()
        val titleLower = signals.title.lowercase()
        val lyricsLower = signals.lyricsText.lowercase().take(2000)
        val keywords = buildList {
            for (kwSet in MoodClassifier.TITLE_KEYWORDS.values) for (kw in kwSet) if (titleLower.contains(kw)) add(kw)
            for (kwSet in MoodClassifier.LYRIC_KEYWORDS.values) for (kw in kwSet) if (lyricsLower.contains(kw)) add(kw)
        }.distinct().joinToString(",") { it.trim() }

        return MoodProfile(
            trackId = trackId,
            energyScore = signals.energyScore,
            bpm = signals.bpm,
            primaryMood = scoreboard.primaryMood,
            moodScores = MoodClassifier.serializeMoodScores(scoreboard.scores),
            genreTags = genreTokens.joinToString(","),
            keywords = keywords,
            sources = sources,
            updatedAt = now,
        )
    }

    /**
     * Classifies library tracks that have no mood profile yet (or whose
     * energy was never analyzed). Online genre lookups are rate-limited by
     * [CloudMetadataResolver] and capped by [onlineLimit].
     */
    suspend fun organize(
        songs: List<Song>,
        enrichOnline: Boolean = true,
        onlineLimit: Int = 25,
    ): OrganizeResult = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext OrganizeResult(0, 0, 0, 0)

        val tracksById = repository.getAllLibraryTracksSync().associateBy { it.id }
        val lyricsByPath = try {
            repository.getAllLyricsOverridesSync().associateBy { it.songPath }
        } catch (_: Exception) {
            emptyMap()
        }
        val existingProfiles = moodDao.getAllMoodProfiles().associateBy { it.trackId }

        val now = System.currentTimeMillis()
        val toClassify = mutableListOf<Song>()
        var skipped = 0

        for (song in songs) {
            val id = song.id.toString()
            val track = tracksById[id]
            val profile = existingProfiles[id]

            // A profile is only trusted when it carries real audio evidence
            // from a usable RMS waveform. Profiles written from legacy/corrupt
            // waveforms (e.g. peak-envelope energy=10, fallback bpm) get
            // re-classified.
            val trackWaveformUsable = track?.waveformAmplitudes != null &&
                !WaveformExtractor.isSaturated(track.waveformAmplitudes) &&
                !WaveformExtractor.isLegacyPeakEnvelope(track.waveformAmplitudes)
            val profileTrusted = profile != null && profile.energyScore in 1..10 && trackWaveformUsable
            if (profileTrusted) {
                skipped++
                continue
            }
            val audioUsable = (track?.energyScore ?: -1) in 1..10 &&
                !WaveformExtractor.isSaturated(track?.waveformAmplitudes) &&
                !WaveformExtractor.isLegacyPeakEnvelope(track?.waveformAmplitudes)
            if (!audioUsable && profile != null && profile.energyScore == -1) {
                // No new audio evidence yet and a tags-only profile exists:
                // keep it and avoid churning on every run. Profiles that CLAIM
                // audio evidence but have no usable waveform are stale garbage
                // and fall through to re-classification.
                skipped++
                continue
            }
            toClassify.add(song)
        }

        var enrichedOnline = 0
        val newProfiles = mutableListOf<MoodProfile>()
        val seenOnlineTitles = mutableSetOf<String>()

        for (song in toClassify) {
            val id = song.id.toString()
            val track = tracksById[id]
            val trackWaveform = track?.waveformAmplitudes
            val waveformUsable = !WaveformExtractor.isSaturated(trackWaveform) &&
                !WaveformExtractor.isLegacyPeakEnvelope(trackWaveform)
            val waveform = if (waveformUsable) trackWaveform else null
            val energy = if (waveformUsable) (track?.energyScore ?: -1) else -1
            val lyrics = lyricsByPath[song.path]?.rawLyrics

            // Online genre enrichment: only for tracks we cannot tag locally,
            // capped per run and de-duplicated to avoid hammering the APIs.
            var onlineGenre: String? = null
            val needsOnline = enrichOnline &&
                genreIsUnknown(song.genre) &&
                enrichedOnline < onlineLimit &&
                song.title !in seenOnlineTitles
            if (needsOnline && metadataResolver != null) {
                try {
                    val meta = metadataResolver.resolve(song.title, song.artist)
                    val genre = meta?.genre?.takeIf { it.isNotBlank() }
                    if (genre != null) {
                        onlineGenre = genre
                        enrichedOnline++
                        seenOnlineTitles.add(song.title)
                    }
                } catch (e: Exception) {
                    Log.w("MoodEngine", "online genre lookup failed for '${song.title}'", e)
                }
            }

            val sources = buildList {
                if (energy in 1..10) add("AUDIO")
                if (!genreIsUnknown(song.genre)) add("TAGS")
                if (onlineGenre != null) add("ONLINE")
                if (!lyrics.isNullOrBlank()) add("LYRICS")
            }.joinToString(",")

            val signals = buildSignals(song, energy, waveform, onlineGenre, lyrics)
            val profile = profileFromSignals(id, signals, sources, now)
            newProfiles.add(profile)
        }

        Log.d("MoodEngine", "organize: songs=${songs.size} toClassify=${toClassify.size} skipped=$skipped profiles=${newProfiles.size} online=$enrichedOnline")

        if (newProfiles.isNotEmpty()) {
            moodDao.upsertMoodProfiles(newProfiles)
        }

        val learnedPlaylists = learnFromUserPlaylists()

        val classifiedCount = newProfiles.count { it.primaryMood.isNotBlank() }
        OrganizeResult(
            analyzed = newProfiles.size,
            classified = classifiedCount,
            enrichedOnline = enrichedOnline,
            playlistsLearned = learnedPlaylists,
        )
    }

    /**
     * Learning pass: for every user playlist, aggregate the mood signals of
     * the tracks it contains. Playlists whose name hints at a mood
     * (e.g. "Gym Anthems", "Chill Sunday") additionally update
     * `learned:<mood>` profiles that bias future mood mixes.
     */
    suspend fun learnFromUserPlaylists(): Int = withContext(Dispatchers.IO) {
        val playlists = try {
            repository.getAllPlaylistsWithTracksSync()
        } catch (_: Exception) {
            return@withContext 0
        }
        val profilesById = moodDao.getAllMoodProfiles().associateBy { it.trackId }
        val tracksById = repository.getAllLibraryTracksSync().associateBy { it.id }
        val now = System.currentTimeMillis()
        var learned = 0

        for (pw in playlists) {
            val signals = pw.tracks.mapNotNull { t ->
                val profile = profilesById[t.id]
                val track = tracksById[t.id]
                if (profile == null && track == null) return@mapNotNull null
                MoodClassifier.TrackSignals(
                    energyScore = profile?.energyScore ?: track?.energyScore ?: -1,
                    bpm = profile?.bpm ?: -1,
                    genre = profile?.genreTags?.takeIf { it.isNotBlank() } ?: "",
                    title = track?.title ?: "",
                    extraGenreTags = emptySet(),
                )
            }
            if (signals.isEmpty()) continue

            // Per-playlist profile (keyed by id) powers "add suggestions"
            MoodClassifier.learn(signals)?.let { learnedProfile ->
                moodDao.upsertPlaylistProfile(
                    learnedProfile.copy(
                        profileKey = "playlist:${pw.playlist.id}",
                        updatedAt = now,
                    )
                )
                learned++
            }

            // Fold mood-named playlists into the learned mood biases
            val moods = MoodClassifier.moodsForPlaylistName(pw.playlist.name)
            for (mood in moods) {
                moodDao.upsertPlaylistProfile(
                    (MoodClassifier.learn(signals) ?: continue).copy(
                        profileKey = "learned:$mood",
                        updatedAt = now,
                    )
                )
            }
        }
        learned
    }

    /**
     * Playback-driven learning: the user's own listening outcome (finished
     * vs skipped) updates the track's profile so future mood mixes rank it
     * accordingly. Skipped tracks sink in their primary mood's mix; fully
     * listened tracks rise.
     */
    suspend fun applyListeningFeedback(trackId: String, listenedFraction: Float) =
        withContext(Dispatchers.IO) {
            if (trackId.isBlank()) return@withContext
            try {
                val profile = moodDao.getMoodProfile(trackId) ?: return@withContext
                if (profile.primaryMood.isBlank()) return@withContext
                val feedback = MoodClassifier.nextListenFeedback(profile.listenFeedback, listenedFraction)
                if (feedback == profile.listenFeedback) return@withContext

                // Repeated early skips mean the assigned category is wrong for
                // this track in the user's judgment: demote the penalized mood
                // and let the next-best mood take over (re-categorization,
                // not just re-ranking). Feedback resets so the new category
                // starts learning from a clean slate.
                if (feedback <= -0.6 && profile.listenSamples + 1 >= 2) {
                    val (newScores, newPrimary) = MoodClassifier.demoteMood(profile.moodScores, profile.primaryMood)
                    moodDao.upsertMoodProfile(
                        profile.copy(
                            moodScores = newScores,
                            primaryMood = newPrimary,
                            listenFeedback = 0.0,
                            listenSamples = 0,
                            sources = if (profile.sources.contains("LISTEN")) profile.sources else profile.sources + ",LISTEN",
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    return@withContext
                }

                moodDao.upsertMoodProfile(
                    profile.copy(
                        listenFeedback = feedback,
                        listenSamples = profile.listenSamples + 1,
                        updatedAt = System.currentTimeMillis(),
                    )
                )
            } catch (_: Exception) {
                // Feedback must never break playback
            }
        }

    /**
     * Loads the blend of base + learned scores for [mood], using persisted
     * mood profiles. Returns null when the library has no profiles yet.
     */
    suspend fun getMoodMix(songs: List<Song>, mood: String, limit: Int = 40): List<Song>? =
        withContext(Dispatchers.Default) {
            if (songs.isEmpty()) return@withContext null
            val profiles = moodDao.getAllMoodProfiles().associateBy { it.trackId }
            if (profiles.isEmpty()) return@withContext null

            val learned = moodDao.getPlaylistProfile("learned:$mood")
            // Learning influence grows with sample size, capped at 40%.
            val lambda = if (learned != null && learned.sampleSize > 0) {
                (learned.sampleSize.coerceAtMost(50) / 50.0) * 0.4
            } else 0.0

            // Listening behaviour: tracks the user actually plays get a small
            // affinity boost, so the mix leans toward proven taste.
            val playCounts = try {
                repository.getAllPlayCounts().associate { it.songId to it.playCount }
            } catch (_: Exception) {
                emptyMap()
            }

            val scored = songs.mapNotNull { song ->
                val profile = profiles[song.id.toString()] ?: return@mapNotNull null
                val base = MoodClassifier.parseMoodScores(profile.moodScores)[mood] ?: return@mapNotNull null
                var score = base
                if (learned != null && lambda > 0.0) {
                    val signals = MoodClassifier.TrackSignals(
                        energyScore = profile.energyScore,
                        bpm = profile.bpm,
                        genre = profile.genreTags,
                        title = song.title,
                    )
                    val learnedScore = MoodClassifier.learnedFit(learned, signals)
                    score = (1 - lambda) * base + lambda * learnedScore
                }
                // Listening nudge (capped), plus a freshness nudge for new tracks
                val plays = playCounts[song.id.toString()] ?: 0
                val listenBonus = 0.04 * (plays.coerceAtMost(10) / 10.0)
                // Playback-feedback: repeatedly skipped tracks sink in this
                // mood's mix, fully-listened tracks rise.
                val feedbackBonus = profile.listenFeedback * 0.08
                val freshBonus = if (System.currentTimeMillis() - song.dateAdded * 1000L < 14L * 24 * 60 * 60 * 1000) 0.02 else 0.0
                Triple(song, score + listenBonus + feedbackBonus + freshBonus, profile)
            }.filter { (_, score, profile) -> score > 0.05 || profile.primaryMood == mood }
            .filter { (_, _, profile) -> !energyContradictsMood(mood, profile) }

            if (scored.isEmpty()) return@withContext emptyList<Song>()

            // Tiered membership. A track earns its place in the mix through
            // REAL evidence, in descending order of trust:
            //   1. The engine classified it as this mood (primary).
            //   2. Title/lyrics carry explicit keyword evidence for the mood
            //      (e.g. "Ishq" for romance) — separates "Aakhri Ishq" from
            //      "Didi (Sher-E-Baloch)" even at identical audio profiles.
            //   3. A strong multi-signal fit score.
            // Only when fewer than 6 such tracks exist do we pad with the
            // wider scored pool (weakest evidence last). No shuffle: the
            // ranking must survive.
            val tier1 = scored.filter { (_, _, p) -> p.primaryMood == mood }
            val tier1Ids = tier1.map { it.first.id }.toSet()
            val rest = scored.filter { it.first.id !in tier1Ids }
            val tier2 = rest.mapNotNull { (song, score, p) ->
                if (mood in MoodClassifier.keywordMoodEvidence(song.title)) {
                    // Keyword-confirmed tracks outrank raw audio-fit peers
                    Triple(song, score + 0.08, p)
                } else null
            }
            val tier2Ids = tier2.map { it.first.id }.toSet()
            val tier3 = rest.filter { (song, score, _) ->
                song.id !in tier2Ids && score >= 0.55
            }
            val confident = (tier1 + tier2 + tier3).sortedByDescending { it.second }
            val pool = if (confident.size >= 6) confident else {
                val confidentIds = confident.map { it.first.id }.toSet()
                (confident + scored.filter { it.first.id !in confidentIds && it.second > 0.15 })
                    .distinctBy { it.first.id }
                    .sortedByDescending { it.second }
            }
            pool.take(limit).map { it.first }
        }

    /**
     * Hard veto: a track whose measured energy falls outside a mood's
     * acceptable window can never enter that mood's mix, regardless of weak
     * genre/title scores. Windows are symmetric around each archetype:
     * loud club tracks can't surface in chill moods and quiet ballads can't
     * surface in party moods.
     */
    private fun energyContradictsMood(mood: String, profile: MoodProfile): Boolean {
        val e = profile.energyScore
        if (e !in 1..10) return false // no audio evidence yet
        val acceptable = when (mood) {
            MoodClassifier.MOOD_RELAX, MoodClassifier.MOOD_FOCUS -> 1..5
            MoodClassifier.MOOD_ROMANCE -> 1..6
            MoodClassifier.MOOD_DRIVE -> 4..10
            MoodClassifier.MOOD_ENERGIZE, MoodClassifier.MOOD_PARTY -> 5..10
            else -> 1..10
        }
        return e !in acceptable
    }

    /**
     * Ranks library tracks (not already in the playlist) by similarity to the
     * playlist's learned profile. Local signals only — no network.
     */
    suspend fun suggestTracksForPlaylist(
        playlistId: Long,
        songs: List<Song>,
        existingTrackIds: Set<String>,
        limit: Int = 15,
    ): List<Song> = withContext(Dispatchers.IO) {
        val profile = moodDao.getPlaylistProfile("playlist:$playlistId")
            ?: return@withContext emptyList()
        if (profile.sampleSize < 3) return@withContext emptyList()

        val profiles = moodDao.getAllMoodProfiles().associateBy { it.trackId }
        val tracksById = repository.getAllLibraryTracksSync().associateBy { it.id }

        songs.asSequence()
            .filter { it.id.toString() !in existingTrackIds }
            .mapNotNull { song ->
                val p = profiles[song.id.toString()]
                val signals = MoodClassifier.TrackSignals(
                    energyScore = p?.energyScore ?: tracksById[song.id.toString()]?.energyScore ?: -1,
                    bpm = p?.bpm ?: -1,
                    genre = p?.genreTags ?: song.genre,
                    title = song.title,
                )
                val fit = MoodClassifier.learnedFit(profile, signals)
                if (fit <= 0.0) null else song to fit
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }
}
