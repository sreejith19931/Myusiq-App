package com.music.myusiq.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CloudMetadata(
    val album: String?,
    val year: String?,
    val genre: String?,
    val artworkUrl: String?,
)

object CloudMetadataResolver {

    suspend fun resolve(title: String, artist: String): CloudMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                val query = URLEncoder.encode("$artist $title", "UTF-8")
                val url = URL("https://itunes.apple.com/search?term=$query&entity=song&limit=1")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val results = json.getJSONArray("results")
                    if (results.length() > 0) {
                        val first = results.getJSONObject(0)
                        
                        val album = first.optString("collectionName")
                        val releaseDate = first.optString("releaseDate") // e.g. "2015-11-06T08:00:00Z"
                        val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
                        val genre = first.optString("primaryGenreName")
                        
                        // Get high-res version of artwork (1000x1000 instead of 100x100)
                        val lowResArt = first.optString("artworkUrl100")
                        val highResArt = lowResArt.replace("100x100bb", "1000x1000bb")

                        return@withContext CloudMetadata(album, year, genre, highResArt)
                    }
                }
                null
            } catch (e: Exception) {
                Log.e("CloudMetadata", "Error resolving metadata for $title", e)
                null
            }
        }
    }
}
