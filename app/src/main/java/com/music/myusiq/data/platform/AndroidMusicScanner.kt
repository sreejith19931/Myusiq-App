package com.music.myusiq.data.platform

import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.core.net.toUri
import com.music.myusiq.data.MusicScanner
import com.music.myusiq.data.RawSong
import com.music.myusiq.utils.FolderPreferences
import com.music.myusiq.utils.PlatformUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidMusicScanner(private val context: Context) : MusicScanner {
    private val folderPreferences = FolderPreferences(context)

    override suspend fun scanAllSongs(): List<RawSong> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<RawSong>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
        )
        val selection = "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_RINGTONE} != 0 OR ${MediaStore.Audio.Media.IS_NOTIFICATION} != 0)"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
            context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataColumn) ?: continue
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn) ?: ""
                    val artist = cursor.getString(artistColumn) ?: ""
                    val duration = cursor.getInt(durationColumn)
                    val albumId = cursor.getLong(albumIdColumn)
                    val album = cursor.getString(albumColumn) ?: ""
                    val genre = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)) ?: ""
                    } else ""
                    val dateAdded = cursor.getLong(dateAddedColumn)
                    val size = cursor.getLong(sizeColumn)

                    songs.add(RawSong(id, title, artist, path, duration, albumId, album, genre, dateAdded, size))
                }
            }
        } catch (e: Exception) {
            Log.e("AndroidMusicScanner", "Error scanning songs", e)
        }
        songs
    }

    override fun getBlacklistedFolderPaths(): Set<String> {
        return folderPreferences.getBlacklistedFolderPaths()
    }

    override fun getAllowedFolderUris(): Set<String> {
        val allowedFolderUris = folderPreferences.getAllowedFolderUris()
        return allowedFolderUris.asSequence().mapNotNull { uriString ->
            try {
                if (uriString.startsWith("/") || uriString.startsWith("file:") || !uriString.contains(":")) {
                    val allowedPath = if (uriString.startsWith("file:")) {
                        uriString.toUri().path ?: ""
                    } else {
                        uriString
                    }
                    PlatformUtils.normalizePath(allowedPath).lowercase()
                } else {
                    val uri = uriString.toUri()
                    if (DocumentsContract.isTreeUri(uri)) {
                        val treeId = DocumentsContract.getTreeDocumentId(uri)
                        if (treeId.startsWith("primary", ignoreCase = true)) {
                            val relativePathInId = treeId.substringAfter("primary:").trim()
                            val basePrimary = Environment.getExternalStorageDirectory().absolutePath
                            val allowedPath = if (relativePathInId.isEmpty()) basePrimary 
                                              else "$basePrimary/$relativePathInId"
                            PlatformUtils.normalizePath(allowedPath).lowercase()
                        } else {
                            val parts = treeId.split(":")
                            if (parts.size >= 2) {
                                val volumeId = parts[0]
                                val relativePath = parts[1]
                                val allowedPath = "/storage/$volumeId/$relativePath"
                                PlatformUtils.normalizePath(allowedPath).lowercase()
                            } else null
                        }
                    } else null
                }
            } catch (e: Exception) {
                Log.e("AndroidMusicScanner", "Error pre-processing allowed folder: $uriString", e)
                null
            }
        }.toSet()
    }

    override fun toggleFolderBlacklist(path: String, isIncluded: Boolean) {
        folderPreferences.toggleFolderBlacklist(path, isIncluded)
    }

    override fun addAllowedFolderUri(path: String) {
        folderPreferences.addAllowedFolderUri(path)
    }
}
