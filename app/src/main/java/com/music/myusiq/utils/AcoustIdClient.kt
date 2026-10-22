package com.music.myusiq.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class AcoustIdMatch(
    val recordingId: String,
    val score: Double,
    val title: String? = null,
    val artist: String? = null
)

object AcoustIdClient {

    private const val CLIENT_API_KEY = "cW82S1pX"
    private const val ACOUSTID_API_URL = "https://api.acoustid.org/v2/lookup"

    /**
     * Queries the AcoustID Web API using duration and acoustic fingerprint.
     *
     * @param durationSec Duration of track in seconds.
     * @param fingerprint Fingerprint string.
     * @return List of AcoustIdMatch results sorted by score.
     */
    suspend fun lookup(durationSec: Int, fingerprint: String): List<AcoustIdMatch> {
        if (durationSec <= 0 || fingerprint.isBlank()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val encodedFingerprint = URLEncoder.encode(fingerprint, "UTF-8")
                val urlStr = "$ACOUSTID_API_URL?client=$CLIENT_API_KEY&duration=$durationSec&fingerprint=$encodedFingerprint&meta=recordings+releasegroups+compress"
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseText)
                    val status = json.optString("status")
                    if (status == "ok") {
                        val results = json.optJSONArray("results") ?: return@withContext emptyList()
                        val matches = mutableListOf<AcoustIdMatch>()

                        for (i in 0 until results.length()) {
                            val item = results.getJSONObject(i)
                            val score = item.optDouble("score", 0.0)
                            val recordings = item.optJSONArray("recordings")
                            if (recordings != null && recordings.length() > 0) {
                                for (j in 0 until recordings.length()) {
                                    val rec = recordings.getJSONObject(j)
                                    val id = rec.optString("id")
                                    val title = rec.optString("title").takeIf { it.isNotBlank() }
                                    val artistCredits = rec.optJSONArray("artists")
                                    val artist = if (artistCredits != null && artistCredits.length() > 0) {
                                        artistCredits.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
                                    } else null

                                    if (id.isNotBlank()) {
                                        matches.add(AcoustIdMatch(id, score, title, artist))
                                    }
                                }
                            }
                        }
                        return@withContext matches.sortedByDescending { it.score }
                    }
                }
                emptyList()
            } catch (e: Exception) {
                Log.d("AcoustIdClient", "AcoustID lookup failed: ${e.message}")
                emptyList()
            }
        }
    }
}
