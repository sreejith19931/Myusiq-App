package com.music.myusiq.ui.viewmodels

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import com.music.myusiq.R
import com.music.myusiq.data.MusicRepository
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.models.SongOverride
import com.music.myusiq.utils.CloudMetadataResolver
import com.music.myusiq.utils.FileTagEditor
import com.music.myusiq.utils.PlatformUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.milliseconds

class MetadataUpdateUseCase(
    private val musicRepository: MusicRepository,
    private val application: Application,
    private val scope: CoroutineScope,
) {
    interface MetadataUpdateListener {
        fun onPendingWriteIntent(intent: IntentSenderRequest, data: PendingTrackEdit)
        fun onUpdateStarted()
        fun onUpdateProgress(songId: Long, title: String, artist: String, album: String)
        fun onUpdateFinished(data: PendingTrackEdit, refreshLibrary: Boolean)
        fun getOriginalTrackList(): List<Song>
        fun getCurrentPlayingSong(): Song?
    }

    fun permanentUpdateSongMetadata(
        context: Context,
        songId: Long,
        title: String,
        artist: String,
        album: String,
        year: String = "",
        genre: String = "",
        artUri: Uri? = null,
        path: String = "",
        listener: MetadataUpdateListener
    ) {
        scope.launch(Dispatchers.IO) {
            val trackUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, songId)
            val pendingData = PendingTrackEdit(songId, title, artist, album, year, genre, artUri, path)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = MediaStore.createWriteRequest(context.contentResolver, listOf(trackUri))
                withContext(Dispatchers.Main) {
                    listener.onPendingWriteIntent(IntentSenderRequest.Builder(intent.intentSender).build(), pendingData)
                }
            } else {
                executeMediaStoreUpdate(context, pendingData, listener = listener)
            }
        }
    }

    suspend fun executeMediaStoreUpdate(
        context: Context,
        data: PendingTrackEdit,
        refreshLibrary: Boolean = true,
        showToast: Boolean = true,
        listener: MetadataUpdateListener
    ) {
        val currentPlayingId = listener.getCurrentPlayingSong()?.id
        if ((currentPlayingId == data.songId) && !showToast) {
            Log.w("MetadataUpdateUseCase", "Skipping background update for currently playing track: ${data.songId}")
            return
        }
        
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, data.songId)
        try {
            Log.d("MetadataUpdateUseCase", "Starting update for: ${data.title} (ID: ${data.songId})")
            
            withContext(Dispatchers.Main) {
                listener.onUpdateStarted()
            }
            
            // 1. Update physical file tags and local artwork cache
            if (data.path.isNotEmpty()) {
                try {
                    FileTagEditor.updateFileTags(context, data.path, uri, data.title, data.artist, data.album, data.year, data.genre, data.artworkUri)
                    
                    data.artworkUri?.let { artUri ->
                        com.music.myusiq.utils.ArtworkUtils.saveArtworkCache(context, data.songId, data.path, artUri)
                    }

                    // 1b. Save to local override table to prevent reverting UI
                    musicRepository.upsertOverride(SongOverride(
                        path = PlatformUtils.normalizePath(data.path),
                        title = data.title,
                        artist = data.artist,
                        album = data.album,
                        year = data.year,
                        genre = data.genre
                    ))
                } catch (e: Exception) {
                    Log.e("MetadataUpdateUseCase", "Tag update failed", e)
                }
            }
            
            // 2. Update MediaStore database entries
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, data.title)
                put(MediaStore.Audio.Media.ARTIST, data.artist)
                put(MediaStore.Audio.Media.ALBUM, data.album)
                
                if (data.year.isNotBlank()) {
                    put(MediaStore.Audio.Media.YEAR, data.year.toIntOrNull() ?: 0)
                }
            }
            
            val updated = try {
                context.contentResolver.update(uri, values, null, null)
            } catch (e: SecurityException) {
                Log.e("MetadataUpdateUseCase", "Permission denied for update", e)
                0
            } catch (e: Exception) {
                Log.e("MetadataUpdateUseCase", "Resolver update crashed", e)
                0
            }
            Log.d("MetadataUpdateUseCase", "MediaStore update result: $updated for ID: ${data.songId}")
            
            withContext(Dispatchers.Main) {
                if (showToast) {
                    if (updated > 0) {
                        val msg = application.getString(R.string.track_details_updated)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    } else {
                        val msg = application.getString(R.string.update_pending_scan)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                // Update local lists immediately in memory
                listener.onUpdateProgress(data.songId, data.title, data.artist, data.album)
            }
            
            // 3. Scan the file to ensure MediaStore is fully synced
            MediaScannerConnection.scanFile(context, arrayOf(data.path), null) { _, _ ->
                scope.launch(Dispatchers.Main) {
                    listener.onUpdateFinished(data, refreshLibrary)
                }
            }
        } catch (e: Exception) {
            Log.e("MetadataUpdateUseCase", "Error in executeMediaStoreUpdate", e)
            withContext(Dispatchers.Main) {
                listener.onUpdateFinished(data, refreshLibrary)
            }
        }
    }

    fun repairUnknownMetadata(
        context: Context,
        songs: List<Song>,
        onProgress: (Int, Int, String) -> Unit,
        onComplete: (Int) -> Unit,
        listener: MetadataUpdateListener
    ) {
        scope.launch(Dispatchers.IO) {
            var repairedCount = 0
            val unknownArtist = application.getString(R.string.unknown_artist)
            
            songs.forEachIndexed { index, song ->
                withContext(Dispatchers.Main) {
                    onProgress(index + 1, songs.size, song.title)
                }
                
                // Clean title using YouTubeTitleCleaner to remove junk
                val (extractedArtist, extractedTitle) = com.music.myusiq.utils.YouTubeTitleCleaner.cleanAndSplit(song.title)
                val cleanTitle = extractedTitle.ifBlank { com.music.myusiq.utils.YouTubeTitleCleaner.cleanTitle(song.title) }
                val cleanArtist = if (song.artist == unknownArtist || song.artist.isBlank()) {
                    if (extractedArtist != "Unknown Artist") extractedArtist else ""
                } else song.artist

                val metadata = CloudMetadataResolver.resolve(cleanTitle, cleanArtist)
                if (metadata != null) {
                    val artworkUri = metadata.artworkUrl?.let { downloadImageToCache(context, it) }
                    val edit = PendingTrackEdit(
                        songId = song.id,
                        title = cleanTitle,
                        artist = cleanArtist,
                        album = metadata.album ?: song.album,
                        year = metadata.year ?: "",
                        genre = metadata.genre ?: "",
                        artworkUri = artworkUri,
                        path = song.path
                    )
                    executeMediaStoreUpdate(context, edit, refreshLibrary = false, showToast = false, listener = listener)
                    repairedCount++
                }
                delay(1000.milliseconds) // Rate limiting for API
            }
            
            withContext(Dispatchers.Main) { 
                onComplete(repairedCount)
            }
        }
    }

    fun startAutoMetadataImprovement(listener: MetadataUpdateListener) {
        val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_improve_metadata", false)) return
        
        scope.launch(Dispatchers.IO) {
            val unknownArtist = application.getString(R.string.unknown_artist)
            val unknownAlbum = application.getString(R.string.unknown_album)
            
            val currentPlayingId = listener.getCurrentPlayingSong()?.id
            val originalTrackList = listener.getOriginalTrackList()
            
            val toImprove = originalTrackList.asSequence().filter { 
                (it.artist == unknownArtist || it.album == unknownAlbum) && it.id != currentPlayingId
            }.take(3)
            
            toImprove.forEach { song ->
                val metadata = CloudMetadataResolver.resolve(song.title, song.artist)
                if (metadata != null) {
                    val artworkUri = metadata.artworkUrl?.let { downloadImageToCache(application, it) }
                    val edit = PendingTrackEdit(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        album = metadata.album ?: song.album,
                        year = metadata.year ?: "",
                        genre = metadata.genre ?: "",
                        artworkUri = artworkUri,
                        path = song.path
                    )
                    executeMediaStoreUpdate(application, edit, refreshLibrary = false, showToast = false, listener = listener)
                }
                delay(3000.milliseconds)
            }
        }
    }

    suspend fun downloadImageToCache(context: Context, imageUrl: String): Uri? {
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

    fun requestBulkMetadataUpdate(context: Context, updates: List<PendingTrackEdit>, listener: MetadataUpdateListener, onIntent: (IntentSenderRequest) -> Unit) {
        if (updates.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = updates.map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.songId) }
                val intent = MediaStore.createWriteRequest(context.contentResolver, uris)
                withContext(Dispatchers.Main) {
                    onIntent(IntentSenderRequest.Builder(intent.intentSender).build())
                }
            } else {
                updates.forEach { 
                    executeMediaStoreUpdate(context, it, it == updates.last(), listener = listener)
                }
            }
        }
    }

    fun executePendingWrite(context: Context, pendingTrackEditData: PendingTrackEdit?, pendingBulkTrackEditData: List<PendingTrackEdit>?, listener: MetadataUpdateListener, onConsume: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d("MetadataUpdateUseCase", "Executing pending write. data=$pendingTrackEditData, bulk=$pendingBulkTrackEditData")
                
                val isBulk = pendingBulkTrackEditData != null && (pendingBulkTrackEditData.size) > 1
                if (isBulk) {
                    withContext(Dispatchers.Main) {
                        val msg = application.getString(R.string.applying_changes)
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                pendingTrackEditData?.let { executeMediaStoreUpdate(context, it, showToast = !isBulk, listener = listener) }
                pendingBulkTrackEditData?.forEach { executeMediaStoreUpdate(context, it, it == pendingBulkTrackEditData.last(), showToast = !isBulk, listener = listener) }
            } catch (e: Exception) {
                Log.e("MetadataUpdateUseCase", "Crash in executePendingWrite", e)
            } finally {
                withContext(Dispatchers.Main) {
                    onConsume()
                }
            }
        }
    }

    fun setAsRingtone(context: Context, song: Song) {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:" + context.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val file = File(song.path)
                if (!file.exists()) throw Exception("File not found")

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, song.title)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp3")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES)
                    put(MediaStore.Audio.Media.IS_RINGTONE, true)
                }

                val ringtoneUri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("Failed to create MediaStore entry")

                context.contentResolver.openOutputStream(ringtoneUri)?.use { output ->
                    file.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }

                RingtoneManager.setActualDefaultRingtoneUri(
                    context,
                    RingtoneManager.TYPE_RINGTONE,
                    ringtoneUri
                )
                
                withContext(Dispatchers.Main) {
                    val msg = application.getString(R.string.ringtone_set, song.title)
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val msg = application.getString(R.string.error_setting_ringtone)
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    Log.e("MetadataUpdateUseCase", "Error setting ringtone", e)
                }
            }
        }
    }
}
