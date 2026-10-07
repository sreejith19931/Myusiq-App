package com.music.myusiq.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.music.myusiq.domain.models.LyricsOverride
import com.music.myusiq.domain.models.SongOverride

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOverride(override: SongOverride)

    @Query("SELECT * FROM song_overrides")
    suspend fun getAllOverrides(): List<SongOverride>

    @Query("DELETE FROM song_overrides WHERE path = :path")
    suspend fun deleteOverride(path: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLyrics(lyrics: LyricsOverride)

    @Query("SELECT * FROM lyrics_overrides WHERE songPath = :path")
    suspend fun getLyricsForPath(path: String): LyricsOverride?

    @Query("UPDATE lyrics_overrides SET songPath = :newPath WHERE songPath = :oldPath")
    suspend fun updateLyricsPath(oldPath: String, newPath: String)

    @Query("DELETE FROM lyrics_overrides WHERE songPath = :path")
    suspend fun deleteLyrics(path: String)

    @Query("DELETE FROM lyrics_overrides")
    suspend fun deleteAllLyricsOverrides()

    @Query("SELECT * FROM lyrics_overrides")
    suspend fun getAllLyricsOverridesSync(): List<LyricsOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongOverrides(overrides: List<SongOverride>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyricsOverrides(lyrics: List<LyricsOverride>)
}
