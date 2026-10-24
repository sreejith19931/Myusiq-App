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
        val contentUris = mutableListOf(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                contentUris.add(MediaStore.Audio.Media.getContentUri("external"))
            } catch (_: Exception) {}
        }

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
        val selection = "${MediaStore.Audio.Media.SIZE} > 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        for (targetUri in contentUris.distinct()) {
            try {
                context.contentResolver.query(targetUri, projection, selection, null, sortOrder)?.use { cursor ->
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
                        var title = cursor.getString(titleColumn) ?: ""
                        var artist = cursor.getString(artistColumn) ?: ""
                        val duration = cursor.getInt(durationColumn)
                        val albumId = cursor.getLong(albumIdColumn)
                        var album = cursor.getString(albumColumn) ?: ""
                        var genre = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.GENRE)) ?: ""
                        } else ""
                        val dateAdded = cursor.getLong(dateAddedColumn)
                        val size = cursor.getLong(sizeColumn)

                        // Avoid duplicate entries
                        if (songs.any { PlatformUtils.normalizePath(it.path).equals(PlatformUtils.normalizePath(path), ignoreCase = true) }) {
                            continue
                        }

                        // Check physical ID3 metadata directly from the file only if MediaStore metadata is generic or missing
                        val isGenericArtist = artist.isBlank() || artist.equals("<unknown>", ignoreCase = true) || artist.equals("unknown artist", ignoreCase = true)
                        val isGenericTitle = title.isBlank() || title.endsWith(".mp3", ignoreCase = true) || title.equals("track", ignoreCase = true)
                        if (isGenericArtist || isGenericTitle || album.isBlank() || album.equals("<unknown>", ignoreCase = true)) {
                            try {
                                val file = java.io.File(path)
                                if (file.exists() && file.length() > 0) {
                                    val fileNameTitle = file.nameWithoutExtension
                                    val retriever = android.media.MediaMetadataRetriever()
                                    retriever.setDataSource(path)
                                    val fileTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                                    val fileArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                    val fileAlbum = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                                    val fileGenre = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)
                                    retriever.release()

                                    if (!fileTitle.isNullOrBlank()) {
                                        title = fileTitle
                                    } else if (isGenericTitle && fileNameTitle.isNotBlank()) {
                                        title = fileNameTitle
                                    }
                                    if (!fileArtist.isNullOrBlank()) artist = fileArtist
                                    if (!fileAlbum.isNullOrBlank()) album = fileAlbum
                                    if (!fileGenre.isNullOrBlank()) genre = fileGenre
                                }
                            } catch (_: Exception) {}
                        }

                        songs.add(RawSong(id, title, artist, path, duration, albumId, album, genre, dateAdded, size))
                    }
                }
            } catch (e: Exception) {
                Log.e("AndroidMusicScanner", "Error scanning songs via MediaStore URI $targetUri", e)
            }
        }

        // Direct File System Fallback across all internal and external storage roots
        try {
            val scannedPaths = songs.map { PlatformUtils.normalizePath(it.path).lowercase() }.toMutableSet()
            val storageRoots = mutableListOf<java.io.File>()

            val extDir = Environment.getExternalStorageDirectory()
            if (extDir != null && extDir.exists()) {
                storageRoots.add(extDir)
            }

            val storageParent = java.io.File("/storage")
            if (storageParent.exists() && storageParent.isDirectory) {
                storageParent.listFiles()?.forEach { file ->
                    if (file.isDirectory && file.canRead() && !file.name.equals("self", true) && !file.name.equals("emulated", true)) {
                        storageRoots.add(file)
                    }
                }
            }

            val audioExtensions = setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus", "wma")

            for (root in storageRoots.distinct()) {
                root.walkTopDown()
                    .onEnter { dir ->
                        val name = dir.name
                        !name.startsWith(".") && !name.equals("Android", ignoreCase = true)
                    }
                    .maxDepth(10)
                    .forEach { file ->
                        try {
                            if (file.isFile && file.length() > 0 && file.extension.lowercase() in audioExtensions) {
                                val normPath = PlatformUtils.normalizePath(file.absolutePath)
                                if (normPath.lowercase() !in scannedPaths) {
                                    scannedPaths.add(normPath.lowercase())
                                    val songId = kotlin.math.abs(file.absolutePath.hashCode().toLong())
                                    var title = file.nameWithoutExtension
                                    var artist = "Unknown Artist"
                                    var album = "Unknown Album"
                                    var genre = ""
                                    var duration = 0

                                    try {
                                        val retriever = android.media.MediaMetadataRetriever()
                                        retriever.setDataSource(file.absolutePath)
                                        val t = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                                        val a = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                                        val alb = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                                        val g = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_GENRE)
                                        val dur = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull()
                                        retriever.release()

                                        if (!t.isNullOrBlank()) title = t
                                        if (!a.isNullOrBlank()) artist = a
                                        if (!alb.isNullOrBlank()) album = alb
                                        if (!g.isNullOrBlank()) genre = g
                                        if (dur != null) duration = dur
                                    } catch (_: Exception) {}

                                    val raw = RawSong(
                                        id = songId,
                                        title = title,
                                        artist = artist,
                                        path = normPath,
                                        duration = duration,
                                        albumId = 0L,
                                        album = album,
                                        genre = genre,
                                        dateAdded = file.lastModified() / 1000,
                                        size = file.length()
                                    )
                                    songs.add(raw)
                                }
                            }
                        } catch (_: Exception) {}
                    }
            }
        } catch (e: Exception) {
            Log.w("AndroidMusicScanner", "Error during direct file system scan: ${e.message}")
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
