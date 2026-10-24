package com.music.myusiq.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.music.myusiq.data.MusicRepository
import com.music.myusiq.domain.models.SongOverride
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MetadataBackupUtils {

    private fun getPublicBackupFile(): File? {
        val dirs = listOfNotNull(
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) } catch (_: Exception) { null },
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) } catch (_: Exception) { null },
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) } catch (_: Exception) { null }
        )
        for (dir in dirs) {
            try {
                val myusiqDir = File(dir, ".myusiq_backup")
                val dirExists = try { myusiqDir.exists() } catch (_: Exception) { false }
                if (!dirExists) {
                    val legacyDir = File(dir, ".rhythm_backup")
                    if (try { legacyDir.exists() } catch (_: Exception) { false }) {
                        val legacyFile = File(legacyDir, "metadata_overrides_backup.json")
                        if (try { legacyFile.exists() } catch (_: Exception) { false }) return legacyFile
                    }
                    try { myusiqDir.mkdirs() } catch (_: Exception) {}
                }
                val file = File(myusiqDir, "metadata_overrides_backup.json")
                val fileExists = try { file.exists() } catch (_: Exception) { false }
                val dirWritable = try { myusiqDir.canWrite() } catch (_: Exception) { false }
                if (fileExists || dirWritable) return file
            } catch (_: Exception) {}
        }
        return null
    }

    private fun getAppBackupFile(context: Context): File {
        return File(context.getExternalFilesDir(null), "metadata_overrides_backup.json")
    }

    private fun saveToPublicMediaStore(context: Context, jsonString: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "metadata_overrides_backup.json")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/.myusiq_backup")
                }

                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("metadata_overrides_backup.json", "%${Environment.DIRECTORY_DOWNLOADS}/.myusiq_backup%")
                val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

                var existingUri: Uri? = null
                resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        existingUri = ContentUris.withAppendedId(queryUri, id)
                    }
                }

                val targetUri = existingUri ?: resolver.insert(queryUri, contentValues)
                if (targetUri != null) {
                    resolver.openOutputStream(targetUri, "rwt")?.use { output ->
                        output.write(jsonString.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                }
            } catch (e: Exception) {
                Log.d("MetadataBackupUtils", "MediaStore public backup save failed: ${e.message}")
            }
        }
    }

    private fun readFromPublicMediaStore(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf("metadata_overrides_backup.json")
                val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

                resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val uri = ContentUris.withAppendedId(queryUri, id)
                        resolver.openInputStream(uri)?.use { input ->
                            return input.bufferedReader(Charsets.UTF_8).readText()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("MetadataBackupUtils", "MediaStore public backup read failed: ${e.message}")
            }
        }
        return null
    }

    fun saveOverrideToBackup(context: Context, override: SongOverride) {
        try {
            val appFile = getAppBackupFile(context)
            val publicFile = getPublicBackupFile()

            val existingList = loadOverridesFromBackup(context).toMutableList()
            existingList.removeAll { it.path == override.path }
            existingList.add(override)

            val jsonArray = JSONArray()
            existingList.forEach { item ->
                val obj = JSONObject().apply {
                    put("path", item.path)
                    put("title", item.title)
                    put("artist", item.artist)
                    put("album", item.album)
                    put("genre", item.genre)
                    put("year", item.year)
                }
                jsonArray.put(obj)
            }

            val jsonString = jsonArray.toString()
            try {
                val tempFile = File(appFile.parentFile, "${appFile.name}.tmp")
                tempFile.writeText(jsonString)
                if (appFile.exists()) appFile.delete()
                tempFile.renameTo(appFile)
            } catch (e: Exception) {
                Log.d("MetadataBackupUtils", "App file write failed: ${e.message}")
            }
            try {
                publicFile?.let { pub ->
                    val tempPubFile = File(pub.parentFile, "${pub.name}.tmp")
                    tempPubFile.writeText(jsonString)
                    if (pub.exists()) pub.delete()
                    tempPubFile.renameTo(pub)
                }
            } catch (e: Exception) {
                Log.d("MetadataBackupUtils", "Public file write failed: ${e.message}")
            }
            saveToPublicMediaStore(context, jsonString)

            Log.d("MetadataBackupUtils", "Saved metadata override backup for ${override.path}")
        } catch (e: Exception) {
            Log.e("MetadataBackupUtils", "Error saving metadata backup", e)
        }
    }

    fun loadOverridesFromBackup(context: Context): List<SongOverride> {
        val appFile = getAppBackupFile(context)
        val publicFile = getPublicBackupFile()

        var jsonText: String? = null

        // 1. Try app-private external storage first (always accessible without permission issues)
        if (try { appFile.exists() && appFile.length() > 0L } catch (_: Exception) { false }) {
            jsonText = try {
                appFile.readText()
            } catch (e: Exception) {
                Log.d("MetadataBackupUtils", "Error reading app backup file: ${e.message}")
                null
            }
        }

        // 2. Try direct public File access (may fail with EACCES / FileNotFoundException on Android 10+)
        if (jsonText.isNullOrBlank() && publicFile != null) {
            if (try { publicFile.exists() && publicFile.length() > 0L } catch (_: Exception) { false }) {
                jsonText = try {
                    publicFile.readText()
                } catch (e: Exception) {
                    Log.d("MetadataBackupUtils", "Direct public file read failed (EACCES/Scoped Storage): ${e.message}")
                    null
                }
            }
        }

        // 3. Try reading from MediaStore ContentResolver
        if (jsonText.isNullOrBlank()) {
            jsonText = readFromPublicMediaStore(context)
        }

        if (jsonText.isNullOrBlank()) return emptyList()

        // Sync loaded content to appFile if appFile is currently empty or missing
        if (try { !appFile.exists() || appFile.length() == 0L } catch (_: Exception) { true }) {
            try {
                appFile.writeText(jsonText)
            } catch (e: Exception) {
                Log.d("MetadataBackupUtils", "Failed to cache loaded backup into appFile: ${e.message}")
            }
        }

        return try {
            val jsonArray = JSONArray(jsonText)
            val list = mutableListOf<SongOverride>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SongOverride(
                        path = obj.optString("path", ""),
                        title = obj.optString("title", ""),
                        artist = obj.optString("artist", ""),
                        album = obj.optString("album", ""),
                        genre = obj.optString("genre", ""),
                        year = obj.optString("year", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e("MetadataBackupUtils", "Error loading metadata backup", e)
            emptyList()
        }
    }

    suspend fun restoreOverridesIfEmpty(context: Context, repository: MusicRepository) {
        try {
            val dbOverrides = repository.getAllOverrides()
            if (dbOverrides.isEmpty()) {
                val backupOverrides = loadOverridesFromBackup(context)
                backupOverrides.forEach { override ->
                    repository.upsertOverride(override)
                }
                if (backupOverrides.isNotEmpty()) {
                    Log.d("MetadataBackupUtils", "Restored ${backupOverrides.size} metadata overrides from backup")
                }
            }
        } catch (e: Exception) {
            Log.e("MetadataBackupUtils", "Error restoring metadata overrides", e)
        }
    }

    fun savePreEditSnapshots(context: Context, snapshots: List<com.music.myusiq.ui.viewmodels.PendingTrackEdit>) {
        try {
            val snapshotFile = File(context.getExternalFilesDir(null), "pre_edit_snapshots.json")
            val jsonArray = JSONArray()
            snapshots.forEach { edit ->
                val obj = JSONObject().apply {
                    put("songId", edit.songId)
                    put("title", edit.title)
                    put("artist", edit.artist)
                    put("album", edit.album)
                    put("year", edit.year)
                    put("genre", edit.genre)
                    put("path", edit.path)
                    put("artworkUri", edit.artworkUri?.toString() ?: "")
                    put("lyrics", edit.lyrics ?: "")
                }
                jsonArray.put(obj)
            }
            snapshotFile.writeText(jsonArray.toString())
            Log.d("MetadataBackupUtils", "Saved pre-edit snapshots for ${snapshots.size} tracks")
        } catch (e: Exception) {
            Log.e("MetadataBackupUtils", "Failed to save pre-edit snapshots", e)
        }
    }

    fun loadPreEditSnapshots(context: Context): List<com.music.myusiq.ui.viewmodels.PendingTrackEdit> {
        return try {
            val snapshotFile = File(context.getExternalFilesDir(null), "pre_edit_snapshots.json")
            if (!snapshotFile.exists() || snapshotFile.length() == 0L) return emptyList()

            val jsonArray = JSONArray(snapshotFile.readText())
            val list = mutableListOf<com.music.myusiq.ui.viewmodels.PendingTrackEdit>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val artUriStr = obj.optString("artworkUri", "")
                val artUri = if (artUriStr.isNotBlank()) Uri.parse(artUriStr) else null
                val lyrics = obj.optString("lyrics", "").ifBlank { null }
                list.add(
                    com.music.myusiq.ui.viewmodels.PendingTrackEdit(
                        songId = obj.optLong("songId", -1L),
                        title = obj.optString("title", ""),
                        artist = obj.optString("artist", ""),
                        album = obj.optString("album", ""),
                        year = obj.optString("year", ""),
                        genre = obj.optString("genre", ""),
                        path = obj.optString("path", ""),
                        artworkUri = artUri,
                        lyrics = lyrics
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e("MetadataBackupUtils", "Failed to load pre-edit snapshots", e)
            emptyList()
        }
    }

    fun hasPreEditSnapshots(context: Context): Boolean {
        return try {
            val snapshotFile = File(context.getExternalFilesDir(null), "pre_edit_snapshots.json")
            snapshotFile.exists() && snapshotFile.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    fun clearPreEditSnapshots(context: Context) {
        try {
            val snapshotFile = File(context.getExternalFilesDir(null), "pre_edit_snapshots.json")
            if (snapshotFile.exists()) snapshotFile.delete()
        } catch (_: Exception) {}
    }

    private fun getPublicHistoryFile(): File? {
        val dirs = listOfNotNull(
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) } catch (_: Exception) { null },
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) } catch (_: Exception) { null },
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) } catch (_: Exception) { null }
        )
        for (dir in dirs) {
            try {
                val myusiqDir = File(dir, ".myusiq_backup")
                if (!myusiqDir.exists()) {
                    try { myusiqDir.mkdirs() } catch (_: Exception) {}
                }
                val file = File(myusiqDir, "track_edit_history.json")
                if (file.exists() || myusiqDir.canWrite()) return file
            } catch (_: Exception) {}
        }
        return null
    }

    private fun getAppHistoryFile(context: Context): File {
        return File(context.getExternalFilesDir(null), "track_edit_history.json")
    }

    private fun saveHistoryToPublicMediaStore(context: Context, jsonString: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "track_edit_history.json")
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/.myusiq_backup")
                }

                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf("track_edit_history.json", "%${Environment.DIRECTORY_DOWNLOADS}/.myusiq_backup%")
                val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

                var existingUri: Uri? = null
                resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        existingUri = ContentUris.withAppendedId(queryUri, id)
                    }
                }

                val targetUri = existingUri ?: resolver.insert(queryUri, contentValues)
                if (targetUri != null) {
                    resolver.openOutputStream(targetUri, "rwt")?.use { output ->
                        output.write(jsonString.toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                }
            } catch (e: Exception) {
                Log.d("MetadataBackupUtils", "MediaStore public history save failed: ${e.message}")
            }
        }
    }

    private fun readHistoryFromPublicMediaStore(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf("track_edit_history.json")
                val queryUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

                resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val uri = ContentUris.withAppendedId(queryUri, id)
                        resolver.openInputStream(uri)?.use { input ->
                            return input.bufferedReader(Charsets.UTF_8).readText()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("MetadataBackupUtils", "MediaStore public history read failed: ${e.message}")
            }
        }
        return null
    }

    fun logTrackEdit(context: Context, oldSong: com.music.myusiq.domain.models.Song, newEdit: com.music.myusiq.ui.viewmodels.PendingTrackEdit) {
        try {
            val historyFile = getAppHistoryFile(context)
            val publicFile = getPublicHistoryFile()
            val existing = loadTrackEditHistory(context).toMutableList()
            existing.removeAll { it.songId == oldSong.id }
            val item = TrackEditHistoryItem(
                songId = oldSong.id,
                path = oldSong.path,
                timestamp = System.currentTimeMillis(),
                oldTitle = oldSong.title,
                oldArtist = oldSong.artist,
                oldAlbum = oldSong.album,
                oldYear = oldSong.year ?: "",
                oldGenre = oldSong.genre,
                newTitle = newEdit.title,
                newArtist = newEdit.artist,
                newAlbum = newEdit.album,
                newYear = newEdit.year,
                newGenre = newEdit.genre
            )
            existing.add(0, item)

            val jsonArray = JSONArray()
            existing.take(100).forEach { h ->
                val obj = JSONObject().apply {
                    put("songId", h.songId)
                    put("path", h.path)
                    put("timestamp", h.timestamp)
                    put("oldTitle", h.oldTitle)
                    put("oldArtist", h.oldArtist)
                    put("oldAlbum", h.oldAlbum)
                    put("oldYear", h.oldYear)
                    put("oldGenre", h.oldGenre)
                    put("newTitle", h.newTitle)
                    put("newArtist", h.newArtist)
                    put("newAlbum", h.newAlbum)
                    put("newYear", h.newYear)
                    put("newGenre", h.newGenre)
                }
                jsonArray.put(obj)
            }
            val jsonString = jsonArray.toString()
            try {
                historyFile.writeText(jsonString)
            } catch (e: Exception) {
                Log.d("MetadataBackupUtils", "App history write failed: ${e.message}")
            }
            try {
                publicFile?.writeText(jsonString)
            } catch (e: Exception) {
                Log.d("MetadataBackupUtils", "Public history write failed: ${e.message}")
            }
            saveHistoryToPublicMediaStore(context, jsonString)
        } catch (e: Exception) {
            Log.e("MetadataBackupUtils", "Failed to log track edit history", e)
        }
    }

    fun loadTrackEditHistory(context: Context): List<TrackEditHistoryItem> {
        val appFile = getAppHistoryFile(context)
        val publicFile = getPublicHistoryFile()
        var jsonText: String? = null

        if (try { appFile.exists() && appFile.length() > 0L } catch (_: Exception) { false }) {
            jsonText = try { appFile.readText() } catch (_: Exception) { null }
        }
        if (jsonText.isNullOrBlank() && publicFile != null) {
            if (try { publicFile.exists() && publicFile.length() > 0L } catch (_: Exception) { false }) {
                jsonText = try { publicFile.readText() } catch (_: Exception) { null }
            }
        }
        if (jsonText.isNullOrBlank()) {
            jsonText = readHistoryFromPublicMediaStore(context)
        }

        if (jsonText.isNullOrBlank()) return emptyList()

        if (try { !appFile.exists() || appFile.length() == 0L } catch (_: Exception) { true }) {
            try { appFile.writeText(jsonText) } catch (_: Exception) {}
        }

        return try {
            val jsonArray = JSONArray(jsonText)
            val list = mutableListOf<TrackEditHistoryItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    TrackEditHistoryItem(
                        songId = obj.optLong("songId", -1L),
                        path = obj.optString("path", ""),
                        timestamp = obj.optLong("timestamp", 0L),
                        oldTitle = obj.optString("oldTitle", ""),
                        oldArtist = obj.optString("oldArtist", ""),
                        oldAlbum = obj.optString("oldAlbum", ""),
                        oldYear = obj.optString("oldYear", ""),
                        oldGenre = obj.optString("oldGenre", ""),
                        newTitle = obj.optString("newTitle", ""),
                        newArtist = obj.optString("newArtist", ""),
                        newAlbum = obj.optString("newAlbum", ""),
                        newYear = obj.optString("newYear", ""),
                        newGenre = obj.optString("newGenre", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e("MetadataBackupUtils", "Failed to load track edit history", e)
            emptyList()
        }
    }

    fun clearTrackEditHistory(context: Context) {
        try {
            val appFile = getAppHistoryFile(context)
            val publicFile = getPublicHistoryFile()
            if (appFile.exists()) appFile.delete()
            if (publicFile != null && publicFile.exists()) publicFile.delete()
        } catch (_: Exception) {}
    }
}

data class TrackEditHistoryItem(
    val songId: Long,
    val path: String,
    val timestamp: Long,
    val oldTitle: String,
    val oldArtist: String,
    val oldAlbum: String,
    val oldYear: String,
    val oldGenre: String,
    val newTitle: String,
    val newArtist: String,
    val newAlbum: String,
    val newYear: String,
    val newGenre: String
)
