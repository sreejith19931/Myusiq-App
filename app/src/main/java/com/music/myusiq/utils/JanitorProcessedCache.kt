package com.music.myusiq.utils

import android.content.Context
import android.os.Environment
import com.music.myusiq.domain.models.Song
import org.json.JSONArray
import java.io.File

/**
 * Remembers which tracks the Library Janitor has already reviewed or fixed,
 * keyed by a fingerprint of the file path AND the track's current metadata.
 *
 * The record is written to the PUBLIC `.myusiq_backup` directory, so it
 * survives app-data clears and reinstalls: a fresh install will not
 * re-scan or re-propose fixes for tracks it already healed, as long as
 * the file's tags still carry the fixed values. If the user (or another
 * app) later edits the tags, the fingerprint changes and the track is
 * reviewed again.
 */
object JanitorProcessedCache {
    private const val FILE_NAME = "janitor_processed.json"
    private const val MAX_ENTRIES = 2000

    @Volatile
    private var memoryCache: MutableSet<String>? = null

    fun fingerprintOf(path: String, title: String, artist: String, album: String): String =
        listOf(
            PlatformUtils.normalizePath(path).lowercase(),
            title.trim().lowercase(),
            artist.trim().lowercase(),
            album.trim().lowercase()
        ).joinToString("|")

    fun fingerprint(song: Song): String =
        fingerprintOf(song.path, song.title, song.artist, song.album)

    fun isProcessed(context: Context, song: Song): Boolean =
        load(context).contains(fingerprint(song))

    fun hasRecords(context: Context): Boolean = load(context).isNotEmpty()

    /** Records fingerprints built from post-fix values (path + new metadata). */
    fun markProcessedFingerprints(context: Context, fingerprints: Collection<String>) {
        val valid = fingerprints.filter { it.isNotBlank() }
        if (valid.isEmpty()) return
        val set = load(context)
        val changed = valid.fold(false) { acc, fp -> acc || set.add(fp) }
        if (!changed) return
        save(context, set)
    }

    fun markProcessed(context: Context, songs: Collection<Song>) {
        markProcessedFingerprints(context, songs.map { fingerprint(it) })
    }

    private fun save(context: Context, set: MutableSet<String>) {
        val json = JSONArray()
        // LinkedHashSet keeps insertion order; keep the most recent MAX_ENTRIES
        set.toList().takeLast(MAX_ENTRIES).forEach { json.put(it) }
        val text = json.toString()
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                ".myusiq_backup"
            )
            if (!dir.exists()) dir.mkdirs()
            File(dir, FILE_NAME).writeText(text)
        } catch (_: Exception) {}
        try {
            context.getExternalFilesDir(null)?.let { appDir ->
                if (!appDir.exists()) appDir.mkdirs()
                File(appDir, FILE_NAME).writeText(text)
            }
        } catch (_: Exception) {}
        memoryCache = set
    }

    private fun load(context: Context): MutableSet<String> {
        memoryCache?.let { return it }
        val set = LinkedHashSet<String>()
        val publicFile = try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                ".myusiq_backup"
            )
            File(dir, FILE_NAME)
        } catch (_: Exception) { null }
        val appFile = try {
            context.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }
        } catch (_: Exception) { null }

        for (file in listOfNotNull(publicFile, appFile)) {
            if (file.exists() && file.length() > 0) {
                try {
                    val arr = JSONArray(file.readText())
                    for (i in 0 until arr.length()) {
                        arr.optString(i).takeIf { it.isNotBlank() }?.let { set.add(it) }
                    }
                    break // public copy wins
                } catch (_: Exception) {}
            }
        }
        memoryCache = set
        return set
    }
}
