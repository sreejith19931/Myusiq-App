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
    private fun genreIsUnknown(genre: String): Boolean =
        genre.isBlank() || genre.equals("Unknown Genre", true)

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
        val bpm = if (waveform.isNullOrBlank() || trackEnergy < 1) -1
        else MusicEnergyAnalyzer.estimateBpm(waveform, song.duration.toLong() * 1000L)

        val extraTags = onlineGenre?.let { MoodClassifier.tokenizeGenre(it).toSet() } ?: emptySet()

        return MoodClassifier.TrackSignals(
            energyScore = trackEnergy,
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
            val hasEnergy = (track?.energyScore ?: -1) in 1..10
            if (profile != null && hasEnergy) {
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
            val waveform = track?.waveformAmplitudes
            val energy = track?.energyScore ?: -1
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
            newProfiles.add(profileFromSignals(id, signals, sources, now))
        }

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
                // Small freshness nudge for recently added tracks
                val freshBonus = if (System.currentTimeMillis() - song.dateAdded * 1000L < 14L * 24 * 60 * 60 * 1000) 0.02 else 0.0
                Triple(song, score + freshBonus, profile.primaryMood)
            }.filter { (_, score, primary) -> score > 0.05 || primary == mood }

            if (scored.isEmpty()) return@withContext emptyList<Song>()
            scored.sortedByDescending { it.second }.shuffled().take(limit).map { it.first }
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
