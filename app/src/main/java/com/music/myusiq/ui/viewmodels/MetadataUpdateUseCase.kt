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
        fun onBulkProgress(current: Int, total: Int, title: String) {}
        fun onBulkFinished() {}
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
            
            // 1. Update physical file tags, local artwork cache, and physical filename
            var newFilePath = data.path
            var targetFileName = ""
            if (data.path.isNotEmpty()) {
                try {
                    val originalFile = File(data.path)
                    val ext = if (originalFile.extension.isNotBlank()) originalFile.extension else "mp3"
                    val cleanTitle = data.title.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
                        .ifBlank { originalFile.nameWithoutExtension }

                    targetFileName = "$cleanTitle.$ext"

                    val parentDir = originalFile.parentFile
                    if (originalFile.exists() && parentDir != null && targetFileName.isNotBlank()) {
                        val targetFile = File(parentDir, targetFileName)
                        if (originalFile != targetFile) {
                            try {
                                if (originalFile.renameTo(targetFile)) {
                                    newFilePath = targetFile.absolutePath
                                    Log.d("MetadataUpdateUseCase", "Directly renamed file on storage to: $newFilePath")
                                }
                            } catch (e: Exception) {
                                Log.d("MetadataUpdateUseCase", "Direct rename failed: ${e.message}")
                            }
                        }
                    }

                    FileTagEditor.updateFileTags(context, data.path, uri, data.title, data.artist, data.album, data.year, data.genre, data.artworkUri)
                    if (newFilePath != data.path) {
                        FileTagEditor.updateFileTags(context, newFilePath, uri, data.title, data.artist, data.album, data.year, data.genre, data.artworkUri)
                    }

                    // Auto-embed and save fetched lyrics if available
                    data.lyrics?.takeIf { it.isNotBlank() }?.let { lyricsText ->
                        val baseName = File(newFilePath).nameWithoutExtension
                        val parent = File(newFilePath).parentFile
                        if (parent != null) {
                            val lyricsDir = File(parent, "lyrics")
                            if (!lyricsDir.exists()) lyricsDir.mkdirs()
                            try { File(lyricsDir, "$baseName.lrc").writeText(lyricsText) } catch (_: Exception) {}
                        }
                        val appLyricsDir = File(context.getExternalFilesDir(null), "lyrics")
                        if (appLyricsDir != null) {
                            if (!appLyricsDir.exists()) appLyricsDir.mkdirs()
                            try { File(appLyricsDir, "$baseName.lrc").writeText(lyricsText) } catch (_: Exception) {}
                        }
                        
                        FileTagEditor.embedLyrics(File(newFilePath), lyricsText)
                        if (data.path != newFilePath) {
                            FileTagEditor.embedLyrics(File(data.path), lyricsText)
                        }
                    }

                    // Rename/migrate corresponding .lrc files so old lrc files do not get orphaned in lyrics folder
                    val oldBase = originalFile.nameWithoutExtension
                    val newBase = File(newFilePath).nameWithoutExtension
                    if (oldBase.isNotBlank() && newBase.isNotBlank() && !oldBase.equals(newBase, ignoreCase = true)) {
                        val lyricsDir = File(parentDir, "lyrics")
                        if (!lyricsDir.exists()) lyricsDir.mkdirs()
                        val appLyricsDir = File(context.getExternalFilesDir(null), "lyrics")
                        listOf(lyricsDir, parentDir, appLyricsDir).forEach { dir ->
                            if (dir != null && dir.exists()) {
                                listOf("lrc", "txt").forEach { ext ->
                                    val oldLrc = File(dir, "$oldBase.$ext")
                                    if (oldLrc.exists()) {
                                        try {
                                            val lyricsText = oldLrc.readText()
                                            // Write migrated file ONLY into lyrics subfolder
                                            val newLrc = File(lyricsDir, "$newBase.lrc")
                                            newLrc.writeText(lyricsText)
                                            if (appLyricsDir != null && appLyricsDir.exists()) {
                                                File(appLyricsDir, "$newBase.lrc").writeText(lyricsText)
                                            }
                                            FileTagEditor.embedLyrics(File(newFilePath), lyricsText)
                                        } catch (_: Exception) {}
                                        try {
                                            if (!oldLrc.delete()) oldLrc.writeText("")
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }
                    }

                    com.music.myusiq.utils.ArtworkUtils.copyArtworkCache(context, data.songId, data.path, data.songId, newFilePath)

                    data.artworkUri?.let { artUri ->
                        com.music.myusiq.utils.ArtworkUtils.saveArtworkCache(context, data.songId, data.path, artUri)
                        com.music.myusiq.utils.ArtworkUtils.saveArtworkCache(context, data.songId, newFilePath, artUri)
                    }

                    // 1b. Save to local override table to prevent reverting UI
                    val normOldPath = PlatformUtils.normalizePath(data.path)
                    val normNewPath = PlatformUtils.normalizePath(newFilePath)

                    val overrideNew = SongOverride(
                        path = normNewPath,
                        title = data.title,
                        artist = data.artist,
                        album = data.album,
                        year = data.year,
                        genre = data.genre
                    )
                    musicRepository.upsertOverride(overrideNew)
                    com.music.myusiq.utils.MetadataBackupUtils.saveOverrideToBackup(context, overrideNew)

                    if (normOldPath != normNewPath) {
                        val overrideOld = SongOverride(
                            path = normOldPath,
                            title = data.title,
                            artist = data.artist,
                            album = data.album,
                            year = data.year,
                            genre = data.genre
                        )
                        musicRepository.upsertOverride(overrideOld)
                        com.music.myusiq.utils.MetadataBackupUtils.saveOverrideToBackup(context, overrideOld)
                    }
                } catch (e: Exception) {
                    Log.e("MetadataUpdateUseCase", "Tag update failed", e)
                }
            }

            // 2. Update MediaStore database entries including DISPLAY_NAME to rename physical file on Scoped Storage
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, data.title)
                put(MediaStore.Audio.Media.ARTIST, data.artist)
                put(MediaStore.Audio.Media.ALBUM, data.album)
                if (targetFileName.isNotBlank()) {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, targetFileName)
                }
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

            // On Android 10+, updating MediaStore DISPLAY_NAME renames the file on storage.
            // Check if the renamed file exists and update newFilePath accordingly.
            val originalFile = File(data.path)
            val parentDir = originalFile.parentFile
            if (updated > 0 && targetFileName.isNotBlank() && parentDir != null) {
                val renamedFile = File(parentDir, targetFileName)
                if (renamedFile.exists() && renamedFile.absolutePath != newFilePath) {
                    val oldPathBeforeRename = newFilePath
                    newFilePath = renamedFile.absolutePath
                    Log.d("MetadataUpdateUseCase", "MediaStore renamed file on storage to: $newFilePath")

                    // Re-sync artwork cache and overrides with true path
                    com.music.myusiq.utils.ArtworkUtils.copyArtworkCache(context, data.songId, oldPathBeforeRename, data.songId, newFilePath)
                    val normOldPath = PlatformUtils.normalizePath(oldPathBeforeRename)
                    val normNewPath = PlatformUtils.normalizePath(newFilePath)

                    val overrideNew = SongOverride(
                        path = normNewPath,
                        title = data.title,
                        artist = data.artist,
                        album = data.album,
                        year = data.year,
                        genre = data.genre
                    )
                    musicRepository.upsertOverride(overrideNew)
                    com.music.myusiq.utils.MetadataBackupUtils.saveOverrideToBackup(context, overrideNew)

                    if (normOldPath != normNewPath) {
                        val overrideOld = SongOverride(
                            path = normOldPath,
                            title = data.title,
                            artist = data.artist,
                            album = data.album,
                            year = data.year,
                            genre = data.genre
                        )
                        musicRepository.upsertOverride(overrideOld)
                        com.music.myusiq.utils.MetadataBackupUtils.saveOverrideToBackup(context, overrideOld)
                    }
                }
            }

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

            // 3. Scan the files to ensure MediaStore is fully synced
            val scanPaths = if (newFilePath != data.path) arrayOf(newFilePath, data.path) else arrayOf(data.path)
            MediaScannerConnection.scanFile(context, scanPaths, null) { _, _ ->
                scope.launch(Dispatchers.Main) {
                    listener.onUpdateFinished(data.copy(path = newFilePath), refreshLibrary)
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
            
            songs.forEachIndexed { index, song ->
                withContext(Dispatchers.Main) {
                    onProgress(index + 1, songs.size, song.title)
                }
                
                // Clean title using YouTubeTitleCleaner to remove junk
                val (extractedArtist, extractedTitle) = com.music.myusiq.utils.YouTubeTitleCleaner.cleanAndSplit(song.title)
                val cleanTitle = extractedTitle.ifBlank { com.music.myusiq.utils.YouTubeTitleCleaner.cleanTitle(song.title) }
                val cleanArtist = if (!CloudMetadataResolver.isGenericArtist(song.artist)) song.artist else if (extractedArtist != "Unknown Artist") extractedArtist else ""

                val metadata = CloudMetadataResolver.resolve(cleanTitle, cleanArtist)
                if (metadata != null) {
                    val artworkUri = metadata.artworkUrl?.let { downloadImageToCache(context, it) }
                    val proposedTitle = metadata.foundTitle ?: cleanTitle
                    val proposedArtist = metadata.foundArtist ?: if (cleanArtist.isNotBlank()) cleanArtist else song.artist
                    val proposedAlbum = metadata.album ?: if (song.album.equals("<unknown>", true)) "Unknown Album" else song.album

                    val edit = PendingTrackEdit(
                        songId = song.id,
                        title = proposedTitle,
                        artist = proposedArtist,
                        album = proposedAlbum,
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

    fun isMessyOrUnknownTrack(song: Song): Boolean {
        val artist = song.artist.trim()
        val title = song.title.trim()

        val isUnknownArtist = CloudMetadataResolver.isGenericArtist(artist)
        val hasFileExtension = Regex("(?i)\\.(mp3|m4a|flac|wav|aac|ogg|wma)$").containsMatchIn(title)
        val isGenericTitle = Regex("(?i)^(track|audio|sound|recording)[_\\s\\-]*\\d*$").matches(title) || title.isBlank()
        val hasClutter = com.music.myusiq.utils.YouTubeTitleCleaner.cleanTitle(title) != title

        return isUnknownArtist || hasFileExtension || isGenericTitle || hasClutter
    }

    private val processedAutoImproveTrackIds = java.util.Collections.synchronizedSet(HashSet<Long>())

    fun startAutoMetadataImprovement(listener: MetadataUpdateListener) {
        val prefs = application.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_improve_metadata", false)) return
        
        scope.launch(Dispatchers.IO) {
            val currentPlayingId = listener.getCurrentPlayingSong()?.id
            val originalTrackList = listener.getOriginalTrackList()
            
            val toImprove = originalTrackList.asSequence().filter { song ->
                song.id != currentPlayingId && !processedAutoImproveTrackIds.contains(song.id) && isMessyOrUnknownTrack(song)
            }.take(3).toList()
            
            if (toImprove.isEmpty()) return@launch

            toImprove.forEach { song ->
                processedAutoImproveTrackIds.add(song.id)

                val (extractedArtist, extractedTitle) = com.music.myusiq.utils.YouTubeTitleCleaner.cleanAndSplit(song.title)
                val cleanTitle = extractedTitle.ifBlank { com.music.myusiq.utils.YouTubeTitleCleaner.cleanTitle(song.title) }
                val cleanArtist = if (!CloudMetadataResolver.isGenericArtist(song.artist)) song.artist else if (extractedArtist != "Unknown Artist") extractedArtist else ""

                val metadata = CloudMetadataResolver.resolve(cleanTitle, cleanArtist)
                if (metadata != null) {
                    val artworkUri = metadata.artworkUrl?.let { downloadImageToCache(application, it) }
                    val proposedTitle = metadata.foundTitle ?: cleanTitle
                    val proposedArtist = metadata.foundArtist ?: if (cleanArtist.isNotBlank()) cleanArtist else song.artist
                    val proposedAlbum = metadata.album ?: if (song.album.equals("<unknown>", true)) "Unknown Album" else song.album

                    val edit = PendingTrackEdit(
                        songId = song.id,
                        title = proposedTitle,
                        artist = proposedArtist,
                        album = proposedAlbum,
                        year = metadata.year ?: "",
                        genre = metadata.genre ?: "",
                        artworkUri = artworkUri,
                        path = song.path
                    )
                    executeMediaStoreUpdate(application, edit, refreshLibrary = false, showToast = false, listener = listener)
                }
                delay(2000.milliseconds)
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

    private fun savePreEditSnapshotsIfAvailable(context: Context, updates: List<PendingTrackEdit>, listener: MetadataUpdateListener) {
        try {
            val originalTrackMap = listener.getOriginalTrackList().associateBy { it.id }
            val snapshots = updates.mapNotNull { edit ->
                val song = originalTrackMap[edit.songId]
                if (song != null) {
                    PendingTrackEdit(
                        songId = song.id,
                        title = song.title,
                        artist = song.artist,
                        album = song.album,
                        year = "",
                        genre = song.genre,
                        path = song.path
                    )
                } else null
            }
            if (snapshots.isNotEmpty()) {
                com.music.myusiq.utils.MetadataBackupUtils.savePreEditSnapshots(context, snapshots)
            }
        } catch (e: Exception) {
            Log.e("MetadataUpdateUseCase", "Failed to record pre-edit snapshot", e)
        }
    }

    fun requestBulkMetadataUpdate(context: Context, updates: List<PendingTrackEdit>, listener: MetadataUpdateListener, onIntent: (IntentSenderRequest) -> Unit) {
        if (updates.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            savePreEditSnapshotsIfAvailable(context, updates, listener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = updates.map { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it.songId) }
                val intent = MediaStore.createWriteRequest(context.contentResolver, uris)
                withContext(Dispatchers.Main) {
                    onIntent(IntentSenderRequest.Builder(intent.intentSender).build())
                }
            } else {
                updates.forEachIndexed { index, edit ->
                    withContext(Dispatchers.Main) {
                        listener.onBulkProgress(index + 1, updates.size, edit.title)
                    }
                    executeMediaStoreUpdate(context, edit, refreshLibrary = false, showToast = false, listener = listener)
                }
                withContext(Dispatchers.Main) {
                    listener.onBulkFinished()
                }
            }
        }
    }

    fun executePendingWrite(context: Context, pendingTrackEditData: PendingTrackEdit?, pendingBulkTrackEditData: List<PendingTrackEdit>?, listener: MetadataUpdateListener, onConsume: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                Log.d("MetadataUpdateUseCase", "Executing pending write. data=$pendingTrackEditData, bulk=$pendingBulkTrackEditData")
                
                val bulkList = pendingBulkTrackEditData ?: listOfNotNull(pendingTrackEditData)
                val isBulk = bulkList.size > 1

                savePreEditSnapshotsIfAvailable(context, bulkList, listener)

                bulkList.forEachIndexed { index, edit ->
                    withContext(Dispatchers.Main) {
                        listener.onBulkProgress(index + 1, bulkList.size, edit.title)
                    }
                    executeMediaStoreUpdate(context, edit, refreshLibrary = false, showToast = !isBulk, listener = listener)
                }
            } catch (e: Exception) {
                Log.e("MetadataUpdateUseCase", "Crash in executePendingWrite", e)
            } finally {
                withContext(Dispatchers.Main) {
                    listener.onBulkFinished()
                    onConsume()
                }
            }
        }
    }

    fun revertLastMetadataFix(
        context: Context,
        listener: MetadataUpdateListener,
        onComplete: (Int) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val snapshots = com.music.myusiq.utils.MetadataBackupUtils.loadPreEditSnapshots(context)
            if (snapshots.isEmpty()) {
                withContext(Dispatchers.Main) { onComplete(0) }
                return@launch
            }

            snapshots.forEachIndexed { index, snapshot ->
                withContext(Dispatchers.Main) {
                    listener.onBulkProgress(index + 1, snapshots.size, snapshot.title)
                }
                executeMediaStoreUpdate(context, snapshot, refreshLibrary = false, showToast = false, listener = listener)
            }

            com.music.myusiq.utils.MetadataBackupUtils.clearPreEditSnapshots(context)

            withContext(Dispatchers.Main) {
                listener.onBulkFinished()
                onComplete(snapshots.size)
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
