package com.music.myusiq.utils

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class LrcFetcher(private val client: OkHttpClient = OkHttpClient()) {

    /**
     * Fetches synced (or plain) lyrics asynchronously from LRCLIB using OkHttp and Coroutines.
     *
     * @param artist Artist name
     * @param title Track/Song title
     * @param album Optional album name
     * @param durationSeconds Optional song duration in seconds
     * @return Synced lyrics string if available, falling back to plain lyrics, or null if not found.
     */
    suspend fun fetchSyncedLyrics(
        artist: String,
        title: String,
        album: String? = null,
        durationSeconds: Int? = null
    ): String? = withContext(Dispatchers.IO) {
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host("lrclib.net")
            .addPathSegments("api/get")
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("track_name", title)

        if (!album.isNullOrBlank()) urlBuilder.addQueryParameter("album_name", album)
        if (durationSeconds != null && durationSeconds > 0) {
            urlBuilder.addQueryParameter("duration", durationSeconds.toString())
        }

        val request = Request.Builder().url(urlBuilder.build()).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val responseBody = response.body?.string() ?: return@withContext null
                val json = JsonParser.parseString(responseBody).asJsonObject
                
                // Return synced lyrics if available, fallback to plain lyrics
                return@withContext json.get("syncedLyrics")?.takeIf { !it.isJsonNull }?.asString
                    ?: json.get("plainLyrics")?.takeIf { !it.isJsonNull }?.asString
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
