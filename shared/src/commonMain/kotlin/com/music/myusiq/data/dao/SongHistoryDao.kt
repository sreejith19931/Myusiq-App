package com.music.myusiq.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.music.myusiq.domain.models.*

@Dao
interface SongHistoryDao {
    @Insert
    suspend fun insertPlayEvent(history: SongHistory)
    @Query("SELECT songId, title, artist, COUNT(*) as playCount FROM song_history GROUP BY songId ORDER BY playCount DESC LIMIT :limit")
    fun getTopTracks(limit: Int): List<TopTrack>
    @Query("SELECT artist, COUNT(*) as playCount FROM song_history GROUP BY artist ORDER BY playCount DESC LIMIT :limit")
    suspend fun getTopArtists(limit: Int): List<TopArtist>
    @Query("SELECT SUM(durationMs) FROM song_history WHERE timestamp >= :startTime")
    fun getTotalListeningTimeMs(startTime: Long): Long?
    @Query("SELECT songId, title, artist, COUNT(*) as playCount FROM song_history WHERE timestamp >= :startTime AND timestamp <= :endTime GROUP BY songId ORDER BY playCount DESC LIMIT 10")
    fun getTopTracksForMonth(startTime: Long, endTime: Long): List<TopTrack>

    @Query("SELECT songId, title, artist, COUNT(*) as playCount FROM song_history WHERE timestamp >= :startTime AND timestamp <= :endTime GROUP BY songId ORDER BY playCount DESC LIMIT :limit")
    fun getTopTracksInRange(startTime: Long, endTime: Long, limit: Int): List<TopTrack>

    @Query("SELECT artist, COUNT(*) as playCount FROM song_history GROUP BY artist ORDER BY playCount DESC LIMIT 5")
    fun getTopArtistsForSmartPlaylist(): List<TopArtist>

    @Query("SELECT SUM(durationMs) FROM song_history")
    fun getTotalListeningTimeAllTime(): Long?

    @Query("SELECT COUNT(*) FROM song_history")
    fun getTotalPlaysAllTime(): Int

    @Query("SELECT artist, COUNT(*) as playCount FROM song_history GROUP BY artist ORDER BY playCount DESC LIMIT 1")
    fun getMostPlayedArtist(): TopArtist?

    @Query("SELECT songId, title, artist, COUNT(*) as playCount FROM song_history GROUP BY songId ORDER BY playCount DESC LIMIT 1")
    fun getMostPlayedTrack(): TopTrack?

    @Query("SELECT * FROM song_history")
    suspend fun getAllHistorySync(): List<SongHistory>

    @Query("SELECT songId, MAX(timestamp) as lastPlayed FROM song_history GROUP BY songId")
    suspend fun getLastPlayedTimestamps(): List<LastPlayedInfo>

    @Query("SELECT songId, COUNT(*) as playCount FROM song_history GROUP BY songId")
    suspend fun getAllPlayCounts(): List<PlayCountInfo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: List<SongHistory>)

    @Query("SELECT songId, COUNT(*) as playCount FROM song_history WHERE strftime('%H', timestamp / 1000, 'unixepoch', 'localtime') BETWEEN :startHour AND :endHour GROUP BY songId ORDER BY playCount DESC LIMIT :limit")
    suspend fun getSongIdsByTimeRange(startHour: String, endHour: String, limit: Int): List<PlayCountInfo>

    @Query("DELETE FROM song_history")
    suspend fun deleteAllHistory()
}
