package com.music.myusiq.utils

import android.content.Context
import androidx.core.content.edit

class FolderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("music_player_folders", Context.MODE_PRIVATE)
    companion object {
        private const val KEY_ALLOWED_URIS = "scannable_folder_uris"
        private const val KEY_BLACKLISTED_PATHS = "blacklisted_folder_paths"
    }
    fun getAllowedFolderUris(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWED_URIS, emptySet()) ?: emptySet()
    }
    fun addAllowedFolderUri(uriString: String) {
        val current = getAllowedFolderUris().toMutableSet()
        current.add(uriString)
        prefs.edit { putStringSet(KEY_ALLOWED_URIS, current) }
    }
    fun removeAllowedFolderUri(uriString: String) {
        val current = getAllowedFolderUris().toMutableSet()
        current.remove(uriString)
        prefs.edit { putStringSet(KEY_ALLOWED_URIS, current) }
    }
    fun getBlacklistedFolderPaths(): Set<String> {
        return prefs.getStringSet(KEY_BLACKLISTED_PATHS, emptySet()) ?: emptySet()
    }
    fun toggleFolderBlacklist(absolutePath: String, isIncluded: Boolean) {
        val current = getBlacklistedFolderPaths().toMutableSet()
        if (isIncluded) {
            current.remove(absolutePath) // If included, remove from blacklist
        } else {
            current.add(absolutePath)    // If excluded, add to blacklist
        }
        prefs.edit { putStringSet(KEY_BLACKLISTED_PATHS, current) }
    }
}