package com.music.myusiq.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.music.myusiq.R
import com.music.myusiq.data.platform.AndroidMusicScanner
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.usecase.AudioFingerprintingUseCase
import com.music.myusiq.domain.usecase.JanitorScanUseCase
import com.music.myusiq.domain.usecase.MatchSearchUseCase
import com.music.myusiq.domain.usecase.MetadataSanitizerUseCase
import com.music.myusiq.domain.usecase.ScanState
import com.music.myusiq.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class JanitorBackgroundWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): ListenableWorker.Result {
        return try {
            val rawSongs = AndroidMusicScanner(context).scanAllSongs()
            val songs = rawSongs.map { raw ->
                Song(
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
            }

            if (songs.isEmpty()) return ListenableWorker.Result.success()

            val sanitizer = MetadataSanitizerUseCase()
            val fingerprinting = AudioFingerprintingUseCase()
            val matchSearch = MatchSearchUseCase()
            val scanUseCase = JanitorScanUseCase(
                downloadImage = { ctx, url -> downloadAndCacheImage(ctx, url) },
                metadataSanitizerUseCase = sanitizer,
                fingerprintingUseCase = fingerprinting,
                matchSearchUseCase = matchSearch
            )

            val scanState = scanUseCase.scanTracks(songs, context)
                .filterIsInstance<ScanState.ScanCompleted>()
                .firstOrNull()

            val results = scanState?.results ?: emptyList()
            if (results.isNotEmpty()) {
                showJanitorNotification(results.size)
            }

            ListenableWorker.Result.success()
        } catch (_: Exception) {
            ListenableWorker.Result.failure()
        }
    }

    private suspend fun downloadAndCacheImage(context: Context, imageUrl: String): Uri? {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(imageUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                val tempFile = File(context.cacheDir, "temp_art_${System.currentTimeMillis()}.jpg")
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Uri.fromFile(tempFile)
            } catch (_: Exception) { null }
            finally {
                connection?.disconnect()
            }
        }
    }

    private fun showJanitorNotification(count: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "janitor_background_channel"

        val channel = NotificationChannel(
            channelId,
            "Library Janitor Background Scans",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifications when library metadata updates or album artwork are ready for review."
        }
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("auto_start_janitor", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_music_note_2)
            .setContentTitle("Library Janitor Updates Ready")
            .setContentText("Found $count tracks needing metadata or artwork updates. Tap to review.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(1001, notification)
    }
}
