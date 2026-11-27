package com.music.myusiq.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.music.myusiq.domain.models.MoodProfile
import com.music.myusiq.domain.models.PlaylistProfile

@Dao
interface MoodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMoodProfile(profile: MoodProfile)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMoodProfiles(profiles: List<MoodProfile>)

    @Query("SELECT * FROM mood_profiles")
    suspend fun getAllMoodProfiles(): List<MoodProfile>

    @Query("SELECT * FROM mood_profiles WHERE trackId = :trackId")
    suspend fun getMoodProfile(trackId: String): MoodProfile?

    @Query("DELETE FROM mood_profiles WHERE trackId = :trackId")
    suspend fun deleteMoodProfile(trackId: String)

    @Query("DELETE FROM mood_profiles")
    suspend fun clearMoodProfiles()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylistProfile(profile: PlaylistProfile)

    @Query("SELECT * FROM playlist_profiles")
    suspend fun getAllPlaylistProfiles(): List<PlaylistProfile>

    @Query("SELECT * FROM playlist_profiles WHERE profileKey = :key")
    suspend fun getPlaylistProfile(key: String): PlaylistProfile?

    @Query("DELETE FROM playlist_profiles WHERE profileKey = :key")
    suspend fun deletePlaylistProfile(key: String)

    @Query("DELETE FROM playlist_profiles")
    suspend fun clearPlaylistProfiles()
}
