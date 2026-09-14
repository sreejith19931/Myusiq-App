package com.music.rhythm.ui.viewmodels

import android.app.Application
import android.media.MediaScannerConnection
import android.util.Log
import com.music.rhythm.data.MusicRepository
import com.music.rhythm.domain.models.LyricsOverride
import com.music.rhythm.utils.PlatformUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
) {
    suspend fun getLyricsForSong(path: String): String? {
        val normalizedPath = PlatformUtils.normalizePath(path)
        return musicRepository.getLyricsForPath(normalizedPath)?.rawLyrics
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
                
                MediaScannerConnection.scanFile(application, arrayOf(lrcFile.absolutePath, txtFile.absolutePath), null, null)
            } catch (e: Exception) {
                Log.e("LyricsUseCase", "Error deleting lyrics from disk", e)
            }
        }
    }

    suspend fun searchLyricsOnline(title: String, artist: String): List<LyricsResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val query = URLEncoder.encode("$artist $title", "UTF-8")
                val url = URL("https://lrclib.net/api/search?q=$query")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonArray = JSONArray(response)
                    val results = mutableListOf<LyricsResponse>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val synced = obj.optString("syncedLyrics")
                        val plain = obj.optString("plainLyrics")
                        results.add(
                            LyricsResponse(
                                lyricContent = synced.ifBlank { plain },
                                suggestedTitle = obj.optString("trackName"),
                                suggestedArtist = obj.optString("artistName"),
                                suggestedAlbum = obj.optString("albumName"),
                                confidenceScore = 1.0f,
                            ),
                        )
                    }
                    connection.disconnect()
                    return@withContext results
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("LyricsUseCase", "Error searching lyrics online", e)
            }
            emptyList()
        }
    }

    suspend fun fetchLyricsOnline(title: String, artist: String): LyricsResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val query = URLEncoder.encode("track_name=$title&artist_name=$artist", "UTF-8")
                val url = URL("https://lrclib.net/api/get?$query")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val obj = JSONObject(response)
                    val synced = obj.optString("syncedLyrics")
                    val plain = obj.optString("plainLyrics")
                    val content = synced.ifBlank { plain }
                    connection.disconnect()
                    if (content.isNotBlank()) {
                        return@withContext LyricsResponse(
                            lyricContent = content,
                            suggestedTitle = obj.optString("trackName"),
                            suggestedArtist = obj.optString("artistName"),
                            suggestedAlbum = obj.optString("albumName"),
                        )
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e("LyricsUseCase", "Error fetching lyrics online", e)
            }
            null
        }
    }
}
