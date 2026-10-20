package com.music.myusiq.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

object ArtworkUtils {

    private fun getPublicBackupDir(): File? {
        val dirs = listOfNotNull(
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) } catch (_: Exception) { null },
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) } catch (_: Exception) { null },
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) } catch (_: Exception) { null },
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) } catch (_: Exception) { null }
        )
        for (dir in dirs) {
            try {
                val myusiqDir = File(dir, ".myusiq_backup/artwork")
                if (!myusiqDir.exists()) {
                    val legacyDir = File(dir, ".rhythm_backup/artwork")
                    if (legacyDir.exists()) return legacyDir
                    myusiqDir.mkdirs()
                }
                if (myusiqDir.exists()) return myusiqDir
            } catch (_: Exception) {}
        }
        return null
    }

    private fun savePublicMediaStoreImage(context: Context, filename: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && bytes.isNotEmpty()) {
            try {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/.myusiq_backup/artwork")
                }

                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf(filename, "%${Environment.DIRECTORY_PICTURES}/.myusiq_backup%")
                val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                var existingUri: Uri? = null
                resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        existingUri = ContentUris.withAppendedId(queryUri, id)
                    }
                }

                val targetUri = existingUri ?: resolver.insert(queryUri, contentValues)
                if (targetUri != null) {
                    resolver.openOutputStream(targetUri, "rwt")?.use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                }
            } catch (e: Exception) {
                Log.d("ArtworkUtils", "Failed to save public MediaStore image: ${e.message}")
            }
        }
    }

    private fun readPublicMediaStoreImage(context: Context, filename: String): ByteArray? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val projection = arrayOf(MediaStore.MediaColumns._ID)
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
                val selectionArgs = arrayOf(filename)
                val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                resolver.query(queryUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val uri = ContentUris.withAppendedId(queryUri, id)
                        resolver.openInputStream(uri)?.use { input ->
                            return input.readBytes()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("ArtworkUtils", "Failed to read public MediaStore image: ${e.message}")
            }
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

        // 2. Check public MediaStore image store
        val publicMediaBytes = readPublicMediaStoreImage(context, "${normPath.hashCode()}.jpg")
        if (publicMediaBytes != null && publicMediaBytes.isNotEmpty()) {
            try {
                if (!cacheDir.exists()) cacheDir.mkdirs()
                idFile.writeBytes(publicMediaBytes)
                hashFile.writeBytes(publicMediaBytes)
                return idFile
            } catch (_: Exception) {}
        }

        // 3. Extract embedded picture directly from physical audio file if cache was cleared
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
                            savePublicMediaStoreImage(context, "${normPath.hashCode()}.jpg", picture)
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

    fun compressAndOptimizeArtwork(rawBytes: ByteArray, maxDimension: Int = 1000, quality: Int = 85): ByteArray {
        if (rawBytes.isEmpty()) return rawBytes
        return try {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)
            
            var sampleSize = 1
            var w = options.outWidth
            var h = options.outHeight
            while (w > maxDimension || h > maxDimension) {
                sampleSize *= 2
                w /= 2
                h /= 2
            }

            val decodeOptions = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) 
                ?: return rawBytes

            val outStream = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outStream)
            bitmap.recycle()
            val compressed = outStream.toByteArray()
            if (compressed.isNotEmpty() && compressed.size < rawBytes.size) compressed else rawBytes
        } catch (e: Exception) {
            Log.d("ArtworkUtils", "Artwork compression skipped: ${e.message}")
            rawBytes
        }
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
                val rawBytes = input.readBytes()
                if (rawBytes.isNotEmpty()) {
                    val bytes = compressAndOptimizeArtwork(rawBytes)
                    idFile.writeBytes(bytes)
                    hashFile.writeBytes(bytes)
                    try { extBackupFile.writeBytes(bytes) } catch (_: Exception) {}
                    try { publicBackupFile?.writeBytes(bytes) } catch (_: Exception) {}
                    savePublicMediaStoreImage(context, "${normPath.hashCode()}.jpg", bytes)
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
            savePublicMediaStoreImage(context, "${normNewPath.hashCode()}.jpg", bytes)

            Log.d("ArtworkUtils", "Copied artwork cache from $oldPath to $newPath")
        } catch (e: Exception) {
            Log.e("ArtworkUtils", "Error copying artwork cache", e)
        }
    }
}
