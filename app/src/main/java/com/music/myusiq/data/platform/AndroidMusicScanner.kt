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

    companion object {
        private var cachedSongs: List<RawSong>? = null
        private var lastScanTimestamp = 0L
        private const val CACHE_TTL_MS = 10_000L // 10 seconds cache to prevent duplicate scans

        fun invalidateCache() {
            cachedSongs = null
            lastScanTimestamp = 0L
        }
    }

    override suspend fun scanAllSongs(): List<RawSong> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedSongs != null && (now - lastScanTimestamp < CACHE_TTL_MS)) {
            return@withContext cachedSongs!!
        }

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

                        // Do not silently skip path duplicates; let them be handled by Duplicate Cleaner
                        // Fallback to filename if title is blank or track default
                        if (title.isBlank() || title.equals("track", ignoreCase = true)) {
                            try {
                                val file = java.io.File(path)
                                if (file.exists()) {
                                    val fileName = file.nameWithoutExtension
                                    if (fileName.isNotBlank()) {
                                        title = fileName
                                    }
                                }
                            } catch (_: Exception) {}
                        }

                        if (artist.isBlank() || artist.equals("<unknown>", ignoreCase = true)) {
                            artist = "Unknown Artist"
                        }
                        if (album.isBlank() || album.equals("<unknown>", ignoreCase = true)) {
                            album = "Unknown Album"
                        }

                        songs.add(RawSong(id, title, artist, path, duration, albumId, album, genre, dateAdded, size))
                    }
                }
            } catch (e: Exception) {
                Log.e("AndroidMusicScanner", "Error scanning songs via MediaStore URI $targetUri", e)
            }
        }

        // Fast path: If MediaStore successfully indexed the device tracks, return immediately for instant cold-start launch
        if (songs.isNotEmpty()) {
            cachedSongs = songs
            lastScanTimestamp = System.currentTimeMillis()
            return@withContext songs
        }

        // Fallback: Perform targeted direct file system scan on standard music & download directories if MediaStore is empty
        try {
            val scannedPaths = songs.asSequence().map { PlatformUtils.normalizePath(it.path).lowercase() }.toMutableSet()
            val storageRoots = mutableListOf<java.io.File>()

            val allowedUris = getAllowedFolderUris()
            if (allowedUris.isNotEmpty()) {
                allowedUris.forEach { path ->
                    val f = java.io.File(path)
                    if (f.exists() && f.isDirectory) storageRoots.add(f)
                }
            }

            val extDir = Environment.getExternalStorageDirectory()
            if (extDir != null && extDir.exists()) {
                listOf(
                    Environment.DIRECTORY_MUSIC,
                    Environment.DIRECTORY_DOWNLOADS,
                    Environment.DIRECTORY_PODCASTS,
                    Environment.DIRECTORY_RINGTONES,
                    "Audiobooks",
                    "Music",
                    "Download",
                    "Podcasts",
                    "Ringtones"
                ).forEach { dirName ->
                    try {
                        val dir = java.io.File(extDir, dirName)
                        if (dir.exists() && dir.isDirectory) storageRoots.add(dir)
                    } catch (_: Exception) {}
                }
            }

            val audioExtensions = setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus", "wma")

            for (root in storageRoots.distinct()) {
                root.walkTopDown()
                    .onEnter { dir ->
                        val name = dir.name
                        !name.startsWith(".") && !name.equals("Android", ignoreCase = true)
                    }
                    .maxDepth(3)
                    .forEach { file ->
                        try {
                            if (file.isFile && file.length() > 0 && file.extension.lowercase() in audioExtensions) {
                                val normPath = PlatformUtils.normalizePath(file.absolutePath)
                                if (normPath.lowercase() !in scannedPaths) {
                                    scannedPaths.add(normPath.lowercase())
                                    val songId = kotlin.math.abs(file.absolutePath.hashCode().toLong())
                                    val title = file.nameWithoutExtension
                                    val artist = "Unknown Artist"
                                    val album = "Unknown Album"

                                    val raw = RawSong(
                                        id = songId,
                                        title = title,
                                        artist = artist,
                                        path = normPath,
                                        duration = 0,
                                        albumId = 0L,
                                        album = album,
                                        genre = "",
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
            Log.w("AndroidMusicScanner", "Error during targeted file system scan: ${e.message}")
        }

        // Also scan any content URIs (SAF tree URIs) granted via OpenDocumentTree on custom AOSP/Axion ROMs
        try {
            val scannedPaths = songs.asSequence().map { PlatformUtils.normalizePath(it.path).lowercase() }.toMutableSet()
            for (uriString in folderPreferences.getAllowedFolderUris()) {
                if (uriString.startsWith("content://")) {
                    try {
                        val treeUri = uriString.toUri()
                        val docDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                        if (docDir != null) {
                            scanDocFileRecursive(docDir, songs, scannedPaths)
                        }
                    } catch (e: Exception) {
                        Log.e("AndroidMusicScanner", "Error scanning content URI tree $uriString", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("AndroidMusicScanner", "Error during content URI scan: ${e.message}")
        }

        cachedSongs = songs
        lastScanTimestamp = System.currentTimeMillis()
        songs
    }

    private fun scanDocFileRecursive(
        dir: androidx.documentfile.provider.DocumentFile,
        songs: MutableList<RawSong>,
        scannedPaths: MutableSet<String>
    ) {
        val audioExtensions = setOf("mp3", "m4a", "flac", "wav", "aac", "ogg", "opus", "wma")
        try {
            dir.listFiles().forEach { file ->
                if (file.isDirectory) {
                    scanDocFileRecursive(file, songs, scannedPaths)
                } else if (file.isFile && file.length() > 0) {
                    val name = file.name ?: ""
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in audioExtensions) {
                        val uri = file.uri
                        val path = uri.toString()
                        val normPath = PlatformUtils.normalizePath(path)
                        if (normPath.lowercase() !in scannedPaths) {
                            scannedPaths.add(normPath.lowercase())
                            val songId = kotlin.math.abs(path.hashCode().toLong())
                            val title = name.substringBeforeLast('.')
                            val raw = RawSong(
                                id = songId,
                                title = title,
                                artist = "Unknown Artist",
                                path = path,
                                duration = 0,
                                albumId = 0L,
                                album = "Unknown Album",
                                genre = "",
                                dateAdded = file.lastModified() / 1000,
                                size = file.length()
                            )
                            songs.add(raw)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    override fun getBlacklistedFolderPaths(): Set<String> {
        val paths = folderPreferences.getBlacklistedFolderPaths().toMutableSet()
        if (folderPreferences.isExcludeVoiceNotesEnabled()) {
            paths.addAll(listOf("voice recorder", "sound recorder", "voice notes", "recordings"))
        }
        if (folderPreferences.isExcludeMessagingEnabled()) {
            paths.addAll(listOf("whatsapp audio", "whatsapp voice notes", "telegram audio", "telegram documents"))
        }
        if (folderPreferences.isExcludeRingtonesEnabled()) {
            paths.addAll(listOf("ringtones", "notifications", "alarms"))
        }
        return paths
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
                        val relativePath = if (treeId.contains(":")) treeId.substringAfter(":").trim() else treeId
                        val basePrimary = Environment.getExternalStorageDirectory().absolutePath
                        val resolvedPath = if (relativePath.isBlank()) basePrimary else "$basePrimary/$relativePath"
                        
                        val f = java.io.File(resolvedPath)
                        if (f.exists()) {
                            PlatformUtils.normalizePath(f.absolutePath).lowercase()
                        } else {
                            var foundPath: String? = null
                            val storageParent = java.io.File("/storage")
                            if (storageParent.exists() && storageParent.isDirectory) {
                                storageParent.listFiles()?.forEach { vol ->
                                    if (vol.isDirectory && !vol.name.equals("self", true)) {
                                        val candidate = java.io.File(vol, relativePath)
                                        if (candidate.exists()) {
                                            foundPath = PlatformUtils.normalizePath(candidate.absolutePath).lowercase()
                                        }
                                    }
                                }
                            }
                            foundPath ?: PlatformUtils.normalizePath(resolvedPath).lowercase()
                        }
                    } else {
                        val path = uri.path
                        if (path != null) PlatformUtils.normalizePath(path).lowercase() else null
                    }
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
