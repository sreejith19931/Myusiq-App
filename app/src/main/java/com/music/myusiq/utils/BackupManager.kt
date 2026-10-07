package com.music.myusiq.utils

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import com.music.myusiq.domain.models.*
import com.music.myusiq.data.dao.SongDao
import com.music.myusiq.DatabaseProvider

object BackupManager {

    suspend fun exportBackup(context: Context, outputStream: OutputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val db = DatabaseProvider.getDatabase(context)
                val backup = JSONObject()

                // 1. Playlists
                val playlistDao = db.playlistDao()
                val playlistsWithTracks = playlistDao.getAllPlaylistsWithTracksSync()
                val playlistsArray = JSONArray()
                playlistsWithTracks.forEach { pwt ->
                    val pObj = JSONObject()
                    pObj.put("id", pwt.playlist.id)
                    pObj.put("name", pwt.playlist.name)
                    val tracksArray = JSONArray()
                    pwt.tracks.forEach { t -> tracksArray.put(t.id) }
                    pObj.put("tracks", tracksArray)
                    playlistsArray.put(pObj)
                }
                backup.put("playlists", playlistsArray)

                // 2. Library Tracks (for Favorites status)
                val libTracks = playlistDao.getAllLibraryTracksSync()
                val libArray = JSONArray()
                libTracks.forEach { t ->
                    val tObj = JSONObject()
                    tObj.put("id", t.id)
                    tObj.put("title", t.title)
                    tObj.put("artist", t.artist)
                    tObj.put("isFavorite", t.isFavorite)
                    libArray.put(tObj)
                }
                backup.put("library_tracks", libArray)

                // 3. Song History
                val history = db.songHistoryDao().getAllHistorySync()
                val historyArray = JSONArray()
                history.forEach { h ->
                    val hObj = JSONObject()
                    hObj.put("songId", h.songId)
                    hObj.put("title", h.title)
                    hObj.put("artist", h.artist)
                    hObj.put("durationMs", h.durationMs)
                    hObj.put("timestamp", h.timestamp)
                    historyArray.put(hObj)
                }
                backup.put("history", historyArray)

                // 4. Overrides & Lyrics
                val songDao = db.songDao()
                val overrides = songDao.getAllOverrides()
                val overridesArray = JSONArray()
                overrides.forEach { o ->
                    val oObj = JSONObject()
                    oObj.put("path", o.path)
                    oObj.put("title", o.title)
                    oObj.put("artist", o.artist)
                    oObj.put("album", o.album)
                    overridesArray.put(oObj)
                }
                backup.put("overrides", overridesArray)

                val lyrics = songDao.getAllLyricsOverridesSync()
                val lyricsArray = JSONArray()
                lyrics.forEach { l ->
                    val lObj = JSONObject()
                    lObj.put("songPath", l.songPath)
                    lObj.put("rawLyrics", l.rawLyrics)
                    lyricsArray.put(lObj)
                }
                backup.put("lyrics", lyricsArray)

                outputStream.write(backup.toString(2).toByteArray())
                outputStream.close()
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun importBackup(context: Context, inputStream: InputStream): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val db = DatabaseProvider.getDatabase(context)
                val content = inputStream.bufferedReader().use { it.readText() }
                val backup = JSONObject(content)

                // 1. Restore Library Tracks (Favorites)
                if (backup.has("library_tracks")) {
                    val libArray = backup.getJSONArray("library_tracks")
                    val tracks = mutableListOf<TrackEntity>()
                    for (i in 0 until libArray.length()) {
                        val tObj = libArray.getJSONObject(i)
                    tracks.add(
                        TrackEntity(
                            id = tObj.getString("id"),
                            title = tObj.getString("title"),
                            artist = tObj.getString("artist"),
                            isFavorite = tObj.getBoolean("isFavorite"),
                        ),
                    )
                }
                db.playlistDao().insertLibraryTracks(tracks)
                }

                // 2. Restore History
                if (backup.has("history")) {
                    val historyArray = backup.getJSONArray("history")
                    val historyList = mutableListOf<SongHistory>()
                    for (i in 0 until historyArray.length()) {
                        val hObj = historyArray.getJSONObject(i)
                        historyList.add(SongHistory(
                            songId = hObj.getString("songId"),
                            title = hObj.getString("title"),
                            artist = hObj.getString("artist"),
                            durationMs = hObj.getLong("durationMs"),
                            timestamp = hObj.getLong("timestamp")
                        ))
                    }
                    db.songHistoryDao().insertHistory(historyList)
                }

                // 3. Restore Overrides & Lyrics
                if (backup.has("overrides")) {
                    val overridesArray = backup.getJSONArray("overrides")
                    val overridesList = mutableListOf<SongOverride>()
                    for (i in 0 until overridesArray.length()) {
                        val oObj = overridesArray.getJSONObject(i)
                        overridesList.add(SongOverride(
                            path = oObj.getString("path"),
                            title = oObj.getString("title"),
                            artist = oObj.getString("artist"),
                            album = oObj.getString("album")
                        ))
                    }
                    db.songDao().insertSongOverrides(overridesList)
                }

                if (backup.has("lyrics")) {
                    val lyricsArray = backup.getJSONArray("lyrics")
                    val lyricsList = mutableListOf<LyricsOverride>()
                    for (i in 0 until lyricsArray.length()) {
                        val lObj = lyricsArray.getJSONObject(i)
                        lyricsList.add(LyricsOverride(
                            songPath = lObj.getString("songPath"),
                            rawLyrics = lObj.getString("rawLyrics")
                        ))
                    }
                    db.songDao().insertLyricsOverrides(lyricsList)
                }

                // 4. Restore Playlists
                if (backup.has("playlists")) {
                    val playlistsArray = backup.getJSONArray("playlists")
                    for (i in 0 until playlistsArray.length()) {
                        val pObj = playlistsArray.getJSONObject(i)
                        val playlist = Playlist(name = pObj.getString("name"))
                        val newPlaylistId = db.playlistDao().createPlaylist(playlist)
                        
                        val tracksArray = pObj.getJSONArray("tracks")
                        val refs = mutableListOf<PlaylistTrackCrossRef>()
                        for (j in 0 until tracksArray.length()) {
                            refs.add(PlaylistTrackCrossRef(newPlaylistId, tracksArray.getString(j)))
                        }
                        db.playlistDao().insertPlaylistTrackCrossRefs(refs)
                    }
                }

                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}
