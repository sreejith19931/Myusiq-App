package com.music.myusiq.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.music.myusiq.DatabaseProvider
import com.music.myusiq.data.MusicRepository
import com.music.myusiq.data.platform.AndroidMusicScanner
import com.music.myusiq.domain.usecase.MoodEngine
import com.music.myusiq.utils.CloudMetadataResolver

/**
 * One-shot background job for the Mood Engine:
 * classifies library tracks (audio + tags + capped online genre enrichment)
 * and re-learns playlist profiles. Safe to re-run at any time; tracks with
 * an existing fresh profile are skipped.
 */
class MoodClassificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        return try {
            val db = DatabaseProvider.getDatabase(applicationContext)
            val repository = MusicRepository(
                songDao = db.songDao(),
                playlistDao = db.playlistDao(),
                songHistoryDao = db.songHistoryDao(),
                musicScanner = AndroidMusicScanner(applicationContext),
            )
            val songs = repository.getSongs(filterShortAudio = true)
            if (songs.isEmpty()) return ListenableWorker.Result.success()

            val engine = MoodEngine(
                repository = repository,
                moodDao = db.moodDao(),
                metadataResolver = CloudMetadataResolver,
            )
            engine.organize(
                songs = songs,
                enrichOnline = inputData.getBoolean(KEY_ENRICH_ONLINE, true),
                onlineLimit = inputData.getInt(KEY_ONLINE_LIMIT, 25),
            )
            ListenableWorker.Result.success()
        } catch (e: Exception) {
            ListenableWorker.Result.retry()
        }
    }

    companion object {
        const val KEY_ENRICH_ONLINE = "enrich_online"
        const val KEY_ONLINE_LIMIT = "online_limit"
    }
}
