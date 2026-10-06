package com.music.rhythm.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.io.FileNotFoundException

object FileTagEditor {

    /**
     * Embeds ID3 metadata and album artwork directly into an audio file (MP3, FLAC, M4A, etc.) on storage using jaudiotagger.
     *
     * @param file Target audio file on storage
     * @param title Track title
     * @param artist Artist name
     * @param album Album title
     * @param year Release year
     * @param genre Track genre
     * @param artworkBytes Optional raw byte array of album artwork image (JPEG/PNG)
     * @param artworkFile Optional artwork file
     * @return true if successfully embedded and saved, false otherwise.
     */
    fun embedId3TagsAndArtwork(
        file: File,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        year: String? = null,
        genre: String? = null,
        artworkBytes: ByteArray? = null,
        artworkFile: File? = null,
    ): Boolean {
        if (!file.exists()) {
            Log.e("FileTagEditor", "File does not exist: ${file.absolutePath}")
            return false
        }

        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag ?: audioFile.createDefaultTag()

            title?.let { tag.setField(FieldKey.TITLE, it) }
            artist?.let { tag.setField(FieldKey.ARTIST, it) }
            album?.let { tag.setField(FieldKey.ALBUM, it) }
            year?.takeIf { it.isNotBlank() && it != "Unknown" }?.let { tag.setField(FieldKey.YEAR, it) }
            genre?.takeIf { it.isNotBlank() && it != "Unknown" }?.let { tag.setField(FieldKey.GENRE, it) }

            val bytesToEmbed = artworkBytes ?: artworkFile?.takeIf { it.exists() }?.readBytes()
            if (bytesToEmbed != null && bytesToEmbed.isNotEmpty()) {
                val artwork = ArtworkFactory.getNew()
                artwork.binaryData = bytesToEmbed
                tag.deleteArtworkField()
                tag.setField(artwork)
            }

            audioFile.commit()
            Log.d("FileTagEditor", "ID3 tags and artwork successfully embedded into ${file.name}")
            true
        } catch (e: Exception) {
            Log.e("FileTagEditor", "Error embedding ID3 tags into ${file.absolutePath}", e)
            false
        }
    }

    /**
     * Updates ID3 tags and calculates the new physical file path via MediaStore Uri / direct file access.
     * Returns the absolute path of the file.
     */
    fun updateFileTags(
        context: Context,
        filePath: String,
        trackUri: Uri,
        title: String,
        artist: String,
        album: String,
        year: String,
        genre: String,
        newArtworkUri: Uri? = null,
    ): String {
        try {
            val originalFile = File(filePath)

            // Attempt direct embedding if file is writable
            var artworkBytes: ByteArray? = null
            if (newArtworkUri != null) {
                try {
                    context.contentResolver.openInputStream(newArtworkUri)?.use { inputStream ->
                        artworkBytes = inputStream.readBytes()
                    }
                } catch (e: Exception) {
                    Log.e("FileTagEditor", "Error reading artwork URI: $newArtworkUri", e)
                }
            }

            if (originalFile.exists() && originalFile.canWrite()) {
                val directSuccess = embedId3TagsAndArtwork(
                    file = originalFile,
                    title = title,
                    artist = artist,
                    album = album,
                    year = year,
                    genre = genre,
                    artworkBytes = artworkBytes
                )
                if (directSuccess) return filePath
            }

            // Fallback to ContentResolver copy loop for MediaStore scoped storage URIs
            val extension = originalFile.extension
            val cacheFile = File(context.cacheDir, "tag_edit_${System.currentTimeMillis()}.$extension")

            try {
                context.contentResolver.openInputStream(trackUri)?.use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    Log.e("FileTagEditor", "Failed to open input stream for URI: $trackUri")
                    return filePath
                }

                if (!cacheFile.exists() || (cacheFile.length() == 0L)) {
                    Log.e("FileTagEditor", "Cache file is empty or does not exist after copy")
                    return filePath
                }

                val audioFile = try { 
                    AudioFileIO.read(cacheFile) 
                } catch (e: Exception) { 
                    Log.e("FileTagEditor", "JAudioTagger failed to read cache file: ${e.message}", e)
                    return filePath 
                }
                val tag = audioFile.tag ?: audioFile.createDefaultTag()

                tag.setField(FieldKey.TITLE, title)
                tag.setField(FieldKey.ARTIST, artist)
                tag.setField(FieldKey.ALBUM, album)
                if (year.isNotBlank() && year != "Unknown") tag.setField(FieldKey.YEAR, year)
                if (genre.isNotBlank() && genre != "Unknown") tag.setField(FieldKey.GENRE, genre)

                if (artworkBytes != null && artworkBytes.isNotEmpty()) {
                    val artwork = ArtworkFactory.getNew()
                    artwork.binaryData = artworkBytes
                    tag.deleteArtworkField()
                    tag.setField(artwork)
                }

                audioFile.commit()
                Log.d("FileTagEditor", "Tags committed to cache file successfully")

                context.contentResolver.openOutputStream(trackUri, "rwt")?.use { output ->
                    cacheFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: run {
                    Log.e("FileTagEditor", "Failed to open output stream for URI: $trackUri")
                    return filePath
                }
                Log.d("FileTagEditor", "Cache file copied back to track URI")
                
                return filePath
            } catch (e: FileNotFoundException) {
                Log.e("FileTagEditor", "File not found or access denied: ${e.message}")
                return filePath
            } catch (e: Exception) {
                Log.e("FileTagEditor", "Error in inner tag edit loop: ${e.message}", e)
                return filePath
            } finally {
                if (cacheFile.exists()) cacheFile.delete()
            }
        } catch (e: Exception) {
            Log.e("FileTagEditor", "Error in updateFileTags", e)
            return filePath
        }
    }
}
