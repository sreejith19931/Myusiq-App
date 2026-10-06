package com.music.rhythm.utils

import android.content.Context
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
