package com.music.myusiq.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.io.FileNotFoundException

object FileTagEditor {

    fun compressArtworkForEmbedding(
        bytes: ByteArray,
        maxDimension: Int = 1000,
        quality: Int = 85
    ): ByteArray {
        if (bytes.isEmpty()) return bytes
        return try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            val origWidth = options.outWidth
            val origHeight = options.outHeight

            if (origWidth <= 0 || origHeight <= 0) return bytes

            if (origWidth <= maxDimension && origHeight <= maxDimension && bytes.size < 300 * 1024) {
                return bytes
            }

            var sampleSize = 1
            var w = origWidth
            var h = origHeight
            while (w > maxDimension * 2 || h > maxDimension * 2) {
                sampleSize *= 2
                w /= 2
                h /= 2
            }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?: return bytes

            val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                val targetW: Int
                val targetH: Int
                if (bitmap.width >= bitmap.height) {
                    targetW = maxDimension
                    targetH = (maxDimension / aspect).toInt().coerceAtLeast(1)
                } else {
                    targetH = maxDimension
                    targetW = (maxDimension * aspect).toInt().coerceAtLeast(1)
                }
                val resized = android.graphics.Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                if (resized != bitmap) {
                    bitmap.recycle()
                }
                resized
            } else {
                bitmap
            }

