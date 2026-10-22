package com.music.myusiq.domain.usecase

import android.content.Context
import android.net.Uri
import com.music.myusiq.domain.models.Song
import com.music.myusiq.domain.models.JanitorUpdateItem
import com.music.myusiq.domain.models.JanitorItemType
import com.music.myusiq.domain.models.PendingTrackEdit // CORRECT IMPORT
import com.music.myusiq.domain.models.SuggestedMetadataItem
import com.music.myusiq.ui.viewmodels.PlaybackViewModel
import com.music.myusiq.utils.CloudMetadataResolver
import com.music.myusiq.utils.YouTubeTitleCleaner
import com.music.myusiq.utils.ArtworkUtils
import com.music.myusiq.domain.usecase.MetadataSanitizerUseCase
import com.music.myusiq.domain.usecase.AudioFingerprintingUseCase
import com.music.myusiq.domain.usecase.MatchSearchUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections

sealed class ScanState {
    data class Progress(val percentage: Int, val message: String) : ScanState()
    data class ScanCompleted(val results: List<JanitorUpdateItem>) : ScanState()
}

class JanitorScanUseCase(
    private val downloadImage: suspend (Context, String) -> Uri?,
    private val metadataSanitizerUseCase: MetadataSanitizerUseCase,
    private val fingerprintingUseCase: AudioFingerprintingUseCase,
    private val matchSearchUseCase: MatchSearchUseCase
) {
    fun scanTracks(tracks: List<Song>, context: Context): Flow<ScanState> = channelFlow {
        val totalTracks = tracks.size
        val results = Collections.synchronizedList(mutableListOf<JanitorUpdateItem>())
        val albumArtworkMap = Collections.synchronizedMap(mutableMapOf<String, Uri>())

        if (totalTracks == 0) {
            try {
                send(ScanState.Progress(100, "No tracks provided for scanning."))
                send(ScanState.ScanCompleted(emptyList()))
            } catch (_: Exception) {}
            return@channelFlow
        }

        withContext(Dispatchers.IO) {
            // Pre-scan for album artwork
            tracks.forEach { song ->
                if (!ArtworkUtils.isGenericAlbum(song.album)) {
                    val artFile = ArtworkUtils.getArtworkFile(context, song.id, song.path, song.album)
                    if (artFile != null && artFile.exists() && artFile.length() > 0) {
                        val albumKey = song.album.trim().lowercase()
                        if (!albumArtworkMap.containsKey(albumKey)) {
                            albumArtworkMap[albumKey] = Uri.fromFile(artFile)
                        }
                    }
                }
            }

            val completedCount = AtomicInteger(0)
            val semaphore = Semaphore(2)

            val jobList = tracks.map { song ->
                launch {
                    semaphore.withPermit {
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(6000L) {
                                // 1. Sanitize metadata using song properties
                                val sanitizedSong = metadataSanitizerUseCase.sanitize(
                                    song,
                                    song.year,
                                    song.path,
                                    song.duration.toString(),
                                    song.albumId
                                )

                                // 2. Generate fingerprint using sanitized metadata and acoustic characteristics
                                val fingerprint = fingerprintingUseCase.generateFingerprint(sanitizedSong, context)

                                // 3. Find matches using sanitized metadata, audio fingerprint, and song context
                                val matchesFlow = matchSearchUseCase.findMatches(
                                    sanitizedSong,
                                    fingerprint,
                                    song.year,
                                    song.genre,
                                    context
                                )

                                val suggestions = mutableListOf<SuggestedMetadataItem>()
                                try {
                                    matchesFlow.collect { suggestions.add(it) }
                                } catch (_: Exception) {}

                                val bestMatch: SuggestedMetadataItem? = suggestions.maxByOrNull { it.confidenceScore }
                                val isHighConfidence = bestMatch != null && bestMatch.confidenceScore >= 0.85f

                                if (bestMatch != null) {
                                    val isComplete = bestMatch.suggestedTitle.isNotBlank() && bestMatch.suggestedArtist.isNotBlank() && bestMatch.suggestedAlbum.isNotBlank()

                                    val artworkUri: Uri? = if (!bestMatch.suggestedArtUri.isNullOrBlank()) {
                                        if (bestMatch.suggestedArtUri.startsWith("http://") || bestMatch.suggestedArtUri.startsWith("https://")) {
                                            try {
                                                kotlinx.coroutines.withTimeoutOrNull(2500L) {
                                                    downloadImage(context, bestMatch.suggestedArtUri)
                                                }
                                            } catch (_: Exception) { null }
                                        } else {
                                            try { Uri.parse(bestMatch.suggestedArtUri) } catch (_: Exception) { null }
                                        }
                                    } else {
                                        val albumKey = song.album.trim().lowercase()
                                        val artFile = ArtworkUtils.getArtworkFile(context, song.id, song.path, song.album)
                                        artFile?.let { Uri.fromFile(it) } ?: albumArtworkMap[albumKey]
                                    }

                                    val pendingEdit = PendingTrackEdit(
                                        songId = song.id,
                                        title = bestMatch.suggestedTitle,
                                        artist = bestMatch.suggestedArtist,
                                        album = bestMatch.suggestedAlbum,
                                        year = bestMatch.suggestedYear ?: "",
                                        genre = bestMatch.suggestedGenre ?: "",
                                        artworkUri = artworkUri,
                                        path = song.path,
                                        matchConfidence = (bestMatch.confidenceScore * 100).toInt()
                                    )

                                    val itemType = if (isComplete && isHighConfidence) JanitorItemType.AUTO_FIX else JanitorItemType.SUGGESTION

                                    val item = JanitorUpdateItem(
                                        songId = song.id.toString(),
                                        song = song,
                                        currentArtworkUri = null,
                                        itemType = itemType,
                                        rawMetadata = mapOf(
                                            "title" to pendingEdit.title,
                                            "artist" to pendingEdit.artist,
                                            "album" to pendingEdit.album,
                                            "year" to pendingEdit.year,
                                            "genre" to pendingEdit.genre
                                        ),
                                        proposedEdit = pendingEdit,
                                        isSelected = isHighConfidence,
                                        suggestedMatches = suggestions
                                    )
                                    results.add(item)
                                } else {
                                    addFallbackItem(song, context, albumArtworkMap, results)
                                }
                            } ?: run {
                                addFallbackItem(song, context, albumArtworkMap, results)
                            }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            addFallbackItem(song, context, albumArtworkMap, results)
                        } finally {
                            val count = completedCount.incrementAndGet()
                            val progressPct = (count * 100 / totalTracks).coerceAtMost(99)
                            try {
                                send(ScanState.Progress(progressPct, "Scanning $count of $totalTracks: ${song.title.take(30)}..."))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }

            // Wait for all jobs to complete
            jobList.joinAll()
        }

        // Second pass: Share album artwork
        val finalResults = results.map { item ->
            val albumKey = item.proposedEdit.album.trim().lowercase()
            if (item.proposedEdit.artworkUri == null && !ArtworkUtils.isGenericAlbum(item.proposedEdit.album)) {
                val sharedArt = albumArtworkMap[albumKey]
                if (sharedArt != null) {
                    item.proposedEdit = item.proposedEdit.copy(
                        artworkUri = sharedArt,
                        matchConfidence = maxOf(item.proposedEdit.matchConfidence, 80)
                    )
                    item.isSelected = true
                }
            }
            item
        }

        try {
            send(ScanState.ScanCompleted(finalResults))
        } catch (_: Exception) {
            // Channel closed or flow cancelled
        }
    }



    private fun addFallbackItem(song: Song, context: Context, albumArtworkMap: Map<String, Uri>, results: MutableList<JanitorUpdateItem>) {
        val fallbackMeta = CloudMetadataResolver.extractOfflineFallbackMetadata(song.path, song.title, song.artist, song.album)
        val albumKey = song.album.trim().lowercase()
        val artFile = ArtworkUtils.getArtworkFile(context, song.id, song.path, song.album)
        val artworkUri = artFile?.let { Uri.fromFile(it) } ?: albumArtworkMap[albumKey]

        val pendingEdit = PendingTrackEdit(
            songId = song.id,
            title = fallbackMeta.foundTitle ?: song.title,
            artist = fallbackMeta.foundArtist ?: song.artist,
            album = fallbackMeta.album ?: song.album,
            year = fallbackMeta.year ?: "",
            genre = fallbackMeta.genre ?: "",
            artworkUri = artworkUri,
            path = song.path,
            matchConfidence = 50
        )
        val item = JanitorUpdateItem(
            songId = song.id.toString(),
            song = song,
            currentArtworkUri = null,
            itemType = JanitorItemType.SUGGESTION,
            rawMetadata = mapOf(
                "title" to pendingEdit.title,
                "artist" to pendingEdit.artist,
                "album" to pendingEdit.album
            ),
            proposedEdit = pendingEdit,
            isSelected = false,
            suggestedMatches = emptyList()
        )
        results.add(item)
    }
}
