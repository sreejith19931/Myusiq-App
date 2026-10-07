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
        val rawLyrics = musicRepository.getLyricsForPath(normalizedPath)?.rawLyrics
            ?: run {
                // Try local file loading as fallback
                val fileLyrics = withContext(Dispatchers.IO) {
                    try {
                        val musicFile = File(path)
                        if (musicFile.exists()) {
                            val lyricsDir = File(musicFile.parentFile, "lyrics")
                            val baseName = musicFile.nameWithoutExtension
                            
                            // 1. Check 'lyrics' subfolder (Priority)
                            val lrcFile = File(lyricsDir, "$baseName.lrc")
                            if (lrcFile.exists()) return@withContext lrcFile.readText()
                            
                            val txtFile = File(lyricsDir, "$baseName.txt")
                            if (txtFile.exists()) return@withContext txtFile.readText()
                            
                            // 2. Check same folder
                            val sameFolderLrc = File(musicFile.parentFile, "$baseName.lrc")
                            if (sameFolderLrc.exists()) return@withContext sameFolderLrc.readText()
                            
                            val sameFolderTxt = File(musicFile.parentFile, "$baseName.txt")
                            if (sameFolderTxt.exists()) return@withContext sameFolderTxt.readText()
                        }
                        null
                    } catch (_: Exception) {
                        null
                    }
                }
                
                if (fileLyrics != null) {
                    // Cache in DB for faster future access and to associate with this new path
                    scope.launch(Dispatchers.IO) {
                        musicRepository.upsertLyrics(LyricsOverride(normalizedPath, fileLyrics))
                    }
                    fileLyrics
                } else null
            }

        return rawLyrics
    }

    fun saveLyrics(path: String, lyrics: String) {
        scope.launch(Dispatchers.IO) {
            musicRepository.upsertLyrics(LyricsOverride(PlatformUtils.normalizePath(path), lyrics))
            try {
                val musicFile = File(path)
                if (musicFile.exists()) {
                    // Save in 'lyrics' folder in the same directory as the music file
                    val lyricsDir = File(musicFile.parentFile, "lyrics")
                    if (!lyricsDir.exists()) lyricsDir.mkdirs()
                    
                    val baseName = musicFile.nameWithoutExtension
                    // Clean up any existing lyrics files first to ensure replacement
                    listOf(".lrc", ".txt").forEach { ext ->
                        val f = File(lyricsDir, "$baseName$ext")
                        if (f.exists()) f.delete()
                    }
                    
                    // Use music file's name for the lyrics file
                    val lrcFile = File(lyricsDir, "$baseName.lrc")
                    lrcFile.writeText(lyrics)
                    
                    // Also try saving as .txt for compatibility if it's plain text
                    if (!lyrics.contains("[")) {
                        val txtFile = File(lyricsDir, "$baseName.txt")
                        txtFile.writeText(lyrics)
                        MediaScannerConnection.scanFile(application, arrayOf(lrcFile.absolutePath, txtFile.absolutePath), null, null)
                    } else {
                        MediaScannerConnection.scanFile(application, arrayOf(lrcFile.absolutePath), null, null)
                    }
                }
            } catch (e: Exception) {
                Log.e("LyricsUseCase", "Error saving lyrics to disk", e)
            }
        }
    }

    fun deleteLyrics(path: String) {
        scope.launch(Dispatchers.IO) {
            musicRepository.deleteLyrics(PlatformUtils.normalizePath(path))
            try {
                val musicFile = File(path)
                val lyricsDir = File(musicFile.parentFile, "lyrics")
                val baseName = musicFile.nameWithoutExtension
                
                val lrcFile = File(lyricsDir, "$baseName.lrc")
                val txtFile = File(lyricsDir, "$baseName.txt")
                
                if (lrcFile.exists()) lrcFile.delete()
                if (txtFile.exists()) txtFile.delete()

                val sameLrc = File(musicFile.parentFile, "$baseName.lrc")
                val sameTxt = File(musicFile.parentFile, "$baseName.txt")
                if (sameLrc.exists()) sameLrc.delete()
                if (sameTxt.exists()) sameTxt.delete()
                
                MediaScannerConnection.scanFile(application, arrayOf(lrcFile.absolutePath, txtFile.absolutePath), null, null)
            } catch (e: Exception) {
                Log.e("LyricsUseCase", "Error deleting lyrics from disk", e)
            }
        }
    }

    suspend fun deleteAllLyrics(songs: List<com.music.myusiq.domain.models.Song>) {
        musicRepository.deleteAllLyrics()
        withContext(Dispatchers.IO) {
            val allSongs = if (songs.isNotEmpty()) songs else musicRepository.getSongs(false)
            allSongs.forEach { song ->
                try {
                    val musicFile = File(song.path)
                    if (musicFile.exists()) {
                        val parentFolder = musicFile.parentFile
                        val lyricsDir = File(parentFolder, "lyrics")
                        val baseName = musicFile.nameWithoutExtension.lowercase()

                        if (lyricsDir.exists()) {
                            lyricsDir.listFiles()?.forEach { file ->
                                if (file.nameWithoutExtension.lowercase() == baseName &&
                                    (file.extension.equals("lrc", ignoreCase = true) || file.extension.equals("txt", ignoreCase = true))) {
                                    file.delete()
                                }
                            }
                        }

                        parentFolder?.listFiles()?.forEach { file ->
                            if (file.nameWithoutExtension.lowercase() == baseName &&
                                (file.extension.equals("lrc", ignoreCase = true) || file.extension.equals("txt", ignoreCase = true))) {
                                file.delete()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LyricsUseCase", "Error clearing lyrics for song: ${song.title}", e)
                }
            }
        }
    }

    suspend fun getSongsWithLyrics(songs: List<com.music.myusiq.domain.models.Song>): List<com.music.myusiq.domain.models.Song> {
        return withContext(Dispatchers.IO) {
            val dbLyricsPaths = musicRepository.getAllLyricsOverridesSync().map { it.songPath.lowercase() }.toSet()
            songs.filter { song ->
                val normPath = PlatformUtils.normalizePath(song.path).lowercase()
                if (dbLyricsPaths.contains(normPath)) return@filter true

                try {
                    val musicFile = File(song.path)
                    if (musicFile.exists()) {
                        val lyricsDir = File(musicFile.parentFile, "lyrics")
                        val baseName = musicFile.nameWithoutExtension
                        val lrcFile = File(lyricsDir, "$baseName.lrc")
                        val txtFile = File(lyricsDir, "$baseName.txt")
                        val sameLrc = File(musicFile.parentFile, "$baseName.lrc")
                        val sameTxt = File(musicFile.parentFile, "$baseName.txt")
                        return@filter lrcFile.exists() || txtFile.exists() || sameLrc.exists() || sameTxt.exists()
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
