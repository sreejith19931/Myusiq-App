package com.music.myusiq.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File

object ArtworkUtils {

    fun getArtworkFile(context: Context, songId: Long, songPath: String): File? {
        val cacheDir = File(context.filesDir, "artwork_cache")
        val idFile = File(cacheDir, "$songId.jpg")
        if (idFile.exists() && idFile.length() > 0) return idFile

        val normPath = PlatformUtils.normalizePath(songPath)
        val hashFile = File(cacheDir, "${normPath.hashCode()}.jpg")
        if (hashFile.exists() && hashFile.length() > 0) return hashFile

        // Extract embedded picture directly from physical audio file if cache was cleared
        if (normPath.isNotBlank()) {
            val audioFile = File(normPath)
            if (audioFile.exists() && audioFile.length() > 0) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(normPath)
                    val picture = retriever.embeddedPicture
                    retriever.release()
                    if (picture != null && picture.isNotEmpty()) {
                        if (!cacheDir.exists()) cacheDir.mkdirs()
                        idFile.writeBytes(picture)
                        hashFile.writeBytes(picture)
                        return idFile
                    }
                } catch (e: Exception) {
                    Log.d("ArtworkUtils", "Could not extract embedded picture from $normPath: ${e.message}")
                }
            }
        }

        return null
    }

    fun saveArtworkCache(context: Context, songId: Long, songPath: String, artUri: Uri) {
        try {
            val cacheDir = File(context.filesDir, "artwork_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val idFile = File(cacheDir, "$songId.jpg")
            val normPath = PlatformUtils.normalizePath(songPath)
            val hashFile = File(cacheDir, "${normPath.hashCode()}.jpg")

            context.contentResolver.openInputStream(artUri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.isNotEmpty()) {
                    idFile.writeBytes(bytes)
                    hashFile.writeBytes(bytes)
                }
            }
            Log.d("ArtworkUtils", "Saved artwork cache for song $songId")
        } catch (e: Exception) {
            Log.e("ArtworkUtils", "Error saving artwork cache", e)
        }
    }
}
