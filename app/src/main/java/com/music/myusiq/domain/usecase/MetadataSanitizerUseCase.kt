package com.music.myusiq.domain.usecase

import com.music.myusiq.domain.models.Song

class MetadataSanitizerUseCase {

    /**
     * Sanitizes a given song object, removing identified source names from fields and aligning with
     * required caller parameters.
     *
     * @param song The original song object containing raw metadata.
     * @param year The year of the track (null safe).
     * @param path The file path to the track (null safe).
     * @param duration The duration of the track in seconds (null safe).
     * @param albumId The ID of the album (null safe).
     * @return A new Song object with sanitized fields.
     */
    fun sanitize(song: Song, year: String?, path: String?, duration: String?, albumId: Long?): Song {
        val cleanedTitle = cleanTitle(song.title)
        val cleanedArtist = cleanArtist(song.artist)
        val cleanedAlbum = cleanAlbum(song.album)

        // Handle safe parameter values, converting null types to appropriate types
        // Assuming year and duration should be Int based on compiler error
        val safeYear = year?.toIntOrNull() ?: 0
        val safePath = path ?: ""
        val safeDuration = duration?.toIntOrNull() ?: 0
        val safeAlbumId = albumId ?: 0L

        return Song(
            id = song.id,
            title = cleanedTitle,
            artist = cleanedArtist,
            album = cleanedAlbum,
            year = safeYear.toString(),
            genre = song.genre,
            path = safePath,
            duration = safeDuration,
            albumId = safeAlbumId,
        )
    }

    private fun cleanTitle(title: String?): String {
        // Remove common junk characters from titles
        return (title ?: "").replace(Regex("[^a-zA-Z0-9\\s\\-_.,()?'\":]"), "").trim()
    }

    private fun cleanArtist(artist: String?): String {
        // Remove common junk characters from artists
        return (artist ?: "").replace(Regex("[^a-zA-Z0-9\\s\\-_.,()?'\"]"), "")
                   .trim()
    }

    private fun cleanAlbum(album: String?): String {
        // Remove common junk characters from album names
        return (album ?: "").replace(Regex("[^a-zA-Z0-9\\s\\-_.,()?'\"]"), "")
                   .trim()
    }
}