package com.music.myusiq.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.music.myusiq.data.platform.AndroidMusicScanner
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.usecase.AudioMatcherUseCase
import com.music.myusiq.utils.CloudMetadataResolver

class AudioMatcherWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        return try {
            val rawSongs = AndroidMusicScanner(context).scanAllSongs()
            val matcher = AudioMatcherUseCase(context)

            // Filter for tracks with unknown artists or messy titles
            val messySongs = rawSongs.filter { raw ->
                CloudMetadataResolver.isGenericArtist(raw.artist) || raw.title.contains("http") || raw.title.contains("www.")
            }.take(20) // process in batches

            for (raw in messySongs) {
                val song = Song(
                    id = raw.id,
                    title = raw.title,
                    artist = raw.artist,
                    path = raw.path,
                    duration = raw.duration,
                    albumId = raw.albumId,
                    album = raw.album,
                    genre = raw.genre,
                    dateAdded = raw.dateAdded,
                    size = raw.size
                )
                matcher.matchAndHealSong(song)
            }

            ListenableWorker.Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            ListenableWorker.Result.failure()
        }
    }
}
