package com.music.myusiq.domain.usecase

import android.content.Context
import android.net.Uri
import android.util.Log
import com.music.myusiq.DatabaseProvider
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.models.SongOverride
import com.music.myusiq.utils.FileTagEditor
import com.music.myusiq.utils.MetadataBackupUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Heals a single track's tags in the background. Runs the same metadata
 * pipeline as the interactive Library Janitor (sanitize -> iTunes/Deezer
 * match with duration validation) but only auto-writes when the match is
 * high-confidence AND the duration agrees — and always snapshots the
 * original tags first so the user can revert.
 */
class AudioMatcherUseCase(private val context: Context) {
    private val sanitizer = MetadataSanitizerUseCase()
    private val matchSearch = MatchSearchUseCase()

    suspend fun matchAndHealSong(song: Song): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(song.path)
                if (!file.exists()) return@withContext false
                if (song.duration <= 0) return@withContext false

                // 1. Sanitize metadata the same way the Janitor does
                val sanitized = sanitizer.sanitize(
                    song,
                    song.year,
                    song.path,
                    song.duration.toString(),
                    song.albumId
                )

                // 2. Find online matches (iTunes/Deezer, duration-validated)
                val candidates = mutableListOf<com.music.myusiq.domain.models.SuggestedMetadataItem>()
                try {
                    matchSearch.findMatches(sanitized, song.year, song.genre, context)
                        .collect { candidates.add(it) }
                } catch (_: Exception) {}

                val best = candidates.maxByOrNull { it.confidenceScore } ?: return@withContext false

                // 3. Only auto-write a certain, complete match
                val isComplete = best.suggestedTitle.isNotBlank() &&
                    best.suggestedArtist.isNotBlank() &&
                    best.suggestedAlbum.isNotBlank()
                if (!isComplete || best.confidenceScore < 0.9f) return@withContext false

                // 4. Snapshot the original tags so the user can revert
                try {
                    MetadataBackupUtils.logTrackEdit(
                        context,
                        song,
                        com.music.myusiq.ui.viewmodels.PendingTrackEdit(
                            songId = song.id,
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            year = song.year ?: "",
                            genre = song.genre,
                            artworkUri = null,
                            path = song.path
                        )
                    )
                } catch (_: Exception) {}

                // 5. Write tags
                val uri = Uri.fromFile(file)
                val newPath = FileTagEditor.updateFileTags(
                    context = context,
                    filePath = song.path,
                    trackUri = uri,
                    title = best.suggestedTitle,
                    artist = best.suggestedArtist,
                    album = best.suggestedAlbum,
                    year = best.suggestedYear ?: song.year ?: "",
                    genre = best.suggestedGenre ?: song.genre,
                    newArtworkUri = null
                )

                // 6. Persist override so the library reflects the fix
                val db = DatabaseProvider.getDatabase(context)
                db.songDao().upsertOverride(
                    SongOverride(
                        path = newPath.ifBlank { song.path },
                        title = best.suggestedTitle,
                        artist = best.suggestedArtist,
                        album = best.suggestedAlbum,
                        year = best.suggestedYear ?: song.year ?: "",
                        genre = best.suggestedGenre ?: song.genre
                    )
                )
                true
            } catch (e: Exception) {
                Log.e("AudioMatcherUseCase", "Error matching/healing song: ${song.title}", e)
                false
            }
        }
    }
}
