package com.music.rhythm.data

interface MusicScanner {
    suspend fun scanAllSongs(): List<RawSong>
    fun getBlacklistedFolderPaths(): Set<String>
    fun getAllowedFolderUris(): Set<String>
    fun toggleFolderBlacklist(path: String, isIncluded: Boolean)
    fun addAllowedFolderUri(path: String)
}

data class RawSong(
    val id: Long,
    val title: String,
    val artist: String,
    val path: String,
    val duration: Int,
    val albumId: Long,
    val album: String,
    val genre: String,
    val dateAdded: Long,
    val size: Long,
)
