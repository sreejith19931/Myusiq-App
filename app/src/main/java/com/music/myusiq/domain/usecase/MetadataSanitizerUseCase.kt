package com.music.myusiq.domain.usecase

import com.music.myusiq.domain.models.Song
import com.music.myusiq.utils.YouTubeTitleCleaner

class MetadataSanitizerUseCase {

    /**
     * Sanitizes a given song object, cleaning title, artist, and album of web clutter,
     * track numbers, file extensions, and generic tags before searching for metadata.
     *
     * @param song The original song object containing raw metadata.
     * @param year The year of the track (null safe).
     * @param path The file path to the track (null safe).
     * @param duration The duration of the track in seconds (null safe).
     * @param albumId The ID of the album (null safe).
     * @return A new Song object with sanitized fields.
     */
    fun sanitize(song: Song, year: String?, path: String?, duration: String?, albumId: Long?): Song {
        val safePath = path ?: song.path

        // Check if raw artist is generic/unknown, but raw title contains "Artist - Song Title"
        val rawArtistIsGeneric = YouTubeTitleCleaner.isGenericArtistName(song.artist)
        val (splitArtist, splitTitle) = if (rawArtistIsGeneric && song.title.contains("-")) {
            YouTubeTitleCleaner.cleanAndSplit(song.title)
        } else {
            Pair("", "")
        }

        val rawTitle = if (splitTitle.isNotBlank()) splitTitle else song.title
        var cleanedTitle = YouTubeTitleCleaner.cleanTitle(rawTitle)
        if (cleanedTitle.isBlank() && safePath.isNotBlank()) {
            cleanedTitle = YouTubeTitleCleaner.cleanFilename(safePath)
        }

        val rawArtist = if (!splitArtist.equals("Unknown Artist", ignoreCase = true) && splitArtist.isNotBlank()) {
            splitArtist
        } else {
            song.artist
        }
        val cleanedArtist = YouTubeTitleCleaner.cleanArtist(rawArtist)
        val cleanedAlbum = YouTubeTitleCleaner.cleanAlbum(song.album)

        val safeYear = year?.toIntOrNull() ?: 0
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
}