package com.music.myusiq.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    val matchConfidence: Double = 1.0,
    val durationMs: Long? = null
)

object CloudMetadataResolver {

    fun extractOfflineFallbackMetadata(path: String, originalTitle: String, originalArtist: String, originalAlbum: String): CloudMetadata {
        val file = File(path)
        val fileNameWithoutExt = file.nameWithoutExtension
        val parentDir = file.parentFile?.name ?: ""
        val grandParentDir = file.parentFile?.parentFile?.name ?: ""

        var fallbackTitle = originalTitle
        var fallbackArtist = if (!isGenericArtist(originalArtist)) originalArtist else ""
        var fallbackAlbum = if (!ArtworkUtils.isGenericAlbum(originalAlbum)) originalAlbum else ""

        if (fileNameWithoutExt.contains(" - ")) {
            val parts = fileNameWithoutExt.split(" - ", limit = 2)
            if (parts.size == 2) {
                if (fallbackArtist.isBlank()) fallbackArtist = parts[0].trim()
                fallbackTitle = parts[1].trim()
            }
        } else if (fileNameWithoutExt.contains("_") && !fileNameWithoutExt.contains(" ")) {
            val parts = fileNameWithoutExt.split("_", limit = 2)
            if (parts.size == 2) {
                if (fallbackArtist.isBlank()) fallbackArtist = parts[0].replace(".", " ").trim()
                fallbackTitle = parts[1].replace(".", " ").trim()
            }
        }

        if (fallbackAlbum.isBlank() && parentDir.isNotBlank() && !ArtworkUtils.isGenericAlbum(parentDir)) {
            fallbackAlbum = parentDir
        }
        if (fallbackArtist.isBlank() && grandParentDir.isNotBlank() && !ArtworkUtils.isGenericAlbum(grandParentDir)) {
            fallbackArtist = grandParentDir
        }

        val cleanedTitle = YouTubeTitleCleaner.cleanTitle(fallbackTitle).ifBlank { fileNameWithoutExt }
        val cleanedArtist = if (fallbackArtist.isNotBlank()) cleanArtist(fallbackArtist) else "Unknown Artist"
        val cleanedAlbum = if (fallbackAlbum.isNotBlank()) fallbackAlbum else "Unknown Album"

        return CloudMetadata(
            album = cleanedAlbum,
            year = null,
            genre = null,
            artworkUrl = null,
            foundArtist = cleanedArtist,
            foundTitle = cleanedTitle,
            matchConfidence = 0.50
        )
    }

    fun cleanArtist(artist: String): String {
        return if (!isGenericArtist(artist)) artist.trim() else ""
    }

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

    private val rateLimiterLock = Any()
    private var lastRequestTimestamp = 0L

    private fun enforceRateLimit(minDelayMs: Long = 150L) {
        synchronized(rateLimiterLock) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTimestamp
            if (elapsed < minDelayMs) {
                try {
                    Thread.sleep(minDelayMs - elapsed)
                } catch (_: Exception) {}
            }
            lastRequestTimestamp = System.currentTimeMillis()
        }
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

        val containsScore = if (norm1.length >= 4 && norm2.length >= 4 && (norm1.contains(norm2) || norm2.contains(norm1))) 0.8 else 0.0

