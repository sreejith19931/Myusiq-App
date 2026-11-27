package com.music.myusiq.utils

import android.content.Context
import com.music.myusiq.domain.models.JanitorItemType
import com.music.myusiq.domain.models.JanitorUpdateItem
import com.music.myusiq.domain.models.PendingTrackEdit
import com.music.myusiq.domain.models.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the Janitor's background scan results so the review sheet can
 * open them directly instead of re-running the whole network scan, and
 * remembers matches the user dismissed so they stop being re-proposed.
 */
object JanitorResultsCache {
    private const val FILE_NAME = "janitor_last_results.json"
    private const val MAX_AGE_MS = 72L * 60 * 60 * 1000

    data class CachedResults(val timestamp: Long, val items: List<JSONObject>)

    fun save(context: Context, results: List<JanitorUpdateItem>) {
        try {
            val root = JSONObject()
            root.put("timestamp", System.currentTimeMillis())
            val arr = JSONArray()
            for (item in results) {
                val e = item.proposedEdit
                arr.put(JSONObject().apply {
                    put("songId", item.songId)
                    put("itemType", item.itemType.name)
                    put("isSelected", item.isSelected)
                    put("title", e.title)
                    put("artist", e.artist)
                    put("album", e.album)
                    put("year", e.year)
                    put("genre", e.genre)
                    put("artworkUri", e.artworkUri?.toString() ?: "")
                    put("path", e.path)
                    put("confidence", e.matchConfidence)
                })
            }
            root.put("items", arr)
            File(context.cacheDir, FILE_NAME).writeText(root.toString())
        } catch (_: Exception) {}
    }

    fun load(context: Context): CachedResults? {
        return try {
            val file = File(context.cacheDir, FILE_NAME)
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val ts = root.optLong("timestamp", 0)
            if (System.currentTimeMillis() - ts > MAX_AGE_MS) {
                file.delete()
                return null
            }
            CachedResults(ts, mutableListOf<JSONObject>().apply {
                val arr = root.optJSONArray("items") ?: return@apply
                for (i in 0 until arr.length()) add(arr.getJSONObject(i))
            })
        } catch (_: Exception) {
            null
        }
    }

    /** Rebuilds JanitorUpdateItems against the current library. */
    fun rebuildItems(
        context: Context,
        library: List<Song>,
    ): List<JanitorUpdateItem> {
        val cached = load(context) ?: return emptyList()
        val byId = library.associateBy { it.id.toString() }
        return cached.items.mapNotNull { e ->
            val song = byId[e.optString("songId")] ?: return@mapNotNull null
            val edit = PendingTrackEdit(
                songId = song.id,
                title = e.optString("title"),
                artist = e.optString("artist"),
                album = e.optString("album"),
                year = e.optString("year"),
                genre = e.optString("genre"),
                artworkUri = e.optString("artworkUri").takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) },
                path = e.optString("path"),
                matchConfidence = e.optInt("confidence", 0)
            )
            val itemType = try { JanitorItemType.valueOf(e.optString("itemType", "SUGGESTION")) } catch (_: Exception) { JanitorItemType.SUGGESTION }
            JanitorUpdateItem(
                songId = song.id.toString(),
                song = song,
                currentArtworkUri = null,
                itemType = itemType,
                rawMetadata = mapOf("title" to edit.title, "artist" to edit.artist, "album" to edit.album),
                proposedEdit = edit,
                isSelected = e.optBoolean("isSelected", false),
                suggestedMatches = emptyList()
            )
        }
    }

    /** Remembers proposals the user closed without applying, so the Janitor
     *  stops re-proposing the same rejected match on every scan. */
    fun recordDismissed(context: Context, items: List<JanitorUpdateItem>) {
        try {
            val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val existing = prefs.getStringSet("janitor_dismissed_matches", emptySet())?.toMutableSet() ?: mutableSetOf()
            for (item in items) {
                val e = item.proposedEdit
                existing.add("${item.songId}|${e.title.trim().lowercase()}|${e.artist.trim().lowercase()}")
            }
            // Bound growth
            if (existing.size > 500) existing.take(250).forEach { existing.remove(it) }
            prefs.edit().putStringSet("janitor_dismissed_matches", existing).apply()
        } catch (_: Exception) {}
    }
}
