package com.music.myusiq.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.music.myusiq.data.MusicRepository
import com.music.myusiq.domain.models.SongOverride
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MetadataBackupUtils {

    private fun getPublicBackupFile(): File? {
        val dirs = listOfNotNull(
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) } catch (_: Exception) { null },
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) } catch (_: Exception) { null },
            try { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) } catch (_: Exception) { null }
        )
        for (dir in dirs) {
            try {
                val rhythmDir = File(dir, ".rhythm_backup")
                if (!rhythmDir.exists()) rhythmDir.mkdirs()
                val file = File(rhythmDir, "metadata_overrides_backup.json")
                if (file.exists() || rhythmDir.canWrite()) return file
            } catch (_: Exception) {}
        }
        return null
    }

    private fun getAppBackupFile(context: Context): File {
        return File(context.getExternalFilesDir(null), "metadata_overrides_backup.json")
    }

    fun saveOverrideToBackup(context: Context, override: SongOverride) {
        try {
            val appFile = getAppBackupFile(context)
            val publicFile = getPublicBackupFile()

            val existingList = loadOverridesFromBackup(context).toMutableList()
            existingList.removeAll { it.path == override.path }
            existingList.add(override)

            val jsonArray = JSONArray()
            existingList.forEach { item ->
                val obj = JSONObject().apply {
                    put("path", item.path)
                    put("title", item.title)
                    put("artist", item.artist)
                    put("album", item.album)
                    put("genre", item.genre)
                    put("year", item.year)
                }
                jsonArray.put(obj)
            }

            val jsonString = jsonArray.toString()
            try { appFile.writeText(jsonString) } catch (_: Exception) {}
            try { publicFile?.writeText(jsonString) } catch (_: Exception) {}

            Log.d("MetadataBackupUtils", "Saved metadata override backup for ${override.path}")
        } catch (e: Exception) {
            Log.e("MetadataBackupUtils", "Error saving metadata backup", e)
        }
    }

    fun loadOverridesFromBackup(context: Context): List<SongOverride> {
        val appFile = getAppBackupFile(context)
        val publicFile = getPublicBackupFile()

        val targetFile = when {
            appFile.exists() && appFile.length() > 0L -> appFile
            publicFile != null && publicFile.exists() && publicFile.length() > 0L -> publicFile
            else -> return emptyList()
        }

        return try {
            val jsonText = targetFile.readText()
            val jsonArray = JSONArray(jsonText)
            val list = mutableListOf<SongOverride>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SongOverride(
                        path = obj.optString("path", ""),
                        title = obj.optString("title", ""),
                        artist = obj.optString("artist", ""),
                        album = obj.optString("album", ""),
                        genre = obj.optString("genre", ""),
                        year = obj.optString("year", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e("MetadataBackupUtils", "Error loading metadata backup", e)
            emptyList()
        }
    }

    suspend fun restoreOverridesIfEmpty(context: Context, repository: MusicRepository) {
        try {
            val dbOverrides = repository.getAllOverrides()
            if (dbOverrides.isEmpty()) {
                val backupOverrides = loadOverridesFromBackup(context)
                backupOverrides.forEach { override ->
                    repository.upsertOverride(override)
                }
                if (backupOverrides.isNotEmpty()) {
                    Log.d("MetadataBackupUtils", "Restored ${backupOverrides.size} metadata overrides from backup")
                }
            }
        } catch (e: Exception) {
            Log.e("MetadataBackupUtils", "Error restoring metadata overrides", e)
        }
    }
}