        return maxOf(jaccard, containsScore)
    }

    fun isAcceptableMatch(
        cleanTitle: String,
        cleanArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        cleanAlbum: String = "",
        candidateAlbum: String = ""
    ): Boolean {
        val pureTitle = if (cleanTitle.contains(" - ")) cleanTitle.substringAfter(" - ").trim() else cleanTitle
        val pureArtist = if (cleanArtist.isBlank() && cleanTitle.contains(" - ")) cleanTitle.substringBefore(" - ").trim() else cleanArtist

        val normCleanTitle = normalizeForMatching(pureTitle)
        val normCleanArtist = normalizeForMatching(pureArtist)
        val normCandArtist = normalizeForMatching(candidateArtist)

        val titleScore = calculateTokenSimilarity(pureTitle, candidateTitle)
        val artistScore = calculateTokenSimilarity(pureArtist, candidateArtist)

        val hasCleanAlbum = cleanAlbum.isNotBlank() && !ArtworkUtils.isGenericAlbum(cleanAlbum)
        val hasCandAlbum = candidateAlbum.isNotBlank() && !ArtworkUtils.isGenericAlbum(candidateAlbum)

        if (hasCleanAlbum && hasCandAlbum) {
            val albumSim = calculateTokenSimilarity(cleanAlbum, candidateAlbum)
            if (albumSim < 0.15) {
                return false // Heavy album mismatch -> ensures correct album and album art
            }
        }

        val isGenericTitleLocal = normCleanTitle.matches(Regex("(?i)^(track|audio|sound|recording)[_\\s\\-]*\\d*$")) || normCleanTitle.length < 3
        val isGenericArtistLocal = isGenericArtist(pureArtist) || normCleanArtist.isBlank()

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
                        ?: fetchMusicBrainzMetadata(cleanTitle, cleanArtist)
                }

                // 2. Fallback: Search with cleanTitle alone
                if (metadata == null && cleanTitle.isNotBlank()) {
                    metadata = fetchItunesMetadata(cleanTitle, cleanTitle, cleanArtist)
                        ?: fetchDeezerMetadata(cleanTitle, cleanTitle, cleanArtist)
                        ?: fetchMusicBrainzMetadata(cleanTitle, cleanArtist)
                }

                // 3. Last fallback: Search raw clean title
                if (metadata == null) {
                    val rawClean = title.replace(Regex("(?i)\\.mp3|\\.m4a|\\.flac|\\.wav|\\.aac|\\.ogg|\\.wma"), "").trim()
                    if (rawClean.isNotBlank() && rawClean != cleanTitle) {
                        metadata = fetchItunesMetadata(rawClean, cleanTitle, cleanArtist)
                            ?: fetchDeezerMetadata(rawClean, cleanTitle, cleanArtist)
                            ?: fetchMusicBrainzMetadata(rawClean, cleanArtist)
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
        enforceRateLimit(150L)
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
                            val durationMs = item.optLong("trackTimeMillis", -1L).takeIf { it > 0 }

                            bestCandidate = CloudMetadata(album, year, genre, highResArt, foundArtist, foundTitle, matchConfidence = score, durationMs = durationMs)
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
        enforceRateLimit(150L)
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
                            val durSec = item.optLong("duration", -1L)
                            val durationMs = if (durSec > 0) durSec * 1000L else null

                            bestCandidate = CloudMetadata(album, null, null, highResArt, foundArtist, foundTitle, matchConfidence = score, durationMs = durationMs)
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

    private fun fetchMusicBrainzMetadata(cleanTitle: String, cleanArtist: String): CloudMetadata? {
        if (cleanTitle.isBlank()) return null
        enforceRateLimit(250L)
        return try {
            val queryStr = if (cleanArtist.isNotBlank()) {
                "recording:\"$cleanTitle\" AND artist:\"$cleanArtist\""
            } else {
                "recording:\"$cleanTitle\""
            }
            val query = URLEncoder.encode(queryStr, "UTF-8").replace("+", "%20")
            val url = URL("https://musicbrainz.org/ws/2/recording?query=$query&fmt=json&limit=5")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "MyusiqMusicPlayer/1.0 (android.app@myusiq.internal)")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val recordings = json.optJSONArray("recordings") ?: return null

                var bestCandidate: CloudMetadata? = null
                var maxScore = -1.0

                for (i in 0 until recordings.length()) {
                    val item = recordings.getJSONObject(i)
                    val foundTitle = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                    val artistCredit = item.optJSONArray("artist-credit")
                    val foundArtist = if (artistCredit != null && artistCredit.length() > 0) {
                        artistCredit.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
                    } else null
                    if (foundArtist == null) continue

                    if (isAcceptableMatch(cleanTitle, cleanArtist, foundTitle, foundArtist)) {
                        val titleSim = calculateTokenSimilarity(cleanTitle, foundTitle)
                        val artistSim = if (cleanArtist.isNotBlank()) calculateTokenSimilarity(cleanArtist, foundArtist) else 1.0
                        val score = (titleSim * 0.6) + (artistSim * 0.4)

                        if (score > maxScore) {
                            maxScore = score
                            val releases = item.optJSONArray("releases")
                            var album: String? = null
                            var year: String? = null
                            if (releases != null && releases.length() > 0) {
                                val rel = releases.getJSONObject(0)
                                album = rel.optString("title").takeIf { it.isNotBlank() }
                                val relDate = rel.optString("date")
                                if (relDate.length >= 4) year = relDate.substring(0, 4)
                            }
                            
                            val tags = item.optJSONArray("tags")
                            val genre = if (tags != null && tags.length() > 0) {
                                tags.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
                            } else null

                            bestCandidate = CloudMetadata(
                                album = album,
                                year = year,
                                genre = genre,
                                artworkUrl = null,
                                foundArtist = foundArtist,
                                foundTitle = foundTitle,
                                matchConfidence = score
                            )
                        }
                    }
                }
                return bestCandidate
            }
            null
        } catch (e: Exception) {
            Log.d("CloudMetadata", "MusicBrainz search failed for '$cleanTitle': ${e.message}")
            null
        }
    }

    suspend fun fetchMusicBrainzByMbid(mbid: String): CloudMetadata? {
        if (mbid.isBlank()) return null
        enforceRateLimit(250L)
        return try {
            val url = URL("https://musicbrainz.org/ws/2/recording/$mbid?inc=artists+releases+tags&fmt=json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "MyusiqMusicPlayer/1.0 (android.app@myusiq.internal)")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val item = JSONObject(response)
                val foundTitle = item.optString("title").takeIf { it.isNotBlank() } ?: return null
                val artistCredit = item.optJSONArray("artist-credit")
                val foundArtist = if (artistCredit != null && artistCredit.length() > 0) {
                    artistCredit.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
                } else null

                val releases = item.optJSONArray("releases")
                var album: String? = null
                var year: String? = null
                if (releases != null && releases.length() > 0) {
                    val rel = releases.getJSONObject(0)
                    album = rel.optString("title").takeIf { it.isNotBlank() }
                    val relDate = rel.optString("date")
                    if (relDate.length >= 4) year = relDate.substring(0, 4)
                }

                val tags = item.optJSONArray("tags")
                val genre = if (tags != null && tags.length() > 0) {
                    tags.getJSONObject(0).optString("name").takeIf { it.isNotBlank() }
                } else null

                var artworkUrl: String? = null
                if (foundArtist != null) {
                    val itunes = fetchItunesMetadata("$foundArtist $foundTitle", foundTitle, foundArtist)
                    artworkUrl = itunes?.artworkUrl
                }

                return CloudMetadata(
                    album = album,
                    year = year,
                    genre = genre,
                    artworkUrl = artworkUrl,
                    foundArtist = foundArtist ?: "Unknown Artist",
                    foundTitle = foundTitle,
                    matchConfidence = 0.95
                )
            }
            null
        } catch (e: Exception) {
            Log.d("CloudMetadata", "MusicBrainz MBID lookup failed for '$mbid': ${e.message}")
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

    private val searchCache = android.util.LruCache<String, List<CloudMetadata>>(100)

    suspend fun searchMultiple(query: String, cleanTitle: String = "", cleanArtist: String = ""): List<CloudMetadata> {
        val cacheKey = "${query.trim().lowercase()}|${cleanTitle.trim().lowercase()}|${cleanArtist.trim().lowercase()}"
        synchronized(searchCache) {
            searchCache.get(cacheKey)?.let { return it }
        }

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

            val resultList = candidates.distinctBy { "${it.foundTitle?.lowercase()}|${it.foundArtist?.lowercase()}" }
                .sortedByDescending { it.matchConfidence }

            if (resultList.isNotEmpty()) {
                synchronized(searchCache) {
                    searchCache.put(cacheKey, resultList)
                }
            }
            resultList
        }
    }
}
