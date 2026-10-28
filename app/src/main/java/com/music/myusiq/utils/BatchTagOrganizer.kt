package com.music.myusiq.utils

import android.content.Context
import android.util.Log
import com.music.myusiq.domain.models.Song
import java.io.File

/**
 * Utility for batch tag editing and file storage reorganization using template strings.
 *
 * Example templates:
 * - "{Artist}/{Album}/{Track} - {Title}.ext"
 * - "{Artist} - {Title}.ext"
 */
object BatchTagOrganizer {

    const val DEFAULT_TEMPLATE = "{Artist}/{Album}/{Track} - {Title}.ext"

    /**
     * Sanitizes a string for use in file and directory path segments by replacing invalid characters.
     */
    fun sanitizePathSegment(segment: String, fallback: String = "Unknown"): String {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return fallback
        // Replace invalid file system characters: \ / : * ? " < > |
        val cleaned = trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifEmpty { fallback }
    }

    /**
     * Formats a target relative path string using a template pattern and track metadata.
     */
    fun formatPathFromTemplate(
        template: String,
        title: String,
        artist: String,
        album: String,
        trackNumber: Int,
        extension: String
    ): String {
        val safeTitle = sanitizePathSegment(title, "Unknown Title")
        val safeArtist = sanitizePathSegment(artist, "Unknown Artist")
        val safeAlbum = sanitizePathSegment(album, "Unknown Album")
        val safeTrack = if (trackNumber > 0) "%02d".format(trackNumber) else "01"
        val safeExt = extension.lowercase().removePrefix(".")

        var result = template
            .replace("{Title}", safeTitle)
            .replace("{Artist}", safeArtist)
            .replace("{Album}", safeAlbum)
            .replace("{Track}", safeTrack)

        if (result.endsWith(".ext", ignoreCase = true)) {
            result = result.substring(0, result.length - 4) + "." + safeExt
        } else if (!result.endsWith(".$safeExt", ignoreCase = true)) {
            result += ".$safeExt"
        }

        return result
    }

    /**
     * Reorganizes a track's audio file on storage according to a template.
     * Returns the newly moved File if successful, or null if failed/unchanged.
     */
    fun reorganizeFile(
        context: Context,
        song: Song,
        template: String = DEFAULT_TEMPLATE,
        baseDirectory: File? = null
    ): File? {
        try {
            val sourceFile = File(song.path)
            if (!sourceFile.exists()) return null

            val ext = sourceFile.extension.ifBlank { "mp3" }
            val parentDir = baseDirectory ?: sourceFile.parentFile ?: return null

            val relativePath = formatPathFromTemplate(
                template = template,
                title = song.title,
                artist = song.artist,
                album = song.album,
                trackNumber = 1,
                extension = ext
            )

            val targetFile = File(parentDir, relativePath)
            if (targetFile.absolutePath == sourceFile.absolutePath) {
                return sourceFile
            }

            targetFile.parentFile?.let { dir ->
                if (!dir.exists()) {
                    dir.mkdirs()
                }
            }

            val moved = sourceFile.renameTo(targetFile)
            if (moved) {
                Log.d("BatchTagOrganizer", "Successfully reorganized track ${song.title} to ${targetFile.absolutePath}")
                return targetFile
            }
        } catch (e: Exception) {
            Log.e("BatchTagOrganizer", "Error reorganizing file for ${song.title}", e)
        }
        return null
    }
}
