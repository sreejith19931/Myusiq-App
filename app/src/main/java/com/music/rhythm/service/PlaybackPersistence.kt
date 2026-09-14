package com.music.rhythm.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import com.music.rhythm.utils.PlatformUtils

val Context.playbackDataStore: DataStore<Preferences> by preferencesDataStore(name = "player_prefs_v4")

class PlaybackPersistence(private val context: Context) {

    companion object {
        val KEY_LAST_SONG_PATH = stringPreferencesKey("last_song_path_v4")
        val KEY_LAST_POSITION = longPreferencesKey("last_position_v4")
        val KEY_SAVED_QUEUE_PATHS = stringPreferencesKey("saved_queue_paths_v4")
    }

    suspend fun saveLastSongPath(songPath: String) {
        val normalizedPath = PlatformUtils.normalizePath(songPath)
        context.playbackDataStore.edit { preferences ->
            preferences[KEY_LAST_SONG_PATH] = normalizedPath
        }
    }

    suspend fun saveLastPosition(position: Long) {
        if (position < 0) return
        context.playbackDataStore.edit { preferences ->
            preferences[KEY_LAST_POSITION] = position
        }
    }

    val lastSongPath: Flow<String?> = context.playbackDataStore.data.map { preferences ->
        preferences[KEY_LAST_SONG_PATH]
    }

    val lastPosition: Flow<Long> = context.playbackDataStore.data.map { preferences ->
        preferences[KEY_LAST_POSITION] ?: 0L
    }

    suspend fun saveCurrentQueue(queuePaths: List<String>) {
        val normalizedPaths = queuePaths.map { PlatformUtils.normalizePath(it) }
        val pathString = normalizedPaths.joinToString(separator = "|")
        context.playbackDataStore.edit { preferences ->
            preferences[KEY_SAVED_QUEUE_PATHS] = pathString
        }
    }

    suspend fun getSavedQueuePaths(): List<String> {
        val prefs = context.playbackDataStore.data.firstOrNull() ?: return emptyList()
        val pathString = prefs[KEY_SAVED_QUEUE_PATHS] ?: return emptyList()
        if (pathString.isEmpty()) return emptyList()
        return pathString.split("|")
    }
}
