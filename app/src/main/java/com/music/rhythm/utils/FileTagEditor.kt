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
     * Updates ID3 tags and calculates the new physical file path.
     * Returns the new desired absolute path of the file.
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

                if (newArtworkUri != null) {
                    try {
                        context.contentResolver.openInputStream(newArtworkUri)?.use { inputStream ->
                            val bytes = inputStream.readBytes()
                            if (bytes.isNotEmpty()) {
                                val artwork = ArtworkFactory.getNew()
                                artwork.binaryData = bytes
                                tag.deleteArtworkField()
                                tag.setField(artwork)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FileTagEditor", "Error reading artwork URI: $newArtworkUri", e)
                    }
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
