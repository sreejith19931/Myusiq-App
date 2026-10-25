package com.music.myusiq.utils

import android.content.Context
import androidx.core.content.edit

class FolderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("music_player_folders", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALLOWED_URIS = "scannable_folder_uris"
        private const val KEY_BLACKLISTED_PATHS = "blacklisted_folder_paths"
        private const val KEY_SCAN_MODE = "folder_scan_mode"
        private const val KEY_EXCLUDE_VOICE_NOTES = "exclude_voice_notes"
        private const val KEY_EXCLUDE_MESSAGING = "exclude_messaging"
        private const val KEY_EXCLUDE_RINGTONES = "exclude_ringtones"
        private const val KEY_SETUP_FOLDERS_INITIALIZED = "setup_folders_initialized"

        const val SCAN_MODE_ALL = "SCAN_ALL"
        const val SCAN_MODE_WHITELIST = "WHITELIST_ONLY"
    }

    fun isSetupFoldersInitialized(): Boolean {
        return prefs.getBoolean(KEY_SETUP_FOLDERS_INITIALIZED, false)
    }

    fun setSetupFoldersInitialized(initialized: Boolean) {
        prefs.edit { putBoolean(KEY_SETUP_FOLDERS_INITIALIZED, initialized) }
    }

    fun getScanMode(): String {
        return prefs.getString(KEY_SCAN_MODE, SCAN_MODE_ALL) ?: SCAN_MODE_ALL
    }

    fun setScanMode(mode: String) {
        prefs.edit { putString(KEY_SCAN_MODE, mode) }
    }

    fun isExcludeVoiceNotesEnabled(): Boolean {
        return prefs.getBoolean(KEY_EXCLUDE_VOICE_NOTES, false)
    }

    fun setExcludeVoiceNotesEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_EXCLUDE_VOICE_NOTES, enabled) }
    }

    fun isExcludeMessagingEnabled(): Boolean {
        return prefs.getBoolean(KEY_EXCLUDE_MESSAGING, false)
    }

    fun setExcludeMessagingEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_EXCLUDE_MESSAGING, enabled) }
    }

    fun isExcludeRingtonesEnabled(): Boolean {
        return prefs.getBoolean(KEY_EXCLUDE_RINGTONES, false)
    }

    fun setExcludeRingtonesEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_EXCLUDE_RINGTONES, enabled) }
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

    fun clearAllowedFolderUris() {
        prefs.edit { remove(KEY_ALLOWED_URIS) }
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