            val outputStream = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)
            scaledBitmap.recycle()

            val compressedBytes = outputStream.toByteArray()
            if (compressedBytes.isNotEmpty()) compressedBytes else bytes
        } catch (e: Exception) {
            Log.e("FileTagEditor", "Failed to compress artwork bytes: ${e.message}")
            bytes
        }
    }

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
            val tag = audioFile.tag ?: audioFile.createDefaultTag().also { audioFile.tag = it }

            title?.let { tag.setField(FieldKey.TITLE, it) }
            artist?.let { tag.setField(FieldKey.ARTIST, it) }
            album?.let { tag.setField(FieldKey.ALBUM, it) }
            year?.takeIf { it.isNotBlank() && it != "Unknown" }?.let { tag.setField(FieldKey.YEAR, it) }
            genre?.takeIf { it.isNotBlank() && it != "Unknown" }?.let { tag.setField(FieldKey.GENRE, it) }

            val rawBytes = artworkBytes ?: artworkFile?.takeIf { it.exists() }?.readBytes()
            val bytesToEmbed = if (rawBytes != null && rawBytes.isNotEmpty()) {
                compressArtworkForEmbedding(rawBytes)
            } else null

            if (bytesToEmbed != null && bytesToEmbed.isNotEmpty()) {
                val artwork = ArtworkFactory.getNew()
                artwork.binaryData = bytesToEmbed
                tag.deleteArtworkField()
                tag.setField(artwork)
            }

            audioFile.tag = tag
            audioFile.commit()
            Log.d("FileTagEditor", "ID3 tags and artwork successfully embedded into ${file.name}")
            true
        } catch (e: Exception) {
            Log.e("FileTagEditor", "Error embedding ID3 tags into ${file.absolutePath}", e)
            false
        }
    }

    /**
     * Embeds lyrics text directly into the ID3/FLAC tag of an audio file using jaudiotagger.
     */
    fun embedLyrics(file: File, lyrics: String): Boolean {
        if (!file.exists()) return false
        return try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag ?: audioFile.createDefaultTag().also { audioFile.tag = it }
            if (lyrics.isNotBlank()) {
                tag.setField(FieldKey.LYRICS, lyrics)
            } else {
                tag.deleteField(FieldKey.LYRICS)
            }
            audioFile.tag = tag
            audioFile.commit()
            Log.d("FileTagEditor", "Lyrics embedded successfully into ${file.name}")
            true
        } catch (e: Exception) {
            Log.e("FileTagEditor", "Error embedding lyrics into ${file.absolutePath}", e)
            false
        }
    }

    /**
     * Extracts embedded lyrics from an audio file's ID3/FLAC tag using MediaMetadataRetriever or jaudiotagger.
     */
    fun extractLyrics(file: File): String? {
        if (!file.exists()) return null

        // 1. Try JAudioTagger
        try {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tag
            val lyrics = tag?.getFirst(FieldKey.LYRICS)
            if (!lyrics.isNullOrBlank()) return lyrics
        } catch (_: Exception) {}

        // 2. Try MediaMetadataRetriever with key 1000 (METADATA_KEY_LYRICS)
        try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val lyrics = retriever.extractMetadata(1000)
            retriever.release()
            if (!lyrics.isNullOrBlank()) return lyrics
        } catch (_: Exception) {}

        return null
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
                    ArtworkUtils.openUriInputStream(context, newArtworkUri)?.use { inputStream ->
                        val rawBytes = inputStream.readBytes()
                        artworkBytes = compressArtworkForEmbedding(rawBytes)
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

            // Attempt write using ParcelFileDescriptor via proc fd
            try {
                context.contentResolver.openFileDescriptor(trackUri, "rw")?.use { pfd ->
                    val fdFile = File("/proc/self/fd/${pfd.fd}")
                    val fdSuccess = embedId3TagsAndArtwork(
                        file = fdFile,
                        title = title,
                        artist = artist,
                        album = album,
                        year = year,
                        genre = genre,
                        artworkBytes = artworkBytes
                    )
                    if (fdSuccess) {
                        Log.d("FileTagEditor", "Proc fd tag embedding succeeded for $filePath")
                    }
                }
            } catch (e: Exception) {
                Log.d("FileTagEditor", "Proc fd embed attempt failed: ${e.message}")
            }

            // Fallback to ContentResolver copy loop for MediaStore scoped storage URIs
            val extension = if (originalFile.extension.isNotBlank()) originalFile.extension else "mp3"
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
                val tag = audioFile.tag ?: audioFile.createDefaultTag().also { audioFile.tag = it }

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

                audioFile.tag = tag
                audioFile.commit()
                Log.d("FileTagEditor", "Tags committed to cache file successfully")

                // 1. Direct binary copy into ParcelFileDescriptor descriptor for guaranteed disk write
                var writeSuccess = false
                val modes = arrayOf("rwt", "wt", "w", "rw")
                for (mode in modes) {
                    try {
                        context.contentResolver.openFileDescriptor(trackUri, mode)?.use { pfd ->
                            java.io.FileOutputStream(pfd.fileDescriptor).use { output ->
                                cacheFile.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                                output.flush()
                            }
                            writeSuccess = true
                            Log.d("FileTagEditor", "Flushed edited tags directly to PFD file descriptor mode '$mode' for $trackUri")
                        }
                        if (writeSuccess) break
                    } catch (e: Exception) {
                        Log.d("FileTagEditor", "PFD write mode '$mode' failed: ${e.message}")
                    }
                }

                // 2. Try ContentResolver output stream with supported modes if PFD write was not used
                if (!writeSuccess) {
                    for (mode in modes) {
                        try {
                            context.contentResolver.openOutputStream(trackUri, mode)?.use { output ->
                                cacheFile.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                                output.flush()
                            }
                            writeSuccess = true
                            Log.d("FileTagEditor", "Copied edited tags via ContentResolver mode '$mode'")
                            break
                        } catch (e: Exception) {
                            Log.d("FileTagEditor", "Mode '$mode' output stream failed: ${e.message}")
                        }
                    }
                }

                // 3. Also try direct overwrite on originalFile if present
                if (originalFile.exists()) {
                    try {
                        java.io.FileOutputStream(originalFile).use { output ->
                            cacheFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                            output.flush()
                        }
                        Log.d("FileTagEditor", "Directly overwrote original file on disk: ${originalFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.d("FileTagEditor", "Direct originalFile file stream overwrite failed: ${e.message}")
                    }
                }

                if (writeSuccess) {
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(filePath), null, null)
                }

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