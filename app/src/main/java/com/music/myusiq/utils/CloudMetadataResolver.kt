package com.music.myusiq.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class CloudMetadata(
    val album: String?,
    val year: String?,
    val genre: String?,
    val artworkUrl: String?,
    val foundArtist: String? = null,
    val foundTitle: String? = null,
    val lyrics: String? = null,
    val matchConfidence: Double = 1.0
)

object CloudMetadataResolver {

    fun isGenericArtist(artist: String): Boolean {
        val lower = artist.trim().lowercase()
        return lower.isBlank() ||
               lower == "unknown artist" ||
               lower == "<unknown>" ||
               lower == "unknown" ||
               lower == "null" ||
               lower == "various artists" ||
               lower == "various" ||
               lower == "v.a." ||
               lower == "va" ||
               lower == "n/a" ||
               lower == "none" ||
               lower == "artist" ||
               lower.matches(Regex("""^track\s*\d*$"""))
    }

    private fun normalizeForMatching(str: String): String {
        return str.lowercase()
            .replace(Regex("""[^\w\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun calculateTokenSimilarity(s1: String, s2: String): Double {
        val norm1 = normalizeForMatching(s1)
        val norm2 = normalizeForMatching(s2)
        if (norm1 == norm2) return 1.0
        if (norm1.isBlank() || norm2.isBlank()) return 0.0

        val tokens1 = norm1.split(" ").filter { it.isNotBlank() }.toSet()
        val tokens2 = norm2.split(" ").filter { it.isNotBlank() }.toSet()

        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0

        val intersection = tokens1.intersect(tokens2).size.toDouble()
        val union = tokens1.union(tokens2).size.toDouble()
        val jaccard = if (union > 0) intersection / union else 0.0

        val containsScore = if (norm1.contains(norm2) || norm2.contains(norm1)) 0.85 else 0.0

        return maxOf(jaccard, containsScore)
    }

    fun isAcceptableMatch(
        cleanTitle: String,
        cleanArtist: String,
        candidateTitle: String,
        candidateArtist: String
    ): Boolean {
        val normCleanTitle = normalizeForMatching(cleanTitle)
        val normCleanArtist = normalizeForMatching(cleanArtist)
        val normCandArtist = normalizeForMatching(candidateArtist)

        val titleScore = calculateTokenSimilarity(cleanTitle, candidateTitle)
        val artistScore = calculateTokenSimilarity(cleanArtist, candidateArtist)

        val isGenericTitleLocal = normCleanTitle.matches(Regex("(?i)^(track|audio|sound|recording)[_\\s\\-]*\\d*$")) || normCleanTitle.length < 3
        val isGenericArtistLocal = isGenericArtist(cleanArtist) || normCleanArtist.isBlank()

        // 1. Reject if local title is generic and artist is generic
        if (isGenericTitleLocal && isGenericArtistLocal) {
            return false
        }

        // 2. If local artist is known, candidate artist MUST match local artist
        if (!isGenericArtistLocal) {
            val artistMatches = artistScore >= 0.35 ||
                    (normCandArtist.isNotBlank() && normCleanArtist.isNotBlank() && (normCandArtist.contains(normCleanArtist) || normCleanArtist.contains(normCandArtist)))

            if (!artistMatches) return false

            return titleScore >= 0.45
        }

        // 3. Local artist is unknown/generic, but local title is specific
        return titleScore >= 0.55
    }

    suspend fun resolve(title: String, artist: String): CloudMetadata? {
        return withContext(Dispatchers.IO) {
            try {
                val (extractedArtist, extractedTitle) = YouTubeTitleCleaner.cleanAndSplit(title)
                val cleanTitle = extractedTitle.ifBlank { YouTubeTitleCleaner.cleanTitle(title) }
                val cleanArtist = if (!isGenericArtist(artist)) {
                    artist
                } else if (extractedArtist != "Unknown Artist" && !isGenericArtist(extractedArtist)) {
                    extractedArtist
                } else ""

                var metadata: CloudMetadata? = null

                // 1. Try search with cleanArtist + cleanTitle
                if (cleanArtist.isNotBlank()) {
                    val queryTerm = "$cleanArtist $cleanTitle"
                    metadata = fetchItunesMetadata(queryTerm, cleanTitle, cleanArtist)
                        ?: fetchDeezerMetadata(queryTerm, cleanTitle, cleanArtist)
                }

                // 2. Fallback: Search with cleanTitle alone
                if (metadata == null && cleanTitle.isNotBlank()) {
                    metadata = fetchItunesMetadata(cleanTitle, cleanTitle, cleanArtist)
                        ?: fetchDeezerMetadata(cleanTitle, cleanTitle, cleanArtist)
                }

                // 3. Last fallback: Search raw clean title
                if (metadata == null) {
                    val rawClean = title.replace(Regex("(?i)\\.mp3|\\.m4a|\\.flac|\\.wav|\\.aac|\\.ogg|\\.wma"), "").trim()
                    if (rawClean.isNotBlank() && rawClean != cleanTitle) {
                        metadata = fetchItunesMetadata(rawClean, cleanTitle, cleanArtist)
                            ?: fetchDeezerMetadata(rawClean, cleanTitle, cleanArtist)
                    }
                }

                // 4. Fetch synchronized LRC lyrics if artist and title are available
                if (metadata != null) {
                    val searchArtist = metadata.foundArtist ?: cleanArtist
                    val searchTitle = metadata.foundTitle ?: cleanTitle
                    if (searchArtist.isNotBlank() && searchTitle.isNotBlank()) {
                        val lyrics = fetchLrcLibLyrics(searchArtist, searchTitle)
                        if (!lyrics.isNullOrBlank()) {
                            metadata = metadata.copy(lyrics = lyrics)
                        }
                    }
                }

                metadata
            } catch (e: Exception) {
                Log.e("CloudMetadata", "Error resolving metadata for $title", e)
                null
            }
        }
    }

    private fun fetchItunesMetadata(searchTerm: String, cleanTitle: String, cleanArtist: String): CloudMetadata? {
        if (searchTerm.isBlank()) return null
        return try {
            val query = URLEncoder.encode(searchTerm, "UTF-8").replace("+", "%20")
            val url = URL("https://itunes.apple.com/search?term=$query&entity=song&limit=5")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val results = json.getJSONArray("results")

                var bestCandidate: CloudMetadata? = null
                var maxScore = -1.0

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val foundArtist = item.optString("artistName").takeIf { it.isNotBlank() } ?: continue
                    val foundTitle = item.optString("trackName").takeIf { it.isNotBlank() } ?: continue

                    if (isAcceptableMatch(cleanTitle, cleanArtist, foundTitle, foundArtist)) {
                        val titleSim = calculateTokenSimilarity(cleanTitle, foundTitle)
                        val artistSim = if (cleanArtist.isNotBlank()) calculateTokenSimilarity(cleanArtist, foundArtist) else 1.0
                        val score = (titleSim * 0.6) + (artistSim * 0.4)

                        if (score > maxScore) {
                            maxScore = score
                            val album = item.optString("collectionName").takeIf { it.isNotBlank() }
                            val releaseDate = item.optString("releaseDate")
                            val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
                            val genre = item.optString("primaryGenreName").takeIf { it.isNotBlank() }
                            val lowResArt = item.optString("artworkUrl100")
                            val highResArt = if (lowResArt.isNotBlank()) lowResArt.replace(Regex("""\d+x\d+bb"""), "1000x1000bb") else null

                            bestCandidate = CloudMetadata(album, year, genre, highResArt, foundArtist, foundTitle, matchConfidence = score)
                        }
                    }
                }
                return bestCandidate
            }
            null
        } catch (e: Exception) {
            Log.d("CloudMetadata", "iTunes search failed for '$searchTerm': ${e.message}")
            null
        }
    }

    private fun fetchDeezerMetadata(searchTerm: String, cleanTitle: String, cleanArtist: String): CloudMetadata? {
        if (searchTerm.isBlank()) return null
        return try {
            val query = URLEncoder.encode(searchTerm, "UTF-8").replace("+", "%20")
            val url = URL("https://api.deezer.com/search?q=$query")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val data = json.optJSONArray("data") ?: return null

                var bestCandidate: CloudMetadata? = null
                var maxScore = -1.0

                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    val foundTitle = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                    val artistObj = item.optJSONObject("artist")
                    val foundArtist = artistObj?.optString("name")?.takeIf { it.isNotBlank() } ?: continue

                    if (isAcceptableMatch(cleanTitle, cleanArtist, foundTitle, foundArtist)) {
                        val titleSim = calculateTokenSimilarity(cleanTitle, foundTitle)
                        val artistSim = if (cleanArtist.isNotBlank()) calculateTokenSimilarity(cleanArtist, foundArtist) else 1.0
                        val score = (titleSim * 0.6) + (artistSim * 0.4)

                        if (score > maxScore) {
                            maxScore = score
                            val albumObj = item.optJSONObject("album")
                            val album = albumObj?.optString("title")?.takeIf { it.isNotBlank() }
                            val highResArt = albumObj?.optString("cover_xl")
                                ?.ifBlank { albumObj.optString("cover_big") }
                                ?.takeIf { it.isNotBlank() }

                            bestCandidate = CloudMetadata(album, null, null, highResArt, foundArtist, foundTitle, matchConfidence = score)
                        }
                    }
                }
                return bestCandidate
            }
            null
        } catch (e: Exception) {
            Log.d("CloudMetadata", "Deezer search failed for '$searchTerm': ${e.message}")
            null
        }
    }

    private fun fetchLrcLibLyrics(artist: String, title: String): String? {
        val encArtist = URLEncoder.encode(artist, "UTF-8").replace("+", "%20")
        val encTitle = URLEncoder.encode(title, "UTF-8").replace("+", "%20")

        // 1. Try exact endpoint
        try {
            val url = URL("https://lrclib.net/api/get?artist_name=$encArtist&track_name=$encTitle")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 4000
            connection.readTimeout = 4000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val syncedLyrics = json.optString("syncedLyrics")
                val plainLyrics = json.optString("plainLyrics")
                val result = syncedLyrics.ifBlank { plainLyrics }
                if (result.isNotBlank()) return result
            }
        } catch (e: Exception) {
            Log.d("CloudMetadata", "LRCLIB exact get failed for '$artist - $title': ${e.message}")
        }

        // 2. Fallback to search endpoint
        return try {
            val searchUrl = URL("https://lrclib.net/api/search?track_name=$encTitle&artist_name=$encArtist")
            val connection = searchUrl.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 4000
            connection.readTimeout = 4000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(response)
                if (array.length() > 0) {
                    val first = array.getJSONObject(0)
                    val syncedLyrics = first.optString("syncedLyrics")
                    val plainLyrics = first.optString("plainLyrics")
                    syncedLyrics.ifBlank { plainLyrics }.takeIf { it.isNotBlank() }
                } else null
            } else null
        } catch (e: Exception) {
            Log.d("CloudMetadata", "LRCLIB search fallback failed for '$artist - $title': ${e.message}")
            null
        }
    }

    suspend fun searchMultiple(query: String, cleanTitle: String = "", cleanArtist: String = ""): List<CloudMetadata> {
        return withContext(Dispatchers.IO) {
            val candidates = mutableListOf<CloudMetadata>()
            
            try {
                val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
                val url = URL("https://itunes.apple.com/search?term=$q&entity=song&limit=10")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(response)
                    val results = json.getJSONArray("results")

                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val foundArtist = item.optString("artistName").takeIf { it.isNotBlank() } ?: continue
                        val foundTitle = item.optString("trackName").takeIf { it.isNotBlank() } ?: continue
                        val album = item.optString("collectionName").takeIf { it.isNotBlank() }
                        val releaseDate = item.optString("releaseDate")
                        val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else null
                        val genre = item.optString("primaryGenreName").takeIf { it.isNotBlank() }
                        val lowResArt = item.optString("artworkUrl100")
                        val highResArt = if (lowResArt.isNotBlank()) lowResArt.replace(Regex("""\d+x\d+bb"""), "1000x1000bb") else null

                        val titleSim = if (cleanTitle.isNotBlank()) calculateTokenSimilarity(cleanTitle, foundTitle) else 0.8
                        val artistSim = if (cleanArtist.isNotBlank()) calculateTokenSimilarity(cleanArtist, foundArtist) else 0.8
                        val score = (titleSim * 0.6) + (artistSim * 0.4)

                        candidates.add(CloudMetadata(album, year, genre, highResArt, foundArtist, foundTitle, matchConfidence = score))
                    }
                }
            } catch (_: Exception) {}

            if (candidates.size < 5) {
                try {
                    val q = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
                    val url = URL("https://api.deezer.com/search?q=$q")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(response)
                        val data = json.optJSONArray("data")
                        if (data != null) {
                            for (i in 0 until data.length()) {
                                val item = data.getJSONObject(i)
                                val foundTitle = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                                val artistObj = item.optJSONObject("artist")
                                val foundArtist = artistObj?.optString("name")?.takeIf { it.isNotBlank() } ?: continue
                                val albumObj = item.optJSONObject("album")
                                val album = albumObj?.optString("title")?.takeIf { it.isNotBlank() }
                                val highResArt = albumObj?.optString("cover_xl")
                                    ?.ifBlank { albumObj.optString("cover_big") }
                                    ?.takeIf { it.isNotBlank() }

                                val titleSim = if (cleanTitle.isNotBlank()) calculateTokenSimilarity(cleanTitle, foundTitle) else 0.8
                                val artistSim = if (cleanArtist.isNotBlank()) calculateTokenSimilarity(cleanArtist, foundArtist) else 0.8
                                val score = (titleSim * 0.6) + (artistSim * 0.4)

                                candidates.add(CloudMetadata(album, null, null, highResArt, foundArtist, foundTitle, matchConfidence = score))
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            candidates.distinctBy { "${it.foundTitle?.lowercase()}|${it.foundArtist?.lowercase()}" }
                .sortedByDescending { it.matchConfidence }
        }
    }
}
