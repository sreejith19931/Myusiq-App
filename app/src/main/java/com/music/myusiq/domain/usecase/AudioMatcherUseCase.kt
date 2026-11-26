package com.music.myusiq.domain.usecase

import android.content.Context
import android.net.Uri
import android.util.Log
import com.music.myusiq.DatabaseProvider
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.models.SongOverride
import com.music.myusiq.utils.AcoustIdClient
import com.music.myusiq.utils.FileTagEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AudioMatcherUseCase(private val context: Context) {
    private val fingerprintingUseCase = AudioFingerprintingUseCase()

    suspend fun matchAndHealSong(song: Song): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(song.path)
                if (!file.exists()) return@withContext false

                val durationSec = (song.duration / 1000).toInt()
                if (durationSec <= 0) return@withContext false

                // 1. Generate acoustic fingerprint
                val fingerprint = fingerprintingUseCase.generateFingerprint(song, context)
                if (fingerprint.isBlank()) return@withContext false

                // 2. Query AcoustID
                val matches = AcoustIdClient.lookup(durationSec, fingerprint)
                if (matches.isEmpty()) return@withContext false

                val bestMatch = matches.first()
                val correctedTitle = bestMatch.title ?: song.title
                val correctedArtist = bestMatch.artist ?: song.artist

                if (correctedTitle.isBlank() || correctedArtist.isBlank()) return@withContext false

                // 3. Update File Tags
                val uri = Uri.fromFile(file)
                val newPath = FileTagEditor.updateFileTags(
                    context = context,
                    filePath = song.path,
                    trackUri = uri,
                    title = correctedTitle,
                    artist = correctedArtist,
                    album = song.album,
                    year = song.year ?: "",
                    genre = song.genre,
                    newArtworkUri = null
                )

                // 4. Update Database Overrides
                val db = DatabaseProvider.getDatabase(context)
                db.songDao().upsertOverride(
                    SongOverride(
                        path = newPath.ifBlank { song.path },
                        title = correctedTitle,
                        artist = correctedArtist,
                        album = song.album,
                        year = song.year ?: "",
                        genre = song.genre
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
