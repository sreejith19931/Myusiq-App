package com.music.myusiq.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File

object ArtworkUtils {

    private fun getPublicBackupDir(): File? {
        val dirs = listOfNotNull(
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) } catch (_: Exception) { null },
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) } catch (_: Exception) { null },
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) } catch (_: Exception) { null }
        )
        for (dir in dirs) {
            try {
                val rhythmDir = File(dir, ".rhythm_backup/artwork")
                if (!rhythmDir.exists()) rhythmDir.mkdirs()
                if (rhythmDir.exists()) return rhythmDir
            } catch (_: Exception) {}
        }
        return null
    }

    fun getArtworkFile(context: Context, songId: Long, songPath: String): File? {
        val cacheDir = File(context.filesDir, "artwork_cache")
        val idFile = File(cacheDir, "$songId.jpg")
        if (idFile.exists() && idFile.length() > 0) return idFile

        val normPath = PlatformUtils.normalizePath(songPath)
        val hashFile = File(cacheDir, "${normPath.hashCode()}.jpg")
        if (hashFile.exists() && hashFile.length() > 0) return hashFile

        // 1. Check persistent external/public artwork backup directories
        val extBackupDir = File(context.getExternalFilesDir(null), "artwork_backup")
        val publicBackupDir = getPublicBackupDir()

        val backupFiles = listOf(
            File(extBackupDir, "${normPath.hashCode()}.jpg"),
            if (publicBackupDir != null) File(publicBackupDir, "${normPath.hashCode()}.jpg") else null
        )

        for (backupFile in backupFiles) {
            if (backupFile != null && backupFile.exists() && backupFile.length() > 0) {
                try {
                    if (!cacheDir.exists()) cacheDir.mkdirs()
                    val bytes = backupFile.readBytes()
                    idFile.writeBytes(bytes)
                    hashFile.writeBytes(bytes)
                    return idFile
                } catch (_: Exception) {}
            }
        }

        // 2. Extract embedded picture directly from physical audio file if cache was cleared
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

                        // Save backup copy in external and public dirs
                        try {
                            if (!extBackupDir.exists()) extBackupDir.mkdirs()
                            File(extBackupDir, "${normPath.hashCode()}.jpg").writeBytes(picture)
                            if (publicBackupDir != null && !publicBackupDir.exists()) publicBackupDir.mkdirs()
                            publicBackupDir?.let { File(it, "${normPath.hashCode()}.jpg").writeBytes(picture) }
                        } catch (_: Exception) {}

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

            val extBackupDir = File(context.getExternalFilesDir(null), "artwork_backup")
            if (!extBackupDir.exists()) extBackupDir.mkdirs()

            val publicBackupDir = getPublicBackupDir()

            val idFile = File(cacheDir, "$songId.jpg")
            val normPath = PlatformUtils.normalizePath(songPath)
            val hashFile = File(cacheDir, "${normPath.hashCode()}.jpg")
            val extBackupFile = File(extBackupDir, "${normPath.hashCode()}.jpg")
            val publicBackupFile = publicBackupDir?.let { File(it, "${normPath.hashCode()}.jpg") }

            context.contentResolver.openInputStream(artUri)?.use { input ->
                val bytes = input.readBytes()
                if (bytes.isNotEmpty()) {
                    idFile.writeBytes(bytes)
                    hashFile.writeBytes(bytes)
                    try { extBackupFile.writeBytes(bytes) } catch (_: Exception) {}
                    try { publicBackupFile?.writeBytes(bytes) } catch (_: Exception) {}
                }
            }
            Log.d("ArtworkUtils", "Saved artwork cache and persistent backups for song $songId")
        } catch (e: Exception) {
            Log.e("ArtworkUtils", "Error saving artwork cache", e)
        }
    }

    fun copyArtworkCache(context: Context, oldSongId: Long, oldPath: String, newSongId: Long, newPath: String) {
        try {
            val existingArt = getArtworkFile(context, oldSongId, oldPath) ?: return
            if (!existingArt.exists() || existingArt.length() == 0L) return

            val cacheDir = File(context.filesDir, "artwork_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val extBackupDir = File(context.getExternalFilesDir(null), "artwork_backup")
            if (!extBackupDir.exists()) extBackupDir.mkdirs()

            val publicBackupDir = getPublicBackupDir()

            val bytes = existingArt.readBytes()
            if (bytes.isEmpty()) return

            val normNewPath = PlatformUtils.normalizePath(newPath)
            val newIdFile = File(cacheDir, "$newSongId.jpg")
            val newHashFile = File(cacheDir, "${normNewPath.hashCode()}.jpg")
            val newExtBackupFile = File(extBackupDir, "${normNewPath.hashCode()}.jpg")
            val newPublicBackupFile = publicBackupDir?.let { File(it, "${normNewPath.hashCode()}.jpg") }

            newIdFile.writeBytes(bytes)
            newHashFile.writeBytes(bytes)
            try { newExtBackupFile.writeBytes(bytes) } catch (_: Exception) {}
            try { newPublicBackupFile?.writeBytes(bytes) } catch (_: Exception) {}

            Log.d("ArtworkUtils", "Copied artwork cache from $oldPath to $newPath")
        } catch (e: Exception) {
            Log.e("ArtworkUtils", "Error copying artwork cache", e)
        }
    }
}