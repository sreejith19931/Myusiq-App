package com.music.myusiq.ui.viewmodels

import android.app.Application
import android.media.MediaScannerConnection
import android.util.Log
import com.music.myusiq.data.MusicRepository
import com.music.myusiq.domain.models.LyricsOverride
import com.music.myusiq.utils.LrcFetcher
import com.music.myusiq.utils.PlatformUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class LyricsUseCase(
    private val musicRepository: MusicRepository,
    private val application: Application,
    private val scope: CoroutineScope,
    private val lrcFetcher: LrcFetcher = LrcFetcher(),
) {
    suspend fun getLyricsForSong(path: String): String? {
        val normalizedPath = PlatformUtils.normalizePath(path)
        val dbRecord = musicRepository.getLyricsForPath(normalizedPath)
        if (dbRecord != null) {
            val lyricsText = dbRecord.rawLyrics
            return if (lyricsText.isBlank()) null else lyricsText
        }

        // Try local file loading and physical audio tag extraction as fallback
        val fileLyrics = withContext(Dispatchers.IO) {
            try {
                val musicFile = File(path)
                if (musicFile.exists()) {
                    val parentFolder = musicFile.parentFile
                    val lyricsDir = File(parentFolder, "lyrics")
                    val appLyricsDir = File(application.getExternalFilesDir(null), "lyrics")
                    val baseName = musicFile.nameWithoutExtension

                    // 1. Check 'lyrics' subfolder (Priority)
                    val lrcFile = File(lyricsDir, "$baseName.lrc")
                    if (lrcFile.exists() && lrcFile.length() > 0) return@withContext lrcFile.readText()

                    val txtFile = File(lyricsDir, "$baseName.txt")
                    if (txtFile.exists() && txtFile.length() > 0) return@withContext txtFile.readText()

                    // 2. Check same folder
                    val sameFolderLrc = File(parentFolder, "$baseName.lrc")
                    if (sameFolderLrc.exists() && sameFolderLrc.length() > 0) return@withContext sameFolderLrc.readText()

                    val sameFolderTxt = File(parentFolder, "$baseName.txt")
                    if (sameFolderTxt.exists() && sameFolderTxt.length() > 0) return@withContext sameFolderTxt.readText()

                    // 3. Check app external backup directory
                    val backupLrc = File(appLyricsDir, "$baseName.lrc")
                    if (backupLrc.exists() && backupLrc.length() > 0) return@withContext backupLrc.readText()

                    // 4. Try extracting embedded lyrics from ID3/FLAC tag directly
                    val embeddedLyrics = com.music.myusiq.utils.FileTagEditor.extractLyrics(musicFile)
                    if (!embeddedLyrics.isNullOrBlank()) return@withContext embeddedLyrics
                }
                null
            } catch (_: Exception) {
                null
            }
        }

        if (!fileLyrics.isNullOrBlank()) {
            val validLyrics: String = fileLyrics
            // Cache in DB for faster future access and to associate with this new path
            scope.launch(Dispatchers.IO) {
                musicRepository.upsertLyrics(LyricsOverride(normalizedPath, validLyrics))
            }
            return validLyrics
        }

        return null
    }

    private fun deleteFileSafely(file: File) {
        if (!file.exists()) return

        // 1. Try truncating file content first to 0 bytes so even if File.delete() is blocked by Scoped Storage, the file content is empty
        try {
            file.writeText("")
        } catch (_: Exception) {
            try {
                java.io.FileOutputStream(file).use { }
            } catch (_: Exception) {}
        }

        // 2. Try standard Java File.delete()
        var deleted = false
        try {
            deleted = file.delete()
        } catch (e: Exception) {
            Log.d("LyricsUseCase", "File.delete() failed for ${file.absolutePath}: ${e.message}")
        }

        // 3. Try MediaStore Files delete
        if (!deleted || file.exists()) {
            try {
                val uri = android.provider.MediaStore.Files.getContentUri("external")
                val selection = "${android.provider.MediaStore.Files.FileColumns.DATA} = ?"
                val selectionArgs = arrayOf(file.absolutePath)
                application.contentResolver.delete(uri, selection, selectionArgs)
            } catch (e: Exception) {
                Log.e("LyricsUseCase", "ContentResolver delete failed for ${file.absolutePath}", e)
            }
        }

        try {
            MediaScannerConnection.scanFile(application, arrayOf(file.absolutePath), null, null)
        } catch (_: Exception) {}
    }

    fun saveLyrics(path: String, lyrics: String) {
        scope.launch(Dispatchers.IO) {
            musicRepository.upsertLyrics(LyricsOverride(PlatformUtils.normalizePath(path), lyrics))
            try {
                val musicFile = File(path)
                if (musicFile.exists()) {
                    val parentFolder = musicFile.parentFile
                    val lyricsDir = File(parentFolder, "lyrics")
                    val appLyricsDir = File(application.getExternalFilesDir(null), "lyrics")
                    if (!lyricsDir.exists()) lyricsDir.mkdirs()
                    if (appLyricsDir != null && !appLyricsDir.exists()) appLyricsDir.mkdirs()

                    val baseName = musicFile.nameWithoutExtension

                    // Clean up any existing lyrics files first to ensure replacement
                    listOf(".lrc", ".txt").forEach { ext ->
                        deleteFileSafely(File(lyricsDir, "$baseName$ext"))
                        deleteFileSafely(File(parentFolder, "$baseName$ext"))
                        if (appLyricsDir != null) deleteFileSafely(File(appLyricsDir, "$baseName$ext"))
                    }

                    // 1. Write .lrc file in 'lyrics' subfolder ONLY
                    val lrcFile = File(lyricsDir, "$baseName.lrc")
                    try { lrcFile.writeText(lyrics) } catch (_: Exception) {}

                    // 2. Write backup copy in app external files dir
                    if (appLyricsDir != null) {
                        val backupLrcFile = File(appLyricsDir, "$baseName.lrc")
                        try { backupLrcFile.writeText(lyrics) } catch (_: Exception) {}
                    }

                    // 3. Embed lyrics directly into the physical audio file ID3 tag
                    try {
                        com.music.myusiq.utils.FileTagEditor.embedLyrics(musicFile, lyrics)
                    } catch (e: Exception) {
                        Log.e("LyricsUseCase", "Error embedding lyrics in audio tag", e)
                    }

                    MediaScannerConnection.scanFile(
                        application,
                        arrayOf(lrcFile.absolutePath),
                        null,
                        null
                    )
                }
            } catch (e: Exception) {
                Log.e("LyricsUseCase", "Error saving lyrics to disk", e)
            }
        }
    }

    suspend fun deleteLyrics(path: String) = withContext(Dispatchers.IO) {
        val normalizedPath = PlatformUtils.normalizePath(path)
        musicRepository.upsertLyrics(LyricsOverride(normalizedPath, ""))
        try {
            val musicFile = File(path)
            if (musicFile.exists()) {
                val parentFolder = musicFile.parentFile
                val lyricsDir = File(parentFolder, "lyrics")
                val appLyricsDir = File(application.getExternalFilesDir(null), "lyrics")
                val baseName = musicFile.nameWithoutExtension

                deleteFileSafely(File(lyricsDir, "$baseName.lrc"))
                deleteFileSafely(File(lyricsDir, "$baseName.txt"))
                deleteFileSafely(File(parentFolder, "$baseName.lrc"))
                deleteFileSafely(File(parentFolder, "$baseName.txt"))
                if (appLyricsDir != null) {
                    deleteFileSafely(File(appLyricsDir, "$baseName.lrc"))
                    deleteFileSafely(File(appLyricsDir, "$baseName.txt"))
                }

                if (lyricsDir != null && lyricsDir.exists()) {
                    lyricsDir.listFiles()?.forEach { file ->
                        if (file.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
                            (file.extension.equals("lrc", ignoreCase = true) || file.extension.equals("txt", ignoreCase = true))) {
                            deleteFileSafely(file)
                        }
                    }
                }

                parentFolder?.listFiles()?.forEach { file ->
                    if (file.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
                        (file.extension.equals("lrc", ignoreCase = true) || file.extension.equals("txt", ignoreCase = true))) {
                        deleteFileSafely(file)
                    }
                }

                // Clear embedded lyrics from physical audio file ID3 tag
                com.music.myusiq.utils.FileTagEditor.embedLyrics(musicFile, "")
            }
        } catch (e: Exception) {
            Log.e("LyricsUseCase", "Error deleting lyrics from disk", e)
        }
    }

    suspend fun deleteLyricsForSongs(songs: List<com.music.myusiq.domain.models.Song>) = withContext(Dispatchers.IO) {
        songs.forEach { deleteLyrics(it.path) }
    }

    suspend fun deleteAllLyrics(songs: List<com.music.myusiq.domain.models.Song>) = withContext(Dispatchers.IO) {
        musicRepository.deleteAllLyrics()
        val allSongs = if (songs.isNotEmpty()) songs else musicRepository.getSongs(false)
        val appLyricsDir = File(application.getExternalFilesDir(null), "lyrics")

        allSongs.forEach { song ->
            val normPath = PlatformUtils.normalizePath(song.path)
            musicRepository.upsertLyrics(LyricsOverride(normPath, ""))
            try {
                val musicFile = File(song.path)
                if (musicFile.exists()) {
                    val parentFolder = musicFile.parentFile
                    val lyricsDir = File(parentFolder, "lyrics")
                    val baseName = musicFile.nameWithoutExtension

                    deleteFileSafely(File(lyricsDir, "$baseName.lrc"))
                    deleteFileSafely(File(lyricsDir, "$baseName.txt"))
                    deleteFileSafely(File(parentFolder, "$baseName.lrc"))
                    deleteFileSafely(File(parentFolder, "$baseName.txt"))
                    if (appLyricsDir != null) {
                        deleteFileSafely(File(appLyricsDir, "$baseName.lrc"))
                        deleteFileSafely(File(appLyricsDir, "$baseName.txt"))
                    }

                    if (lyricsDir != null && lyricsDir.exists()) {
                        lyricsDir.listFiles()?.forEach { file ->
                            if (file.extension.equals("lrc", ignoreCase = true) || file.extension.equals("txt", ignoreCase = true)) {
                                deleteFileSafely(file)
                            }
                        }
                    }

                    parentFolder?.listFiles()?.forEach { file ->
                        if (file.nameWithoutExtension.equals(baseName, ignoreCase = true) &&
                            (file.extension.equals("lrc", ignoreCase = true) || file.extension.equals("txt", ignoreCase = true))) {
                            deleteFileSafely(file)
                        }
                    }

                    // Clear embedded lyrics from physical audio file ID3 tag
                    com.music.myusiq.utils.FileTagEditor.embedLyrics(musicFile, "")
                }
            } catch (e: Exception) {
                Log.e("LyricsUseCase", "Error clearing lyrics for song: ${song.title}", e)
            }
        }

        // Wipe ALL .lrc and .txt files in all lyrics directories across all music folders
        try {
            val parentFolders = allSongs.mapNotNull { File(it.path).parentFile }.distinct()
            parentFolders.forEach { parent ->
                val lyricsDir = File(parent, "lyrics")
                if (lyricsDir.exists() && lyricsDir.isDirectory) {
                    lyricsDir.listFiles()?.forEach { file ->
                        if (file.extension.equals("lrc", ignoreCase = true) || file.extension.equals("txt", ignoreCase = true)) {
                            deleteFileSafely(file)
                        }
                    }
                }
            }

            if (appLyricsDir != null && appLyricsDir.exists()) {
                appLyricsDir.listFiles()?.forEach { file ->
                    if (file.extension.equals("lrc", ignoreCase = true) || file.extension.equals("txt", ignoreCase = true)) {
                        deleteFileSafely(file)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("LyricsUseCase", "Error wiping lyrics folders", e)
        }
    }

    suspend fun getSongsWithLyrics(songs: List<com.music.myusiq.domain.models.Song>): List<com.music.myusiq.domain.models.Song> {
        return withContext(Dispatchers.IO) {
            val overridesMap = musicRepository.getAllLyricsOverridesSync().associateBy { it.songPath.lowercase() }
            songs.filter { song ->
                val normPath = PlatformUtils.normalizePath(song.path).lowercase()
                val override = overridesMap[normPath]
                if (override != null) {
                    return@filter override.rawLyrics.isNotBlank()
                }

                try {
                    val musicFile = File(song.path)
                    if (musicFile.exists()) {
                        val parentFolder = musicFile.parentFile
                        val lyricsDir = File(parentFolder, "lyrics")
                        val appLyricsDir = File(application.getExternalFilesDir(null), "lyrics")
                        val baseName = musicFile.nameWithoutExtension
                        val lrcFile = File(lyricsDir, "$baseName.lrc")
                        val txtFile = File(lyricsDir, "$baseName.txt")
                        val sameLrc = File(parentFolder, "$baseName.lrc")
                        val sameTxt = File(parentFolder, "$baseName.txt")
                        val backupLrc = File(appLyricsDir, "$baseName.lrc")

                        val hasFile = (lrcFile.exists() && lrcFile.length() > 0) ||
                                (txtFile.exists() && txtFile.length() > 0) ||
                                (sameLrc.exists() && sameLrc.length() > 0) ||
                                (sameTxt.exists() && sameTxt.length() > 0) ||
                                (backupLrc.exists() && backupLrc.length() > 0)

                        if (hasFile) return@filter true

                        val embedded = com.music.myusiq.utils.FileTagEditor.extractLyrics(musicFile)
                        if (!embedded.isNullOrBlank()) return@filter true
                    }
                } catch (_: Exception) {}
                false
            }
        }
    }

    suspend fun searchLyricsOnline(title: String, artist: String): List<LyricsResponse> {
        return coroutineScope {
            val results = mutableListOf<LyricsResponse>()
            
            // 1. LRCLIB Search
            val lrclibDeferred = async(Dispatchers.IO) {
                val lrclibResults = mutableListOf<LyricsResponse>()
                try {
                    val query = URLEncoder.encode("$artist $title", "UTF-8")
                    val url = URL("https://lrclib.net/api/search?q=$query")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonArray = JSONArray(response)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val synced = obj.optString("syncedLyrics")
                            val plain = obj.optString("plainLyrics")
                            val content = synced.ifBlank { plain }
                            if (content.isNotBlank()) {
                                lrclibResults.add(
                                    LyricsResponse(
                                        lyricContent = content,
                                        suggestedTitle = obj.optString("trackName").ifBlank { title },
                                        suggestedArtist = obj.optString("artistName").ifBlank { artist },
                                        suggestedAlbum = obj.optString("albumName"),
                                        confidenceScore = 1.0f,
                                    )
                                )
                            }
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e("LyricsUseCase", "Error searching LRCLIB", e)
                }
                lrclibResults
            }

            // 2. Lyrist Search API
            val lyristDeferred = async(Dispatchers.IO) {
                val lyristResults = mutableListOf<LyricsResponse>()
                try {
                    val encodedTitle = URLEncoder.encode(title, "UTF-8")
                    val encodedArtist = URLEncoder.encode(artist, "UTF-8")
                    val url = URL("https://lyrist.vercel.app/api/$encodedTitle/$encodedArtist")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val obj = JSONObject(response)
                        val lyrics = obj.optString("lyrics")
                        if (lyrics.isNotBlank()) {
                            lyristResults.add(
                                LyricsResponse(
                                    lyricContent = lyrics,
                                    suggestedTitle = obj.optString("title").ifBlank { title },
                                    suggestedArtist = obj.optString("artist").ifBlank { artist },
                                    suggestedAlbum = obj.optString("album"),
                                    artworkUrl = obj.optString("image").takeIf { it.isNotBlank() },
                                    confidenceScore = 0.9f,
                                )
                            )
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e("LyricsUseCase", "Error searching Lyrist", e)
                }
                lyristResults
            }

            // 3. ChartLyrics Search API
            val chartLyricsDeferred = async(Dispatchers.IO) {
                val chartResults = mutableListOf<LyricsResponse>()
                try {
                    val encodedArtist = URLEncoder.encode(artist, "UTF-8")
                    val encodedTitle = URLEncoder.encode(title, "UTF-8")
                    val url = URL("http://api.chartlyrics.com/apiv1.asmx/SearchLyricDirect?artist=$encodedArtist&song=$encodedTitle")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 4000
                    connection.readTimeout = 4000

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val xml = connection.inputStream.bufferedReader().use { it.readText() }
                        val lyrics = xml.substringAfter("<Lyric>", "").substringBefore("</Lyric>").trim()
                        val trackName = xml.substringAfter("<LyricSong>", "").substringBefore("</LyricSong>").trim()
                        val artistName = xml.substringAfter("<LyricArtist>", "").substringBefore("</LyricArtist>").trim()
                        
                        if (lyrics.isNotBlank() && !lyrics.contains("Not found", ignoreCase = true)) {
                            chartResults.add(
                                LyricsResponse(
                                    lyricContent = lyrics,
                                    suggestedTitle = trackName.ifBlank { title },
                                    suggestedArtist = artistName.ifBlank { artist },
                                    suggestedAlbum = "",
                                    confidenceScore = 0.8f,
                                )
                            )
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e("LyricsUseCase", "Error searching ChartLyrics", e)
                }
                chartResults
            }

            val res1 = lrclibDeferred.await()
            val res2 = lyristDeferred.await()
            val res3 = chartLyricsDeferred.await()

            results.addAll(res1)
            results.addAll(res2)
            results.addAll(res3)

            results.distinctBy { it.lyricContent?.take(50) ?: it.suggestedTitle }
        }
    }

    suspend fun fetchLyricsOnline(title: String, artist: String): LyricsResponse? {
        val fetchedSynced = lrcFetcher.fetchSyncedLyrics(artist, title)
        if (!fetchedSynced.isNullOrBlank()) {
            return LyricsResponse(
                lyricContent = fetchedSynced,
                suggestedTitle = title,
                suggestedArtist = artist,
                suggestedAlbum = "",
                confidenceScore = 1.0f,
            )
        }
        val search = searchLyricsOnline(title, artist)
        return search.firstOrNull()
    }
}
